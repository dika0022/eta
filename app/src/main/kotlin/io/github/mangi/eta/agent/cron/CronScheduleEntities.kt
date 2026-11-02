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
)
