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
    // Event-driven: no next_run_at polling. Fired by CronNotificationTrigger when a matching
    // notification is posted, instead of a WorkManager timer.
    const val NOTIFICATION = "notification"
}

internal object CronWorkScheduler {
    private const val KEY_SCHEDULE_ID = "eta_cron_schedule_id"
    private const val KEY_DUE_AT = "eta_cron_due_at"
    private const val KEY_MANUAL = "eta_cron_manual"

    private fun regularWorkName(id: String) = "eta-cron-schedule-$id"
    private fun manualWorkName(id: String) = "eta-cron-manual-$id"
    private fun eventWorkName(id: String) = "eta-cron-event-$id"

    // Automatic (non-manual) runs must not drain a nearly-empty battery in the background. A
    // manual "run now" tap is an explicit request, so it is exempt from this constraint.
    private fun automaticConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    private fun manualConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

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
            .setConstraints(automaticConstraints())
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
            .setConstraints(manualConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            manualWorkName(scheduleId), ExistingWorkPolicy.APPEND_OR_REPLACE, request,
        ).await()
    }

    /**
     * Fires a NOTIFICATION-type schedule right away. Unlike [enqueueManual], this still respects
     * enabled/paused (checked again at lock-acquire time against the latest row) — an event
     * firing is not the same as the user explicitly asking to run it.
     */
    suspend fun enqueueEventTrigger(context: Context, scheduleId: String) {
        val request = OneTimeWorkRequestBuilder<CronScheduleWorker>()
            .setInputData(workDataOf(
                KEY_SCHEDULE_ID to scheduleId,
                KEY_DUE_AT to System.currentTimeMillis(),
                KEY_MANUAL to false,
            ))
            .setConstraints(automaticConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            eventWorkName(scheduleId), ExistingWorkPolicy.APPEND_OR_REPLACE, request,
        ).await()
    }

    suspend fun cancel(context: Context, scheduleId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(regularWorkName(scheduleId)).await()
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(manualWorkName(scheduleId)).await()
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(eventWorkName(scheduleId)).await()
    }
}

/** Persistent schedule CRUD API shared by model tools, workers and lifecycle recovery. */
internal object CronScheduleManager {
    private const val MAX_PROMPT_CHARS = 12_000
    private const val MIN_INTERVAL_MINUTES = 1L
    private const val MAX_INTERVAL_MINUTES = 5_256_000L // Ten years; avoids timestamp overflow.
    private const val MAX_SCHEDULES = 100
    private const val CONFIRMATION_TTL_MS = 24 * 60 * 60 * 1_000L

    private fun dao(context: Context) = EtaDatabase.get(context.applicationContext).cronDao()

    private data class GeoCondition(val lat: Double, val lng: Double, val radiusM: Float, val transition: String)

    /** All four fields must arrive together (or not at all); anything else is a mistake worth failing loudly on. */
    private fun normalizeGeoCondition(
        scheduleType: String,
        lat: Double?,
        lng: Double?,
        radiusM: Float?,
        transition: String?,
    ): GeoCondition? {
        if (lat == null && lng == null && radiusM == null && transition == null) return null
        require(scheduleType != CronScheduleType.NOTIFICATION) {
            "A location condition cannot be combined with a notification schedule"
        }
        val safeLat = requireNotNull(lat) { "geo_lat is required once any location field is set" }
        val safeLng = requireNotNull(lng) { "geo_lng is required once any location field is set" }
        val safeRadius = requireNotNull(radiusM) { "geo_radius_m is required once any location field is set" }
        val safeTransition = requireNotNull(transition) {
            "geo_transition is required once any location field is set"
        }.uppercase()
        require(safeLat in -90.0..90.0) { "geo_lat must be between -90 and 90" }
        require(safeLng in -180.0..180.0) { "geo_lng must be between -180 and 180" }
        require(safeRadius in 50f..50_000f) { "geo_radius_m must be between 50 and 50000 meters" }
        require(safeTransition == "ENTER" || safeTransition == "EXIT") { "geo_transition must be ENTER or EXIT" }
        return GeoCondition(safeLat, safeLng, safeRadius, safeTransition)
    }

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
        dryRun: Boolean = false,
        notifyPackage: String? = null,
        notifyMatch: String? = null,
        geoLat: Double? = null,
        geoLng: Double? = null,
        geoRadiusM: Float? = null,
        geoTransition: String? = null,
        requireConfirmation: Boolean = false,
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
            CronScheduleType.INTERVAL, CronScheduleType.ONCE, CronScheduleType.NOTIFICATION -> null
            else -> throw IllegalArgumentException("schedule_type must be cron, interval, once or notification")
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
        val normalizedNotifyMatch = if (normalizedType == CronScheduleType.NOTIFICATION) {
            notifyMatch?.trim().orEmpty().also {
                require(it.isNotEmpty()) { "notify_match is required for notification schedules" }
            }.take(200)
        } else {
            require(notifyMatch == null && notifyPackage == null) {
                "notify_match/notify_package only apply to notification schedules"
            }
            null
        }
        val normalizedNotifyPackage = if (normalizedType == CronScheduleType.NOTIFICATION) {
            notifyPackage?.trim()?.takeIf { it.isNotEmpty() }
        } else null
        val geoCondition = normalizeGeoCondition(normalizedType, geoLat, geoLng, geoRadiusM, geoTransition)

        val nextRun = when (normalizedType) {
            CronScheduleType.CRON -> CronExpression.parse(requireNotNull(normalizedCron)).nextAfter(now, timeZoneId)
            CronScheduleType.INTERVAL -> now + TimeUnit.MINUTES.toMillis(requireNotNull(normalizedInterval))
            // No timer: CronNotificationTrigger fires this directly when a matching notification posts.
            CronScheduleType.NOTIFICATION -> 0L
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
            dryRun = dryRun,
            notifyPackage = normalizedNotifyPackage,
            notifyMatch = normalizedNotifyMatch,
            geoLat = geoCondition?.lat,
            geoLng = geoCondition?.lng,
            geoRadiusM = geoCondition?.radiusM,
            geoTransition = geoCondition?.transition,
            requireConfirmation = requireConfirmation,
        )
        dao(context).upsertSchedule(entity)
        if (normalizedType != CronScheduleType.NOTIFICATION) {
            CronWorkScheduler.enqueueSchedule(context, entity.id, entity.nextRunAt, append = false)
        }
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
        dryRun: Boolean? = null,
        requireConfirmation: Boolean? = null,
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
                // Event-driven: there is no timer to recompute, keep the 0L sentinel as-is.
                CronScheduleType.NOTIFICATION -> current.nextRunAt
                else -> requireNotNull(nextRunAt)
            },
            maxRetries = retries,
            dryRun = dryRun ?: current.dryRun,
            requireConfirmation = requireConfirmation ?: current.requireConfirmation,
            updatedAt = now,
        )
        dao(context).upsertSchedule(updated)
        if (current.scheduleType != CronScheduleType.NOTIFICATION) {
            CronWorkScheduler.enqueueSchedule(context, id, updated.nextRunAt, append = false)
        }
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
        if (current.scheduleType != CronScheduleType.NOTIFICATION) {
            CronWorkScheduler.enqueueSchedule(context, id, updated.nextRunAt, append = false)
        }
        return updated
    }

    suspend fun runNow(context: Context, id: String) {
        requireNotNull(dao(context).schedule(id)) { "Schedule not found: $id" }
        CronWorkScheduler.enqueueManual(context, id)
    }

    suspend fun list(context: Context): List<CronScheduleEntity> = dao(context).allSchedules()

    suspend fun pendingConfirmations(context: Context, limit: Int = 20): List<CronRunEntity> =
        dao(context).pendingConfirmations(limit.coerceIn(1, 100))

    /**
     * Resolves a PENDING_CONFIRMATION run. Approving does not execute inline — it hands off to
     * [CronWorkScheduler.enqueueManual], the same "run it now, bypass gates" path a user tapping
     * "run now" already goes through, so the actual execution shows up as its own fresh history
     * row while this row stays as the record of what was asked and decided.
     */
    suspend fun confirmPendingRun(context: Context, runKey: String, approve: Boolean): CronRunEntity {
        val database = dao(context)
        val run = requireNotNull(database.run(runKey)) { "Run not found: $runKey" }
        require(run.status == "PENDING_CONFIRMATION") {
            "This run is not awaiting confirmation (status=${run.status})"
        }
        val now = System.currentTimeMillis()
        if (now - run.scheduledAt > CONFIRMATION_TTL_MS) {
            val expired = run.copy(status = "EXPIRED", finishedAt = now)
            database.upsertRun(expired)
            return expired
        }
        val resolved = run.copy(status = if (approve) "APPROVED" else "DENIED", finishedAt = now)
        database.upsertRun(resolved)
        if (approve) {
            requireNotNull(database.schedule(run.scheduleId)) { "Schedule not found: ${run.scheduleId}" }
            CronWorkScheduler.enqueueManual(context, run.scheduleId)
        }
        return resolved
    }

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
            } else if (current.scheduleType == CronScheduleType.NOTIFICATION) {
                // Event-driven: no WorkManager timer to restore, it just waits for CronNotificationTrigger.
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
