package io.github.mangi.eta.agent.cron

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Bridges the model tool calls to the persistent cron repository. */
internal class CronToolExecutor(context: Context) {
    private val appContext = context.applicationContext

    fun execute(name: String, args: JSONObject): String = try {
        runBlocking(Dispatchers.IO) {
            when (name) {
                "cron_create" -> create(args)
                "cron_list" -> list()
                "cron_update" -> update(args)
                "cron_delete" -> mutate(args) { id -> CronScheduleManager.delete(appContext, id) }
                "cron_pause" -> mutate(args) { id -> CronScheduleManager.pause(appContext, id) }
                "cron_resume" -> mutate(args) { id -> CronScheduleManager.resume(appContext, id) }
                "cron_run" -> mutate(args) { id -> CronScheduleManager.runNow(appContext, id) }
                "cron_history" -> history(args)
                "cron_pending" -> pending(args)
                "cron_confirm" -> confirm(args)
                else -> error("Unknown cron tool: $name")
            }
        }
    } catch (error: Exception) {
        JSONObject()
            .put("ok", false)
            .put("code", "CRON_OPERATION_FAILED")
            .put("message", error.message ?: error.javaClass.simpleName)
            .toString()
    }

    private suspend fun create(args: JSONObject): String {
        val schedule = CronScheduleManager.create(
            context = appContext,
            name = args.getString("name"),
            prompt = args.getString("prompt"),
            scheduleType = args.getString("schedule_type"),
            cronExpression = args.optString("cron_expression").takeIf(String::isNotBlank),
            intervalMinutes = args.takeLong("interval_minutes"),
            runAtMillis = args.takeLong("run_at_ms"),
            timeZoneId = args.optString("time_zone").takeIf(String::isNotBlank)
                ?: java.time.ZoneId.systemDefault().id,
            maxRetries = args.takeInt("max_retries") ?: 2,
            dryRun = args.takeBoolean("dry_run") ?: false,
            notifyPackage = args.optString("notify_package").takeIf(String::isNotBlank),
            notifyMatch = args.optString("notify_match").takeIf(String::isNotBlank),
            geoLat = args.takeDouble("geo_lat"),
            geoLng = args.takeDouble("geo_lng"),
            geoRadiusM = args.takeDouble("geo_radius_m")?.toFloat(),
            geoTransition = args.optString("geo_transition").takeIf(String::isNotBlank),
            requireConfirmation = args.takeBoolean("require_confirmation") ?: false,
        )
        return JSONObject().put("ok", true).put("schedule", scheduleJson(schedule)).toString()
    }

    private suspend fun list(): String {
        val schedules = CronScheduleManager.list(appContext)
        return JSONObject()
            .put("ok", true)
            .put("count", schedules.size)
            .put("schedules", JSONArray().also { array -> schedules.forEach { array.put(scheduleJson(it)) } })
            .toString()
    }

    private suspend fun update(args: JSONObject): String {
        val schedule = CronScheduleManager.update(
            context = appContext,
            id = args.getString("id"),
            name = args.optString("name").takeIf(String::isNotBlank),
            prompt = args.optString("prompt").takeIf(String::isNotBlank),
            cronExpression = args.optString("cron_expression").takeIf(String::isNotBlank),
            intervalMinutes = args.takeLong("interval_minutes"),
            runAtMillis = args.takeLong("run_at_ms"),
            timeZoneId = args.optString("time_zone").takeIf(String::isNotBlank),
            maxRetries = args.takeInt("max_retries"),
            dryRun = args.takeBoolean("dry_run"),
            requireConfirmation = args.takeBoolean("require_confirmation"),
        )
        return JSONObject().put("ok", true).put("schedule", scheduleJson(schedule)).toString()
    }

    private suspend fun mutate(args: JSONObject, operation: suspend (String) -> Any?): String {
        val id = args.getString("id")
        val result = operation(id)
        val payload = JSONObject().put("ok", true).put("id", id)
        // delete()/runNow() return Unit (not null), so checking "!is CronScheduleEntity" is what
        // actually distinguishes the void operations (cron_delete, cron_run) from the ones that
        // hand back the updated schedule (cron_pause, cron_resume).
        if (result is CronScheduleEntity) payload.put("schedule", scheduleJson(result)) else payload.put("accepted", true)
        return payload.toString()
    }

    private suspend fun history(args: JSONObject): String {
        val id = args.getString("id")
        val rows = CronScheduleManager.history(appContext, id, args.optInt("limit", 20))
        return JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("runs", JSONArray().also { array -> rows.forEach { array.put(runJson(it)) } })
            .toString()
    }

    private suspend fun pending(args: JSONObject): String {
        val rows = CronScheduleManager.pendingConfirmations(appContext, args.optInt("limit", 20))
        return JSONObject()
            .put("ok", true)
            .put("count", rows.size)
            .put("pending", JSONArray().also { array ->
                rows.forEach { array.put(runJson(it).put("schedule_id", it.scheduleId)) }
            })
            .toString()
    }

    private suspend fun confirm(args: JSONObject): String {
        val run = CronScheduleManager.confirmPendingRun(
            context = appContext,
            runKey = args.getString("run_key"),
            approve = args.getBoolean("approve"),
        )
        return JSONObject().put("ok", true).put("run", runJson(run)).toString()
    }

    private fun runJson(row: CronRunEntity): JSONObject = JSONObject()
        .put("run_key", row.runKey)
        .put("scheduled_at", row.scheduledAt)
        .put("started_at", row.startedAt)
        .put("finished_at", row.finishedAt ?: JSONObject.NULL)
        .put("status", row.status)
        .put("attempt", row.attempt)
        .put("output", row.output)
        .put("error", row.error ?: JSONObject.NULL)
        .put("worker_run_id", row.workerRunId ?: JSONObject.NULL)
        .put("tool_calls", row.toolCallsJson?.let { JSONArray(it) } ?: JSONArray())

    private fun scheduleJson(schedule: CronScheduleEntity): JSONObject = JSONObject()
        .put("id", schedule.id)
        .put("name", schedule.name)
        .put("prompt", schedule.prompt)
        .put("schedule_type", schedule.scheduleType)
        .put("cron_expression", schedule.cronExpression ?: JSONObject.NULL)
        .put("interval_minutes", schedule.intervalMinutes ?: JSONObject.NULL)
        .put("run_at", schedule.runAtMillis?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        .put("run_at_ms", schedule.runAtMillis ?: JSONObject.NULL)
        .put("time_zone", schedule.timeZoneId)
        .put("next_run_at", schedule.nextRunAt)
        .put("enabled", schedule.enabled)
        .put("paused", schedule.paused)
        .put("max_retries", schedule.maxRetries)
        .put("last_run_at", schedule.lastRunAt ?: JSONObject.NULL)
        .put("last_status", schedule.lastStatus ?: JSONObject.NULL)
        .put("last_error", schedule.lastError ?: JSONObject.NULL)
        .put("dry_run", schedule.dryRun)
        .put("notify_package", schedule.notifyPackage ?: JSONObject.NULL)
        .put("notify_match", schedule.notifyMatch ?: JSONObject.NULL)
        .put("geo_lat", schedule.geoLat ?: JSONObject.NULL)
        .put("geo_lng", schedule.geoLng ?: JSONObject.NULL)
        .put("geo_radius_m", schedule.geoRadiusM ?: JSONObject.NULL)
        .put("geo_transition", schedule.geoTransition ?: JSONObject.NULL)
        .put("require_confirmation", schedule.requireConfirmation)

    private fun JSONObject.takeLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val value = get(key)) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.takeInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return when (val value = get(key)) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun JSONObject.takeDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val value = get(key)) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONObject.takeBoolean(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return when (val value = get(key)) {
            is Boolean -> value
            is String -> value.trim().toBooleanStrictOrNull()
            else -> null
        }
    }
}
