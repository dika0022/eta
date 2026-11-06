package io.github.mangi.eta.agent.cron

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

        // Optional location condition on this schedule. A manual "run now" bypasses it, the same
        // way it already bypasses paused/disabled — an explicit tap means "run it regardless".
        val gate = if (manual) null else CronLocationGate.evaluate(applicationContext, schedule)
        if (gate != null && !gate.shouldRun) {
            try {
                dao.upsertRun(
                    CronRunEntity(
                        runKey = runKey, scheduleId = id, scheduledAt = dueAt,
                        startedAt = startedAt, finishedAt = startedAt,
                        status = "SKIPPED", attempt = attempt,
                        output = "Dilewati: ${gate.reason}", workerRunId = workerRunId,
                    ),
                )
                finishSchedule(dao, schedule, startedAt, "SKIPPED", null, manual)
                return Result.success()
            } finally {
                dao.releaseLock(id, token, System.currentTimeMillis())
                runCatching { dao.pruneHistory(id, HISTORY_KEEP) }
            }
        }

        // Same bypass rule as the location gate: a manual tap (which also covers a just-approved
        // confirmation, dispatched via enqueueManual) means "run it regardless", so only automatic
        // firings are ever parked for confirmation in the first place.
        if (!manual && schedule.requireConfirmation) {
            try {
                dao.upsertRun(
                    CronRunEntity(
                        runKey = runKey, scheduleId = id, scheduledAt = dueAt,
                        startedAt = startedAt, finishedAt = null,
                        status = "PENDING_CONFIRMATION", attempt = attempt,
                        output = "Menunggu persetujuan pengguna sebelum dijalankan.",
                        workerRunId = workerRunId,
                    ),
                )
                runCatching { CronConfirmationNotifier.notify(applicationContext, schedule, runKey) }
                finishSchedule(dao, schedule, startedAt, "PENDING_CONFIRMATION", null, manual)
                return Result.success()
            } finally {
                dao.releaseLock(id, token, System.currentTimeMillis())
                runCatching { dao.pruneHistory(id, HISTORY_KEEP) }
            }
        }

        // Captures a bounded audit trail of tool calls made during the run (name + short
        // previews, not full content), so an unattended run leaves behind more than just the
        // model's final text. Declared outside the try so the exception handler can read
        // whatever was captured before things went wrong. ConcurrentHashMap/synchronizedList
        // because this worker cannot verify the event callback is always delivered from one
        // thread.
        val pendingToolArgs = ConcurrentHashMap<String, String>()
        val auditEntries = Collections.synchronizedList(mutableListOf<JSONObject>())
        fun auditJson(): String? = if (auditEntries.isEmpty()) null else JSONArray(auditEntries).toString()

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
            val effectivePrompt = if (schedule.dryRun) DRY_RUN_PREFIX + schedule.prompt else schedule.prompt
            val result = withContext(Dispatchers.IO) {
                AgentRuntimeClient(applicationContext, AndroidAgentLogger).run(
                    AgentRuntimeWire.RunRequest(
                        runId = workerRunId,
                        prompt = effectivePrompt,
                        config = config,
                        images = emptyList(),
                        // A cron run has no UI handoff or conversation identity. Its output is stored
                        // in cron history so background execution does not attach to a user's chat.
                    ),
                ) { event ->
                    when (event) {
                        is AgentEvent.ToolStarted ->
                            pendingToolArgs[event.toolCallId] = event.argsPreview.take(AUDIT_FIELD_CHARS)
                        is AgentEvent.ToolFinished -> {
                            if (auditEntries.size < AUDIT_MAX_ENTRIES) {
                                auditEntries.add(
                                    JSONObject()
                                        .put("name", event.name)
                                        .put("args_preview", pendingToolArgs[event.toolCallId] ?: "")
                                        .put("result_summary", event.resultSummary.take(AUDIT_FIELD_CHARS))
                                        .put("success", event.success ?: JSONObject.NULL),
                                )
                            }
                            pendingToolArgs.remove(event.toolCallId)
                        }
                        else -> Unit
                    }
                }
            }

            if (!result.ok && attempt <= schedule.maxRetries) {
                dao.upsertRun(
                    CronRunEntity(
                        runKey = runKey, scheduleId = id, scheduledAt = dueAt,
                        startedAt = startedAt, status = "RETRYING", attempt = attempt,
                        output = result.content.take(MAX_HISTORY_CHARS),
                        error = result.error?.take(2_000) ?: "Runtime Agent mengembalikan hasil yang tidak berhasil",
                        workerRunId = workerRunId, toolCallsJson = auditJson(),
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
                    toolCallsJson = auditJson(),
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
                    toolCallsJson = auditJson(),
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
        // Event-driven (manual taps and notification triggers alike) has no next_run_at to
        // recompute and no WorkManager timer to re-enqueue; afterRun() only makes sense for
        // schedules with an actual cron/interval cadence.
        val eventDriven = manual || latest.scheduleType == CronScheduleType.NOTIFICATION
        val updated = if (eventDriven) {
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
        if (!eventDriven && updated.enabled && !updated.paused) {
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
        // Bounds the audit trail's worst case to roughly 30 * (~550 bytes) ≈ 16 KB — comfortably
        // small for a SQLite TEXT column and for the model to read back via cron_history.
        private const val AUDIT_MAX_ENTRIES = 30
        private const val AUDIT_FIELD_CHARS = 240
        private const val DRY_RUN_PREFIX = "[MODE PRATINJAU / DRY RUN]\n" +
            "Jadwal ini sedang diuji dalam mode pratinjau. JANGAN benar-benar menjalankan aksi " +
            "apa pun yang mengubah data, mengirim pesan, menghapus berkas, melakukan pembelian, " +
            "atau efek samping lain di dunia nyata — walau alat (tool) untuk itu tersedia. " +
            "Jelaskan saja langkah-langkah yang AKAN Anda lakukan dan alasannya, seolah-olah ini " +
            "rencana tertulis, bukan eksekusi sungguhan.\n\n---\n\n"
    }
}
