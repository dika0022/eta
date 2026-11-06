package io.github.mangi.eta.agent.cron

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Matches a posted notification against active NOTIFICATION-type cron schedules and fires the
 * ones that match. Called from [io.github.mangi.eta.agent.device.AgentNotificationHistoryService]
 * on its own binder thread, so this hops straight onto its own coroutine and never throws back
 * into the caller — a crash here must not take down notification-listener access for the app.
 */
internal object CronNotificationTrigger {
    fun onPosted(context: Context, packageName: String, title: String?, text: String?) {
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val dao = EtaDatabase.get(appContext).cronDao()
                val candidates = dao.activeSchedulesByType(CronScheduleType.NOTIFICATION)
                if (candidates.isEmpty()) return@runCatching
                val haystack = listOfNotNull(title, text).joinToString(" ").lowercase()
                candidates.forEach { schedule ->
                    val matchPackage = schedule.notifyPackage
                    val matchText = schedule.notifyMatch?.lowercase().orEmpty()
                    if (matchText.isEmpty()) return@forEach
                    val packageOk = matchPackage.isNullOrEmpty() || matchPackage.equals(packageName, ignoreCase = true)
                    val textOk = haystack.contains(matchText)
                    if (packageOk && textOk) {
                        CronWorkScheduler.enqueueEventTrigger(appContext, schedule.id)
                    }
                }
            }.onFailure { error ->
                AndroidAgentLogger.warn(
                    "Cron notification trigger failed: type=${error.javaClass.simpleName}",
                )
            }
        }
    }
}
