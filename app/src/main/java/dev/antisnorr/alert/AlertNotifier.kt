package dev.antisnorr.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.antisnorr.R

/**
 * Local notification plumbing.
 *
 * Two channels:
 *  - MONITOR: the persistent foreground-service notification.
 *  - ALERT: the short-lived "you are snoring" notification. Gadgetbridge
 *    picks it up through its notification listener and forwards it to the band.
 */
object AlertNotifier {
    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_ALERT = "snore_alerts"

    private const val ALERT_LIFETIME_MS = 20_000L
    private const val ALERT_ID_BASE = 1_000

    private val handler = Handler(Looper.getMainLooper())
    private var counter = 0

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            context.getString(R.string.notification_channel_monitor),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_monitor_text)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        val alerts = NotificationChannel(
            CHANNEL_ALERT,
            context.getString(R.string.notification_channel_alert),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.alert_title)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannel(monitor)
        manager.createNotificationChannel(alerts)
    }

    fun postAlert(context: Context, title: String, text: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val id = ALERT_ID_BASE + (counter++ % 100)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_snore)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            return
        }

        // Do not leave a pile of alerts on the lock screen after a long night.
        handler.postDelayed({
            NotificationManagerCompat.from(context).cancel(id)
        }, ALERT_LIFETIME_MS)
    }
}
