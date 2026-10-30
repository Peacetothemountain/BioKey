package com.android.BioKey.core.security

import android.os.SystemClock
import android.util.Log
import com.android.BioKey.BuildConfig
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.diagnostics.DiagnosticLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * In-memory lock session state.
 *
 * Sessions follow the Android App Lock model: once you unlock an app it stays unlocked for as long
 * as it is on screen, no matter what happens inside it (refreshes, dialogs, sign-in sheets,
 * keyboards, chat heads, assistant overlays). A session only starts ageing once the app has
 * actually left the screen ([onAppLeft]), which each engine decides: the accessibility engine
 * from the window list rather than from individual window events, compatibility mode once the
 * app's activities have stopped.
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
     * How many times within [DISPLACED_WINDOW_MS] an app back over its own lock screen is covered
     * again at once ([coverAgainAfterDisplaced]). Past that it keeps doing it, and goes Home. Only
     * straight back counts: anything else in front between ends the run ([onForegroundChanged]).
     */
    private const val MAX_DISPLACED_COVERS = 2
    private const val DISPLACED_WINDOW_MS = 10_000L

    /** BioKey's own package: its lock screen in front is no app's run of returns ending. */
    private const val OWN_PACKAGE = BuildConfig.APPLICATION_ID

    /**
     * A new session must reach the screen within this long. One whose app never appears (its
     * notification opened another app, it crashed, Home was pressed first) ends instead of
     * waiting, unaged, for whoever opens the app next. A session whose lock screen closed onto a
     * helper the app had open over itself (a sign-in screen, a custom tab) has arrived, and is
     * hopping there, in both engines: it then ages as a hop does ([HOP_MAX_MS], ended by going
     * anywhere else), or, re-locking "when the screen turns off", only ends with the screen.
     */
    const val ARRIVAL_GRACE_MS = 15_000L

    /** How long BioKey's own session survives a trip to the system file picker for a backup. */
    private const val OWN_PICKER_LEASE_MS = 120_000L

    /** How long BioKey's own session survives another app's lock screen brought up over it ([beginOwnLockScreenHop]). */
    private const val OWN_LOCK_SCREEN_HOP_MS = 120_000L

    /** How long after an app left the screen its window may still flicker back during the exit animation. */
    private const val JUST_LEFT_MS = 2_000L

    /** How long a successful self-protection challenge lets the user stay in BioKey's settings pages. */
    private const val SELF_PROTECTION_LEASE_MS = 120_000L

    /**
     * BioKey's own session ends after this long without interaction, in BioKey or away from it.
     * Leaving BioKey ends it at once, except for the trips BioKey starts itself (a system settings
     * page, the backup file picker, another app's lock screen over it), which this limits too.
     */
    const val OWN_APP_IDLE_MS = 3 * 60_000L

    /**
     * The same lock-all reason within this long, with nothing unlocked in between, is logged
     * once: the screen going off reaches both the process-wide receiver and the accessibility
     * engine's own.
     */
    private const val LOCK_ALL_LOG_DEDUPE_MS = 2_000L

    /**
     * An unlocked app off screen: since when, and [hopTarget], the helper app the user went to
     * straight from it, while they are there. Never changed in place: a reader on another thread
     * (compatibility mode reads on its own) must never see the time of one exit with the hop of
     * another, which would end a session in the middle of a sign-in.
     */
    private class Away(val since: Long, val hopTarget: String?)

    /** One unlocked app. [away] is null while the app is on screen. */
    private class Session(val unlockedAt: Long) {
        val away = AtomicReference<Away?>(null)
        /** The app has been on screen since the unlock (see [ARRIVAL_GRACE_MS]). */
        @Volatile var seen = false
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val pendingLocks = ConcurrentHashMap<String, Long>()
    private val abandonedAt = ConcurrentHashMap<String, Long>()
    /** When an app's lock screen was last displaced rather than left ([markLockDisplaced]). */
    private val displacedAt = ConcurrentHashMap<String, Long>()
    /** When an app was last covered again over a displaced lock screen ([coverAgainAfterDisplaced]). */
    private val displacedCovers = ConcurrentHashMap<String, List<Long>>()
    private val leftAt = ConcurrentHashMap<String, Long>()

    @Volatile private var selfProtectionGrantedAt = 0L
    @Volatile private var selfProtectionVisitedGuardedScreen = false

    /**
     * BioKey's main screen is on display (started, not stopped), locked or not. Its windows can't
     * be read by its own accessibility service, nor send it events: this is how it knows.
     */
    @Volatile var ownScreenShowing = false

    @Volatile private var ownUnlockedAt = 0L
    @Volatile private var ownLastActiveAt = 0L
    @Volatile private var ownInForeground = false
    @Volatile private var externalNavigationAt = 0L
    @Volatile private var ownPickerAt = 0L
    @Volatile private var ownLockScreenHopAt = 0L
    /** The app whose lock screen that trip is for ([beginOwnLockScreenHop]). */
    @Volatile private var ownLockScreenHopFor: String? = null

    /** Bumped by every [lockAll], so an unlock that raced a device lock can be told apart. */
    @Volatile private var generation = 0

    /** The last lock-all reason logged, and when (see [LOCK_ALL_LOG_DEDUPE_MS]). */
    @Volatile private var lastLockAllLogged: Pair<String, Long>? = null

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

    /**
     * Where the re-lock delay in force comes from (the vault, as it loads): a delay changed in
     * Settings while an app is away applies to it, not the one it left under.
     */
    @Volatile
    var timeoutModeOf: ((String) -> LockTimeoutMode)? = null

    /** Re-checks a session when its away time runs out so notifications re-mask on time. */
    private fun scheduleExpiry(packageName: String, delayMs: Long, timeoutMode: LockTimeoutMode) {
        cancelExpiry(packageName)
        val job = timerScope.launch {
            delay(delayMs + 50L)
            onExpiryDue(packageName, timeoutMode)
        }
        for (pkg in PasswordManager.aliasesOf(packageName)) expiryJobs[pkg] = job
    }

    /**
     * [packageName]'s away time under [scheduled] ran out: it ends under the delay in force now.
     * Made longer meanwhile, the session carries on, and is looked at again when that runs out.
     */
    internal fun onExpiryDue(packageName: String, scheduled: LockTimeoutMode) {
        val mode = timeoutModeOf?.invoke(packageName) ?: scheduled
        if (!isAppUnlocked(packageName, mode) || mode.durationMillis <= 0L) return
        val away = sessionOf(packageName)?.away?.get() ?: return
        val left = away.since + awayAllowanceMs(mode, away.hopTarget != null) - now()
        if (left > 0L) scheduleExpiry(packageName, left, mode)
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
            displacedAt.remove(pkg)
            displacedCovers.remove(pkg)
        }
        // A lock-all on another thread may have cleared the sessions just before these went in:
        // it bumps the generation first, so looking again catches it.
        if (generation != this.generation) {
            for (pkg in PasswordManager.aliasesOf(packageName)) sessions.remove(pkg, session)
            return staleUnlock(packageName)
        }
        Log.i(TAG, "Session started for $packageName")
        DiagnosticLog.log("Unlocked $packageName")
        // A lock-all after this one ends something new: it is logged, whatever came just before.
        lastLockAllLogged = null
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
        if (session.away.getAndSet(null) == null) return
        cancelExpiry(packageName)
    }

    /**
     * The unlocked app left the screen.
     *
     * @param hopTarget the helper app the user went straight to, or null for Home, Recents or a
     *   regular app. Only a helper hop keeps an IMMEDIATE session alive (for [HOP_MAX_MS]).
     */
    /** [mode] as the diagnostic log says it: "after 30 s", "at screen off". */
    private fun relockLabel(mode: LockTimeoutMode) = when (mode) {
        LockTimeoutMode.IMMEDIATE -> "immediately"
        LockTimeoutMode.ON_SCREEN_OFF -> "at screen off"
        else -> if (mode.durationMillis < 60_000L) "after ${mode.durationMillis / 1000} s" else "after ${mode.durationMillis / 60_000} min"
    }

    fun onAppLeft(packageName: String, timeoutMode: LockTimeoutMode, hopTarget: String?) {
        val session = sessionOf(packageName) ?: return
        session.seen = true
        val away = Away(since = now(), hopTarget = hopTarget)
        if (!session.away.compareAndSet(null, away)) return
        for (pkg in PasswordManager.aliasesOf(packageName)) leftAt[pkg] = away.since
        Log.d(TAG, "$packageName left the screen (hop=$hopTarget)")
        // The diagnostic log names locked apps only: a hop's destination is just "a helper app".
        DiagnosticLog.log("$packageName left the screen" + if (hopTarget != null) " · hop to a helper app" else " · re-lock: ${relockLabel(timeoutMode)}")
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
     *
     * Also ends other apps' runs of returns over their own lock screen ([coverAgainAfterDisplaced]):
     * one opened again after Home or another app is the owner opening it, every time, however
     * quickly (compatibility mode sees no tap on its icon to end the cooldown), not an app that
     * keeps putting itself back.
     */
    fun onForegroundChanged(
        foregroundPackage: String,
        timeoutModeOf: (String) -> LockTimeoutMode,
        continuesHop: (String) -> Boolean = { false }
    ) {
        if (foregroundPackage != OWN_PACKAGE) {
            val front = PasswordManager.aliasesOf(foregroundPackage)
            displacedCovers.keys.removeAll { it !in front }
        }
        for ((pkg, session) in sessions) {
            val away = session.away.get() ?: continue
            val hop = away.hopTarget ?: continue
            if (hop == foregroundPackage || pkg in PasswordManager.aliasesOf(foregroundPackage) || continuesHop(foregroundPackage)) continue
            // Only the exit it was read from: back on screen meanwhile, the app isn't away at all.
            if (!session.away.compareAndSet(away, Away(away.since, hopTarget = null))) continue
            val mode = timeoutModeOf(pkg)
            Log.d(TAG, "Hop from $pkg ended at $foregroundPackage")
            DiagnosticLog.log("Hop from $pkg ended: another app came to the front")
            if (!isAppUnlocked(pkg, mode)) continue
            if (mode.durationMillis > 0L) {
                scheduleExpiry(pkg, (away.since + mode.durationMillis - now()).coerceAtLeast(0L), mode)
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
        // The file picker BioKey opened itself, from its own unlocked screen: the owner's trip.
        if (packageName in ownPickers && isOwnPickerActive()) return true
        val session = sessionOf(packageName) ?: return false
        if (!session.seen && now() - session.unlockedAt > ARRIVAL_GRACE_MS) {
            return !expire(packageName, session, "never reached the screen") && isAppUnlocked(packageName, timeoutMode)
        }
        val away = session.away.get()
        if (away == null || timeoutMode == LockTimeoutMode.ON_SCREEN_OFF) return true
        val hopping = away.hopTarget != null
        if (timeoutMode == LockTimeoutMode.IMMEDIATE && !hopping) {
            return !expire(packageName, session, "left, re-locks immediately") && isAppUnlocked(packageName, timeoutMode)
        }
        if (now() - away.since < awayAllowanceMs(timeoutMode, hopping)) return true
        return !expire(packageName, session, "re-lock time passed") && isAppUnlocked(packageName, timeoutMode)
    }

    /**
     * The locked apps open right now: a session that [isAppUnlocked] still honours (looking ends
     * any that ran out). Both of Google Password Manager's names count as one app's.
     */
    fun unlockedApps(timeoutModeOf: (String) -> LockTimeoutMode): Set<String> =
        sessions.keys.filterTo(HashSet()) { isAppUnlocked(it, timeoutModeOf(it)) }

    /**
     * How long [packageName], unlocked and off screen, has left before it locks again; null while
     * it's on screen, when it waits for the screen to turn off, or when it isn't unlocked.
     */
    fun awayRemainingMs(packageName: String, timeoutMode: LockTimeoutMode): Long? {
        if (!isAppUnlocked(packageName, timeoutMode) || timeoutMode == LockTimeoutMode.ON_SCREEN_OFF) return null
        val away = sessionOf(packageName)?.away?.get() ?: return null
        return (awayAllowanceMs(timeoutMode, away.hopTarget != null) - (now() - away.since)).coerceAtLeast(0L)
    }

    /**
     * Ends [session], found over: [packageName]'s session as it was when looked at. One started
     * since, by an unlock on another thread (while an expiry timer was checking the last one,
     * say), is left alone. Returns whether [session] ended here.
     */
    private fun expire(packageName: String, session: Session, reason: String): Boolean {
        var ended = false
        for (pkg in PasswordManager.aliasesOf(packageName)) if (sessions.remove(pkg, session)) ended = true
        if (!ended) return false
        // What [endSessions] does after removing a session, without removing whatever is there now.
        cancelExpiry(packageName)
        for (pkg in PasswordManager.aliasesOf(packageName)) pendingLocks.remove(pkg)
        Log.i(TAG, "Session ended for $packageName")
        DiagnosticLog.log("Session ended: $packageName ($reason)")
        notifyLocked(setOf(packageName))
        return true
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
        displacedAt.clear()
        displacedCovers.clear()
        leftAt.clear()
        clearSelfProtection()
        if (includeOwnApp) lockOwnApp()
        Log.i(TAG, "All sessions wiped")
        if (reason != null) logLockAll(reason)
        lockAllListeners.forEach { runCatching(it) }
    }

    /**
     * One diagnostic line per lock-all, not one per receiver that reported it: a second "screen
     * off" moments after the first says nothing new, and would halve how much history the log
     * keeps. Every lock-all still ends every session and tells the listeners.
     */
    private fun logLockAll(reason: String) {
        val t = now()
        val last = lastLockAllLogged
        if (last != null && last.first == reason && t - last.second in 0 until LOCK_ALL_LOG_DEDUPE_MS) return
        lastLockAllLogged = reason to t
        DiagnosticLog.log("All sessions ended ($reason)")
    }

    fun onScreenTurnedOff() = lockAll(reason = "screen off")

    @Volatile private var deviceLocked = false

    /**
     * The device's locked state, as last seen. Locking the device (screen off, screensaver, the
     * power menu's Lockdown, a lock with the screen still on) ends every session, every time.
     */
    fun onDeviceLockState(locked: Boolean) {
        if (locked && !deviceLocked) lockAll(reason = "phone locked")
        if (!locked && deviceLocked) phoneUnlocks++
        deviceLocked = locked
    }

    /**
     * A screensaver is showing (DREAMING_STARTED until DREAMING_STOPPED, or the phone unlocked):
     * the phone counts as locked, nothing is covered over it, and a lock screen started as it came
     * waits behind it whatever the keyguard's own lock delay says ([ScreenReceiver] keeps this).
     */
    var screensaverShowing: Boolean
        get() = screensaverFlow.value
        set(value) { screensaverFlow.value = value }
    private val screensaverFlow = MutableStateFlow(false)
    /** [screensaverShowing], to follow: a lock screen held while one showed prompts as it ends. */
    val screensaver: StateFlow<Boolean> = screensaverFlow.asStateFlow()

    /** Times the phone has been seen unlocked (the keyguard going away), as the engines report it. */
    @Volatile
    var phoneUnlocks = 0
        private set

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

    /** The owner left [packageName]'s lock screen on purpose (Cancel, Back) without unlocking. */
    fun markLockAbandoned(packageName: String) = abandon(packageName, displaced = false)

    /**
     * [packageName]'s lock screen went to the background without an unlock, without the owner
     * leaving it on purpose: something else came over it. Often the locked app itself (a launch
     * screen opening the app's main one, a sign-in check), which brings it back in front during
     * the cooldown: it is then covered again at once ([coverAgainAfterDisplaced]), not sent Home.
     */
    fun markLockDisplaced(packageName: String) = abandon(packageName, displaced = true)

    private fun abandon(packageName: String, displaced: Boolean) {
        val t = now()
        for (pkg in PasswordManager.aliasesOf(packageName)) {
            pendingLocks.remove(pkg)
            abandonedAt[pkg] = t
            if (displaced) displacedAt[pkg] = t else displacedAt.remove(pkg)
        }
        lockAbandonedListeners.forEach { runCatching { it(packageName) } }
    }

    /**
     * Whether [packageName], back in front during its cooldown, is to be covered again at once:
     * its lock screen was displaced ([markLockDisplaced]), not left, and it hasn't come back over
     * it [MAX_DISPLACED_COVERS] times already within [DISPLACED_WINDOW_MS] (an app that keeps
     * putting itself over its lock screen goes Home instead). True counts as one of those covers.
     */
    fun coverAgainAfterDisplaced(packageName: String): Boolean {
        val t = now()
        val aliases = PasswordManager.aliasesOf(packageName)
        val stamp = aliases.firstNotNullOfOrNull { displacedAt[it] } ?: return false
        if (t - stamp >= ABANDONED_LOCK_COOLDOWN_MS) return false
        val recent = aliases.firstNotNullOfOrNull { displacedCovers[it] }.orEmpty().filter { t - it < DISPLACED_WINDOW_MS }
        if (recent.size >= MAX_DISPLACED_COVERS) return false
        for (pkg in aliases) {
            displacedAt.remove(pkg)
            displacedCovers[pkg] = recent + t
        }
        return true
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
        for (pkg in PasswordManager.aliasesOf(packageName)) {
            abandonedAt.remove(pkg)
            displacedAt.remove(pkg)
        }
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
        lastLockAllLogged = null
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
        lockOwnApp(timedOut = true)
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
        ownLockScreenHopAt = 0L
        clearSelfProtection()
        if (!isOwnAppUnlocked()) return
        ownInForeground = true
        ownLastActiveAt = now()
    }

    /**
     * BioKey's screen stopped on a trip it started itself ([isOwnTripActive]). One the owner asked
     * for (a settings page, the file picker) counts as activity; another app's lock screen brought
     * up over BioKey ([beginOwnLockScreenHop]) doesn't: nobody touched BioKey for it, which can
     * happen with nobody at the phone (a timed pause running out), so it mustn't stretch
     * [OWN_APP_IDLE_MS]. Coming back still counts ([onOwnAppResumed]).
     */
    fun onOwnAppStopped() {
        if (!isOwnAppUnlocked()) return
        ownInForeground = false
        if (externalNavigationAt != 0L || ownPickerAt != 0L) ownLastActiveAt = now()
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
        endTripAway()
        return false
    }

    /**
     * BioKey is about to open the system file picker for a backup or restore: its session survives
     * that one trip (at most [OWN_PICKER_LEASE_MS], ended by coming back, by going anywhere but the
     * picker, or by any lock-all). Unlike [beginExternalNavigation], it grants no self-protection.
     */
    fun beginOwnPicker(pickers: Set<String> = emptySet()) {
        if (!hasActiveOwnerSession()) return
        val t = now()
        ownLastActiveAt = t
        ownPickerAt = t
        ownPickers = pickers
    }

    /** The file picker's own packages: locked themselves (a "Files" app), they open for BioKey's own trip. */
    @Volatile private var ownPickers: Set<String> = emptySet()

    fun isOwnPickerActive(): Boolean {
        val started = ownPickerAt
        if (started == 0L) return false
        if (now() - started < OWN_PICKER_LEASE_MS && isOwnAppUnlocked()) return true
        ownPickerAt = 0L
        endTripAway()
        return false
    }

    /**
     * An engine is about to bring up [target]'s lock screen over BioKey's own unlocked screen, in
     * front or beside the app in front (a locked app's window in picture-in-picture, a locked app
     * beside BioKey in split screen as protection resumes). BioKey starts that trip itself, and
     * the lock screen closes back onto BioKey, so its session survives it: at most
     * [OWN_LOCK_SCREEN_HOP_MS], ended by coming back, by any other app coming to the front (Home,
     * once that lock screen is cancelled), by that lock screen stopping anywhere but over BioKey
     * ([endOwnLockScreenHop]), or by any lock-all. Unlike [beginExternalNavigation], no other app
     * (not even Settings) is part of the trip, and it grants no self-protection. Never for an app
     * that came to the front by itself: that is the owner leaving BioKey.
     */
    fun beginOwnLockScreenHop(target: String) {
        if (!hasActiveOwnerSession()) return
        ownLockScreenHopFor = target
        ownLockScreenHopAt = now()
    }

    fun isOwnLockScreenHopActive(): Boolean {
        val started = ownLockScreenHopAt
        if (started == 0L) return false
        if (now() - started < OWN_LOCK_SCREEN_HOP_MS && isOwnAppUnlocked()) return true
        ownLockScreenHopAt = 0L
        endTripAway()
        return false
    }

    /**
     * Whether the lock-screen trip running now is for [pkg]: that app coming to the front is its
     * lock screen closing onto it (beside BioKey in split screen, once unlocked), not another app.
     */
    fun isOwnLockScreenHopFor(pkg: String): Boolean = ownLockScreenHopAt != 0L && ownLockScreenHopFor == pkg

    /**
     * BioKey's lock screen, shown since [shownSince] ([SystemClock.elapsedRealtime]), stopped or
     * closed: a trip begun before then went through it, and ends. Closing back onto BioKey, its
     * screen has resumed by now, which ended the trip already ([onOwnAppResumed]); anywhere else
     * (Home, an app no engine sees, such as a work app in compatibility mode), BioKey's session
     * ends with the trip, as it would have when BioKey stopped. A trip begun since is for the lock
     * screen asked for next, over BioKey back in front, and carries on.
     */
    fun endOwnLockScreenHop(shownSince: Long) {
        val started = ownLockScreenHopAt
        if (started == 0L || started > shownSince) return
        ownLockScreenHopAt = 0L
        if (!ownInForeground) {
            Log.i(TAG, "BioKey's lock-screen trip ended away from BioKey; session ended")
            lockOwnApp()
        }
    }

    /**
     * BioKey is on a trip it started itself ([beginExternalNavigation], [beginOwnPicker],
     * [beginOwnLockScreenHop]): its screen stopping then isn't leaving BioKey.
     */
    fun isOwnTripActive(): Boolean = isExternalNavigationActive() || isOwnPickerActive() || isOwnLockScreenHopActive()

    /**
     * A trip's lease ran out while BioKey was away: the session it kept ends with it, rather than
     * carrying on until [OWN_APP_IDLE_MS] for whoever opens BioKey next. Back in BioKey, coming
     * back already ended the trip ([onOwnAppResumed]).
     */
    private fun endTripAway() {
        if (!ownInForeground && ownUnlockedAt != 0L) {
            Log.i(TAG, "BioKey's trip away ran out; session ended")
            lockOwnApp(timedOut = true, tripRanOut = true)
        }
    }

    private val ownLocksFlow = MutableStateFlow(0)

    /**
     * Counts BioKey's own session ending, however it ended (a timeout, "lock all" from a tile or
     * the shortcut, the screen turning off): its screens follow at once rather than at their next check.
     */
    val ownLocks: StateFlow<Int> = ownLocksFlow.asStateFlow()

    /** Whether BioKey's session last ended by running out (3 minutes idle, a trip away), not by being locked. */
    @Volatile
    var ownSessionTimedOut = false
        private set

    /** Whether that was a trip of BioKey's own (a settings page, the file picker) running out while away. */
    @Volatile
    var ownTripRanOut = false
        private set

    fun lockOwnApp(timedOut: Boolean = false, tripRanOut: Boolean = false) {
        ownSessionTimedOut = timedOut
        ownTripRanOut = tripRanOut
        SecurityEngine.INSTANCE.forgetOwnProof()
        ownUnlockedAt = 0L
        ownLastActiveAt = 0L
        ownInForeground = false
        externalNavigationAt = 0L
        ownPickerAt = 0L
        ownLockScreenHopAt = 0L
        ownLocksFlow.update { it + 1 }
    }
}
