package io.github.mangi.eta.agent.cron

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Rebinds wall-clock cron triggers after boot, clock changes or timezone changes. */
internal class CronRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val refreshCron = intent?.action == "android.intent.action.TIME_SET" ||
                    intent?.action == Intent.ACTION_TIMEZONE_CHANGED ||
                    intent?.action == Intent.ACTION_DATE_CHANGED
                // A failure here (e.g. during BOOT_COMPLETED) must not crash the process; log and
                // move on, the same way EtaApp.onCreate() guards its own rescheduleAll() call.
                runCatching {
                    CronScheduleManager.rescheduleAll(context.applicationContext, refreshCronTimes = refreshCron)
                }.onFailure { error ->
                    AndroidAgentLogger.warn(
                        "Cron reschedule on ${intent?.action} failed: type=${error.javaClass.simpleName}",
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
