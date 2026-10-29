package com.android.BioKey.core.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.android.BioKey.R
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.feature.main.MainActivity
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Lock <new app>?": an app just installed that auto-sort didn't lock (it doesn't recognise every
 * banking or chat app) is offered once, as a notification with a Lock button, so it doesn't stay
 * open until the owner happens to notice. Locking only ever adds protection, so the button needs
 * no authentication; taking a lock off still does, in BioKey.
 *
 * Nothing is kept: the notification is the whole of it. On by default; a switch in Settings.
 */
object NewAppSuggestions {

    const val CHANNEL_ID = "biokey_new_apps"
    private const val PREFS = "biokey_new_apps"
    private const val KEY_ENABLED = "enabled"
    private const val ACTION_LOCK = "com.android.BioKey.action.LOCK_NEW_APP"

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    @Volatile private var loaded = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        _enabled.value = prefs(context).getBoolean(KEY_ENABLED, true)
        loaded = true
    }

    fun setEnabled(context: Context, on: Boolean) {
        _enabled.value = on
        loaded = true
        prefs(context).edit { putBoolean(KEY_ENABLED, on) }
        if (on) ensureChannel(context)
    }

    private fun notificationId(pkg: String) = 2000 + (pkg.hashCode() and 0x7fff)

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.new_app_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.new_app_channel_description)
            }
        )
    }

    /** [pkg] was just installed (not updated), and auto-sort has had its turn. */
    fun onInstalled(context: Context, pkg: String, label: String) {
        load(context)
        if (!_enabled.value) return
        val vault = SecurityVault.getInstance(context)
        if (!vault.isSetupCompleted() || pkg == context.packageName) return
        // Auto-sort locked it already, or the owner had it on from before.
        if (pkg in vault.selectedAppsFlow.value) return
        // Only what can be opened from the home screen: that's what a lock covers.
        if (context.packageManager.getLaunchIntentForPackage(pkg) == null) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val lock = PendingIntent.getBroadcast(
            context,
            notificationId(pkg),
            Intent(context, LockReceiver::class.java).setAction(ACTION_LOCK).setData(Uri.fromParts("package", pkg, null)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Tapped, it opens BioKey at that app, to lock it in a profile of the owner's choosing.
        val open = PendingIntent.getActivity(
            context,
            notificationId(pkg),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SEARCH, label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(context.getString(R.string.new_app_public_title))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(context.getString(R.string.new_app_title, label))
            .setContentText(context.getString(R.string.new_app_text))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            // The new app's name stays off the phone's lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_tile_lock_all, context.getString(R.string.new_app_lock), lock)
            .setGroup(GROUP)
            .build()
        try {
            manager.notify(notificationId(pkg), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify.
        }
        updateSummary(context)
    }

    /** The notification's Lock button. Not exported: only the notification BioKey posted can send it. */
    class LockReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_LOCK) return
            val pkg = intent.data?.schemeSpecificPart ?: return
            NotificationManagerCompat.from(context).cancel(notificationId(pkg))
            // The vault may have to be read from disk (BioKey just started for this): not on the main thread.
            val pending: PendingResult? = goAsync()
            val app = context.applicationContext
            worker.execute {
                try {
                    // Still installed and openable: the button may be tapped long after.
                    if (app.packageManager.getLaunchIntentForPackage(pkg) != null) {
                        SecurityVault.getInstance(app).setAppLock(pkg, true)
                        DiagnosticLog.log("Locked $pkg from the new-app notification")
                    }
                    updateSummary(app)
                } finally {
                    pending?.finish()
                }
            }
        }
    }

    /** Where the Lock button's work runs; tests run it at once. */
    @VisibleForTesting
    internal var worker: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-new-app").apply { isDaemon = true } }

    private const val GROUP = "com.android.BioKey.NEW_APPS"
    private const val SUMMARY_ID = 1999

    /**
     * Several offers at once (a new phone restoring its apps) gather under one summary, which
     * opens BioKey; a lone offer stands on its own.
     */
    private fun updateSummary(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val offers = runCatching {
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .count { it.notification.group == GROUP && it.id != SUMMARY_ID }
        }.getOrDefault(0)
        if (offers < 2) {
            manager.cancel(SUMMARY_ID)
            return
        }
        val open = PendingIntent.getActivity(
            context,
            SUMMARY_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(context.resources.getQuantityString(R.plurals.new_apps_summary, offers, offers))
            .setContentText(context.getString(R.string.new_apps_summary_text))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(SUMMARY_ID, summary)
        } catch (_: SecurityException) {
        }
    }
}
