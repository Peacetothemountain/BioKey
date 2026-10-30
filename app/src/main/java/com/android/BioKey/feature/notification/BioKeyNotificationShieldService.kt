package com.android.BioKey.feature.notification

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
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
import com.android.BioKey.core.data.PasswordManager
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
        /** How soon access is looked at again after the listener is bound or unbound. */
        private const val ACCESS_RECHECK_MS = 1_000L
        private const val SNOOZE_24_HOURS_MS = 24 * 60 * 60 * 1000L

        /**
         * How often hidden notifications' snoozes are pushed back (at the next lock-all), so the
         * 24 h snooze doesn't run out while the app stays locked. Android saves its snooze list to
         * disk after each push-back, hence not at every screen-off.
         */
        private const val EXTEND_SNOOZES_EVERY_MS = 6 * 60 * 60 * 1000L

        /**
         * A phone left unused has no lock-all to push the snoozes back at, so an alarm does it
         * 10 to 12 h after the last push-back. It is inexact: Doze can hold it until its next
         * maintenance window (by default at most 6 h away), still well before the 24 h run out.
         */
        private const val SNOOZE_ALARM_AFTER_MS = 10 * 60 * 60 * 1000L
        private const val SNOOZE_ALARM_WINDOW_MS = 2 * 60 * 60 * 1000L
        private const val SNOOZE_ALARM_TAG = "BioKey:extendSnoozes"

        /** How long a notification being un-snoozed may take to come back before it can be hidden again. */
        private const val RESTORE_WINDOW_MS = 2_500L

        /**
         * Sweeps look for hidden notifications their apps withdrew (which only corrects stand-in
         * counts and kept links) at most this often: it means fetching the whole snoozed list.
         */
        private const val WITHDRAWN_CHECK_EVERY_MS = 60_000L

        /**
         * A lock-all this soon after a sweep that left nothing a lock-all could hide, with nothing
         * shown since, skips its own sweep ([LockAllSweeps]). Later ones sweep whatever the
         * bookkeeping says, as a safety net.
         */
        private const val SWEEP_SKIP_WINDOW_MS = 60_000L

        private val IGNORED_PACKAGES = setOf("android", "com.android.systemui")

        /** Deep-link intents cached per locked package and notification key with post-time metadata. */
        val cachedPendingIntents = ConcurrentHashMap<String, ConcurrentHashMap<String, CachedPendingIntent>>()

        @Volatile private var activeService: BioKeyNotificationShieldService? = null

        /** Bound right now: Android only binds a listener that has access. No call into Android. */
        val isBound: Boolean get() = activeService != null

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

        /**
         * Asked of Android's notification manager itself: the setting listing the listeners can be
         * written after this service is bound or unbound, so a look then would read the old value.
         */
        fun isNotificationAccessGranted(context: Context): Boolean = runCatching {
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(context, BioKeyNotificationShieldService::class.java))
        }.getOrElse { NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }

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

    /**
     * The count each locked app's stand-in was last posted with. A hidden notification that leaves
     * it as it was (an app updating its message) posts nothing: each post makes the shade redraw
     * the stand-in. Forgotten whenever the stand-in goes (cancelled, or dismissed by the user), at
     * every lock-all and on disconnecting, so a stand-in that may be gone is always posted again.
     */
    private val standInCounts = HashMap<String, Int>()

    private val lockAllSweeps = LockAllSweeps(SWEEP_SKIP_WINDOW_MS)

    @Volatile private var connected = false
    private var sweepPending = false
    private var withdrawnCheckedAt = 0L
    private var snoozesExtendedAt = 0L
    private var snoozeAlarmSet = false
    private var channelReady = false

    private val alarms by lazy { getSystemService(AlarmManager::class.java) }
    private val snoozeAlarm = AlarmManager.OnAlarmListener {
        snoozeAlarmSet = false
        if (connected) extendSnoozes(force = true)
    }

    private val onLockAll: () -> Unit = { main.post(::onAllSessionsEnded) }
    // Sessions are reported under the name the app was unlocked by: Google Password Manager's
    // virtual package, while its notifications come from Credential Manager.
    private val onAppUnlocked: (String) -> Unit = { pkg ->
        main.post {
            // Its notifications show from now on: the next lock-all must sweep.
            lockAllSweeps.onShowing()
            if (connected) PasswordManager.aliasesOf(pkg).forEach(::restoreNotificationsForPackage)
        }
    }
    // Told once per batch: a restore or auto-sort that turns on 40 locks is one post and one sweep.
    private val onAppsLocked: (Set<String>) -> Unit = { pkgs ->
        main.post {
            val aliases = PasswordManager.expand(pkgs)
            restoringKeys.entries.removeIf { it.value.packageName in aliases }
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
        val app = applicationContext
        ShieldAccessWatch.checkSoon(app)
        // Again a moment later, should Android still be settling the change.
        main.postDelayed({ ShieldAccessWatch.checkSoon(app) }, ACCESS_RECHECK_MS)
        activeService = this
        connected = true
        // Nothing seen while unbound is known: the stand-ins are posted afresh, the next lock-all sweeps.
        standInCounts.clear()
        lockAllSweeps.onShowing()
        LockStateTracker.addLockAllListener(onLockAll)
        LockStateTracker.addAppUnlockListener(onAppUnlocked)
        LockStateTracker.addAppsLockListener(onAppsLocked)
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
        // Kept from before a reconnect but no longer snoozed: withdrawn, or back in the shade (where
        // the sweep that follows hides them again if their app is still locked). Snoozing those
        // again below would hide an unlocked app's notifications, or a locked app's without a stand-in.
        val snoozedKeys = snoozed.mapTo(HashSet()) { it.key }
        for (pkg in forgetWithdrawn(hiddenNotificationKeys, snoozedKeys) + forgetWithdrawn(hiddenSummaryKeys, snoozedKeys)) {
            cachedPendingIntents[pkg]?.keys?.retainAll(snoozedKeys)
        }
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
        for (pkg in standIns - touched) cancelStandIn(pkg)
        for (pkg in touched) {
            val stillHidden = vault.isAppLocked(pkg) && vault.isProtectionActive() && vault.isNotificationShieldEnabled() &&
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
        // Access taken away is the likeliest reason: recorded if so ([ShieldAccessWatch]).
        val app = applicationContext
        ShieldAccessWatch.checkSoon(app)
        Handler(Looper.getMainLooper()).postDelayed({ ShieldAccessWatch.checkSoon(app) }, ACCESS_RECHECK_MS)
        connected = false
        activeService = null
        standInCounts.clear()
        scope?.cancel()
        scope = null
        LockStateTracker.removeLockAllListener(onLockAll)
        LockStateTracker.removeAppUnlockListener(onAppUnlocked)
        LockStateTracker.removeAppsLockListener(onAppsLocked)
        cancelSnoozeAlarm()
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
        LockStateTracker.removeAppsLockListener(onAppsLocked)
        cancelSnoozeAlarm()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (shouldHide(sbn) && hide(sbn)) {
            updateStandIn(sbn.packageName)
        } else if (mayHideLater(sbn)) {
            lockAllSweeps.onShowing()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        // One of BioKey's stand-ins, gone however it went (dismissed, snoozed by the user): the
        // next hidden notification posts it again, even with the same count.
        if (sbn.packageName == packageName) {
            sbn.tag?.takeIf { it.startsWith(TAG_PREFIX) }?.let { standInCounts.remove(it.removePrefix(TAG_PREFIX)) }
        }
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
                cancelStandIn(pkg)
                cachedPendingIntents.remove(pkg)
                hiddenNotificationKeys.remove(pkg)
                unsnooze(pkg, hiddenSummaryKeys.remove(pkg).orEmpty())
            } else {
                // Update stand-in notification with the remaining count
                postReplacement(pkg, remainingCount)
            }
        }
    }

    /**
     * Every session ended (screen off, lock-all, protection resumed): locked apps' notifications
     * hide again. A sweep, unless one moments ago left nothing a lock-all could hide and nothing
     * has shown since ([LockAllSweeps]): a screen-off is reported two or three times over. What
     * was left showing (during a session, a pause, with the shield off) always counts, so a
     * device lock never leaves a locked app's message on the keyguard.
     */
    private fun onAllSessionsEnded() {
        restoringKeys.clear()
        standInCounts.clear()
        if (!connected) return
        extendSnoozes(force = false)
        if (lockAllSweeps.needsSweep(SystemClock.elapsedRealtime())) requestSweep()
    }

    /**
     * Hides what locked apps are showing, forgets hidden notifications their apps have withdrawn
     * since (at most every [WITHDRAWN_CHECK_EVERY_MS]), and brings the stand-ins up to date.
     */
    private fun sweepActiveNotifications() {
        val active = try {
            activeNotifications
        } catch (e: Exception) {
            Log.w(TAG, "Listener not bound or active notifications unavailable: ${e.javaClass.simpleName}")
            lockAllSweeps.onShowing()
            return
        }
        if (active == null) {
            lockAllSweeps.onShowing()
            return
        }
        val changed = HashSet<String>()
        var leftShowing = false
        for (sbn in active) {
            if (shouldHide(sbn) && hide(sbn)) {
                changed += sbn.packageName
            } else if (mayHideLater(sbn)) {
                leftShowing = true
            }
        }
        // Only while something is hidden, and not at every sweep: it fetches the whole snoozed list.
        val now = SystemClock.elapsedRealtime()
        lockAllSweeps.onSwept(leftShowing, now)
        val anyHidden = hiddenNotificationKeys.isNotEmpty() || hiddenSummaryKeys.isNotEmpty()
        if (anyHidden && (withdrawnCheckedAt == 0L || now - withdrawnCheckedAt >= WITHDRAWN_CHECK_EVERY_MS)) {
            withdrawnCheckedAt = now
            // Read after the active list: a notification being snoozed right now is in one of the two.
            runCatching { snoozedNotifications }.getOrNull()?.let { snoozed ->
                val present = HashSet<String>(active.size + snoozed.size)
                active.mapTo(present) { it.key }
                snoozed.mapTo(present) { it.key }
                changed += forgetWithdrawn(hiddenNotificationKeys, present)
                changed += forgetWithdrawn(hiddenSummaryKeys, present)
                for (pkg in changed) cachedPendingIntents[pkg]?.keys?.retainAll(present)
            }
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

        if (!isHideableKind(sbn)) return false

        if (!vault.isProtectionActive() || !vault.isNotificationShieldEnabled()) return false
        if (!vault.isAppLocked(pkg)) return false
        return !LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))
    }

    /**
     * Whether [sbn], left showing, is one a later lock-all could hide: any of a locked app's (a
     * lock-all ends its session and its restore window), and while protection is paused or the
     * shield is off any at all (protection resuming is a lock-all). An app that isn't locked
     * only becomes hideable by being locked, which sweeps by itself ([onAppsLocked]).
     */
    private fun mayHideLater(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName ?: return false
        if (pkg == packageName || pkg in IGNORED_PACKAGES || !isHideableKind(sbn)) return false
        return pkg in vault.getLockedPackages() || !vault.isProtectionActive() || !vault.isNotificationShieldEnabled()
    }

    /** Whether [sbn] is a kind that is ever hidden: never calls, alarms, media, ongoing ones and the like. */
    private fun isHideableKind(sbn: StatusBarNotification): Boolean {
        val notif = sbn.notification
        if (!sbn.isClearable || sbn.isOngoing) return false
        if (notif.flags and (Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT) != 0) return false
        if (notif.category in NEVER_HIDDEN_CATEGORIES) return false
        if (notif.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return false
        return notif.fullScreenIntent == null
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
        // An alarm already set (at the last push-back, or for an older snooze) comes before this
        // younger one runs out too.
        if (!snoozeAlarmSet) scheduleSnoozeAlarm()
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
        if (!vault.isProtectionActive() || !vault.isNotificationShieldEnabled()) return
        var extended = false
        for (hidden in listOf(hiddenNotificationKeys, hiddenSummaryKeys)) {
            for ((pkg, keys) in hidden) {
                if (!vault.isAppLocked(pkg) || keys.isEmpty()) continue
                for (key in keys) runCatching { snoozeNotification(key, SNOOZE_24_HOURS_MS) }
                extended = true
            }
        }
        if (extended) scheduleSnoozeAlarm() else cancelSnoozeAlarm()
    }

    /**
     * Pushes the snoozes back [SNOOZE_ALARM_AFTER_MS] from now, should no lock-all do it first
     * (each push-back moves the alarm on). Delivered to [main] while this service is bound.
     */
    private fun scheduleSnoozeAlarm() {
        val manager = alarms ?: return
        val start = SystemClock.elapsedRealtime() + SNOOZE_ALARM_AFTER_MS
        snoozeAlarmSet = runCatching {
            manager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, start, SNOOZE_ALARM_WINDOW_MS, SNOOZE_ALARM_TAG, snoozeAlarm, main)
        }.isSuccess
    }

    private fun cancelSnoozeAlarm() {
        if (!snoozeAlarmSet) return
        snoozeAlarmSet = false
        runCatching { alarms?.cancel(snoozeAlarm) }
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
        cancelStandIn(pkg)
        cachedPendingIntents.remove(pkg)
        val childKeys = hiddenNotificationKeys.remove(pkg).orEmpty()
        val summaryKeys = hiddenSummaryKeys.remove(pkg).orEmpty()
        unsnooze(pkg, childKeys + summaryKeys)
    }

    /** Brings [keys] back into the shade; they aren't hidden again for [RESTORE_WINDOW_MS]. */
    private fun unsnooze(pkg: String, keys: Collection<String>) {
        if (keys.isEmpty()) return
        // They show again: the next lock-all must sweep.
        lockAllSweeps.onShowing()
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
        cancelStandIn(pkg)
        hiddenNotificationKeys.remove(pkg)
        hiddenSummaryKeys.remove(pkg)
        cachedPendingIntents.remove(pkg)
    }

    private fun cancelStandIn(pkg: String) {
        standInCounts.remove(pkg)
        NotificationManagerCompat.from(this).cancel(TAG_PREFIX + pkg, SUMMARY_ID)
    }

    /** Posts [lockedPackage]'s stand-in with [count], unless it is showing with that count already. */
    private fun postReplacement(lockedPackage: String, count: Int) {
        if (standInCounts[lockedPackage] == count) return
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
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(getString(R.string.shield_public_title))
            .setContentText(getString(R.string.shield_public_text))
            .build()
        val text = resources.getQuantityString(R.plurals.shield_stand_in_text, count, count)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_biokey_mark)
            .setContentTitle(label)
            .setContentText(text)
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
            standInCounts[lockedPackage] = count
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify.
        }
    }

    private fun ensureChannel() {
        if (channelReady) return
        val nm = getSystemService(NotificationManager::class.java)
        // Created, or its name and description updated (Android keeps the owner's importance).
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.shield_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.shield_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        channelReady = true
    }
}

/**
 * Whether a lock-all needs a sweep of the shade. Every screen-off is reported two or three times
 * (the app's screen receiver and the accessibility service, then the keyguard as "phone
 * locked"), and a sweep fetches every notification showing. A lock-all may skip it only when the
 * last sweep, under [skipWindowMs] ago, left nothing showing that a lock-all could hide, and
 * nothing has shown since: no app unlocked, nothing restored, no such notification posted. Any
 * doubt (a sweep that failed, a reconnect) counts as something showing.
 */
internal class LockAllSweeps(private val skipWindowMs: Long) {
    private var clean = false
    private var cleanAt = 0L

    /** Something a lock-all could hide may be showing. */
    fun onShowing() {
        clean = false
    }

    /** A sweep ran at [now] and left [leftShowing] something a lock-all could hide. */
    fun onSwept(leftShowing: Boolean, now: Long) {
        clean = !leftShowing
        cleanAt = now
    }

    fun needsSweep(now: Long): Boolean = !clean || now - cleanAt !in 0 until skipWindowMs
}
