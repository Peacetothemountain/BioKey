package com.android.BioKey.core.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
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

    const val CHANNEL_ID = "biokey_protection_alerts"
    private const val ID_SERVICE_STOPPED = 1001
    private const val ID_STORAGE_CLEARED = 1103

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

    private const val PREFS = "biokey_protection_alerts"
    private const val KEY_REMINDERS_STOPPED = "reminders_stopped"

    /**
     * The owner stopped the reminders, and app locking is still switched off. A different failure
     * since (switched back on, but not running) is reminded about again: the stop was for a switch-off.
     */
    fun remindersStopped(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REMINDERS_STOPPED, false) &&
            ProtectionEngine.mode(context) == ProtectionEngine.Mode.OFF

    /** "Remind me": the reminders come back now, as if never stopped. */
    fun resumeReminders(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_REMINDERS_STOPPED) }
        DiagnosticLog.log("Reminders that app locking is off resumed by the owner")
        if (!ProtectionEngine.isProtecting(app)) ProtectionCheckJob.schedule(app)
    }

    /**
     * The owner switched app locking off on purpose: no more reminders until it's on again (it
     * stays recorded, and home still says it's off). Asked for with the owner's face or fingerprint.
     */
    fun stopReminders(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_REMINDERS_STOPPED, true) }
        ProtectionCheckJob.cancel(app)
        NotificationManagerCompat.from(app).cancel(ID_SERVICE_STOPPED)
        DiagnosticLog.log("Reminders that app locking is off stopped by the owner")
    }

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // Created, or updated: Android takes a new name and description, never touches the importance
        // the owner chose.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.alerts_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.alerts_channel_description)
            }
        )
    }

    /** [silent]: a reminder ([remindIfOff]) brings the alert back without a sound. */
    private fun post(context: Context, id: Int, title: String, text: String, ongoing: Boolean = false, silent: Boolean = false) {
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
            .setSilent(silent)
            // On the phone's lock screen, only that BioKey has something to say: whoever holds
            // the locked phone isn't told protection is off.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_biokey_mark)
                    .setContentTitle(context.getString(R.string.brand_name))
                    .build()
            )
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
        // Noted now, not after the grace: the process may not live that long. An engine back
        // within moments makes it no gap (ProtectionGaps' minimum).
        ProtectionGaps.onEngineDown(app)
        // The owner stopped the reminders (a deliberate switch-off): still recorded, not alerted.
        if (remindersStopped(app)) return
        // Reminders that outlive BioKey's process, until an engine runs again.
        ProtectionCheckJob.schedule(app)
        main.removeCallbacksAndMessages(STOP_TOKEN)
        if (now) {
            postServiceStopped(app)
            return
        }
        main.postDelayed({
            if (!BioKeyAccessibilityService.isRunning && !UsageWatchService.isRunning) postServiceStopped(app)
        }, STOP_TOKEN, HANDOVER_GRACE_MS)
    }

    private fun postServiceStopped(context: Context, silent: Boolean = false) {
        if (ProtectionEngine.shouldRunCompatibility(context)) {
            // Compatibility mode is the engine to run and has what it needs, but didn't start
            // (Android limits starting it from the background); opening BioKey starts it. Checked
            // first: under Advanced Protection, asking for permissions already granted would be
            // wrong. Not while the accessibility switch is still on: then BioKey won't start
            // compatibility mode.
            post(
                context,
                ID_SERVICE_STOPPED,
                context.getString(R.string.alert_off_title),
                context.getString(R.string.alert_compat_not_running),
                ongoing = true,
                silent = silent
            )
            return
        }
        if (AdvancedProtection.blocksBioKey(context)) {
            // Android switched the service off and won't let it back on: point to compatibility mode.
            post(
                context,
                ID_SERVICE_STOPPED,
                context.getString(R.string.alert_switch_to_compat_title),
                context.getString(R.string.alert_switch_to_compat),
                ongoing = true,
                silent = silent
            )
            return
        }
        post(
            context,
            ID_SERVICE_STOPPED,
            context.getString(R.string.alert_off_title),
            context.getString(R.string.alert_service_stopped),
            ongoing = true,
            silent = silent
        )
    }

    /**
     * Compatibility mode lost one of its permissions ([missing], e.g. "usage access") and stopped:
     * it can't see or cover locked apps without it.
     */
    fun compatibilityStopped(context: Context, missing: String) {
        ProtectionGaps.onEngineDown(context)
        ProtectionCheckJob.schedule(context.applicationContext)
        main.removeCallbacksAndMessages(STOP_TOKEN)
        post(
            context.applicationContext,
            ID_SERVICE_STOPPED,
            context.getString(R.string.alert_off_title),
            context.getString(R.string.alert_compat_lost, missing),
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

    /**
     * The phone was just unlocked: if app locking is still off, say so again. Its alert may have
     * been swiped away by whoever switched it off.
     */
    fun remindIfOff(context: Context) {
        val app = context.applicationContext
        if (!SecurityVault.getInstance(app).isSetupCompleted() || ProtectionEngine.isProtecting(app)) return
        if (remindersStopped(app)) return
        if (isShowing(app, ID_SERVICE_STOPPED)) return
        postServiceStopped(app, silent = true)
    }

    /**
     * BioKey's storage was cleared and it isn't set up again ([ProtectionGaps.checkStorageCleared]):
     * said, and again every so often ([ProtectionCheckJob]) and at each unlock once the accessibility
     * service is back on, until it is. Hidden on the phone's lock screen. (A clearing switches the
     * service off and, from Android 13, takes back the notification permission: there it's said
     * only once setup has asked for it again; setup's own screen says so first.)
     */
    fun remindIfStorageCleared(context: Context) {
        val app = context.applicationContext
        if (SecurityVault.getInstance(app).isSetupCompleted()) return
        val after = ProtectionGaps.clearedAt(app) ?: return
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) return
        UnlockAttempts.ensureChannel(app)
        val text = app.getString(
            R.string.storage_cleared_notification_text,
            DateUtils.formatDateTime(app, after, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
        )
        val open = PendingIntent.getActivity(
            app,
            ID_STORAGE_CLEARED,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(app, UnlockAttempts.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(app.getString(R.string.storage_cleared_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .build()
        try {
            manager.notify(ID_STORAGE_CLEARED, notification)
        } catch (e: SecurityException) {
            DiagnosticLog.log("Storage cleared alert not allowed: ${e.message}")
        }
    }

    fun cancelStorageCleared(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_STORAGE_CLEARED)
    }

    fun serviceRestored(context: Context) {
        ProtectionGaps.onEngineUp(context)
        ProtectionCheckJob.cancel(context)
        // Back on: the next time it stops, it's reminded about again.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_REMINDERS_STOPPED) }
        main.removeCallbacksAndMessages(STOP_TOKEN)
        main.removeCallbacksAndMessages(CONNECT_TOKEN)
        NotificationManagerCompat.from(context).cancel(ID_SERVICE_STOPPED)
    }
}
