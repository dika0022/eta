package io.github.mangi.eta.agent.cron

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
internal interface CronScheduleDao {
    @Query("SELECT * FROM agent_cron_schedules ORDER BY created_at DESC")
    suspend fun allSchedules(): List<CronScheduleEntity>

    @Query("SELECT * FROM agent_cron_schedules WHERE id = :id LIMIT 1")
    suspend fun schedule(id: String): CronScheduleEntity?

    @Query("SELECT * FROM agent_cron_schedules WHERE enabled = 1 AND paused = 0 ORDER BY next_run_at ASC")
    suspend fun activeSchedules(): List<CronScheduleEntity>

    @Upsert
    suspend fun upsertSchedule(schedule: CronScheduleEntity)

    @Query("DELETE FROM agent_cron_schedules WHERE id = :id")
    suspend fun deleteSchedule(id: String)

    /** Atomic database lease prevents overlapping work even if WorkManager delivers duplicate requests. */
    @Query(
        "UPDATE agent_cron_schedules SET lock_token = :token, lock_until = :lockUntil, updated_at = :now " +
            "WHERE id = :id AND (:allowDisabled = 1 OR enabled = 1) " +
            "AND (:allowPaused = 1 OR paused = 0) " +
            "AND (lock_token IS NULL OR lock_until IS NULL OR lock_until <= :now)",
    )
    suspend fun acquireLock(
        id: String,
        token: String,
        now: Long,
        lockUntil: Long,
        allowPaused: Boolean,
        allowDisabled: Boolean,
    ): Int

    @Query(
        "UPDATE agent_cron_schedules SET lock_token = NULL, lock_until = NULL, updated_at = :now " +
            "WHERE id = :id AND lock_token = :token",
    )
    suspend fun releaseLock(id: String, token: String, now: Long)

    @Upsert
    suspend fun upsertRun(run: CronRunEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRunIfAbsent(run: CronRunEntity): Long

    @Query("SELECT * FROM agent_cron_runs WHERE schedule_id = :scheduleId ORDER BY started_at DESC LIMIT :limit")
    suspend fun history(scheduleId: String, limit: Int): List<CronRunEntity>

    @Query("SELECT * FROM agent_cron_runs WHERE run_key = :runKey LIMIT 1")
    suspend fun run(runKey: String): CronRunEntity?

    /** Keep the most recent records for a schedule; background history must not grow forever. */
    @Query(
        "DELETE FROM agent_cron_runs WHERE schedule_id = :scheduleId AND run_key NOT IN " +
            "(SELECT run_key FROM agent_cron_runs WHERE schedule_id = :scheduleId " +
            "ORDER BY started_at DESC LIMIT :keep)",
    )
    suspend fun pruneHistory(scheduleId: String, keep: Int)
}
