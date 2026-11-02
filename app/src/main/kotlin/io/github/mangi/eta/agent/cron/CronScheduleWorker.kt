package io.github.mangi.eta.agent.cron

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class CronScheduleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_SCHEDULE_ID) ?: return Result.failure()
        val scheduledAt = inputData.getLong(KEY_DUE_AT, 0L)
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val dao = EtaDatabase.get(applicationContext).cronDao()
        val schedule = dao.schedule(id) ?: return Result.success()
        if (!manual && (!schedule.enabled || schedule.paused)) return Result.success()

        val now = System.currentTimeMillis()
        // A clock or timezone change can leave an old delayed request in the queue. Re-enqueue at the
        // persisted wall-clock due time rather than executing early.
        if (!manual && scheduledAt > now + 1_000L) {
            CronWorkScheduler.enqueueSchedule(applicationContext, id, scheduledAt, append = true)
            return Result.success()
        }

        val token = UUID.randomUUID().toString()
        val acquired = dao.acquireLock(
            id = id,
            token = token,
            now = now,
            lockUntil = now + LOCK_TTL_MS,
            allowPaused = manual,
            allowDisabled = manual,
        )
        if (acquired != 1) {
            val latest = dao.schedule(id) ?: return Result.success()
            return if (latest.lockUntil?.let { it > now } == true) Result.retry() else Result.success()
        }

        val dueAt = if (manual) now else scheduledAt
        // this.id is WorkManager's own request id for this enqueued work. Unlike a freshly
        // generated UUID, it stays the same across automatic retries of the same manual trigger,
        // so a retried manual run keeps updating one history row instead of splitting into several.
        val runKey = if (manual) "$id@manual-${this.id}" else "$id@$dueAt"
        val attempt = runAttemptCount + 1
        val startedAt = System.currentTimeMillis()
        val workerRunId = "cron-${UUID.randomUUID()}"
        try {
            dao.upsertRun(
                CronRunEntity(
                    runKey = runKey,
                    scheduleId = id,
                    scheduledAt = dueAt,
                    startedAt = startedAt,
                    status = "RUNNING",
                    attempt = attempt,
                    workerRunId = workerRunId,
                ),
            )

            val config = RuntimeConfigRepository.currentRuntimeConfig()
                ?: throw IllegalStateException("Belum ada penyedia/model AI aktif yang dikonfigurasi")
            val result = withContext(Dispatchers.IO) {
                AgentRuntimeClient(applicationContext, AndroidAgentLogger).run(
                    AgentRuntimeWire.RunRequest(
                        runId = workerRunId,
                        prompt = schedule.prompt,
                        config = config,
                        images = emptyList(),
                        // A cron run has no UI handoff or conversation identity. Its output is stored
                        // in cron history so background execution does not attach to a user's chat.
                    ),
                ) { /* Cron history is the background execution transcript boundary. */ }
            }

            if (!result.ok && attempt <= schedule.maxRetries) {
                dao.upsertRun(
                    CronRunEntity(
                        runKey = runKey, scheduleId = id, scheduledAt = dueAt,
                        startedAt = startedAt, status = "RETRYING", attempt = attempt,
                        output = result.content.take(MAX_HISTORY_CHARS),
                        error = result.error?.take(2_000) ?: "Runtime Agent mengembalikan hasil yang tidak berhasil",
                        workerRunId = workerRunId,
                    ),
                )
                return Result.retry()
            }

            val finishedAt = System.currentTimeMillis()
            val finalStatus = if (result.ok) "SUCCESS" else "FAILED"
            dao.upsertRun(
                CronRunEntity(
                    runKey = runKey, scheduleId = id, scheduledAt = dueAt,
                    startedAt = startedAt, finishedAt = finishedAt,
                    status = finalStatus, attempt = attempt,
                    output = result.content.take(MAX_HISTORY_CHARS),
                    error = result.error?.take(2_000), workerRunId = workerRunId,
                ),
            )
            finishSchedule(dao, schedule, finishedAt, finalStatus, result.error, manual)
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val finishedAt = System.currentTimeMillis()
            val message = error.message ?: error.javaClass.simpleName
            val shouldRetry = attempt <= schedule.maxRetries
            dao.upsertRun(
                CronRunEntity(
                    runKey = runKey, scheduleId = id, scheduledAt = dueAt,
                    startedAt = startedAt,
                    finishedAt = if (shouldRetry) null else finishedAt,
                    status = if (shouldRetry) "RETRYING" else "FAILED",
                    attempt = attempt,
                    error = message.take(2_000), workerRunId = workerRunId,
                ),
            )
            if (shouldRetry) return Result.retry()
            finishSchedule(dao, schedule, finishedAt, "FAILED", message, manual)
            return Result.success()
        } finally {
            dao.releaseLock(id, token, System.currentTimeMillis())
            runCatching { dao.pruneHistory(id, HISTORY_KEEP) }
        }
    }

    private suspend fun finishSchedule(
        dao: CronScheduleDao,
        original: CronScheduleEntity,
        finishedAt: Long,
        status: String,
        error: String?,
        manual: Boolean,
    ) {
        val latest = dao.schedule(original.id) ?: return
        val updated = if (manual) {
            latest.copy(
                lastRunAt = finishedAt,
                lastStatus = status,
                lastError = error?.take(2_000),
                lockToken = null,
                lockUntil = null,
                updatedAt = finishedAt,
            )
        } else {
            CronScheduleManager.afterRun(latest, finishedAt, status, error)
        }
        dao.upsertSchedule(updated)
        if (!manual && updated.enabled && !updated.paused) {
            CronWorkScheduler.enqueueSchedule(applicationContext, updated.id, updated.nextRunAt, append = true)
        }
    }

    companion object {
        private const val KEY_SCHEDULE_ID = "eta_cron_schedule_id"
        private const val KEY_DUE_AT = "eta_cron_due_at"
        private const val KEY_MANUAL = "eta_cron_manual"
        private const val LOCK_TTL_MS = 20 * 60 * 1_000L
        private const val MAX_HISTORY_CHARS = 48_000
        private const val HISTORY_KEEP = 250
    }
}
