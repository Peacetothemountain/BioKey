package com.android.BioKey.core.security

import android.os.SystemClock
import android.util.Log
import com.android.BioKey.core.data.LockTimeoutMode
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
 * Nothing here is persisted: every unlock lives only in RAM and is wiped on screen-off, process
 * death, or manual lock-all, so a reboot or a killed process always fails closed (everything
 * locked again). All times use [SystemClock.elapsedRealtime] so wall-clock changes can't extend
 * a session.
 */
object LockStateTracker {

    private const val TAG = "LockStateTracker"

    /**
     * IMMEDIATE mode tolerance for non-launcher focus changes we couldn't classify, so a
     * transient system window doesn't double-prompt. Leaving via Home locks with no grace.
     */
    private const val IMMEDIATE_GRACE_MS = 1_500L

    /**
     * Handshake Immunity: After an app is unlocked, internal lifecycle shifts
     * (splash-to-main, task stack replacement, in-app sign in, 2FA, OAuth, and activity
     * recreation) remain immune from relocking for up to 120 seconds, unless the user
     * explicitly navigates to the launcher. This permanently eliminates double-authentication.
     */
    const val HANDSHAKE_IMMUNITY_DURATION_MS = 120_000L

    /** Longest a locked app stays unlocked while the user fetches a 2FA code elsewhere. */
    private const val AUTH_SUBFLOW_MAX_MS = 120_000L

    /** Suppresses duplicate lock-screen launches from bursts of accessibility events. */
    private const val PENDING_LOCK_DEBOUNCE_MS = 1_200L

    /** How long a successful self-protection challenge lets the user stay in BioKey's settings pages. */
    private const val SELF_PROTECTION_LEASE_MS = 120_000L

    /** BioKey's own session ends after this long without interaction, in BioKey or away from it. */
    const val OWN_APP_IDLE_MS = 3 * 60_000L

    private const val PKG_GMS_PASSWORDS = "com.google.android.gms.passwordmanager"
    private const val PKG_CREDENTIAL_MANAGER = "com.google.android.apps.credentialmanager"
    private val PASSWORD_MANAGER_ALIASES = setOf(PKG_GMS_PASSWORDS, PKG_CREDENTIAL_MANAGER)

    private val unlockedAt = ConcurrentHashMap<String, Long>()
    private val exitedAt = ConcurrentHashMap<String, Long>()
    private val pendingLocks = ConcurrentHashMap<String, Long>()
    private val failedAttempts = ConcurrentHashMap<String, Int>()

    // Tracks packages whose most recent exit was an explicit Home/Launcher exit
    private val launcherExited = ConcurrentHashMap.newKeySet<String>()

    // Per-package Auth Sub-flow tracking to eliminate multi-app collision
    private val authSubFlows = ConcurrentHashMap<String, Long>()

    @Volatile private var selfProtectionGrantedAt = 0L

    @Volatile private var ownUnlockedAt = 0L
    @Volatile private var ownLastActiveAt = 0L
    @Volatile private var ownInForeground = false

    private val lockAllListeners = CopyOnWriteArraySet<() -> Unit>()
    private val appUnlockListeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val appLockListeners = CopyOnWriteArraySet<(String) -> Unit>()

    /** Notified after every [lockAll] (screen-off, manual lock), e.g. to re-redact notifications. */
    fun addLockAllListener(listener: () -> Unit) { lockAllListeners += listener }
    fun removeLockAllListener(listener: () -> Unit) { lockAllListeners -= listener }

    /** Notified whenever an app is unlocked, e.g. for notification shield stand-in dismissal. */
    fun addAppUnlockListener(listener: (String) -> Unit) { appUnlockListeners += listener }
    fun removeAppUnlockListener(listener: (String) -> Unit) { appUnlockListeners -= listener }

    /** Notified whenever an app is locked or exits to launcher, e.g. to re-mask notifications. */
    fun addAppLockListener(listener: (String) -> Unit) { appLockListeners += listener }
    fun removeAppLockListener(listener: (String) -> Unit) { appLockListeners -= listener }

    // Short-lived Step-Up lease for dangerous security modifications
    @Volatile private var stepUpGrantedAt = 0L
    private const val STEP_UP_LEASE_MS = 20_000L

    fun onStepUpPromptSuccess() {
        stepUpGrantedAt = now()
    }

    fun hasActiveStepUpLease(): Boolean {
        val active = (now() - stepUpGrantedAt) < STEP_UP_LEASE_MS
        if (!active) stepUpGrantedAt = 0L
        return active
    }

    fun consumeStepUpLease() {
        stepUpGrantedAt = 0L
    }

    private fun now() = SystemClock.elapsedRealtime()

    /** Google Password Manager is reachable through two packages; treat them as one app. */
    private fun aliasesOf(packageName: String): Set<String> =
        if (packageName in PASSWORD_MANAGER_ALIASES) PASSWORD_MANAGER_ALIASES else setOf(packageName)

    private fun sessionKey(packageName: String): String =
        aliasesOf(packageName).firstOrNull { unlockedAt.containsKey(it) } ?: packageName

    // ---------------------------------------------------------------------------------------
    // Locked-app sessions
    // ---------------------------------------------------------------------------------------

    private val lockScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val activeGraceJobs = ConcurrentHashMap<String, Job>()

    /**
     * Schedules an active background timer to enforce [lockPackage] when a grace period expires,
     * immediately notifying [appLockListeners] so notifications re-shield without waiting for app re-entry.
     */
    fun scheduleGraceTimeout(packageName: String, delayMs: Long) {
        cancelGraceTimeout(packageName)
        if (delayMs <= 0L) {
            lockPackage(packageName)
            return
        }
        val job = lockScope.launch {
            delay(delayMs)
            Log.i(TAG, "Grace period expired for $packageName ($delayMs ms); actively locking package")
            lockPackage(packageName)
        }
        for (pkg in aliasesOf(packageName)) {
            activeGraceJobs[pkg] = job
        }
    }

    fun cancelGraceTimeout(packageName: String) {
        for (pkg in aliasesOf(packageName)) {
            activeGraceJobs.remove(pkg)?.cancel()
        }
    }

    fun onAppUnlocked(packageName: String) {
        val t = now()
        cancelGraceTimeout(packageName)
        for (pkg in aliasesOf(packageName)) {
            unlockedAt[pkg] = t
            exitedAt.remove(pkg)
            pendingLocks.remove(pkg)
            failedAttempts.remove(pkg)
            launcherExited.remove(pkg)
        }
        Log.i(TAG, "Session started for $packageName")
        appUnlockListeners.forEach { runCatching { it(packageName) } }
    }

    /** The app is in the foreground again within its session: cancel any pending exit timer. */
    fun onAppResumed(packageName: String) {
        cancelGraceTimeout(packageName)
        for (pkg in aliasesOf(packageName)) {
            exitedAt.remove(pkg)
            pendingLocks.remove(pkg)
            launcherExited.remove(pkg)
        }
    }

    /**
     * Records when the user left the app.
     *
     * If [isLauncherExit] is true (Home gesture / back to launcher), the exit is recorded
     * as an explicit launcher exit so returning to the app requires authentication.
     */
    fun onAppExited(
        packageName: String,
        isLauncherExit: Boolean = false,
        at: Long = now(),
        timeoutMode: LockTimeoutMode? = null
    ) {
        val key = sessionKey(packageName)
        if (isAuthSubFlowActive(key)) return

        for (pkg in aliasesOf(packageName)) {
            exitedAt[pkg] = at
            if (isLauncherExit) {
                launcherExited.add(pkg)
            } else {
                launcherExited.remove(pkg)
            }
        }

        if (unlockedAt.containsKey(key)) {
            if (timeoutMode != null) {
                if (isLauncherExit && timeoutMode == LockTimeoutMode.IMMEDIATE) {
                    lockPackage(packageName)
                } else if (timeoutMode == LockTimeoutMode.IMMEDIATE) {
                    scheduleGraceTimeout(packageName, IMMEDIATE_GRACE_MS)
                } else if (timeoutMode.durationMillis > 0L) {
                    scheduleGraceTimeout(packageName, timeoutMode.durationMillis)
                }
            } else if (isLauncherExit) {
                appLockListeners.forEach { runCatching { it(packageName) } }
            }
        }
    }

    fun isRecentlyExited(packageName: String, thresholdMs: Long = 1_500L): Boolean {
        val key = sessionKey(packageName)
        val exit = exitedAt[key] ?: return false
        return (now() - exit) < thresholdMs
    }

    fun isAppUnlocked(packageName: String, timeoutMode: LockTimeoutMode): Boolean {
        val key = sessionKey(packageName)
        val unlockTime = unlockedAt[key] ?: return false
        if (isAuthSubFlowActive(key)) return true

        val exit = exitedAt[key] ?: return true
        val sinceExit = now() - exit
        val wasLauncher = launcherExited.contains(key)

        // Handshake Immunity:
        // Protects brief internal transitions (splash-to-main, 2FA, OAuth).
        // Must NEVER bypass IMMEDIATE mode once an app has exited to an external non-auth window.
        // Must NEVER exceed the configured timeout duration for shorter grace periods.
        if (!wasLauncher && timeoutMode != LockTimeoutMode.IMMEDIATE) {
            val effectiveImmunity = if (timeoutMode.durationMillis > 0L) {
                minOf(HANDSHAKE_IMMUNITY_DURATION_MS, timeoutMode.durationMillis)
            } else {
                HANDSHAKE_IMMUNITY_DURATION_MS
            }
            if ((now() - unlockTime < effectiveImmunity) && (timeoutMode.durationMillis <= 0L || sinceExit < timeoutMode.durationMillis)) {
                return true
            }
        }

        val stillUnlocked = when (timeoutMode) {
            LockTimeoutMode.IMMEDIATE -> if (wasLauncher) false else sinceExit < IMMEDIATE_GRACE_MS
            LockTimeoutMode.ON_SCREEN_OFF -> true
            LockTimeoutMode.GRACE_30_SECONDS,
            LockTimeoutMode.GRACE_1_MINUTE,
            LockTimeoutMode.GRACE_5_MINUTES,
            LockTimeoutMode.GRACE_15_MINUTES -> sinceExit < timeoutMode.durationMillis
        }

        if (!stillUnlocked) lockPackage(packageName)
        return stillUnlocked
    }

    fun lockPackage(packageName: String) {
        cancelGraceTimeout(packageName)
        for (pkg in aliasesOf(packageName)) {
            unlockedAt.remove(pkg)
            exitedAt.remove(pkg)
            pendingLocks.remove(pkg)
            launcherExited.remove(pkg)
            authSubFlows.remove(pkg)
        }
        appLockListeners.forEach { runCatching { it(packageName) } }
    }

    /**
     * Wipes every session, including BioKey's own unless [includeOwnApp] is false. Used on
     * screen-off, manual "lock all" and when protection resumes.
     * Failed-attempt counters deliberately survive so pressing power can't reset them.
     */
    fun lockAll(includeOwnApp: Boolean = true) {
        activeGraceJobs.values.forEach { it.cancel() }
        activeGraceJobs.clear()
        unlockedAt.clear()
        exitedAt.clear()
        pendingLocks.clear()
        launcherExited.clear()
        authSubFlows.clear()
        clearSelfProtection()
        consumeStepUpLease()
        if (includeOwnApp) lockOwnApp()
        Log.i(TAG, "All sessions wiped")
        lockAllListeners.forEach { runCatching(it) }
    }

    fun onScreenTurnedOff() = lockAll()

    // ---------------------------------------------------------------------------------------
    // Per-App 2FA / Sign-in Sub-flows
    // ---------------------------------------------------------------------------------------

    fun enterAuthSubFlow(hostPackage: String) {
        val key = sessionKey(hostPackage)
        if (!unlockedAt.containsKey(key)) return
        authSubFlows[key] = now()
    }

    /** User came straight back to the host app: keep its session. */
    fun completeAuthSubFlow(hostPackage: String? = null) {
        val targets = hostPackage?.let { setOf(sessionKey(it)) } ?: authSubFlows.keys.toSet()
        for (host in targets) {
            authSubFlows.remove(host)
            onAppResumed(host)
        }
    }

    /**
     * User went somewhere other than back to the host (Home, another app): the host's session
     * now ages from this moment as a normal exit.
     */
    fun abandonAuthSubFlow(hostPackage: String? = null, isLauncherExit: Boolean = false) {
        val targets = hostPackage?.let { setOf(sessionKey(it)) } ?: authSubFlows.keys.toSet()
        for (host in targets) {
            authSubFlows.remove(host)
            onAppExited(host, isLauncherExit)
        }
    }

    fun isAuthSubFlowActive(packageName: String): Boolean {
        val key = sessionKey(packageName)
        val startedAt = authSubFlows[key] ?: return false
        if (now() - startedAt < AUTH_SUBFLOW_MAX_MS) return true

        // Overstayed: evict from subflow and age exit from startedAt without handshake bypass
        authSubFlows.remove(key)
        for (pkg in aliasesOf(key)) {
            exitedAt[pkg] = startedAt
            launcherExited.remove(pkg)
        }
        return false
    }

    fun getActiveAuthHost(): String? {
        val now = now()
        val expired = mutableListOf<String>()
        var activeHost: String? = null
        for ((host, startedAt) in authSubFlows) {
            if (now - startedAt < AUTH_SUBFLOW_MAX_MS) {
                activeHost = host
                break
            } else {
                expired.add(host)
            }
        }
        for (host in expired) isAuthSubFlowActive(host)
        return activeHost
    }

    // ---------------------------------------------------------------------------------------
    // Lock-screen launch debouncing and failed attempts
    // ---------------------------------------------------------------------------------------

    fun isRegularLockPending(packageName: String): Boolean {
        val stamp = aliasesOf(packageName).firstNotNullOfOrNull { pendingLocks[it] } ?: return false
        if (now() - stamp < PENDING_LOCK_DEBOUNCE_MS) return true
        clearRegularLockPending(packageName)
        return false
    }

    fun setRegularLockPending(packageName: String) {
        val t = now()
        for (pkg in aliasesOf(packageName)) pendingLocks[pkg] = t
    }

    fun clearRegularLockPending(packageName: String) {
        for (pkg in aliasesOf(packageName)) pendingLocks.remove(pkg)
    }

    fun recordFailedAttempt(packageName: String): Int =
        failedAttempts.merge(packageName, 1, Int::plus) ?: 1

    fun getFailedAttempts(packageName: String): Int = failedAttempts[packageName] ?: 0

    fun clearFailedAttempts(packageName: String) {
        failedAttempts.remove(packageName)
    }

    // ---------------------------------------------------------------------------------------
    // BioKey self-protection (settings / uninstall pages guarded by BioKey itself)
    // ---------------------------------------------------------------------------------------

    fun setSelfProtectionGranted(granted: Boolean) {
        selfProtectionGrantedAt = if (granted) now() else 0L
    }

    fun isSelfProtectionGranted(): Boolean {
        val granted = selfProtectionGrantedAt
        if (granted == 0L) return false
        if (now() - granted < SELF_PROTECTION_LEASE_MS) return true
        selfProtectionGrantedAt = 0L
        return false
    }

    fun clearSelfProtection() {
        selfProtectionGrantedAt = 0L
    }

    // ---------------------------------------------------------------------------------------
    // BioKey's own session
    // ---------------------------------------------------------------------------------------

    fun onOwnAppUnlocked() {
        val t = now()
        ownUnlockedAt = t
        ownLastActiveAt = t
        ownInForeground = true
    }

    fun onStepUpAuthenticated() = onOwnAppUnlocked()

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
        ownLastActiveAt = now()
        if (preAuthorizeSelfProtection) setSelfProtectionGranted(true)
    }

    fun lockOwnApp() {
        ownUnlockedAt = 0L
        ownLastActiveAt = 0L
        ownInForeground = false
    }
}
