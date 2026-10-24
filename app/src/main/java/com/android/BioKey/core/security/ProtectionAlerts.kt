package com.android.BioKey.core.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.android.BioKey.R
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.UsageWatchService
import com.android.BioKey.feature.main.MainActivity

/**
 * High-priority alerts for when BioKey's protection stops working.
 *
 * Every alert opens BioKey, never a system settings page directly: turning an engine back on goes
 * through BioKey's own disclosure and consent first (Play policy).
 */
object ProtectionAlerts {

    private const val CHANNEL_ID = "biokey_protection_alerts"
    private const val ID_SERVICE_STOPPED = 1001

    /**
     * How long a stopped engine waits before alerting. When one engine hands over to the other
     * (Advanced Protection turned on, accessibility switched back on), the next one starts within
     * this time and there is nothing to alert about.
     */
    private const val HANDOVER_GRACE_MS = 3_000L

    /**
     * How long BioKey's accessibility service, switched on, gets to connect once BioKey's process
     * starts before it counts as not running ([checkAccessibilityConnects]): Android connects it
     * moments after the process starts, unless it gave up restarting one that kept crashing.
     */
    private const val CONNECT_WAIT_MS = 10_000L
    private val STOP_TOKEN = Any()
    private val CONNECT_TOKEN = Any()
    private val main = Handler(Looper.getMainLooper())

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
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        val notification = builder.build()
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify.
        }
    }

    /**
     * An engine stopped. Unless [now], the alert waits [HANDOVER_GRACE_MS] and is only posted if no
     * engine is running by then.
     */
    fun serviceStopped(context: Context, now: Boolean = false) {
        val app = context.applicationContext
        main.removeCallbacksAndMessages(STOP_TOKEN)
        if (now) {
            postServiceStopped(app)
            return
        }
        main.postDelayed({
            if (!BioKeyAccessibilityService.isRunning && !UsageWatchService.isRunning) postServiceStopped(app)
        }, STOP_TOKEN, HANDOVER_GRACE_MS)
    }

    private fun postServiceStopped(context: Context) {
        if (ProtectionEngine.shouldRunCompatibility(context)) {
            // Compatibility mode is the engine to run and has what it needs, but didn't start
            // (Android limits starting it from the background); opening BioKey starts it. Checked
            // first: under Advanced Protection, asking for permissions already granted would be
            // wrong. Not while the accessibility switch is still on: then BioKey won't start
            // compatibility mode.
            post(
                context,
                ID_SERVICE_STOPPED,
                "App lock is off",
                "BioKey's compatibility mode isn't running, so locked apps are not protected. Tap to open BioKey and start it.",
                ongoing = true
            )
            return
        }
        if (AdvancedProtection.blocksBioKey(context)) {
            // Android switched the service off and won't let it back on: point to compatibility mode.
            post(
                context,
                ID_SERVICE_STOPPED,
                "Switch BioKey to compatibility mode",
                "Advanced Protection reserves accessibility for accessibility tools. Tap to give BioKey usage access and display-over-apps instead, so it keeps locking your apps.",
                ongoing = true
            )
            return
        }
        post(
            context,
            ID_SERVICE_STOPPED,
            "App lock is off",
            "BioKey's app lock service stopped, so locked apps are not protected. Tap to open BioKey and turn it back on.",
            ongoing = true
        )
    }

    /**
     * Compatibility mode lost one of its permissions ([missing], e.g. "usage access") and stopped:
     * it can't see or cover locked apps without it.
     */
    fun compatibilityStopped(context: Context, missing: String) {
        main.removeCallbacksAndMessages(STOP_TOKEN)
        post(
            context.applicationContext,
            ID_SERVICE_STOPPED,
            "App lock is off",
            "BioKey's compatibility mode lost $missing, so locked apps are not protected. Tap to open BioKey and turn it back on.",
            ongoing = true
        )
    }

    /**
     * BioKey's process just started (the notification shield rebinding, the tile, an alarm). With
     * the accessibility switch on, the service still not running [CONNECT_WAIT_MS] later means
     * Android stopped restarting it after repeated crashes: nothing covers apps, and the switch
     * still reads as on in Settings. Alerts then, as when an engine stops; the alert goes as soon
     * as the service connects ([serviceRestored]). The boot receiver checks the same after a boot.
     */
    fun checkAccessibilityConnects(context: Context) {
        val app = context.applicationContext
        main.removeCallbacksAndMessages(CONNECT_TOKEN)
        main.postDelayed({
            if (SecurityVault.getInstance(app).isSetupCompleted() &&
                ProtectionEngine.mode(app) == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING &&
                !isShowing(app, ID_SERVICE_STOPPED) // The boot check's own, say: not again.
            ) {
                DiagnosticLog.log("Accessibility engine is switched on but not running")
                postServiceStopped(app)
            }
        }, CONNECT_TOKEN, CONNECT_WAIT_MS)
    }

    private fun isShowing(context: Context, id: Int): Boolean =
        runCatching { context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == id } }.getOrDefault(false)

    fun serviceRestored(context: Context) {
        main.removeCallbacksAndMessages(STOP_TOKEN)
        main.removeCallbacksAndMessages(CONNECT_TOKEN)
        NotificationManagerCompat.from(context).cancel(ID_SERVICE_STOPPED)
    }
}
