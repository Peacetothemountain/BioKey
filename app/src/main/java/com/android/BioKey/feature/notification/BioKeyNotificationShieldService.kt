package com.android.BioKey.feature.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.android.BioKey.R
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.LockScreenActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * Non-destructive notification shield: while a locked app is locked, its notifications are
 * snoozed in the shade and replaced by one neutral "N hidden notifications" entry per app.
 *
 * Tapping the entry opens BioKey's lock and then launches the preserved content Intent.
 * When the app is unlocked from launcher or recents, the stand-in notification is automatically
 * dismissed and snoozed notifications are restored.
 */
class BioKeyNotificationShieldService : NotificationListenerService() {

    companion object {
        private const val TAG = "BioKeyNotifShield"
        private const val CHANNEL_ID = "biokey_privacy_shield_channel"
        private const val TAG_PREFIX = "biokey-shield:"
        private const val SUMMARY_ID = 1
        private const val SNOOZE_24_HOURS_MS = 24 * 60 * 60 * 1000L

        private val IGNORED_PACKAGES = setOf("android", "com.android.systemui")

        /** Deep-link intents cached per locked package to preserve notification routing. */
        val cachedPendingIntents = ConcurrentHashMap<String, PendingIntent>()

        fun isNotificationAccessGranted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun getNotificationSettingsIntent(context: Context? = null): Intent =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && context != null) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        android.content.ComponentName(context, BioKeyNotificationShieldService::class.java).flattenToString()
                    )
                }
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
    }

    private val vault by lazy { SecurityVault.getInstance(applicationContext) }

    /** Set of active snoozed notification keys per package to prevent count ballooning. */
    private val hiddenNotificationKeys = ConcurrentHashMap<String, MutableSet<String>>()
    /** Set of active snoozed group summary keys per package so summaries are properly restored. */
    private val hiddenSummaryKeys = ConcurrentHashMap<String, MutableSet<String>>()

    @Volatile private var connected = false

    private val onLockAll: () -> Unit = { if (connected) sweepActiveNotifications() }
    private val onAppUnlocked: (String) -> Unit = { pkg ->
        if (connected) restoreNotificationsForPackage(pkg)
    }
    private val onAppLocked: (String) -> Unit = { _ ->
        if (connected) sweepActiveNotifications()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        LockStateTracker.addLockAllListener(onLockAll)
        LockStateTracker.addAppUnlockListener(onAppUnlocked)
        LockStateTracker.addAppLockListener(onAppLocked)
        sweepActiveNotifications()
    }

    override fun onListenerDisconnected() {
        connected = false
        LockStateTracker.removeLockAllListener(onLockAll)
        LockStateTracker.removeAppUnlockListener(onAppUnlocked)
        LockStateTracker.removeAppLockListener(onAppLocked)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        LockStateTracker.removeLockAllListener(onLockAll)
        LockStateTracker.removeAppUnlockListener(onAppUnlocked)
        LockStateTracker.removeAppLockListener(onAppLocked)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (shouldHide(sbn)) hide(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Our replacement entry was tapped or swiped away
        if (sbn.packageName == packageName) {
            sbn.tag?.takeIf { it.startsWith(TAG_PREFIX) }?.let {
                val pkg = it.removePrefix(TAG_PREFIX)
                hiddenNotificationKeys.remove(pkg)
                hiddenSummaryKeys.remove(pkg)
                // Retain cachedPendingIntents so LockScreenActivity can consume it via .remove(pkg) upon unlock
            }
        }
    }

    private fun sweepActiveNotifications() {
        val active = try {
            activeNotifications
        } catch (e: Exception) {
            Log.w(TAG, "Listener not bound or active notifications unavailable: ${e.message}")
            return
        } ?: return
        active.filter(::shouldHide).forEach(::hide)
    }

    private fun shouldHide(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName ?: return false
        if (pkg == packageName || pkg in IGNORED_PACKAGES) return false
        if (!sbn.isClearable || sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        // Never suppress emergency calls or alarms
        if (sbn.notification.category in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM)) return false
        if (sbn.notification.fullScreenIntent != null) return false

        if (!vault.isGlobalLockEnabled() || !vault.isNotificationShieldEnabled()) return false
        if (!vault.isAppLocked(pkg)) return false
        return !LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode())
    }

    private fun hide(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        try {
            // Non-destructive snooze: snooze notification for 24h so user messages and OTPs are preserved
            snoozeNotification(sbn.key, SNOOZE_24_HOURS_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't snooze notification: ${e.message}")
            return
        }

        // Cache deep link intent for navigation after unlock
        sbn.notification.contentIntent?.let { cachedPendingIntents[pkg] = it }

        val isSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (isSummary) {
            hiddenSummaryKeys.computeIfAbsent(pkg) { ConcurrentHashMap.newKeySet() }.add(sbn.key)
        } else {
            hiddenNotificationKeys.computeIfAbsent(pkg) { ConcurrentHashMap.newKeySet() }.add(sbn.key)
        }
        val childCount = hiddenNotificationKeys[pkg]?.size ?: 0
        val count = if (childCount > 0) childCount else 1
        postReplacement(pkg, count)
    }

    private fun restoreNotificationsForPackage(pkg: String) {
        // Dismiss stand-in notification immediately
        NotificationManagerCompat.from(this).cancel(TAG_PREFIX + pkg, SUMMARY_ID)
        val childKeys = hiddenNotificationKeys.remove(pkg).orEmpty()
        val summaryKeys = hiddenSummaryKeys.remove(pkg).orEmpty()
        val allKeys = childKeys + summaryKeys
        if (allKeys.isEmpty()) return
        for (key in allKeys) {
            runCatching {
                // Re-awaken snoozed notification
                snoozeNotification(key, 50L)
            }
        }
    }

    private fun postReplacement(lockedPackage: String, count: Int) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()

        val unlockIntent = Intent(this, LockScreenActivity::class.java).apply {
            putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, lockedPackage)
            putExtra(LockScreenActivity.EXTRA_LAUNCH_AFTER_UNLOCK, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            lockedPackage.hashCode(),
            unlockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(lockedPackage, 0)).toString()
        } catch (_: Exception) {
            "Locked app"
        }
        val publicVersion = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock_key)
            .setContentTitle("Locked app")
            .setContentText("Notification hidden by BioKey")
            .build()
        val text = if (count == 1) "1 hidden notification" else "$count hidden notifications"

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock_key)
            .setContentTitle(label)
            .setContentText("$text · tap to unlock")
            .setNumber(count)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setLocalOnly(true)
            .build()
        try {
            manager.notify(TAG_PREFIX + lockedPackage, SUMMARY_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify.
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Hidden notifications", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Stand-ins for notifications from locked apps"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }
}
