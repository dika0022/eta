package io.github.mangi.eta.agent.cron

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_cron_schedules",
    indices = [
        Index(value = ["enabled", "paused", "next_run_at"]),
        Index(value = ["lock_until"]),
    ],
)
internal data class CronScheduleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val prompt: String,
    @ColumnInfo(name = "schedule_type") val scheduleType: String,
    @ColumnInfo(name = "cron_expression") val cronExpression: String? = null,
    @ColumnInfo(name = "interval_minutes") val intervalMinutes: Long? = null,
    @ColumnInfo(name = "run_at_ms") val runAtMillis: Long? = null,
    @ColumnInfo(name = "time_zone_id") val timeZoneId: String,
    @ColumnInfo(name = "next_run_at") val nextRunAt: Long,
    val enabled: Boolean,
    val paused: Boolean,
    @ColumnInfo(name = "max_retries") val maxRetries: Int,
    @ColumnInfo(name = "last_run_at") val lastRunAt: Long? = null,
    @ColumnInfo(name = "last_status") val lastStatus: String? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "lock_token") val lockToken: String? = null,
    @ColumnInfo(name = "lock_until") val lockUntil: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "dry_run") val dryRun: Boolean = false,
    // Only used when scheduleType == CronScheduleType.NOTIFICATION.
    @ColumnInfo(name = "notify_package") val notifyPackage: String? = null,
    @ColumnInfo(name = "notify_match") val notifyMatch: String? = null,
    // Optional location gate attachable to CRON/INTERVAL/ONCE schedules: when set, the schedule's
    // normal timer still fires, but CronLocationGate decides whether to actually run the prompt
    // based on the device's last known (passively read) location. All four must be set together.
    @ColumnInfo(name = "geo_lat") val geoLat: Double? = null,
    @ColumnInfo(name = "geo_lng") val geoLng: Double? = null,
    @ColumnInfo(name = "geo_radius_m") val geoRadiusM: Float? = null,
    @ColumnInfo(name = "geo_transition") val geoTransition: String? = null,
    // When true, an automatic trigger (not a manual "run now") parks as PENDING_CONFIRMATION
    // instead of running immediately; see CronScheduleManager.confirmPendingRun.
    @ColumnInfo(name = "require_confirmation") val requireConfirmation: Boolean = false,
)

@Entity(
    tableName = "agent_cron_runs",
    foreignKeys = [
        ForeignKey(
            entity = CronScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("schedule_id"), Index("started_at")],
)
internal data class CronRunEntity(
    @PrimaryKey @ColumnInfo(name = "run_key") val runKey: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: String,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    val status: String,
    val attempt: Int,
    val output: String = "",
    val error: String? = null,
    @ColumnInfo(name = "worker_run_id") val workerRunId: String? = null,
    // Compact JSON array of {name, args_preview, result_summary, success} per tool call made
    // during this run — see CronToolCallAudit. Capped in size; null when there were none yet
    // (e.g. a PENDING_CONFIRMATION row) or the run predates this column.
    @ColumnInfo(name = "tool_calls_json") val toolCallsJson: String? = null,
)
