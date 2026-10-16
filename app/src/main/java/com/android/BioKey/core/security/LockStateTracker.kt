package com.android.BioKey.core.security

import android.os.SystemClock
import android.util.Log
import com.android.BioKey.core.data.LockTimeoutMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

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
     * code, email link, file picker, camera) and back. Going anywhere else ends the hop.
     */
    const val HOP_MAX_MS = 120_000L

    /** Suppresses duplicate lock-screen launches from bursts of accessibility events. */
    private const val PENDING_LOCK_DEBOUNCE_MS = 1_200L

    /** Suppresses immediate re-triggering while exiting an abandoned lock screen. */
    private const val ABANDONED_LOCK_COOLDOWN_MS = 900L

    /** How long after an app left the screen its window may still flicker back during the exit animation. */
    private const val JUST_LEFT_MS = 2_000L

    /** How long a successful self-protection challenge lets the user stay in BioKey's settings pages. */
    private const val SELF_PROTECTION_LEASE_MS = 120_000L

    /** BioKey's own session ends after this long without interaction, in BioKey or away from it. */
    const val OWN_APP_IDLE_MS = 3 * 60_000L

    private const val PKG_GMS_PASSWORDS = "com.google.android.gms.passwordmanager"
    private const val PKG_CREDENTIAL_MANAGER = "com.google.android.apps.credentialmanager"
    private val PASSWORD_MANAGER_ALIASES = setOf(PKG_GMS_PASSWORDS, PKG_CREDENTIAL_MANAGER)

    /**
     * One unlocked app. [awaySince] is 0 while the app is on screen. [hopTarget] is set while the
     * user is in a helper app they went to straight from this one.
     */
    private class Session {
        @Volatile var awaySince = 0L
        @Volatile var hopTarget: String? = null
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

    private val lockAllListeners = CopyOnWriteArraySet<() -> Unit>()
    private val appUnlockListeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val appLockListeners = CopyOnWriteArraySet<(String) -> Unit>()

    /** Notified after every [lockAll] (screen-off, manual lock), e.g. to re-redact notifications. */
    fun addLockAllListener(listener: () -> Unit) { lockAllListeners += listener }
    fun removeLockAllListener(listener: () -> Unit) { lockAllListeners -= listener }

    /** Notified whenever an app is unlocked, e.g. for notification shield stand-in dismissal. */
    fun addAppUnlockListener(listener: (String) -> Unit) { appUnlockListeners += listener }
    fun removeAppUnlockListener(listener: (String) -> Unit) { appUnlockListeners -= listener }

    /** Notified whenever an app's session ends, e.g. to re-mask notifications. */
    fun addAppLockListener(listener: (String) -> Unit) { appLockListeners += listener }
    fun removeAppLockListener(listener: (String) -> Unit) { appLockListeners -= listener }

    private fun now() = SystemClock.elapsedRealtime()

    /** Google Password Manager is reachable through two packages; treat them as one app. */
    private fun aliasesOf(packageName: String): Set<String> =
        if (packageName in PASSWORD_MANAGER_ALIASES) PASSWORD_MANAGER_ALIASES else setOf(packageName)

    private fun sessionOf(packageName: String): Session? =
        aliasesOf(packageName).firstNotNullOfOrNull { sessions[it] }

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
        for (pkg in aliasesOf(packageName)) expiryJobs[pkg] = job
    }

    private fun cancelExpiry(packageName: String) {
        for (pkg in aliasesOf(packageName)) expiryJobs.remove(pkg)?.cancel()
    }

    fun onAppUnlocked(packageName: String) {
        cancelExpiry(packageName)
        val session = Session()
        for (pkg in aliasesOf(packageName)) {
            sessions[pkg] = session
            pendingLocks.remove(pkg)
            abandonedAt.remove(pkg)
        }
        Log.i(TAG, "Session started for $packageName")
        appUnlockListeners.forEach { runCatching { it(packageName) } }
    }

    /** The unlocked app is on screen: it is in use, so nothing ages. */
    fun onAppVisible(packageName: String) {
        val session = sessionOf(packageName) ?: return
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
        if (session.awaySince != 0L) return
        session.awaySince = now()
        session.hopTarget = hopTarget
        for (pkg in aliasesOf(packageName)) leftAt[pkg] = session.awaySince
        Log.d(TAG, "$packageName left the screen (hop=$hopTarget)")
        when {
            timeoutMode == LockTimeoutMode.IMMEDIATE && hopTarget == null -> lockPackage(packageName)
            timeoutMode == LockTimeoutMode.ON_SCREEN_OFF -> Unit
            else -> scheduleExpiry(packageName, awayAllowanceMs(timeoutMode, hopTarget != null), timeoutMode)
        }
    }

    /**
     * Something new is in front. Any hop that isn't the helper the user went to (or back to the
     * app itself, which [onAppVisible] handles) is over: the app now ages as a normal exit.
     */
    fun onForegroundChanged(foregroundPackage: String, timeoutModeOf: (String) -> LockTimeoutMode) {
        for ((pkg, session) in sessions) {
            val hop = session.hopTarget ?: continue
            if (hop == foregroundPackage || pkg in aliasesOf(foregroundPackage)) continue
            session.hopTarget = null
            val mode = timeoutModeOf(pkg)
            Log.d(TAG, "Hop from $pkg ended at $foregroundPackage")
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
        val stamp = aliasesOf(packageName).firstNotNullOfOrNull { leftAt[it] } ?: return false
        if (now() - stamp < JUST_LEFT_MS) return true
        for (pkg in aliasesOf(packageName)) leftAt.remove(pkg)
        return false
    }

    fun isAppUnlocked(packageName: String, timeoutMode: LockTimeoutMode): Boolean {
        val session = sessionOf(packageName) ?: return false
        val away = session.awaySince
        if (away == 0L || timeoutMode == LockTimeoutMode.ON_SCREEN_OFF) return true
        val hopping = session.hopTarget != null
        if (timeoutMode == LockTimeoutMode.IMMEDIATE && !hopping) {
            lockPackage(packageName)
            return false
        }
        val stillUnlocked = now() - away < awayAllowanceMs(timeoutMode, hopping)
        if (!stillUnlocked) lockPackage(packageName)
        return stillUnlocked
    }

    fun lockPackage(packageName: String) {
        cancelExpiry(packageName)
        var hadSession = false
        for (pkg in aliasesOf(packageName)) {
            if (sessions.remove(pkg) != null) hadSession = true
            pendingLocks.remove(pkg)
        }
        if (hadSession) Log.i(TAG, "Session ended for $packageName")
        appLockListeners.forEach { runCatching { it(packageName) } }
    }

    /**
     * Wipes every session, including BioKey's own unless [includeOwnApp] is false. Used on
     * screen-off, manual "lock all" and when protection resumes.
     */
    fun lockAll(includeOwnApp: Boolean = true) {
        expiryJobs.values.forEach { it.cancel() }
        expiryJobs.clear()
        sessions.clear()
        pendingLocks.clear()
        abandonedAt.clear()
        leftAt.clear()
        clearSelfProtection()
        if (includeOwnApp) lockOwnApp()
        Log.i(TAG, "All sessions wiped")
        lockAllListeners.forEach { runCatching(it) }
    }

    fun onScreenTurnedOff() = lockAll()

    // ---------------------------------------------------------------------------------------
    // Lock-screen launch debouncing
    // ---------------------------------------------------------------------------------------

    fun isRegularLockPending(packageName: String): Boolean {
        val stamp = aliasesOf(packageName).firstNotNullOfOrNull { pendingLocks[it] } ?: return false
        if (now() - stamp < PENDING_LOCK_DEBOUNCE_MS) return true
        clearRegularLockPending(packageName)
        return false
    }

    fun setRegularLockPending(packageName: String) {
        val t = now()
        for (pkg in aliasesOf(packageName)) {
            pendingLocks[pkg] = t
            abandonedAt.remove(pkg)
        }
    }

    fun clearRegularLockPending(packageName: String) {
        for (pkg in aliasesOf(packageName)) pendingLocks.remove(pkg)
    }

    fun markLockAbandoned(packageName: String) {
        val t = now()
        for (pkg in aliasesOf(packageName)) {
            pendingLocks.remove(pkg)
            abandonedAt[pkg] = t
        }
    }

    fun isRecentlyAbandoned(packageName: String): Boolean {
        val stamp = aliasesOf(packageName).firstNotNullOfOrNull { abandonedAt[it] } ?: return false
        if (now() - stamp < ABANDONED_LOCK_COOLDOWN_MS) return true
        for (pkg in aliasesOf(packageName)) abandonedAt.remove(pkg)
        return false
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

    fun lockOwnApp() {
        ownUnlockedAt = 0L
        ownLastActiveAt = 0L
        ownInForeground = false
        externalNavigationAt = 0L
    }
}
