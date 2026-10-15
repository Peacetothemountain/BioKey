package com.android.BioKey.core.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.android.BioKey.R
import com.android.BioKey.feature.main.MainActivity

/**
 * High-priority alerts for when BioKey's protection stops working.
 */
object ProtectionAlerts {

    private const val CHANNEL_ID = "biokey_protection_alerts"
    private const val ID_SERVICE_STOPPED = 1001

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Protection alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warns when app locking stops working"
            }
        )
    }

    private fun post(context: Context, id: Int, title: String, text: String, ongoing: Boolean = false) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val turnOnIntent = PendingIntent.getActivity(
            context,
            id + 1,
            com.android.BioKey.feature.main.IntentNavigationHelper.accessibilitySettingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock_key)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(open)
            .addAction(R.drawable.ic_lock_key, "Turn on", turnOnIntent)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify.
        }
    }

    fun serviceStopped(context: Context) = post(
        context,
        ID_SERVICE_STOPPED,
        "App lock is off",
        "BioKey's app lock service stopped, so locked apps are not protected. Tap to turn it back on.",
        ongoing = true
    )

    fun serviceRestored(context: Context) = NotificationManagerCompat.from(context).cancel(ID_SERVICE_STOPPED)
}
