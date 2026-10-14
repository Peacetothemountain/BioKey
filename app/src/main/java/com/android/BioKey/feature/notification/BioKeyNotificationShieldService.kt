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

        /** Deep-link intents cached per locked package and notification key with post-time metadata. */
        val cachedPendingIntents = ConcurrentHashMap<String, ConcurrentHashMap<String, CachedPendingIntent>>()

        @Volatile private var activeService: BioKeyNotificationShieldService? = null

        fun restoreAllSnoozedNotifications() {
            activeService?.restoreAll()
        }

        fun restoreNotificationsForPackages(packages: Collection<String>) {
            activeService?.restorePackages(packages)
        }

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

    data class CachedPendingIntent(
        val pendingIntent: PendingIntent,
        val postTime: Long,
        val isSummary: Boolean
    )

    private data class RestoringEntry(
        val packageName: String,
        val expiryElapsed: Long
    )

    private val vault by lazy { SecurityVault.getInstance(applicationContext) }

    /** Set of active snoozed notification keys per package to prevent count ballooning. */
    private val hiddenNotificationKeys = ConcurrentHashMap<String, MutableSet<String>>()
    /** Set of active snoozed group summary keys per package so summaries are properly restored. */
    private val hiddenSummaryKeys = ConcurrentHashMap<String, MutableSet<String>>()
    /** Keys currently being un-snoozed via snoozeNotification(key, 50L), mapped to package and expiry elapsedRealtime. */
    private val restoringKeys = ConcurrentHashMap<String, RestoringEntry>()

    @Volatile private var connected = false

    private val onLockAll: () -> Unit = {
        restoringKeys.clear()
        if (connected) sweepActiveNotifications()
    }
    private val onAppUnlocked: (String) -> Unit = { pkg ->
        if (connected) restoreNotificationsForPackage(pkg)
    }
    private val onAppLocked: (String) -> Unit = { pkg ->
        restoringKeys.entries.removeIf { it.value.packageName == pkg }
        if (connected) sweepActiveNotifications()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeService = this
        connected = true
        LockStateTracker.addLockAllListener(onLockAll)
        LockStateTracker.addAppUnlockListener(onAppUnlocked)
        LockStateTracker.addAppLockListener(onAppLocked)
        sweepActiveNotifications()
    }

    override fun onListenerDisconnected() {
        connected = false
        activeService = null
        LockStateTracker.removeLockAllListener(onLockAll)
        LockStateTracker.removeAppUnlockListener(onAppUnlocked)
        LockStateTracker.removeAppLockListener(onAppLocked)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        activeService = null
        LockStateTracker.removeLockAllListener(onLockAll)
        LockStateTracker.removeAppUnlockListener(onAppUnlocked)
        LockStateTracker.removeAppLockListener(onAppLocked)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (shouldHide(sbn)) hide(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        if (reason == REASON_SNOOZED) {
            // Ignored: triggered by our own snoozeNotification(sbn.key, SNOOZE_24_HOURS_MS) call.
            return
        }
        handleNotificationRemoved(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        handleNotificationRemoved(sbn)
    }

    private fun handleNotificationRemoved(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) {
            return
        }

        // An underlying notification from a locked app was dismissed or revoked by the app itself:
        val removedFromChildren = hiddenNotificationKeys[pkg]?.remove(sbn.key) == true
        val removedFromSummaries = hiddenSummaryKeys[pkg]?.remove(sbn.key) == true
        cachedPendingIntents[pkg]?.remove(sbn.key)
        restoringKeys.remove(sbn.key)

        if (removedFromChildren || removedFromSummaries) {
            val remainingCount = hiddenNotificationKeys[pkg]?.size ?: 0
            if (remainingCount == 0) {
                // No hidden notifications remain; dismiss the stand-in and un-snooze any orphaned group summaries
                NotificationManagerCompat.from(this).cancel(TAG_PREFIX + pkg, SUMMARY_ID)
                cachedPendingIntents.remove(pkg)
                hiddenNotificationKeys.remove(pkg)
                val leftoverSummaries = hiddenSummaryKeys.remove(pkg).orEmpty()
                val expiry = android.os.SystemClock.elapsedRealtime() + 2_500L
                for (summaryKey in leftoverSummaries) {
                    restoringKeys[summaryKey] = RestoringEntry(pkg, expiry)
                    runCatching { snoozeNotification(summaryKey, 50L) }
                }
            } else {
                // Update stand-in notification with the remaining count
                postReplacement(pkg, remainingCount)
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

        val restoreEntry = restoringKeys[sbn.key]
        if (restoreEntry != null) {
            if (android.os.SystemClock.elapsedRealtime() < restoreEntry.expiryElapsed) {
                restoringKeys.remove(sbn.key)
                return false
            }
            restoringKeys.remove(sbn.key)
        }

        val notif = sbn.notification
        if (!sbn.isClearable || sbn.isOngoing) return false
        if (notif.flags and (Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT) != 0) return false
        // Never suppress calls, alarms, media transport, progress, navigation, or active services
        if (notif.category in setOf(
                Notification.CATEGORY_CALL,
                Notification.CATEGORY_ALARM,
                Notification.CATEGORY_TRANSPORT,
                Notification.CATEGORY_PROGRESS,
                Notification.CATEGORY_SERVICE,
                Notification.CATEGORY_NAVIGATION
            )
        ) return false
        if (notif.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return false
        if (notif.fullScreenIntent != null) return false

        if (!vault.isGlobalLockEnabled() || !vault.isNotificationShieldEnabled()) return false
        if (!vault.isAppLocked(pkg)) return false
        return !LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))
    }

    private fun hide(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val isSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        // Cache deep link intent before snoozing: child notifications take precedence over group summaries
        sbn.notification.contentIntent?.let { intent ->
            val pkgIntents = cachedPendingIntents.computeIfAbsent(pkg) { ConcurrentHashMap() }
            pkgIntents[sbn.key] = CachedPendingIntent(intent, sbn.postTime, isSummary)
        }

        if (isSummary) {
            hiddenSummaryKeys.computeIfAbsent(pkg) { ConcurrentHashMap.newKeySet() }.add(sbn.key)
        } else {
            hiddenNotificationKeys.computeIfAbsent(pkg) { ConcurrentHashMap.newKeySet() }.add(sbn.key)
        }

        try {
            // Non-destructive snooze: snooze notification for 24h so user messages and OTPs are preserved
            snoozeNotification(sbn.key, SNOOZE_24_HOURS_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't snooze notification: ${e.message}")
            hiddenSummaryKeys[pkg]?.remove(sbn.key)
            hiddenNotificationKeys[pkg]?.remove(sbn.key)
            cachedPendingIntents[pkg]?.remove(sbn.key)
            return
        }

        val childCount = hiddenNotificationKeys[pkg]?.size ?: 0
        val count = if (childCount > 0) childCount else 1
        postReplacement(pkg, count)
    }

    fun restoreAll() {
        val pkgs = (hiddenNotificationKeys.keys + hiddenSummaryKeys.keys + cachedPendingIntents.keys).toSet()
        for (pkg in pkgs) {
            restoreNotificationsForPackage(pkg)
        }
    }

    fun restorePackages(packages: Collection<String>) {
        if (!connected) return
        for (pkg in packages) {
            restoreNotificationsForPackage(pkg)
        }
    }

    private fun restoreNotificationsForPackage(pkg: String) {
        // Dismiss stand-in notification immediately
        NotificationManagerCompat.from(this).cancel(TAG_PREFIX + pkg, SUMMARY_ID)
        cachedPendingIntents.remove(pkg)
        val childKeys = hiddenNotificationKeys.remove(pkg).orEmpty()
        val summaryKeys = hiddenSummaryKeys.remove(pkg).orEmpty()
        val allKeys = childKeys + summaryKeys
        if (allKeys.isEmpty()) return
        val expiry = android.os.SystemClock.elapsedRealtime() + 2_500L
        for (key in allKeys) {
            restoringKeys[key] = RestoringEntry(pkg, expiry)
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
            data = android.net.Uri.fromParts("package", lockedPackage, null)
            putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, lockedPackage)
            putExtra(LockScreenActivity.EXTRA_LAUNCH_AFTER_UNLOCK, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            unlockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val label = com.android.BioKey.feature.lock.AppInfoCache.get(lockedPackage)?.label ?: try {
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
            .setAutoCancel(false)
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
