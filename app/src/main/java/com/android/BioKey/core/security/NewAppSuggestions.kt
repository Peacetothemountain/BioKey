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
import com.android.BioKey.core.FeatureLinks
import com.android.BioKey.core.diagnostics.DiagnosticLog
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
    private const val ACTION_DISMISSED = "com.android.BioKey.action.NEW_APP_DISMISSED"

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

    // Clear of every other id BioKey posts (compatibility mode's own is 2001).
    private fun notificationId(pkg: String) = 10_000 + (pkg.hashCode() and 0x7fff)

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
            FeatureLinks.openMainScreen(context).putExtra(FeatureLinks.EXTRA_SEARCH, label),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Swiped away: the summary counts one fewer.
        val dismissed = PendingIntent.getBroadcast(
            context,
            notificationId(pkg),
            Intent(context, LockReceiver::class.java).setAction(ACTION_DISMISSED).setData(Uri.fromParts("package", pkg, null)),
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
            .setDeleteIntent(dismissed)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_tile_lock_all, context.getString(R.string.new_app_lock), lock)
            .setGroup(GROUP)
            .build()
        try {
            manager.notify(notificationId(pkg), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify.
            return
        }
        updateSummary(context, posted = notificationId(pkg))
    }

    /** [pkg] was uninstalled: an offer to lock it would lock nothing. */
    fun onUninstalled(context: Context, pkg: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(pkg))
        updateSummary(context, cancelled = notificationId(pkg))
    }

    /**
     * Offers gone without a word to BioKey (tapped, which opens BioKey): the summary is counted
     * again, once Android's list of what's showing has caught up. Off the calling thread.
     */
    fun refreshSummarySoon(context: Context) {
        val app = context.applicationContext
        worker.execute { runCatching { updateSummary(app) } }
    }

    /**
     * The notification's Lock button, and an offer swiped away. Not exported: only the
     * notification BioKey posted can send it.
     */
    class LockReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (intent.action == ACTION_DISMISSED) {
                val app = context.applicationContext
                val pending: PendingResult? = goAsync()
                worker.execute {
                    try {
                        updateSummary(app, cancelled = notificationId(pkg))
                    } finally {
                        pending?.finish()
                    }
                }
                return
            }
            if (intent.action != ACTION_LOCK) return
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
                    updateSummary(app, cancelled = notificationId(pkg))
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
    /** The summary's count of offers, kept in it. */
    private const val EXTRA_COUNT = "com.android.BioKey.NEW_APPS_COUNT"

    /**
     * Several offers at once (a new phone restoring its apps) gather under one summary, which
     * opens BioKey; a lone offer stands on its own. [posted] or [cancelled]: an offer's id just
     * posted or cancelled. Android's list of what's showing is updated a moment later, so that one
     * is counted (or not) as it will be.
     */
    @Synchronized
    private fun updateSummary(context: Context, posted: Int? = null, cancelled: Int? = null) {
        val manager = NotificationManagerCompat.from(context)
        val active = runCatching { context.getSystemService(NotificationManager::class.java).activeNotifications.toList() }
            .getOrDefault(emptyList())
        // By channel, not group: Android may regroup an app's notifications on its own.
        val ids = active.filter {
            it.notification.channelId == CHANNEL_ID && it.id != SUMMARY_ID && it.tag == null &&
                it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0
        }.map { it.id }.toMutableSet()
        posted?.let(ids::add)
        cancelled?.let(ids::remove)
        val offers = ids.size
        if (offers == 0) {
            manager.cancel(SUMMARY_ID)
            return
        }
        // Down to one under a summary that's up: the summary stays, counting one. Cancelling it
        // would take the last offer with it (Android cancels a group with its summary).
        val shown = active.firstOrNull { it.id == SUMMARY_ID }
        if (offers < 2 && shown == null) return
        // Already counting these: posted again, it would only move up the list.
        if (shown != null && shown.notification.extras.getInt(EXTRA_COUNT, -1) == offers) return
        val open = PendingIntent.getActivity(
            context,
            SUMMARY_ID,
            FeatureLinks.openMainScreen(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(context.resources.getQuantityString(R.plurals.new_apps_summary, offers, offers))
            .addExtras(android.os.Bundle().apply { putInt(EXTRA_COUNT, offers) })
            .setContentText(context.getString(R.string.new_apps_summary_text))
            .setGroup(GROUP)
            .setGroupSummary(true)
            // Recounted, not news.
            .setOnlyAlertOnce(true)
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
