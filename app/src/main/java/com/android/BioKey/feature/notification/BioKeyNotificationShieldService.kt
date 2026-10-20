package com.android.BioKey.feature.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.android.BioKey.R
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.AppInfoCache
import com.android.BioKey.feature.lock.LockScreenActivity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Non-destructive notification shield: while a locked app is locked, its notifications are
 * snoozed in the shade and replaced by one neutral "N hidden notifications" entry per app.
 *
 * Tapping the entry opens BioKey's lock and then launches the preserved content Intent.
 * When the app is unlocked from launcher or recents, the stand-in notification is automatically
 * dismissed and snoozed notifications are restored.
 *
 * Everything runs on the main thread: listener callbacks arrive there, and session changes
 * (which come from any thread, often in the middle of covering an app) are posted there, so the
 * engines' cover always goes up before any of the shield's work.
 */
class BioKeyNotificationShieldService : NotificationListenerService() {

    companion object {
        private const val TAG = "BioKeyNotifShield"
        private const val CHANNEL_ID = "biokey_privacy_shield_channel"
        private const val TAG_PREFIX = "biokey-shield:"
        private const val SUMMARY_ID = 1
        private const val SNOOZE_24_HOURS_MS = 24 * 60 * 60 * 1000L

        /**
         * How often hidden notifications' snoozes are pushed back (at the next lock-all), so the
         * 24 h snooze never runs out while the app stays locked.
         */
        private const val EXTEND_SNOOZES_EVERY_MS = 6 * 60 * 60 * 1000L

        /** How long a notification being un-snoozed may take to come back before it can be hidden again. */
        private const val RESTORE_WINDOW_MS = 2_500L

        private val IGNORED_PACKAGES = setOf("android", "com.android.systemui")

        /** Deep-link intents cached per locked package and notification key with post-time metadata. */
        val cachedPendingIntents = ConcurrentHashMap<String, ConcurrentHashMap<String, CachedPendingIntent>>()

        @Volatile private var activeService: BioKeyNotificationShieldService? = null

        /** Hands [pkg]'s deep links to the lock screen, once. */
        fun takeDeepLinks(pkg: String): Map<String, CachedPendingIntent>? = cachedPendingIntents.remove(pkg)

        fun restoreAllSnoozedNotifications() {
            val service = activeService ?: return
            service.main.post { service.restoreAll() }
        }

        fun restoreNotificationsForPackages(packages: Collection<String>) {
            val service = activeService ?: return
            val pkgs = packages.toList()
            service.main.post { service.restorePackages(pkgs) }
        }

        fun isNotificationAccessGranted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /**
         * Removes from [hidden] (package -> keys) every key not in [present]. Returns the packages
         * that lost keys: an app can withdraw a notification while it is snoozed, and listeners
         * aren't told.
         */
        internal fun forgetWithdrawn(hidden: MutableMap<String, MutableSet<String>>, present: Set<String>): Set<String> {
            val changed = HashSet<String>()
            for ((pkg, keys) in hidden) if (keys.retainAll(present)) changed += pkg
            return changed
        }

        /** Calls, alarms, media, navigation etc. must always show. */
        private val NEVER_HIDDEN_CATEGORIES = setOf(
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_NAVIGATION
        )
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
    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null

    /** Set of active snoozed notification keys per package to prevent count ballooning. */
    private val hiddenNotificationKeys = HashMap<String, MutableSet<String>>()
    /** Set of active snoozed group summary keys per package so summaries are properly restored. */
    private val hiddenSummaryKeys = HashMap<String, MutableSet<String>>()
    /** Keys currently being un-snoozed via snoozeNotification(key, 50L), mapped to package and expiry elapsedRealtime. */
    private val restoringKeys = HashMap<String, RestoringEntry>()

    @Volatile private var connected = false
    private var sweepPending = false
    private var snoozesExtendedAt = 0L
    private var channelReady = false

    private val onLockAll: () -> Unit = { main.post(::onAllSessionsEnded) }
    private val onAppUnlocked: (String) -> Unit = { pkg ->
        main.post { if (connected) restoreNotificationsForPackage(pkg) }
    }
    private val onAppLocked: (String) -> Unit = { pkg ->
        main.post {
            restoringKeys.entries.removeIf { it.value.packageName == pkg }
            requestSweep()
        }
    }

    private val sweepRunnable = Runnable {
        sweepPending = false
        if (connected) sweepActiveNotifications()
    }

    /** One sweep for any number of requests until it runs (adding 20 locked apps ends 20 sessions). */
    private fun requestSweep() {
        if (sweepPending) return
        sweepPending = true
        main.post(sweepRunnable)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeService = this
        connected = true
        LockStateTracker.addLockAllListener(onLockAll)
        LockStateTracker.addAppUnlockListener(onAppUnlocked)
        LockStateTracker.addAppLockListener(onAppLocked)
        rehydrateSnoozed()
        requestSweep()
        // Switching the shield on hides what locked apps are already showing.
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).apply {
            launch { vault.notificationShieldFlow.collect { on -> if (on) requestSweep() } }
        }
    }

    /**
     * What this service has hidden lives in memory, but the snoozes live in the system for 24 h.
     * After a restart (update, crash, low memory) rebuild the bookkeeping from the system's
     * snoozed list, so hidden notifications always come back when their app is unlocked.
     * Snoozes from apps that aren't locked are the user's own and are left alone.
     */
    private fun rehydrateSnoozed() {
        val snoozed = runCatching { snoozedNotifications }.getOrNull() ?: return
        // Our own stand-ins mark the apps we hid, even ones that have since been unlocked in BioKey.
        val standIns = runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { it.packageName == packageName && it.tag?.startsWith(TAG_PREFIX) == true }
            .mapNotNull { it.tag?.removePrefix(TAG_PREFIX) }
            .toSet()
        val touched = HashSet<String>()
        for (sbn in snoozed) {
            val pkg = sbn.packageName ?: continue
            if (pkg == packageName || pkg in IGNORED_PACKAGES) continue
            if (!vault.isAppLocked(pkg) && pkg !in standIns) continue
            val isSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
            val keys = if (isSummary) hiddenSummaryKeys else hiddenNotificationKeys
            keys.getOrPut(pkg) { HashSet() }.add(sbn.key)
            sbn.notification.contentIntent?.let {
                cachedPendingIntents.computeIfAbsent(pkg) { ConcurrentHashMap() }[sbn.key] =
                    CachedPendingIntent(it, sbn.postTime, isSummary)
            }
            touched += pkg
        }
        // Stand-ins whose notifications are all gone are stale.
        for (pkg in standIns - touched) NotificationManagerCompat.from(this).cancel(TAG_PREFIX + pkg, SUMMARY_ID)
        for (pkg in touched) {
            val stillHidden = vault.isAppLocked(pkg) && vault.isGlobalLockEnabled() && vault.isNotificationShieldEnabled() &&
                !LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))
            if (!stillHidden) {
                restoreNotificationsForPackage(pkg)
            } else {
                postReplacement(pkg, (hiddenNotificationKeys[pkg]?.size ?: 0).coerceAtLeast(1))
            }
        }
        // How long these snoozes have left isn't known: start them over.
        extendSnoozes(force = true)
    }

    override fun onListenerDisconnected() {
        connected = false
        activeService = null
        scope?.cancel()
        scope = null
        LockStateTracker.removeLockAllListener(onLockAll)
        LockStateTracker.removeAppUnlockListener(onAppUnlocked)
        LockStateTracker.removeAppLockListener(onAppLocked)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        connected = false
        activeService = null
        scope?.cancel()
        scope = null
        main.removeCallbacksAndMessages(null)
        LockStateTracker.removeLockAllListener(onLockAll)
        LockStateTracker.removeAppUnlockListener(onAppUnlocked)
        LockStateTracker.removeAppLockListener(onAppLocked)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (shouldHide(sbn) && hide(sbn)) updateStandIn(sbn.packageName)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        if (reason == REASON_SNOOZED) {
            // Ignored: triggered by our own snoozeNotification(sbn.key, SNOOZE_24_HOURS_MS) call.
            return
        }
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
                unsnooze(pkg, hiddenSummaryKeys.remove(pkg).orEmpty())
            } else {
                // Update stand-in notification with the remaining count
                postReplacement(pkg, remainingCount)
            }
        }
    }

    /** Every session ended (screen off, lock-all): locked apps' notifications hide again. */
    private fun onAllSessionsEnded() {
        restoringKeys.clear()
        if (!connected) return
        extendSnoozes(force = false)
        requestSweep()
    }

    /**
     * Hides what locked apps are showing, forgets hidden notifications their apps have withdrawn
     * since, and brings the stand-ins up to date.
     */
    private fun sweepActiveNotifications() {
        val active = try {
            activeNotifications
        } catch (e: Exception) {
            Log.w(TAG, "Listener not bound or active notifications unavailable: ${e.javaClass.simpleName}")
            return
        } ?: return
        val changed = HashSet<String>()
        for (sbn in active) if (shouldHide(sbn) && hide(sbn)) changed += sbn.packageName
        // Read after the active list: a notification being snoozed right now is in one of the two.
        runCatching { snoozedNotifications }.getOrNull()?.let { snoozed ->
            val present = HashSet<String>(active.size + snoozed.size)
            active.mapTo(present) { it.key }
            snoozed.mapTo(present) { it.key }
            changed += forgetWithdrawn(hiddenNotificationKeys, present)
            changed += forgetWithdrawn(hiddenSummaryKeys, present)
            for (pkg in changed) cachedPendingIntents[pkg]?.keys?.retainAll(present)
        }
        changed.forEach(::updateStandIn)
    }

    private fun shouldHide(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName ?: return false
        if (pkg == packageName || pkg in IGNORED_PACKAGES) return false

        val restoreEntry = restoringKeys[sbn.key]
        if (restoreEntry != null) {
            if (SystemClock.elapsedRealtime() < restoreEntry.expiryElapsed) {
                restoringKeys.remove(sbn.key)
                return false
            }
            restoringKeys.remove(sbn.key)
        }

        val notif = sbn.notification
        if (!sbn.isClearable || sbn.isOngoing) return false
        if (notif.flags and (Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT) != 0) return false
        if (notif.category in NEVER_HIDDEN_CATEGORIES) return false
        if (notif.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return false
        if (notif.fullScreenIntent != null) return false

        if (!vault.isGlobalLockEnabled() || !vault.isNotificationShieldEnabled()) return false
        if (!vault.isAppLocked(pkg)) return false
        return !LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))
    }

    /** Snoozes [sbn] and keeps track of it; false if it couldn't be snoozed. The caller updates the stand-in. */
    private fun hide(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        val isSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        // Cache deep link intent before snoozing: child notifications take precedence over group summaries
        sbn.notification.contentIntent?.let { intent ->
            val pkgIntents = cachedPendingIntents.computeIfAbsent(pkg) { ConcurrentHashMap() }
            pkgIntents[sbn.key] = CachedPendingIntent(intent, sbn.postTime, isSummary)
        }

        val keys = if (isSummary) hiddenSummaryKeys else hiddenNotificationKeys
        keys.getOrPut(pkg) { HashSet() }.add(sbn.key)

        try {
            // Non-destructive snooze: snooze notification for 24h so user messages and OTPs are preserved
            snoozeNotification(sbn.key, SNOOZE_24_HOURS_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't snooze notification: ${e.javaClass.simpleName}")
            hiddenSummaryKeys[pkg]?.remove(sbn.key)
            hiddenNotificationKeys[pkg]?.remove(sbn.key)
            cachedPendingIntents[pkg]?.remove(sbn.key)
            return false
        }
        return true
    }

    /**
     * Snoozing again pushes the system's 24 h alarm back. Without it, a locked app that isn't
     * opened for a day gets every hidden notification re-posted in full, with sound.
     */
    private fun extendSnoozes(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && snoozesExtendedAt != 0L && now - snoozesExtendedAt < EXTEND_SNOOZES_EVERY_MS) return
        snoozesExtendedAt = now
        if (!vault.isGlobalLockEnabled() || !vault.isNotificationShieldEnabled()) return
        for (hidden in listOf(hiddenNotificationKeys, hiddenSummaryKeys)) {
            for ((pkg, keys) in hidden) {
                if (!vault.isAppLocked(pkg)) continue
                for (key in keys) runCatching { snoozeNotification(key, SNOOZE_24_HOURS_MS) }
            }
        }
    }

    private fun restoreAll() {
        if (!connected) return
        val pkgs = (hiddenNotificationKeys.keys + hiddenSummaryKeys.keys + cachedPendingIntents.keys).toSet()
        for (pkg in pkgs) {
            restoreNotificationsForPackage(pkg)
        }
    }

    private fun restorePackages(packages: Collection<String>) {
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
        unsnooze(pkg, childKeys + summaryKeys)
    }

    /** Brings [keys] back into the shade; they aren't hidden again for [RESTORE_WINDOW_MS]. */
    private fun unsnooze(pkg: String, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val expiry = SystemClock.elapsedRealtime() + RESTORE_WINDOW_MS
        for (key in keys) {
            restoringKeys[key] = RestoringEntry(pkg, expiry)
            // Re-awaken snoozed notification
            runCatching { snoozeNotification(key, 50L) }
        }
    }

    /** Shows [pkg]'s stand-in with its current count, or removes it once nothing of the app's is hidden. */
    private fun updateStandIn(pkg: String) {
        val children = hiddenNotificationKeys[pkg]?.size ?: 0
        val summaries = hiddenSummaryKeys[pkg]?.size ?: 0
        if (children + summaries > 0) {
            postReplacement(pkg, children.coerceAtLeast(1))
            return
        }
        NotificationManagerCompat.from(this).cancel(TAG_PREFIX + pkg, SUMMARY_ID)
        hiddenNotificationKeys.remove(pkg)
        hiddenSummaryKeys.remove(pkg)
        cachedPendingIntents.remove(pkg)
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

        val label = AppInfoCache.quickLabel(this, lockedPackage)
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
        if (channelReady) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Hidden notifications", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Stand-ins for notifications from locked apps"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
        }
        channelReady = true
    }
}
