package io.github.mangi.eta.agent.cron

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.MainActivity

/**
 * Alerts the user that a schedule marked require_confirmation is waiting on them. Tapping it just
 * opens the app — there is no dedicated review screen yet, approval happens by asking Eta
 * (cron_pending / cron_confirm), the same way every other cron_* tool already works.
 */
internal object CronConfirmationNotifier {
    private const val CHANNEL = "eta_cron_confirmations"
    private const val NOTIFICATION_ID = 1125

    fun notify(context: Context, schedule: CronScheduleEntity, runKey: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Persetujuan jadwal cron", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Menunggu persetujuan: ${schedule.name}")
            .setContentText("Tanya Eta \"apa yang menunggu persetujuan?\" untuk menyetujui atau menolak.")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        if (manager.areNotificationsEnabled()) manager.notify(runKey, NOTIFICATION_ID, notification)
    }
}
