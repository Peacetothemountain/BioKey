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
 * High-priority alerts for when BioKey's protection stops working, so a disabled service or a
 * removed device admin doesn't go unnoticed.
 */
object ProtectionAlerts {

    private const val CHANNEL_ID = "biokey_protection_alerts"
    private const val ID_SERVICE_STOPPED = 1001
    private const val ID_ADMIN_REMOVED = 1002
    private const val ID_TAMPER = 1003
    private const val ID_IDENTITY_DELAY = 1004
    private const val ID_LOG_TAMPER = 1005

    fun ensureChannels(context: Context) {
        ensureChannel(context)
    }

    private fun ensureChannel(context: Context) {
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
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
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
        "BioKey's app lock service stopped, so locked apps are not protected. Open BioKey to turn it back on.",
        ongoing = true
    )

    fun serviceRestored(context: Context) = NotificationManagerCompat.from(context).cancel(ID_SERVICE_STOPPED)

    fun adminRemoved(context: Context) = post(
        context,
        ID_ADMIN_REMOVED,
        "BioKey device admin removed",
        "BioKey can now be uninstalled without warning. If this wasn't you, open BioKey and re-enable it.",
        ongoing = true
    )

    fun adminRestored(context: Context) = NotificationManagerCompat.from(context).cancel(ID_ADMIN_REMOVED)

    fun identityCheckDelayStarted(context: Context, until: String) = post(
        context,
        ID_IDENTITY_DELAY,
        "Biometrics changed on this device",
        "A fingerprint or face was added or removed. Identity Check keeps your locked apps closed until $until. " +
            "If this wasn't you, someone may know your screen lock: change it now."
    )

    fun tamperDetected(context: Context) = post(
        context,
        ID_TAMPER,
        "BioKey settings were modified",
        "BioKey's settings changed outside the app. Protection has been re-armed; review your locked apps."
    )

    fun logTamperDetected(context: Context) = post(
        context,
        ID_LOG_TAMPER,
        "Security log integrity alert",
        "BioKey's security event log failed integrity checks or was modified externally. Review settings immediately."
    )
}
