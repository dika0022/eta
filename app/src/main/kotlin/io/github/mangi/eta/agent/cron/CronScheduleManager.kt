package io.github.mangi.eta.agent.cron

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import io.github.mangi.eta.data.db.EtaDatabase
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object CronScheduleType {
    const val CRON = "cron"
    const val INTERVAL = "interval"
    const val ONCE = "once"
}

internal object CronWorkScheduler {
    private const val KEY_SCHEDULE_ID = "eta_cron_schedule_id"
    private const val KEY_DUE_AT = "eta_cron_due_at"
    private const val KEY_MANUAL = "eta_cron_manual"

    private fun regularWorkName(id: String) = "eta-cron-schedule-$id"
    private fun manualWorkName(id: String) = "eta-cron-manual-$id"

    suspend fun enqueueSchedule(
        context: Context,
        scheduleId: String,
        dueAt: Long,
        append: Boolean,
        replace: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        val request = OneTimeWorkRequestBuilder<CronScheduleWorker>()
            .setInputData(workDataOf(
                KEY_SCHEDULE_ID to scheduleId,
                KEY_DUE_AT to dueAt,
                KEY_MANUAL to false,
            ))
            .setInitialDelay((dueAt - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            regularWorkName(scheduleId),
            when {
                append -> ExistingWorkPolicy.APPEND_OR_REPLACE
                replace -> ExistingWorkPolicy.REPLACE
                else -> ExistingWorkPolicy.KEEP
            },
            request,
        ).await()
    }

    suspend fun enqueueManual(context: Context, scheduleId: String) {
        val request = OneTimeWorkRequestBuilder<CronScheduleWorker>()
            .setInputData(workDataOf(
                KEY_SCHEDULE_ID to scheduleId,
                KEY_DUE_AT to System.currentTimeMillis(),
                KEY_MANUAL to true,
            ))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            manualWorkName(scheduleId), ExistingWorkPolicy.APPEND_OR_REPLACE, request,
        ).await()
    }

    suspend fun cancel(context: Context, scheduleId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(regularWorkName(scheduleId)).await()
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(manualWorkName(scheduleId)).await()
    }
}

/** Persistent schedule CRUD API shared by model tools, workers and lifecycle recovery. */
internal object CronScheduleManager {
    private const val MAX_PROMPT_CHARS = 12_000
    private const val MIN_INTERVAL_MINUTES = 1L
    private const val MAX_INTERVAL_MINUTES = 5_256_000L // Ten years; avoids timestamp overflow.
    private const val MAX_SCHEDULES = 100

    private fun dao(context: Context) = EtaDatabase.get(context.applicationContext).cronDao()

    suspend fun create(
        context: Context,
        name: String,
        prompt: String,
        scheduleType: String,
        cronExpression: String? = null,
        intervalMinutes: Long? = null,
        runAtMillis: Long? = null,
        timeZoneId: String = ZoneId.systemDefault().id,
        maxRetries: Int = 2,
    ): CronScheduleEntity {
        val safeName = name.trim().take(100)
        val safePrompt = prompt.trim()
        require(safeName.isNotEmpty()) { "Nama jadwal wajib diisi" }
        require(safePrompt.isNotEmpty() && safePrompt.length <= MAX_PROMPT_CHARS) {
            "Prompt must contain 1-$MAX_PROMPT_CHARS characters"
        }
        require(maxRetries in 0..5) { "max_retries must be between 0 and 5" }
        ZoneId.of(timeZoneId)

        require(dao(context).allSchedules().size < MAX_SCHEDULES) {
            "At most $MAX_SCHEDULES scheduled tasks can be stored"
        }
        val now = System.currentTimeMillis()
        val normalizedType = scheduleType.lowercase()
        val normalizedCron = when (normalizedType) {
            CronScheduleType.CRON -> {
                require(!cronExpression.isNullOrBlank()) { "cron_expression is required for cron schedules" }
                CronExpression.parse(cronExpression.trim())
                cronExpression.trim()
            }
            CronScheduleType.INTERVAL, CronScheduleType.ONCE -> null
            else -> throw IllegalArgumentException("schedule_type must be cron, interval or once")
        }
        val normalizedInterval = if (normalizedType == CronScheduleType.INTERVAL) {
            requireNotNull(intervalMinutes) { "interval_minutes is required for interval schedules" }
                .also { require(it in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES) {
                    "interval_minutes must be between 1 and $MAX_INTERVAL_MINUTES"
                } }
        } else null
        val normalizedRunAt = if (normalizedType == CronScheduleType.ONCE) {
            requireNotNull(runAtMillis) { "run_at_ms is required for once schedules" }
                .also { require(it > 0L) { "run_at_ms must be a positive Unix epoch timestamp" } }
        } else null

        val nextRun = when (normalizedType) {
            CronScheduleType.CRON -> CronExpression.parse(requireNotNull(normalizedCron)).nextAfter(now, timeZoneId)
            CronScheduleType.INTERVAL -> now + TimeUnit.MINUTES.toMillis(requireNotNull(normalizedInterval))
            else -> requireNotNull(normalizedRunAt)
        }
        val entity = CronScheduleEntity(
            id = UUID.randomUUID().toString(),
            name = safeName,
            prompt = safePrompt,
            scheduleType = normalizedType,
            cronExpression = normalizedCron,
            intervalMinutes = normalizedInterval,
            runAtMillis = normalizedRunAt,
            timeZoneId = timeZoneId,
            nextRunAt = nextRun,
            enabled = true,
            paused = false,
            maxRetries = maxRetries,
            createdAt = now,
            updatedAt = now,
        )
        dao(context).upsertSchedule(entity)
        CronWorkScheduler.enqueueSchedule(context, entity.id, entity.nextRunAt, append = false)
        return entity
    }

    suspend fun update(
        context: Context,
        id: String,
        name: String? = null,
        prompt: String? = null,
        cronExpression: String? = null,
        intervalMinutes: Long? = null,
        runAtMillis: Long? = null,
        timeZoneId: String? = null,
        maxRetries: Int? = null,
    ): CronScheduleEntity {
        val current = requireNotNull(dao(context).schedule(id)) { "Schedule not found: $id" }
        val zone = timeZoneId ?: current.timeZoneId
        ZoneId.of(zone)
        val newName = name?.trim()?.take(100) ?: current.name
        val newPrompt = prompt?.trim() ?: current.prompt
        require(newName.isNotEmpty()) { "Nama jadwal tidak boleh kosong" }
        require(newPrompt.isNotEmpty() && newPrompt.length <= MAX_PROMPT_CHARS) { "Panjang instruksi tidak valid" }
        val nextCron = if (current.scheduleType == CronScheduleType.CRON) {
            (cronExpression ?: current.cronExpression ?: error("Ekspresi cron belum diisi")).also {
                CronExpression.parse(it)
            }
        } else current.cronExpression
        val nextInterval = if (current.scheduleType == CronScheduleType.INTERVAL) {
            (intervalMinutes ?: current.intervalMinutes ?: error("Jarak pengulangan belum diisi")).also {
                require(it in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES) {
                    "interval_minutes must be between 1 and $MAX_INTERVAL_MINUTES"
                }
            }
        } else current.intervalMinutes
        val nextRunAt = if (current.scheduleType == CronScheduleType.ONCE) {
            runAtMillis ?: current.runAtMillis ?: error("Missing run_at_ms")
        } else current.runAtMillis
        val retries = maxRetries ?: current.maxRetries
        require(retries in 0..5) { "max_retries must be between 0 and 5" }
        val now = System.currentTimeMillis()
        val updated = current.copy(
            name = newName,
            prompt = newPrompt,
            cronExpression = nextCron,
            intervalMinutes = nextInterval,
            runAtMillis = nextRunAt,
            timeZoneId = zone,
            nextRunAt = when (current.scheduleType) {
                CronScheduleType.CRON -> CronExpression.parse(requireNotNull(nextCron)).nextAfter(now, zone)
                CronScheduleType.INTERVAL -> now + TimeUnit.MINUTES.toMillis(requireNotNull(nextInterval))
                else -> requireNotNull(nextRunAt)
            },
            maxRetries = retries,
            updatedAt = now,
        )
        dao(context).upsertSchedule(updated)
        CronWorkScheduler.enqueueSchedule(context, id, updated.nextRunAt, append = false)
        return updated
    }

    suspend fun delete(context: Context, id: String) {
        CronWorkScheduler.cancel(context, id)
        dao(context).deleteSchedule(id)
    }

    suspend fun pause(context: Context, id: String): CronScheduleEntity {
        val current = requireNotNull(dao(context).schedule(id)) { "Schedule not found: $id" }
        val updated = current.copy(paused = true, updatedAt = System.currentTimeMillis())
        dao(context).upsertSchedule(updated)
        CronWorkScheduler.cancel(context, id)
        return updated
    }

    suspend fun resume(context: Context, id: String): CronScheduleEntity {
        val current = requireNotNull(dao(context).schedule(id)) { "Schedule not found: $id" }
        require(current.enabled) { "Jadwal sekali jalan ini telah selesai. Buat jadwal baru untuk mengulanginya" }
        val now = System.currentTimeMillis()
        val next = if (current.nextRunAt > now) current.nextRunAt else when (current.scheduleType) {
            CronScheduleType.CRON -> CronExpression.parse(requireNotNull(current.cronExpression)).nextAfter(now, current.timeZoneId)
            CronScheduleType.INTERVAL -> now + TimeUnit.MINUTES.toMillis(current.intervalMinutes ?: MIN_INTERVAL_MINUTES)
            else -> current.nextRunAt
        }
        val updated = current.copy(paused = false, nextRunAt = next, updatedAt = now)
        dao(context).upsertSchedule(updated)
        CronWorkScheduler.enqueueSchedule(context, id, updated.nextRunAt, append = false)
        return updated
    }

    suspend fun runNow(context: Context, id: String) {
        requireNotNull(dao(context).schedule(id)) { "Schedule not found: $id" }
        CronWorkScheduler.enqueueManual(context, id)
    }

    suspend fun list(context: Context): List<CronScheduleEntity> = dao(context).allSchedules()

    suspend fun history(context: Context, id: String, limit: Int): List<CronRunEntity> {
        requireNotNull(dao(context).schedule(id)) { "Schedule not found: $id" }
        return dao(context).history(id, limit.coerceIn(1, 100))
    }

    suspend fun rescheduleAll(context: Context, refreshCronTimes: Boolean = false) {
        val now = System.currentTimeMillis()
        val database = dao(context)
        database.allSchedules().forEach { current ->
            if (!current.enabled || current.paused) {
                CronWorkScheduler.cancel(context, current.id)
            } else {
                val next = if (refreshCronTimes && current.scheduleType == CronScheduleType.CRON) {
                    CronExpression.parse(requireNotNull(current.cronExpression)).nextAfter(now, current.timeZoneId)
                } else current.nextRunAt
                val updated = if (next == current.nextRunAt) current else current.copy(nextRunAt = next, updatedAt = now)
                if (updated != current) database.upsertSchedule(updated)
                CronWorkScheduler.enqueueSchedule(
                    context, current.id, updated.nextRunAt, append = false,
                    replace = refreshCronTimes,
                )
            }
        }
    }

    internal fun afterRun(schedule: CronScheduleEntity, now: Long, status: String, error: String?): CronScheduleEntity {
        val next = when (schedule.scheduleType) {
            CronScheduleType.CRON -> CronExpression.parse(requireNotNull(schedule.cronExpression))
                .nextAfter(now, schedule.timeZoneId)
            CronScheduleType.INTERVAL -> {
                val interval = TimeUnit.MINUTES.toMillis(schedule.intervalMinutes ?: MIN_INTERVAL_MINUTES)
                var candidate = schedule.nextRunAt + interval
                while (candidate <= now) candidate += interval
                candidate
            }
            else -> 0L
        }
        val recurring = schedule.scheduleType != CronScheduleType.ONCE
        return schedule.copy(
            enabled = if (recurring) schedule.enabled else false,
            nextRunAt = next,
            lastRunAt = now,
            lastStatus = status,
            lastError = error?.take(2_000),
            lockToken = null,
            lockUntil = null,
            updatedAt = now,
        )
    }
}
