package com.android.BioKey.core.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.android.BioKey.R
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.feature.main.MainActivity
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * While the owner has paused protection, says so outside BioKey: a quiet notification that stays
 * until protection is back, with a Resume button. A pause "until I turn it back on" is otherwise
 * easy to forget, and nothing but BioKey's own screens and its tile would show it.
 *
 * Compatibility mode's own notification already says "Protection paused" (Android requires that
 * one while it runs), so this one is left out there. Kept off the lock screen, like BioKey's other
 * notifications: it would tell whoever picked the phone up that locked apps open without a check.
 *
 * Resume only ever puts protection back, so it asks for nothing ([LockAllNow] does the same).
 */
object PauseNotice {

    const val CHANNEL_ID = "biokey_paused"
    private const val NOTIFICATION_ID = 1201
    internal const val ACTION_RESUME = "com.android.BioKey.action.RESUME_PROTECTION"

    private val watching = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Keeps the notification in step with the pause from here on. Once per run of BioKey. */
    fun watch(context: Context) {
        if (!watching.compareAndSet(false, true)) return
        val app = context.applicationContext
        val vault = SecurityVault.getInstance(app)
        scope.launch {
            combine(vault.setupCompletedFlow, vault.globalLockEnabledFlow, vault.pausedUntilFlow) { _, _, _ -> }
                .collect { runCatching { update(app) } }
        }
    }

    /** Whether the notification belongs up right now. */
    @VisibleForTesting
    internal fun shows(context: Context): Boolean {
        val vault = SecurityVault.getInstance(context)
        return vault.isSetupCompleted() && !vault.isProtectionActive() &&
            // Compatibility mode's own notification says it already.
            ProtectionEngine.mode(context) != ProtectionEngine.Mode.COMPATIBILITY
    }

    /** Puts the notification up, changes what it says, or takes it down, as things stand. */
    fun update(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (!shows(app)) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!manager.areNotificationsEnabled()) return
        // Created, or its name and description updated (Android keeps the owner's importance).
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, app.getString(R.string.pause_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = app.getString(R.string.pause_channel_description)
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            app, NOTIFICATION_ID,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val resume = PendingIntent.getBroadcast(
            app, NOTIFICATION_ID,
            Intent(app, ResumeReceiver::class.java).setAction(ACTION_RESUME).setPackage(app.packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = text(app)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(app.getString(R.string.compat_notification_title_paused))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, app.getString(R.string.pause_notice_resume), resume)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify.
        }
    }

    /** What home says under "Paused": until when, or until resumed. */
    @VisibleForTesting
    internal fun text(context: Context): String {
        val until = SecurityVault.getInstance(context).pausedUntilFlow.value
        return if (until > System.currentTimeMillis()) {
            context.getString(R.string.home_paused_body_timed, com.android.BioKey.ui.format.clockTime(context, until))
        } else context.getString(R.string.home_paused_body)
    }

    /** The notification's Resume button. Not exported: only the notification BioKey posted can send it. */
    class ResumeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RESUME) return
            val app = context.applicationContext
            val vault = SecurityVault.getInstance(app)
            if (vault.isSetupCompleted() && !vault.isProtectionActive()) {
                DiagnosticLog.log("Protection resumed from its notification")
                // Nothing unlocked during the pause carries over ([SecurityVault.resumeProtection]).
                vault.resumeProtection()
            }
            runCatching { update(app) }
        }
    }

    @VisibleForTesting
    internal const val NOTIFICATION_ID_FOR_TESTS = NOTIFICATION_ID
}
