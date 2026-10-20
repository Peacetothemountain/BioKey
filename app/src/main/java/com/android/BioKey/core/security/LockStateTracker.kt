package com.android.BioKey.core.security

import android.os.SystemClock
import android.util.Log
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.diagnostics.DiagnosticLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-memory lock session state.
 *
 * Sessions follow the Android App Lock model: once you unlock an app it stays unlocked for as long
 * as it is on screen, no matter what happens inside it (refreshes, dialogs, sign-in sheets,
 * keyboards, chat heads, assistant overlays). A session only starts ageing once the app has
 * actually left the screen, which [com.android.BioKey.feature.lock.BioKeyAccessibilityService]
 * decides from the window list rather than from individual window events.
 *
 * Nothing here is persisted: every unlock lives only in RAM and is wiped on screen-off, process
 * death, or lock-all, so a reboot or a killed process always fails closed. All times use
 * [SystemClock.elapsedRealtime] so wall-clock changes can't extend a session.
 */
object LockStateTracker {

    private const val TAG = "LockStateTracker"

    /**
     * Longest an unlocked app stays unlocked while the user hops straight to a helper app (2FA
     * code, email link, photo or file picker, camera) and back: long enough to browse an album for
     * the right photo. Going anywhere else ends the hop at once.
     */
    const val HOP_MAX_MS = 5 * 60_000L

    /** Suppresses duplicate lock-screen launches from bursts of accessibility events. */
    private const val PENDING_LOCK_DEBOUNCE_MS = 1_200L

    /**
     * Suppresses immediate re-triggering while exiting an abandoned lock screen. Engines re-check
     * the app once it ends ([addLockAbandonedListener]), so a fast return to it is still covered.
     */
    const val ABANDONED_LOCK_COOLDOWN_MS = 900L

    /**
     * A new session must reach the screen within this long. One whose app never appears (its
     * notification opened something else, it crashed, Home was pressed first) ends instead of
     * waiting, unaged, for whoever opens the app next.
     */
    const val ARRIVAL_GRACE_MS = 15_000L

    /** How long BioKey's own session survives a trip to the system file picker for a backup. */
    private const val OWN_PICKER_LEASE_MS = 120_000L

    /** How long after an app left the screen its window may still flicker back during the exit animation. */
    private const val JUST_LEFT_MS = 2_000L

    /** How long a successful self-protection challenge lets the user stay in BioKey's settings pages. */
    private const val SELF_PROTECTION_LEASE_MS = 120_000L

    /** BioKey's own session ends after this long without interaction, in BioKey or away from it. */
    const val OWN_APP_IDLE_MS = 3 * 60_000L

    /**
     * One unlocked app. [awaySince] is 0 while the app is on screen. [hopTarget] is set while the
     * user is in a helper app they went to straight from this one.
     */
    private class Session(val unlockedAt: Long) {
        @Volatile var awaySince = 0L
        @Volatile var hopTarget: String? = null
        /** The app has been on screen since the unlock (see [ARRIVAL_GRACE_MS]). */
        @Volatile var seen = false
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val pendingLocks = ConcurrentHashMap<String, Long>()
    private val abandonedAt = ConcurrentHashMap<String, Long>()
    private val leftAt = ConcurrentHashMap<String, Long>()

    @Volatile private var selfProtectionGrantedAt = 0L
    @Volatile private var selfProtectionVisitedGuardedScreen = false

    @Volatile private var ownUnlockedAt = 0L
    @Volatile private var ownLastActiveAt = 0L
    @Volatile private var ownInForeground = false
    @Volatile private var externalNavigationAt = 0L
    @Volatile private var ownPickerAt = 0L

    /** Bumped by every [lockAll], so an unlock that raced a device lock can be told apart. */
    @Volatile private var generation = 0

    private val lockAllListeners = CopyOnWriteArraySet<() -> Unit>()
    private val appUnlockListeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val appLockListeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val appsLockListeners = CopyOnWriteArraySet<(Set<String>) -> Unit>()
    private val lockAbandonedListeners = CopyOnWriteArraySet<(String) -> Unit>()

    /** Notified after every [lockAll] (screen-off, manual lock), e.g. to re-redact notifications. */
    fun addLockAllListener(listener: () -> Unit) { lockAllListeners += listener }
    fun removeLockAllListener(listener: () -> Unit) { lockAllListeners -= listener }

    /** Notified whenever an app is unlocked, e.g. for notification shield stand-in dismissal. */
    fun addAppUnlockListener(listener: (String) -> Unit) { appUnlockListeners += listener }
    fun removeAppUnlockListener(listener: (String) -> Unit) { appUnlockListeners -= listener }

    /** Notified whenever an app's session ends, e.g. to re-mask notifications. */
    fun addAppLockListener(listener: (String) -> Unit) { appLockListeners += listener }
    fun removeAppLockListener(listener: (String) -> Unit) { appLockListeners -= listener }

    /**
     * Like [addAppLockListener], but told once per batch ([lockPackages]): turning on 40 locks
     * from a backup is one call, so a listener that rescans everything (the notification shield)
     * does it once rather than 40 times.
     */
    fun addAppsLockListener(listener: (Set<String>) -> Unit) { appsLockListeners += listener }
    fun removeAppsLockListener(listener: (Set<String>) -> Unit) { appsLockListeners -= listener }

    /**
     * Notified when a lock screen is left without authenticating ([markLockAbandoned]). The app
     * isn't covered again for [ABANDONED_LOCK_COOLDOWN_MS]; engines use this to look once more
     * when that ends.
     */
    fun addLockAbandonedListener(listener: (String) -> Unit) { lockAbandonedListeners += listener }
    fun removeLockAbandonedListener(listener: (String) -> Unit) { lockAbandonedListeners -= listener }

    /** The current [lockAll] generation: capture it when a prompt starts, pass it to [onAppUnlocked]. */
    fun lockGeneration(): Int = generation

    private fun now() = SystemClock.elapsedRealtime()

    private fun sessionOf(packageName: String): Session? =
        PasswordManager.aliasesOf(packageName).firstNotNullOfOrNull { sessions[it] }

    // ---------------------------------------------------------------------------------------
    // Locked-app sessions
    // ---------------------------------------------------------------------------------------

    private val timerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val expiryJobs = ConcurrentHashMap<String, Job>()

    /** Re-checks a session when its away time runs out so notifications re-mask on time. */
    private fun scheduleExpiry(packageName: String, delayMs: Long, timeoutMode: LockTimeoutMode) {
        cancelExpiry(packageName)
        val job = timerScope.launch {
            delay(delayMs + 50L)
            isAppUnlocked(packageName, timeoutMode)
        }
        for (pkg in PasswordManager.aliasesOf(packageName)) expiryJobs[pkg] = job
    }

    private fun cancelExpiry(packageName: String) {
        for (pkg in PasswordManager.aliasesOf(packageName)) expiryJobs.remove(pkg)?.cancel()
    }

    /**
     * Starts a session for [packageName]. [generation] is [lockGeneration] as it was when the
     * prompt started: if every session ended since (the phone was locked mid-prompt), the unlock
     * is stale and no session starts. Returns whether one did.
     */
    fun onAppUnlocked(packageName: String, generation: Int = this.generation): Boolean {
        if (generation != this.generation) return staleUnlock(packageName)
        cancelExpiry(packageName)
        val session = Session(unlockedAt = now())
        for (pkg in PasswordManager.aliasesOf(packageName)) {
            sessions[pkg] = session
            pendingLocks.remove(pkg)
            abandonedAt.remove(pkg)
        }
        // A lock-all on another thread may have cleared the sessions just before these went in:
        // it bumps the generation first, so looking again catches it.
        if (generation != this.generation) {
            for (pkg in PasswordManager.aliasesOf(packageName)) sessions.remove(pkg, session)
            return staleUnlock(packageName)
        }
        Log.i(TAG, "Session started for $packageName")
        DiagnosticLog.log("Unlocked $packageName")
        appUnlockListeners.forEach { runCatching { it(packageName) } }
        return true
    }

    private fun staleUnlock(packageName: String): Boolean {
        Log.i(TAG, "Unlock of $packageName arrived after a lock-all; ignored")
        DiagnosticLog.log("Unlock of $packageName ignored: the phone was locked meanwhile")
        return false
    }

    /** The unlocked app is on screen: it is in use, so nothing ages. */
    fun onAppVisible(packageName: String) {
        val session = sessionOf(packageName) ?: return
        session.seen = true
        if (session.awaySince == 0L) return
        session.awaySince = 0L
        session.hopTarget = null
        cancelExpiry(packageName)
    }

    /**
     * The unlocked app left the screen.
     *
     * @param hopTarget the helper app the user went straight to, or null for Home, Recents or a
     *   regular app. Only a helper hop keeps an IMMEDIATE session alive (for [HOP_MAX_MS]).
     */
    fun onAppLeft(packageName: String, timeoutMode: LockTimeoutMode, hopTarget: String?) {
        val session = sessionOf(packageName) ?: return
        session.seen = true
        if (session.awaySince != 0L) return
        session.awaySince = now()
        session.hopTarget = hopTarget
        for (pkg in PasswordManager.aliasesOf(packageName)) leftAt[pkg] = session.awaySince
        Log.d(TAG, "$packageName left the screen (hop=$hopTarget)")
        // The diagnostic log names locked apps only: a hop's destination is just "a helper app".
        DiagnosticLog.log("$packageName left the screen" + if (hopTarget != null) " · hop to a helper app" else " · re-lock: ${timeoutMode.name.lowercase()}")
        when {
            timeoutMode == LockTimeoutMode.IMMEDIATE && hopTarget == null -> lockPackage(packageName, "left, re-locks immediately")
            timeoutMode == LockTimeoutMode.ON_SCREEN_OFF -> Unit
            else -> scheduleExpiry(packageName, awayAllowanceMs(timeoutMode, hopTarget != null), timeoutMode)
        }
    }

    /**
     * Something new is in front. A hop carries on through anything that [continuesHop] accepts
     * (another helper, a system screen, BioKey's own lock screen: a sign-in sheet that sends the
     * user to an authenticator, say); anywhere else ends it, and the app ages as a normal exit.
     * Coming back to the app itself is [onAppVisible]'s job.
     */
    fun onForegroundChanged(
        foregroundPackage: String,
        timeoutModeOf: (String) -> LockTimeoutMode,
        continuesHop: (String) -> Boolean = { false }
    ) {
        for ((pkg, session) in sessions) {
            val hop = session.hopTarget ?: continue
            if (hop == foregroundPackage || pkg in PasswordManager.aliasesOf(foregroundPackage) || continuesHop(foregroundPackage)) continue
            session.hopTarget = null
            val mode = timeoutModeOf(pkg)
            Log.d(TAG, "Hop from $pkg ended at $foregroundPackage")
            DiagnosticLog.log("Hop from $pkg ended: another app came to the front")
            if (!isAppUnlocked(pkg, mode)) continue
            if (mode.durationMillis > 0L) {
                scheduleExpiry(pkg, (session.awaySince + mode.durationMillis - now()).coerceAtLeast(0L), mode)
            }
        }
    }

    private fun awayAllowanceMs(timeoutMode: LockTimeoutMode, hopping: Boolean): Long {
        val grace = timeoutMode.durationMillis.coerceAtLeast(0L)
        return if (hopping) maxOf(grace, HOP_MAX_MS) else grace
    }

    /**
     * True for a short while after [packageName] left the screen. Going Home or dismissing an app
     * in Recents animates its window away, and Android can hand that window focus once more on
     * the way out: during this window, such a flicker is the exit, not the app coming back.
     */
    fun justLeft(packageName: String): Boolean {
        val stamp = PasswordManager.aliasesOf(packageName).firstNotNullOfOrNull { leftAt[it] } ?: return false
        if (now() - stamp < JUST_LEFT_MS) return true
        for (pkg in PasswordManager.aliasesOf(packageName)) leftAt.remove(pkg)
        return false
    }

    fun isAppUnlocked(packageName: String, timeoutMode: LockTimeoutMode): Boolean {
        val session = sessionOf(packageName) ?: return false
        if (!session.seen && now() - session.unlockedAt > ARRIVAL_GRACE_MS) {
            lockPackage(packageName, "never reached the screen")
            return false
        }
        val away = session.awaySince
        if (away == 0L || timeoutMode == LockTimeoutMode.ON_SCREEN_OFF) return true
        val hopping = session.hopTarget != null
        if (timeoutMode == LockTimeoutMode.IMMEDIATE && !hopping) {
            lockPackage(packageName, "left, re-locks immediately")
            return false
        }
        val stillUnlocked = now() - away < awayAllowanceMs(timeoutMode, hopping)
        if (!stillUnlocked) lockPackage(packageName, "re-lock time passed")
        return stillUnlocked
    }

    fun lockPackage(packageName: String, reason: String? = null) = lockPackages(setOf(packageName), reason)

    /** Ends the sessions of [packages] together, then tells the listeners. */
    fun lockPackages(packages: Set<String>, reason: String? = null) {
        endSessions(packages, reason)
        notifyLocked(packages)
    }

    /**
     * The first half of [lockPackages]: ends the sessions without telling anyone. The vault does
     * this under its own lock, before anything can see the apps locked, and calls [notifyLocked]
     * once it has let go of it (the listeners are slow: they rescan notifications).
     */
    fun endSessions(packages: Set<String>, reason: String? = null) {
        for (packageName in packages) {
            cancelExpiry(packageName)
            var hadSession = false
            for (pkg in PasswordManager.aliasesOf(packageName)) {
                if (sessions.remove(pkg) != null) hadSession = true
                pendingLocks.remove(pkg)
            }
            if (hadSession) {
                Log.i(TAG, "Session ended for $packageName")
                DiagnosticLog.log("Session ended: $packageName" + (reason?.let { " ($it)" } ?: ""))
            }
        }
    }

    /** The second half of [lockPackages]: tells the listeners that [packages] are locked again. */
    fun notifyLocked(packages: Set<String>) {
        if (packages.isEmpty()) return
        for (pkg in packages) appLockListeners.forEach { runCatching { it(pkg) } }
        appsLockListeners.forEach { runCatching { it(packages) } }
    }

    /**
     * Wipes every session, including BioKey's own unless [includeOwnApp] is false. Used on
     * screen-off, manual "lock all" and when protection resumes.
     */
    fun lockAll(includeOwnApp: Boolean = true, reason: String? = null) {
        // First, so an unlock racing this from another thread sees it ([onAppUnlocked]).
        generation++
        expiryJobs.values.forEach { it.cancel() }
        expiryJobs.clear()
        sessions.clear()
        pendingLocks.clear()
        abandonedAt.clear()
        leftAt.clear()
        clearSelfProtection()
        if (includeOwnApp) lockOwnApp()
        Log.i(TAG, "All sessions wiped")
        if (reason != null) DiagnosticLog.log("All sessions ended ($reason)")
        lockAllListeners.forEach { runCatching(it) }
    }

    fun onScreenTurnedOff() = lockAll(reason = "screen off")

    @Volatile private var deviceLocked = false

    /**
     * The device's locked state, as last seen. Locking the device (screen off, screensaver, the
     * power menu's Lockdown, a lock with the screen still on) ends every session, every time.
     */
    fun onDeviceLockState(locked: Boolean) {
        if (locked && !deviceLocked) lockAll(reason = "phone locked")
        deviceLocked = locked
    }

    // ---------------------------------------------------------------------------------------
    // Lock-screen launch debouncing
    // ---------------------------------------------------------------------------------------

    fun isRegularLockPending(packageName: String): Boolean {
        val stamp = PasswordManager.aliasesOf(packageName).firstNotNullOfOrNull { pendingLocks[it] } ?: return false
        if (now() - stamp < PENDING_LOCK_DEBOUNCE_MS) return true
        clearRegularLockPending(packageName)
        return false
    }

    fun setRegularLockPending(packageName: String) {
        val t = now()
        for (pkg in PasswordManager.aliasesOf(packageName)) {
            pendingLocks[pkg] = t
            abandonedAt.remove(pkg)
        }
    }

    fun clearRegularLockPending(packageName: String) {
        for (pkg in PasswordManager.aliasesOf(packageName)) pendingLocks.remove(pkg)
    }

    fun markLockAbandoned(packageName: String) {
        val t = now()
        for (pkg in PasswordManager.aliasesOf(packageName)) {
            pendingLocks.remove(pkg)
            abandonedAt[pkg] = t
        }
        lockAbandonedListeners.forEach { runCatching { it(packageName) } }
    }

    fun isRecentlyAbandoned(packageName: String): Boolean = abandonCooldownRemainingMs(packageName) > 0L

    /** How much of [packageName]'s abandon cooldown is left, or 0. */
    fun abandonCooldownRemainingMs(packageName: String): Long {
        val stamp = PasswordManager.aliasesOf(packageName).firstNotNullOfOrNull { abandonedAt[it] } ?: return 0L
        val left = ABANDONED_LOCK_COOLDOWN_MS - (now() - stamp)
        if (left > 0L) return left
        clearAbandoned(packageName)
        return 0L
    }

    /** Ends [packageName]'s abandon cooldown early, e.g. when the user taps the app again. */
    fun clearAbandoned(packageName: String) {
        for (pkg in PasswordManager.aliasesOf(packageName)) abandonedAt.remove(pkg)
    }

    // ---------------------------------------------------------------------------------------
    // BioKey self-protection (settings / uninstall pages guarded by BioKey itself)
    // ---------------------------------------------------------------------------------------

    fun setSelfProtectionGranted(granted: Boolean, visitedGuardedScreen: Boolean = granted) {
        selfProtectionGrantedAt = if (granted) now() else 0L
        selfProtectionVisitedGuardedScreen = granted && visitedGuardedScreen
    }

    fun markSelfProtectionVisitedGuardedScreen() {
        if (isSelfProtectionGranted()) {
            selfProtectionVisitedGuardedScreen = true
        }
    }

    fun hasVisitedGuardedSelfProtectionScreen(): Boolean =
        isSelfProtectionGranted() && selfProtectionVisitedGuardedScreen

    fun isSelfProtectionGranted(): Boolean {
        val granted = selfProtectionGrantedAt
        if (granted == 0L) return false
        if (now() - granted < SELF_PROTECTION_LEASE_MS) return true
        selfProtectionGrantedAt = 0L
        selfProtectionVisitedGuardedScreen = false
        return false
    }

    fun clearSelfProtection() {
        selfProtectionGrantedAt = 0L
        selfProtectionVisitedGuardedScreen = false
    }

    // ---------------------------------------------------------------------------------------
    // BioKey's own session
    // ---------------------------------------------------------------------------------------

    fun onOwnAppUnlocked() {
        val t = now()
        ownUnlockedAt = t
        ownLastActiveAt = t
        ownInForeground = true
        externalNavigationAt = 0L
    }

    fun isOwnAppUnlocked(): Boolean {
        if (ownUnlockedAt == 0L) return false
        if (now() - ownLastActiveAt < OWN_APP_IDLE_MS) return true
        Log.i(TAG, "BioKey session ended after inactivity")
        lockOwnApp()
        return false
    }

    fun onOwnAppInteraction(): Boolean {
        if (!isOwnAppUnlocked()) return false
        ownLastActiveAt = now()
        return true
    }

    fun onOwnAppResumed() {
        externalNavigationAt = 0L
        ownPickerAt = 0L
        clearSelfProtection()
        if (!isOwnAppUnlocked()) return
        ownInForeground = true
        ownLastActiveAt = now()
    }

    fun onOwnAppStopped() {
        if (!isOwnAppUnlocked()) return
        ownInForeground = false
        ownLastActiveAt = now()
    }

    fun hasActiveOwnerSession(): Boolean = ownInForeground && isOwnAppUnlocked()

    fun beginExternalNavigation(preAuthorizeSelfProtection: Boolean) {
        if (!hasActiveOwnerSession()) return
        val t = now()
        ownLastActiveAt = t
        externalNavigationAt = t
        if (preAuthorizeSelfProtection) setSelfProtectionGranted(granted = true, visitedGuardedScreen = false)
    }

    fun isExternalNavigationActive(): Boolean {
        val started = externalNavigationAt
        if (started == 0L) return false
        if (now() - started < SELF_PROTECTION_LEASE_MS && isOwnAppUnlocked()) return true
        externalNavigationAt = 0L
        return false
    }

    /**
     * BioKey is about to open the system file picker for a backup or restore: its session survives
     * that one trip (at most [OWN_PICKER_LEASE_MS], ended by coming back, by going anywhere but the
     * picker, or by any lock-all). Unlike [beginExternalNavigation], it grants no self-protection.
     */
    fun beginOwnPicker() {
        if (!hasActiveOwnerSession()) return
        val t = now()
        ownLastActiveAt = t
        ownPickerAt = t
    }

    fun isOwnPickerActive(): Boolean {
        val started = ownPickerAt
        if (started == 0L) return false
        if (now() - started < OWN_PICKER_LEASE_MS && isOwnAppUnlocked()) return true
        ownPickerAt = 0L
        return false
    }

    fun lockOwnApp() {
        ownUnlockedAt = 0L
        ownLastActiveAt = 0L
        ownInForeground = false
        externalNavigationAt = 0L
        ownPickerAt = 0L
    }
}
