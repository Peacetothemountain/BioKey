package com.android.BioKey.feature.lock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.diagnostics.EngineTrace
import com.android.BioKey.core.diagnostics.LatencyMarks
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Enforcement engine: covers locked apps with [LockScreenActivity] until the owner authenticates.
 *
 * Two inputs, two jobs:
 * - Window *events* say "this app just came to the front". They are the fast path: a locked app
 *   that isn't unlocked gets the [InstantShieldOverlay] immediately, before anything else runs.
 * - The window *list* says which apps are actually on screen. Sessions are driven only by it
 *   ([reconcile]): an unlocked app stays unlocked while any of its windows is visible, whatever
 *   else pops up over it (dialogs, sheets, keyboards, chat heads, assistants, refreshes), and it
 *   only counts as left once its windows are gone for [SessionRules.LEAVE_CONFIRM_MS].
 *
 * Windows a full-screen cover can't stay over (picture-in-picture, a window beside the lock
 * screen, another display) get a [WindowMask] patch of their own.
 *
 * Also guards BioKey itself (self-protection): Settings/installer screens that could disable or
 * uninstall BioKey require authentication while self-protection is on.
 */
class BioKeyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BioKeyA11yService"

        /** Coalesces window churn (animations, transitions) into one visibility pass. */
        private const val RECONCILE_DELAY_MS = 150L

        /** A visibility pass that failed is tried again this many times, each wait twice the last. */
        private const val RECONCILE_RETRIES = 5

        /** How long a cover raised by a tap waits for the tapped app's window (cold starts). */
        private const val PREDICTION_WAIT_MS = 3_000L


        /** After an unlock, how long an unreadable window that opens is taken to be the unlocked app's. */
        private const val UNLOCK_ATTRIBUTION_MS = 2_000L

        /**
         * A lock screen started this recently, and not resumed yet, isn't started again by another
         * event ([launchLockScreen]); the shield's own checks (after 60 ms, then every 700 ms) do.
         */
        private const val LAUNCH_REPEAT_MS = 300L

        /** How many unreadable windows are remembered as some app's ([attributedWindows]). */
        private const val MAX_ATTRIBUTED_WINDOWS = 16


        /** Window events, always on. Clicks and content changes only while needed: see [applyEventTypes]. */
        private const val BASE_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED

        /** Tags the checks that run when an abandoned lock's cooldown ends. */
        private val ABANDON_TOKEN = Any()

        private val runningState = MutableStateFlow(false)

        /** [isRunning], to follow: what BioKey says about protection changes as the service connects or stops. */
        val running: StateFlow<Boolean> = runningState.asStateFlow()

        var isRunning: Boolean
            get() = runningState.value
            private set(value) {
                runningState.value = value
            }

        @Volatile private var activeService: BioKeyAccessibilityService? = null

        /** BioKey's prompt has handed over to the device PIN, pattern or password screen (only while a prompt is up). */
        @Volatile
        var isConfirmingDeviceCredential: Boolean = false
            get() = field && SecurityEngine.INSTANCE.isAuthenticating()
            private set

        fun lockDevice(): Boolean {
            return activeService?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
        }

        fun dismissSelfProtection(): Boolean {
            val svc = activeService ?: return false
            svc.navigateBackFromGuardedSelfProtection()
            return true
        }

        fun isServiceEnabled(context: Context): Boolean {
            if (isRunning) return true
            val expected = ComponentName(context, BioKeyAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }

        const val SELF_PROTECTION_PENDING_KEY = "biokey:self-protection"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var vault: SecurityVault
    private lateinit var shield: InstantShieldOverlay
    /** Patches over windows a full-screen cover can't stay over: see [MaskController]. */
    private lateinit var maskControl: MaskController
    private lateinit var labels: AppLabels
    /** Only in builds with self-protection: the Play build never reads Settings' pages. */
    private var selfProtection: SelfProtectionDetector? = null
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }
    /** The title BioKey's own windows go by (its name): they can't be read, but must never be taken for another app's. */
    private var ownTitle: String? = null

    /** The app most recently brought to the front by a window event. */
    private var foregroundPackage: String? = null

    /**
     * Accessibility window id -> owning package, learned from window events. Google Password
     * Manager's own windows are under its virtual package ([resolveForegroundPackage]).
     */
    private val windowPackages = HashMap<Int, String>()
    /** Accessibility window id -> window type, to tell an app's floating windows from its screens. */
    private val windowTypes = HashMap<Int, Int>()
    /**
     * Unreadable windows (apps that hide them from accessibility services send no window events)
     * taken to be an app's because they opened right after a tap on it or its unlock. Kept while
     * such a window is off screen, unlike [windowPackages]: it could never be learned again.
     */
    private val attributedWindows = object : LinkedHashMap<Int, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > MAX_ATTRIBUTED_WINDOWS
    }
    /** Last activity class seen per package (to recognise custom tabs as helpers). */
    private val lastClassByPackage = HashMap<String, String>()

    /** A launch predicted from a tap (package, when), until its window shows up: see [attributeUnreadable]. */
    private var expectedLaunch: Pair<String, Long>? = null
    /** The app unlocked last (package, when), for an unreadable window it opens: see [attributeUnreadable]. */
    private var recentUnlock: Pair<String, Long>? = null

    /** Which sessions are on screen, which have left, which locked apps to cover: see [SessionRules]. */
    private val rules = SessionRules(object : SessionRules.Env {
        override fun isLocked(pkg: String) = vault.isAppLocked(pkg)
        override fun timeoutOf(pkg: String) = vault.getTimeoutMode(pkg)
        override fun isLauncher(pkg: String) = this@BioKeyAccessibilityService.isLauncher(pkg)
        override fun isHopTarget(pkg: String) = this@BioKeyAccessibilityService.isHopTarget(pkg)
        override fun mayCover(pkg: String) = isCoverableByVisibility(pkg) && !isLauncher(pkg) && pkg !in maskedApps() &&
            needsCover(pkg) && !LockStateTracker.isRegularLockPending(pkg)
        override fun appTitled(title: String) = labels.appTitled(title, vault.getLockedPackages())?.takeIf { it != packageName }
        // App names and the screen titles apps declare (from the package manager) only: what a
        // window's own events say about it is never kept, as the disclosure promises.
        override fun isTitleOf(pkg: String, title: String) = labels.isTitleOf(pkg, title, vault.getLockedPackages())
    })

    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            EngineTrace.record("d.bc", intent.action)
            // First, and whatever goes wrong below: the screen going off ends every session.
            if (intent.action == Intent.ACTION_SCREEN_OFF) LockStateTracker.onScreenTurnedOff()
            if (!isRunning) return
            guarded("Screen change") {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        // A locked app left on screen: its lock screen goes up now, behind the
                        // keyguard, once every session has ended with the screen (ScreenReceiver,
                        // on this thread too), so unlocking the phone uncovers the cover, not the app.
                        foregroundPackage?.let { left -> mainHandler.post { coverLeftOnScreen(left) } }
                        watchLauncher(false)
                        foregroundPackage = null
                        expectedLaunch = null
                        recentUnlock = null
                        rules.reset()
                        isConfirmingDeviceCredential = false
                        maskControl.arm()
                    }
                    // Sessions ended with the screen, and it may come back on without a keyguard (it
                    // timed out moments ago): whatever is on screen is checked again.
                    Intent.ACTION_SCREEN_ON -> {
                        reevaluateForeground()
                        scheduleReconcile(0L)
                    }
                    // The keyguard just went away: whatever is on screen must be re-checked even if
                    // no window event arrives (e.g. a locked app was left open when the screen went off).
                    Intent.ACTION_USER_PRESENT -> {
                        // Cleared and not set up again: said at every unlock until it is.
                        ProtectionAlerts.remindIfStorageCleared(this@BioKeyAccessibilityService)
                        windowList()?.let { maskControl.update(it, full = false) }
                        reevaluateForeground()
                        mainHandler.postDelayed(::reevaluateForeground, 250)
                    }
                }
            }
        }
    }

    /** A keyboard was enabled or disabled: its windows must never count as an app switch. */
    private val inputMethodObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = ForegroundRules.onInputMethodsChanged(this@BioKeyAccessibilityService)
    }

    private var userUnlockedReceiver: BroadcastReceiver? = null
    /** [initServiceComponents] has been asked for: by the unlock, or by the look after registering for it. */
    private val initStarted = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        isRunning = true
        val userManager = getSystemService(UserManager::class.java)
        if (userManager?.isUserUnlocked == false) {
            Log.w(TAG, "Device in Direct Boot (before first unlock): deferring initialization")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_USER_UNLOCKED) initOnce()
                }
            }
            userUnlockedReceiver = receiver
            ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
            // Looked at again once registered: an unlock in between would otherwise go unseen, and
            // the service would say it runs while nothing is ever covered.
            if (userManager.isUserUnlocked) initOnce()
            return
        }
        initOnce()
    }

    /** Runs [initServiceComponents] once, whichever of the unlock broadcast and the second look comes first. */
    private fun initOnce() {
        if (!initStarted.compareAndSet(false, true)) return
        userUnlockedReceiver?.let {
            runCatching { unregisterReceiver(it) }
            userUnlockedReceiver = null
        }
        initServiceComponents()
    }

    private fun initServiceComponents() {
        if (!isRunning) return
        vault = SecurityVault.getInstance(this)
        AppInfoCache.preWarm(this, vault.getLockedPackages())
        // An app that stops being locked lets its icon go, as in compatibility mode.
        AppInfoCache.follow(vault)
        labels = AppLabels(this).apply { warmUp(vault.getLockedPackages()) }
        ownTitle = runCatching { applicationInfo.loadLabel(packageManager).toString() }.getOrNull()
        shield = InstantShieldOverlay(
            this,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            goHome = { pressHome() },
            onTimeout = ::onShieldTimeout
        )
        maskControl = MaskController(this, vault, shield, mainHandler, maskHost)
        if (SecurityVault.selfProtectionAvailable) selfProtection = SelfProtectionDetector(this)
        // Read now, in the background, rather than on the main thread at the first app switch.
        ForegroundRules.warmUp(this)
        AppInfoCache.onConfiguration(resources.configuration)
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS), false, inputMethodObserver
            )
        }
        ContextCompat.registerReceiver(
            this,
            deviceStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        LockStateTracker.addLockAllListener(onLockAll)
        LockStateTracker.addLockAbandonedListener(onLockAbandoned)
        LockStateTracker.addAppUnlockListener(onAppUnlocked)
        LockStateTracker.addAppLockListener(onAppLocked)
        ProtectionAlerts.serviceRestored(this)
        // The accessibility engine is the better one: compatibility mode stands down.
        UsageWatchService.stop(this)
        startEngineTrace(this, vault, "accessibility")
        // A locked app may already be in front (service restarted, or enabled over it).
        mainHandler.postDelayed(::reevaluateForeground, 250)
        Log.i(TAG, "Accessibility service connected & components initialized")
        DiagnosticLog.log("Accessibility engine started")
    }

    /**
     * Sessions just ended (protection resumed from the tile or a timer, lock-all) while an app may
     * be on screen: cover it now rather than at the next window event. Skipped with the screen off,
     * where nothing is visible and launching the lock screen would wake the display.
     */
    private val onLockAll: () -> Unit = {
        mainHandler.post {
            rules.reset()
            if (isRunning && ::vault.isInitialized && isInteractive()) {
                // A locked app in picture-in-picture is masked at once.
                guarded("Masks after lock-all") { windowList()?.let { maskControl.update(it, full = false) } }
                if (launcherInFront) watchLauncher(true)
                reevaluateForeground()
            }
        }
    }

    /**
     * A lock screen was left without unlocking. Its app isn't covered again during the cooldown
     * (its window can flicker back while the lock screen leaves), except when it comes back over a
     * displaced lock screen ([LockStateTracker.coverAgainAfterDisplaced]), so it is looked at once more
     * when the cooldown ends: an app re-opened meanwhile must not stay open.
     */
    private val onLockAbandoned: (String) -> Unit = { pkg ->
        mainHandler.postDelayed({ onAbandonCooldownOver(pkg) }, ABANDON_TOKEN, LockStateTracker.ABANDONED_LOCK_COOLDOWN_MS + 20L)
    }

    private val onAppUnlocked: (String) -> Unit = { pkg ->
        mainHandler.post {
            if (!isRunning || !::vault.isInitialized) return@post
            // Its windows are the user's again.
            for (alias in PasswordManager.aliasesOf(pkg)) maskControl.forget(alias)
            if (pkg != SELF_PROTECTION_PENDING_KEY) {
                val now = SystemClock.elapsedRealtime()
                recentUnlock = pkg to now
                rules.noteUnlocked(pkg, now)
            }
            if (launcherInFront) watchLauncher(true)
        }
    }

    /** A session ended: with the launcher in front, Recents may have nothing left to watch for. */
    private val onAppLocked: (String) -> Unit = {
        mainHandler.post { if (isRunning && ::vault.isInitialized && launcherInFront) watchLauncher(true) }
    }

    private fun isInteractive() = (power?.isInteractive == true).also { EngineTrace.answer("interactive", it) }

    private fun isDeviceLocked() = (keyguard?.isDeviceLocked == true).also { EngineTrace.answer("deviceLocked", it) }

    private fun isKeyguardLocked() = (keyguard?.isKeyguardLocked == true).also { EngineTrace.answer("keyguard", it) }

    /** Home, as the service presses it. Recorded for a replay ([EngineTrace]). */
    private fun pressHome(): Boolean {
        EngineTrace.record("o.home")
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Whose [w] reads as, for a trace ([EngineTrace.windows]): known from its events, or asked; a
     * window known to be unreadable is left empty, without asking it again (nor, as [packageOf]
     * would, remembering the answer: a debug build must decide as a release build does).
     */
    private fun traceRootPackage(w: AccessibilityWindowInfo): String? =
        windowPackages[w.id] ?: if (w.id in attributedWindows || reader.wasUnreadable(w.id)) null else windowPackage(w)

    // -------------------------------------------------------------------------------------------
    // Event handling
    // -------------------------------------------------------------------------------------------

    private var activeSelfProtectionHost: String? = null

    private val reconcileRunnable = Runnable { reconcile() }
    private var reconcileScheduled = false
    /** When the scheduled pass runs ([SystemClock.uptimeMillis]). */
    private var reconcileDueAt = 0L
    private var reconcileFailures = 0

    private val revokeSelfProtectionAfterExitRunnable = Runnable { guarded("Self-protection exit check", ::revokeSelfProtectionAfterExit) }
    private val trailingSelfProtectionRunnable = Runnable { guarded("Self-protection check", ::checkSelfProtectionAgain) }

    /** Runs a Handler task, where an exception would crash the service: it is logged instead. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "$what failed: ${describe(t)}")
        }
    }

    /**
     * An error as release builds log it (they keep Log.e): its class and where it was thrown. Never
     * its message, which can name the app being handled (a missing app's error is its package).
     */
    private fun describe(t: Throwable) = "${t.javaClass.name} at ${t.stackTrace.firstOrNull()}"

    private fun revokeSelfProtectionAfterExit() {
        if (!SecurityVault.selfProtectionAvailable) return
        if (shield.isShowing || LockScreenActivity.isLockScreenResumed || SecurityEngine.INSTANCE.isAuthenticating()) return
        if (LockStateTracker.hasVisitedGuardedSelfProtectionScreen()) {
            val host = activeSelfProtectionHost
            val stillGuarded = host != null && (selfProtection?.isGuardedScreen(host, null) == true)
            if (!stillGuarded) {
                LockStateTracker.clearSelfProtection()
            }
        }
    }

    private fun checkSelfProtectionAgain() {
        val host = activeSelfProtectionHost ?: return
        // Switched off (or not in this build): no reading Settings pages' text at all.
        if (!SecurityVault.selfProtectionAvailable || !::vault.isInitialized || !vault.isAccessibilitySelfProtectionEnabled()) return
        if (shield.isShowing || LockScreenActivity.isLockScreenResumed || SecurityEngine.INSTANCE.isAuthenticating() ||
            LockStateTracker.isRecentlyAbandoned(SELF_PROTECTION_PENDING_KEY)
        ) return
        if ((selfProtection?.isGuardedScreen(host, null) == true)) {
            onGuardedScreen()
        } else if (LockStateTracker.hasVisitedGuardedSelfProtectionScreen()) {
            mainHandler.removeCallbacks(revokeSelfProtectionAfterExitRunnable)
            mainHandler.postDelayed(revokeSelfProtectionAfterExitRunnable, 1_500L)
        }
    }

    // Clicks and content changes are costly and say more than BioKey needs, so they're on only
    // while something needs them: taps while the launcher is in front (tap prediction, Recents),
    // content changes while a self-protection host is to be inspected or while Recents could
    // close an app that has a session (see [RecentsWatcher]).
    private var contentForSelfProtection = false
    private var launcherInFront = false
    private var contentForRecents = false
    private var appliedEventTypes = BASE_EVENT_TYPES
    private var eventSettings: AccessibilityServiceInfo? = null

    private val recents by lazy { RecentsWatcher(this, ::activeRoot, ::onClosedFromRecents, ::onClearAllRecents) }

    private fun updateContentMonitoring(enable: Boolean) {
        contentForSelfProtection = enable
        applyEventTypes()
    }

    /** The launcher is (or isn't) in front: watch its taps, and Recents while an app has a session to end there. */
    private fun watchLauncher(inFront: Boolean) {
        val watchRecents = inFront && anySession()
        if (!watchRecents) recents.reset()
        launcherInFront = inFront
        contentForRecents = watchRecents
        applyEventTypes()
    }

    private fun applyEventTypes() {
        var types = BASE_EVENT_TYPES
        if (launcherInFront) types = types or AccessibilityEvent.TYPE_VIEW_CLICKED
        if (contentForSelfProtection || contentForRecents) types = types or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (types == appliedEventTypes) return
        val info = eventSettings ?: serviceInfo ?: return
        eventSettings = info
        info.eventTypes = types
        // Never batched: Android holds back (and drops) window events too while batching.
        info.notificationTimeout = 0
        if (runCatching { serviceInfo = info }.isSuccess) appliedEventTypes = types
    }

    /** Whether any locked app has a session: only then can closing it in Recents end something. */
    private fun anySession(): Boolean =
        ::vault.isInitialized && vault.getLockedPackages().any { LockStateTracker.isAppUnlocked(it, vault.getTimeoutMode(it)) }

    /**
     * A locked app that isn't unlocked and should be covered right now. [ignoreAbandoned]: the
     * user just tapped it, so a lock screen they left a moment ago doesn't count.
     */
    private fun needsCover(pkg: String, ignoreAbandoned: Boolean = false): Boolean =
        pkg != packageName &&
            vault.isAppLocked(pkg) &&
            !LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg)) &&
            (ignoreAbandoned || !LockStateTracker.isRecentlyAbandoned(pkg)) &&
            !(LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg)

    private fun needsSelfProtectionMonitoring(): Boolean =
        SecurityVault.selfProtectionAvailable &&
            vault.isSetupCompleted() &&
            vault.isProtectionActive() &&
            vault.isAccessibilitySelfProtectionEnabled() &&
            !LockStateTracker.hasVisitedGuardedSelfProtectionScreen()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::vault.isInitialized) return
        EngineTrace.event(event)
        reader.duringEvent { handleEvent(event) }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        // Switched on in Android's settings before BioKey's setup (and the explanation it shows
        // first): nothing it sees is looked at until then.
        if (!vault.isSetupCompleted()) return
        try {
            val type = event.eventType
            val eventPkg = event.packageName?.toString()
            val eventCls = event.className?.toString().orEmpty()

            if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                if (eventPkg != null && isLauncher(eventPkg)) {
                    if (contentForRecents) recents.onLauncherClicked(event)
                    predictLaunch(event)
                }
                return
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && contentForRecents && eventPkg != null && isLauncher(eventPkg)) {
                recents.onLauncherChanged(eventPkg, event.contentChangeTypes)
            }
            if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                onWindowsChanged(event)
                return
            }

            if (!SecurityEngine.INSTANCE.isAuthenticating()) {
                isConfirmingDeviceCredential = false
            }

            // Worked out once per event: it looks at the window list.
            var exitTransition: Boolean? = null
            val display = displayOf(event)
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && eventPkg != null) {
                // Fast path: cover a locked app before any other work. The shield covers the
                // phone's own screen only: an app on another display gets its lock screen there.
                val target = resolveForegroundPackage(event)
                if (target != null && !isSystemOrOverlay(target, eventCls) && needsCover(target)) {
                    exitTransition = isExitTransition(target)
                    if (exitTransition == false && isPhoneScreen(display)) LatencyMarks.trigger("accessibility", target, event.eventTime)
                    if (exitTransition == false && isPhoneScreen(display)) shield.show(target) { launchLockScreen(target, isSelfProtection = false) }
                }
                if (event.windowId >= 0) windowPackages[event.windowId] = target ?: eventPkg
                if (eventCls.isNotEmpty()) lastClassByPackage[eventPkg] = eventCls

                if (isDeviceCredentialScreen(eventPkg, eventCls)) {
                    isConfirmingDeviceCredential = true
                } else if (
                    isConfirmingDeviceCredential &&
                    eventPkg != packageName &&
                    eventPkg != "com.android.settings" &&
                    eventPkg != "com.android.systemui" &&
                    eventPkg !in ForegroundRules.inputMethods(this) &&
                    eventPkg !in ForegroundRules.SYSTEM_OVERLAYS
                ) {
                    isConfirmingDeviceCredential = false
                }
                val inSelfProtHost = SelfProtectionDetector.isHostPackage(eventPkg) && !isConfirmingDeviceCredential
                if (inSelfProtHost) {
                    activeSelfProtectionHost = eventPkg
                    updateContentMonitoring(needsSelfProtectionMonitoring())
                } else if (eventPkg != packageName && !isSystemOrOverlay(eventPkg, eventCls)) {
                    activeSelfProtectionHost = null
                    mainHandler.removeCallbacks(revokeSelfProtectionAfterExitRunnable)
                    updateContentMonitoring(false)
                }
            }

            // Self-protection runs first: installers count as system overlays below.
            if (eventPkg != null && SelfProtectionDetector.isHostPackage(eventPkg) && !isConfirmingDeviceCredential) {
                if (SecurityEngine.INSTANCE.isAuthenticatingFor(SELF_PROTECTION_PENDING_KEY) ||
                    LockStateTracker.isRegularLockPending(SELF_PROTECTION_PENDING_KEY)
                ) {
                    if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !LockScreenActivity.isLockScreenResumed) {
                        shield.show(SELF_PROTECTION_PENDING_KEY) {
                            launchLockScreen(SELF_PROTECTION_PENDING_KEY, isSelfProtection = true)
                        }
                        launchLockScreen(SELF_PROTECTION_PENDING_KEY, isSelfProtection = true)
                    }
                    return
                }
                if (SecurityVault.selfProtectionAvailable && vault.isSetupCompleted() && vault.isProtectionActive() &&
                    vault.isAccessibilitySelfProtectionEnabled() &&
                    !LockStateTracker.isRecentlyAbandoned(SELF_PROTECTION_PENDING_KEY)
                ) {
                    val guarded = selfProtection?.isGuardedScreen(eventPkg, event) { delayMs ->
                        mainHandler.removeCallbacks(trailingSelfProtectionRunnable)
                        mainHandler.postDelayed(trailingSelfProtectionRunnable, delayMs)
                    }
                    if (guarded == true) {
                        if (onGuardedScreen()) return
                    } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                        if (LockStateTracker.hasVisitedGuardedSelfProtectionScreen()) {
                            mainHandler.removeCallbacks(revokeSelfProtectionAfterExitRunnable)
                            mainHandler.postDelayed(revokeSelfProtectionAfterExitRunnable, 1_500L)
                        }
                        mainHandler.removeCallbacks(trailingSelfProtectionRunnable)
                        mainHandler.postDelayed(trailingSelfProtectionRunnable, 180L)
                    }
                }
            }

            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

            val target = resolveForegroundPackage(event) ?: return
            // A chat head or floating widget of an app that isn't locked says nothing about which
            // app is in front. A locked app's own floating window is still covered (fail closed).
            if (!vault.isAppLocked(target) && isFloatingWindow(event.windowId)) {
                scheduleReconcile()
                return
            }
            handleForeground(target, eventCls, exitTransition, display)
            scheduleReconcile()
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error processing accessibility event: ${describe(t)}")
            // Whatever this event should have covered is looked for in the window list instead.
            scheduleReconcile()
        }
    }

    /**
     * The display [event]'s window is on, where Android says (13 and later). Null when it can't
     * tell: that counts as the phone's own screen, which the shield covers, and an app on another
     * display is patched at the next visibility pass instead.
     */
    private fun displayOf(event: AccessibilityEvent): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) event.displayId.takeIf { it != Display.INVALID_DISPLAY } else null

    /** [displayId] (null: not known) is the phone's own screen, the one the shield covers. */
    private fun isPhoneScreen(displayId: Int?) = displayId == null || displayId == Display.DEFAULT_DISPLAY

    /** Whether window [id] is known not to be an app's screen (a chat head, a floating widget); its type is looked up once. */
    private fun isFloatingWindow(id: Int): Boolean {
        if (id < 0) return false
        val type = windowTypes[id] ?: windowList()?.firstOrNull { it.id == id }?.type?.also { windowTypes[id] = it } ?: return false
        return type != AccessibilityWindowInfo.TYPE_APPLICATION
    }

    // -------------------------------------------------------------------------------------------
    // Launch prediction
    // -------------------------------------------------------------------------------------------

    /** Locked apps called [label] as a launcher icon, Recents card or search result shows it ("Work Messages" too). */
    private fun lockedPackagesNamed(label: CharSequence): List<String> {
        val name = AppLabels.key(label) ?: return emptyList()
        val locked = vault.getLockedPackages()
        val work = name.removePrefix("work ").trim()
        val named = labels.lockedNamed(name, locked)
        return if (work == name || work.isEmpty()) named else (named + labels.lockedNamed(work, locked)).distinct()
    }

    /**
     * A tap on a launcher icon, a Recents card or a search result is the earliest sign an app is
     * about to open: earlier than any window it creates. If what was tapped is a locked app, the
     * cover goes up right now, so not one frame of the app is drawn uncovered. The cover then
     * waits for the app's window and the normal lock flow takes over.
     */
    private fun predictLaunch(event: AccessibilityEvent) {
        // A tap on anything else (a folder named like the app, another app) ends the wait for one.
        expectedLaunch = null
        if (!vault.isProtectionActive() || shield.isShowing || LockScreenActivity.isLockScreenResumed) return
        val spoken = buildList {
            event.contentDescription?.let(::add)
            addAll(event.text)
        }
        for (raw in spoken) {
            // A lock screen left a moment ago doesn't count: tapping the app again is on purpose.
            val target = lockedPackagesNamed(raw).firstOrNull { needsCover(it, ignoreAbandoned = true) } ?: continue
            // Its name above its Recents card opens the launcher's menu, not the app.
            if (isRecentsCardMenu(event)) return
            LockStateTracker.clearAbandoned(target)
            expectedLaunch = target to SystemClock.elapsedRealtime()
            DiagnosticLog.log("Tap on $target: covering it before it opens")
            LatencyMarks.trigger("accessibility", target, event.eventTime)
            shield.showPredicted(
                targetPackage = target,
                waitMs = PREDICTION_WAIT_MS,
                // A tap before the app appears means nothing is opening (e.g. a folder that
                // shares the app's name): stand down. If the app is there, lock it.
                onTapped = { onPredictionExpired(target, tapped = true) },
                onExpired = { onPredictionExpired(it) }
            )
            return
        }
    }

    /**
     * The app's name above its Recents card (in Android's own launcher, and those built on it)
     * opens the launcher's menu for it: split screen, app info, pin. A cover would hide that menu,
     * and the app isn't opening. Told apart from the card by the card's picture (its "snapshot"
     * view, found by its ID, which needs no flag): beside the tapped view, inside the same card,
     * rather than inside it. Read only for a tap that names a locked app: three small IPCs.
     */
    private fun isRecentsCardMenu(event: AccessibilityEvent): Boolean {
        val launcher = event.packageName?.toString() ?: return false
        val snapshot = "$launcher:id/snapshot"
        fun AccessibilityNodeInfo.holdsSnapshot(): Boolean =
            findAccessibilityNodeInfosByViewId(snapshot).orEmpty().also { found -> found.forEach { it.release() } }.isNotEmpty()
        val source = runCatching { event.source }.getOrNull() ?: return false
        var card: AccessibilityNodeInfo? = null
        return try {
            // The card itself, or its picture: that opens the app.
            if (source.holdsSnapshot()) return false
            card = source.parent ?: return false
            card.holdsSnapshot()
        } catch (_: RuntimeException) {
            false
        } finally {
            card?.release()
            source.release()
        }
    }

    /**
     * The predicted app didn't show up in time, or the cover was [tapped] first. Cover the app if
     * it's there now (an unreadable window in front is taken to be it), else stand down.
     */
    private fun onPredictionExpired(target: String, tapped: Boolean = false) {
        try {
            val list = windowList()
            val front = list?.let(::frontWindow)
            val frontPkg = front?.let(::packageOf)
            val arrived = mayBe(target, frontPkg) || (front != null && frontPkg == null && attributeWindow(front, target))
            if (arrived && needsCover(target)) {
                expectedLaunch = null
                lock(target, "opened after a tap", front?.displayId)
                return
            }
            shield.hide()
            // Tapped while the launcher is still in front: the app may yet open, so its window is
            // still expected (for the rest of the wait).
            if (!tapped || frontPkg == null || !isLauncher(frontPkg)) expectedLaunch = null
        } catch (t: Throwable) {
            Log.e(TAG, "Tap-predicted cover check failed: ${describe(t)}")
            if (vault.isAppLocked(target)) lock(target, "opened after a tap") else shield.hide()
        }
    }

    // -------------------------------------------------------------------------------------------
    // Window changes
    // -------------------------------------------------------------------------------------------

    private fun onWindowsChanged(event: AccessibilityEvent) {
        val changes = event.windowChanges
        val surfaced = AccessibilityEvent.WINDOWS_CHANGE_ADDED or
            AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
            AccessibilityEvent.WINDOWS_CHANGE_FOCUSED
        // The lock screen coming up (Lockdown, a lock with the screen on) adds and focuses a
        // window; moves and resizes can't lock the phone. [reconcile] asks every time too.
        if (changes and (surfaced or AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0) {
            LockStateTracker.onDeviceLockState(isDeviceLocked())
        }
        // A window we already know came (back) to the front, e.g. a locked app resumed from
        // Recents: cover it now instead of waiting for its state event.
        val known = windowPackages[event.windowId] ?: attributedWindows[event.windowId]
        val surfacedNeedsLook = changes and surfaced != 0 && when {
            known == null -> awaitingUnreadableWindow()
            isLauncher(known) -> true
            else -> needsCover(known)
        }
        // Patches follow their windows, and a window going into picture-in-picture gets one at once.
        val masksNeedLook = !maskControl.isEmpty || changes and AccessibilityEvent.WINDOWS_CHANGE_PIP != 0
        if (!surfacedNeedsLook && !masksNeedLook) {
            scheduleReconcile()
            return
        }
        val list = windowList()
        if (list != null) {
            if (surfacedNeedsLook) list.firstOrNull { it.id == event.windowId }?.let(::onWindowSurfaced)
            maskControl.update(list, full = false)
        }
        scheduleReconcile()
    }

    /** Window [w] came to the front. Only if it really is in front: a window can be "added" or "focused" for a moment on its way out. */
    private fun onWindowSurfaced(w: AccessibilityWindowInfo) {
        if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION || !(w.isActive || w.isFocused)) return
        val pkg = windowPackages[w.id] ?: attributedWindows[w.id]
        if (pkg == null) {
            attributeUnreadable(w)
            return
        }
        if (isLauncher(pkg)) {
            // The launcher doesn't always send a state event when it comes back: taps on it are watched from here too.
            if (!launcherInFront) watchLauncher(true)
            if (contentForRecents) recents.scanSoon(pkg)
            return
        }
        if (!isSystemOrOverlay(pkg, "") && isCoverableByVisibility(pkg) && needsCover(pkg) && !isExitTransition(pkg)) {
            lock(pkg, "came to the front", w.displayId)
        }
    }

    private fun awaitingUnreadableWindow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return expectedLaunch?.let { now - it.second < PREDICTION_WAIT_MS } == true ||
            recentUnlock?.let { now - it.second < UNLOCK_ATTRIBUTION_MS } == true
    }

    /** An unreadable window [w] came to the front just after a tap on a locked app, or after an unlock: it is that app's. */
    private fun attributeUnreadable(w: AccessibilityWindowInfo) {
        val now = SystemClock.elapsedRealtime()
        expectedLaunch?.let { (target, at) ->
            if (now - at >= PREDICTION_WAIT_MS) {
                expectedLaunch = null
            } else if (attributeWindow(w, target)) {
                expectedLaunch = null
                if (needsCover(target)) lock(target, "opened after a tap; its window can't be read", w.displayId)
                return
            }
        }
        recentUnlock?.let { (pkg, at) ->
            if (now - at >= UNLOCK_ATTRIBUTION_MS) {
                recentUnlock = null
            } else if (attributeWindow(w, pkg)) {
                recentUnlock = null
            }
        }
    }

    /**
     * Apps that hide their windows from accessibility services (accessibility-data-sensitive, as
     * many banking apps' are) send no window events and can't be read, so their window can only
     * be told to be [pkg]'s from what just happened: a tap on [pkg], or its unlock. Takes [window]
     * as [pkg]'s if it is an app window in front that BioKey has never identified, can't read,
     * and isn't BioKey's own. Returns whether it did.
     */
    private fun attributeWindow(window: AccessibilityWindowInfo, pkg: String): Boolean {
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !(window.isActive || window.isFocused)) return false
        if (window.id in windowPackages || window.id in attributedWindows) return false
        // BioKey's own windows can't be read either: the lock screen, its prompt, the main screen.
        if (LockScreenActivity.isLockScreenResumed || SecurityEngine.INSTANCE.isAuthenticating() ||
            LockStateTracker.isRegularLockPending(pkg) || isOwnWindow(window)
        ) return false
        if (windowPackage(window) != null) return false
        attributedWindows[window.id] = pkg
        foregroundPackage = pkg
        DiagnosticLog.log("A window that can't be read opened: taken to be $pkg")
        return true
    }

    private fun isOwnWindow(window: AccessibilityWindowInfo): Boolean =
        ownTitle != null && window.title?.toString() == ownTitle

    /**
     * BioKey's own screen is in front: the last window event was its main screen's, with no other
     * app's since, and the window in front, [front], is BioKey's or can't be read (BioKey's own
     * can't), or isn't known. A locked app coming to the front by itself never passes: its window
     * event makes it the last one in front ([lock] too).
     */
    private fun isOwnScreenInFront(front: String?): Boolean =
        foregroundPackage == packageName && (front == null || front == packageName || front == SessionRules.UNIDENTIFIED_APP)

    private fun frontWindow(list: List<AccessibilityWindowInfo>) = WindowReader.frontWindow(list)

    /** Whether the window the user is using ([frontWindow]) is [pkg]'s. */
    private fun isInFront(pkg: String): Boolean = mayBe(pkg, windowList()?.let(::frontWindow)?.let(::packageOf))

    /** Whether a window read as [pkg] can be [target]'s: Google Password Manager's read as Play services or Credential Manager. */
    private fun mayBe(target: String, pkg: String?): Boolean =
        pkg != null && (pkg == target || (target in PasswordManager.ALIASES && (pkg in PasswordManager.ALIASES || pkg == PasswordManager.GMS)))

    /**
     * Going Home or dismissing an app's card in Recents animates the app's window away, and
     * Android can hand that window focus (and send its events) once more on the way out. Right
     * after an app left, its window turning up while the launcher is still on screen is that exit,
     * not the app coming back. [reconcile] covers it if it stays ([SessionRules.EXIT_SETTLE_MS]); a real return
     * is covered at once anyway, by the tap on its icon or card ([predictLaunch]), or because
     * the launcher isn't on screen (switching straight back to it).
     */
    private fun isExitTransition(pkg: String): Boolean {
        if (!LockStateTracker.justLeft(pkg)) return false
        val list = windowList() ?: return false
        val launcherOnScreen = list.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION && windowPackages[w.id]?.let(::isLauncher) == true
        }
        if (launcherOnScreen) {
            Log.i(TAG, "$pkg surfaced while leaving; waiting for it to settle")
            DiagnosticLog.log("$pkg flickered back while leaving; waiting for it to settle")
            scheduleReconcile(SessionRules.EXIT_SETTLE_MS)
        }
        return launcherOnScreen
    }

    /** Runs a visibility pass after [delayMs], or sooner if one is already due sooner. */
    private fun scheduleReconcile(delayMs: Long = RECONCILE_DELAY_MS) {
        val due = SystemClock.uptimeMillis() + delayMs
        if (reconcileScheduled && reconcileDueAt <= due) return
        mainHandler.removeCallbacks(reconcileRunnable)
        reconcileScheduled = true
        reconcileDueAt = due
        mainHandler.postAtTime(reconcileRunnable, due)
    }

    /**
     * Credential Manager and Play services host sign-in sheets for every app, so their windows are
     * only ever covered once a window event of their own identified them as the password manager
     * ([windowPackages] then holds the virtual package for them).
     */
    private fun isCoverableByVisibility(pkg: String) =
        pkg != packageName && pkg != PasswordManager.CREDENTIAL_MANAGER && pkg != PasswordManager.GMS

    /** Every look at the screen: see [WindowReader]. */
    private val reader by lazy {
        WindowReader(
            readWindows = { windowsOnAllDisplays },
            readActiveRoot = { rootInActiveWindow },
            setUp = { ::vault.isInitialized && vault.isSetupCompleted() },
            known = { id -> windowPackages[id] ?: attributedWindows[id] },
            learn = { id, pkg -> windowPackages[id] = pkg },
            onRead = { if (EngineTrace.enabled) EngineTrace.windows(it, ::traceRootPackage) }
        )
    }

    /** The active window's root node; null before setup ([WindowReader]). */
    internal fun activeRoot(): AccessibilityNodeInfo? = reader.activeRoot()

    /** Every window on every display; null before setup ([WindowReader]). */
    internal fun windowList(): List<AccessibilityWindowInfo>? = reader.windowList()

    /** Whose window [w] is ([WindowReader.packageOf]). */
    private fun packageOf(w: AccessibilityWindowInfo): String? = reader.packageOf(w)

    /**
     * Brings sessions in line with what is actually on screen. See the class comment. A pass that
     * fails is tried again shortly (never crashing the service, which would stop all covering).
     */
    private fun reconcile() {
        reconcileScheduled = false
        reconcileDueAt = 0L
        if (!isRunning || !::vault.isInitialized) return
        // Before setup (and the explanation it shows first), nothing on screen is looked at.
        if (!vault.isSetupCompleted()) return
        try {
            reconcileNow()
            reconcileFailures = 0
        } catch (t: Throwable) {
            reconcileFailures++
            Log.e(TAG, "Visibility pass failed ($reconcileFailures): ${describe(t)}")
            if (reconcileFailures == 1) DiagnosticLog.log("Visibility check failed; trying again")
            // Backing off, then waiting for the next window event: a lasting fault mustn't spin.
            if (reconcileFailures <= RECONCILE_RETRIES) scheduleReconcile(RECONCILE_DELAY_MS shl (reconcileFailures - 1))
        }
    }

    private fun reconcileNow() {
        // Locking the phone with the screen on (Lockdown) ends every session, even if the
        // keyguard's own window change went by unseen.
        LockStateTracker.onDeviceLockState(isDeviceLocked())
        if (!isInteractive()) return
        val windowList = windowList() ?: return

        val visible = LinkedHashSet<String>()
        val pipWindows = HashMap<String, Int>()
        val windowed = HashSet<String>()
        val displayOf = HashMap<String, Int>()
        val liveIds = HashSet<Int>()
        var active: Pair<String, CharSequence?>? = null
        var focused: Pair<String, CharSequence?>? = null
        for (w in windowList) {
            liveIds += w.id
            windowTypes[w.id] = w.type
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            // Windows that hide from non-tool accessibility services (accessibility-data-sensitive,
            // e.g. BioKey's own) can't be identified unless attributed: count them as some other app.
            val pkg = packageOf(w) ?: SessionRules.UNIDENTIFIED_APP
            visible += pkg
            if (w.isInPictureInPictureMode) {
                pipWindows.putIfAbsent(pkg, w.id)
            } else {
                windowed += pkg
                displayOf.putIfAbsent(pkg, w.displayId)
            }
            if (active == null && w.isActive) active = pkg to w.title
            if (focused == null && w.isFocused) focused = pkg to w.title
        }
        // Nothing app-like on screen (the keyguard, a transition) is not an answer: what is known stays known.
        if (visible.isEmpty()) return
        windowPackages.keys.retainAll(liveIds)
        windowTypes.keys.retainAll(liveIds)
        reader.forgetAllBut(liveIds)

        maskControl.update(windowList, full = true)

        if (foregroundPackage == PasswordManager.VIRTUAL_PACKAGE && (PasswordManager.GMS in visible || PasswordManager.CREDENTIAL_MANAGER in visible)) {
            visible += PasswordManager.VIRTUAL_PACKAGE
        }
        val inFront = active ?: focused
        val frontApp = (inFront?.first ?: visible.first()).let {
            if ((it == PasswordManager.GMS || it == PasswordManager.CREDENTIAL_MANAGER) && foregroundPackage == PasswordManager.VIRTUAL_PACKAGE) PasswordManager.VIRTUAL_PACKAGE else it
        }
        // The launcher's own events don't always say it has come or gone (taps there are only
        // delivered while it is in front).
        val launcherNowInFront = isLauncher(frontApp)
        if (launcherInFront != launcherNowInFront) watchLauncher(launcherNowInFront)
        val coverAllowed = vault.isProtectionActive() && !shield.isShowing && !LockScreenActivity.isLockScreenResumed &&
            !SecurityEngine.INSTANCE.isAuthenticating()
        val outcome = rules.reconcile(
            SessionRules.Screen(
                visible = visible,
                pip = pipWindows.keys - windowed,
                front = frontApp,
                lastForeground = foregroundPackage,
                now = SystemClock.elapsedRealtime(),
                coverAllowed = coverAllowed,
                frontTitle = inFront?.second?.toString()?.takeIf { frontApp == SessionRules.UNIDENTIFIED_APP }
            )
        )
        outcome.recheckInMs?.let(::scheduleReconcile)
        val cover = outcome.cover
        if (cover != null) {
            if (outcome.coverInPip) {
                Log.i(TAG, "Locked app $cover in picture-in-picture couldn't be masked; bringing it out")
                maskControl.coverFromPip(cover, pipWindows[cover])
            } else {
                // Beside BioKey's own screen, unlocked and in front (split screen, as protection
                // resumes from it, say): its lock screen closes back onto BioKey, a trip BioKey
                // starts itself rather than the owner leaving it.
                if (cover != frontApp && isPhoneScreen(displayOf[cover]) && isOwnScreenInFront(frontApp)) LockStateTracker.beginOwnLockScreenHop(cover)
                lock(cover, "on screen without a session", displayOf[cover])
            }
            return
        }
        // BioKey's own settings pages in split screen or a window event that was missed.
        if (coverAllowed && visible.size > 1) {
            for (pkg in visible) {
                if (SelfProtectionDetector.isHostPackage(pkg) && shouldGuardSelf() &&
                    !LockStateTracker.isRecentlyAbandoned(SELF_PROTECTION_PENDING_KEY) &&
                    (selfProtection?.isGuardedScreen(pkg, null) == true)
                ) {
                    triggerSelfProtection()
                    return
                }
            }
        }
    }

    private fun windowPackage(window: AccessibilityWindowInfo) = WindowReader.windowPackage(window)

    /** See [ForegroundRules.isHopTarget]; keyboards and unreadable windows also leave a hop running. */
    private fun isHopTarget(pkg: String): Boolean =
        pkg == SessionRules.UNIDENTIFIED_APP || pkg in ForegroundRules.inputMethods(this) ||
            ForegroundRules.isHopTarget(this, pkg, lastClassByPackage[pkg])

    // -------------------------------------------------------------------------------------------
    // Abandoned locks
    // -------------------------------------------------------------------------------------------

    /**
     * An abandoned lock's cooldown has ended. If its app came back meanwhile (a fast tap on its
     * icon, a quick switch, a trampoline), nothing else will look at it again: cover it now.
     */
    private fun onAbandonCooldownOver(pkg: String) {
        guarded("Abandoned lock check") {
            if (!isRunning || !::vault.isInitialized || !isInteractive()) return
            // Abandoned again since: that one's own check follows.
            if (LockStateTracker.isRecentlyAbandoned(pkg)) return
            if (pkg == SELF_PROTECTION_PENDING_KEY) {
                mainHandler.removeCallbacks(trailingSelfProtectionRunnable)
                checkSelfProtectionAgain()
                return
            }
            if (!shield.isShowing && !LockScreenActivity.isLockScreenResumed && needsCover(pkg) &&
                !LockStateTracker.isRegularLockPending(pkg)
            ) {
                val front = windowList()?.let(::frontWindow)
                if (front != null && packageOf(front) == pkg && !isExitTransition(pkg)) {
                    lock(pkg, "re-opened while its lock screen was closing", front.displayId)
                    return
                }
            }
            // Split screen, picture-in-picture, another window: the visibility pass covers those.
            scheduleReconcile(0L)
        }
    }

    /** Locked apps whose windows are patched: never covered by a full-screen cover too. */
    private fun maskedApps(): Set<String> = if (::maskControl.isInitialized) maskControl.maskedApps else emptySet()

    /** What [MaskController] asks of the service. */
    private val maskHost = object : MaskController.Host {
        override fun windowList() = this@BioKeyAccessibilityService.windowList()
        override fun knownPackage(windowId: Int) = windowPackages[windowId] ?: attributedWindows[windowId]
        override fun packageOf(window: AccessibilityWindowInfo) = this@BioKeyAccessibilityService.packageOf(window)
        override fun isCoverable(pkg: String) = isCoverableByVisibility(pkg)
        override fun isOwnWindow(window: AccessibilityWindowInfo) = this@BioKeyAccessibilityService.isOwnWindow(window)
        override fun isInteractive() = this@BioKeyAccessibilityService.isInteractive()
        override fun isKeyguardLocked() = this@BioKeyAccessibilityService.isKeyguardLocked()
        override fun needsCover(pkg: String) = this@BioKeyAccessibilityService.needsCover(pkg, ignoreAbandoned = true)
        override fun isOwnScreenInFront(front: String?) = this@BioKeyAccessibilityService.isOwnScreenInFront(front)
        override fun mayBe(target: String, pkg: String?) = this@BioKeyAccessibilityService.mayBe(target, pkg)
        override fun lock(pkg: String, why: String, displayId: Int?) = this@BioKeyAccessibilityService.lock(pkg, why, displayId)
        override fun bringLockScreenForward(pkg: String, displayId: Int) =
            launchLockScreen(pkg, isSelfProtection = false, displayId = displayId, reprompt = true)
        override fun guarded(what: String, block: () -> Unit) = this@BioKeyAccessibilityService.guarded(what, block)
        override fun describe(t: Throwable) = this@BioKeyAccessibilityService.describe(t)
    }

    // -------------------------------------------------------------------------------------------
    // Foreground changes
    // -------------------------------------------------------------------------------------------

    /**
     * [target] came to the front; [exitTransition], if already known, is [isExitTransition] for it.
     * [displayId] is the display it is on, when known (see [displayOf]).
     */
    private fun handleForeground(target: String, cls: String, exitTransition: Boolean? = null, displayId: Int? = null) {
        // BioKey's own windows (lock screen, main UI) have their own authentication.
        if (target == packageName) {
            if (cls == MainActivity::class.java.name) {
                LockStateTracker.clearSelfProtection()
                foregroundPackage = target
            }
            return
        }
        // Keyboards, permission dialogs, the shade etc. float over the current app: no app switch.
        if (isSystemOrOverlay(target, cls)) return
        // BioKey's backup file picker is the one trip its own session survives.
        if (LockStateTracker.isOwnPickerActive() && target !in ForegroundRules.DOCUMENTS_UI) LockStateTracker.lockOwnApp()
        // Another app's lock screen BioKey brought up over its own screen is a trip it started
        // itself ([reconcileNow], [onPipExpandOver]): any other app coming to the front ends it.
        // The app it is for, coming back beside BioKey once unlocked, is that lock screen closing,
        // whose own end ends the trip unless BioKey is back (LockStateTracker.endOwnLockScreenHop):
        // its window can say so before BioKey's screen has resumed.
        if (!LockStateTracker.isOwnLockScreenHopFor(target) && LockStateTracker.isOwnLockScreenHopActive()) LockStateTracker.lockOwnApp()

        val isLauncher = isLauncher(target)
        // Recents lives in the launcher: watch it (and taps there) while it's in front. Turning
        // that off takes two IPCs, so it waits until the cover is decided.
        if (isLauncher) {
            watchLauncher(true)
            if (contentForRecents) recents.scanSoon(target)
        } else {
            // The app identified itself (or another one did): no unreadable window to wait for.
            expectedLaunch = null
        }
        onAppInFront(target, cls, isLauncher, exitTransition, displayId)
        if (!isLauncher && launcherInFront) watchLauncher(false)
    }

    private fun onAppInFront(target: String, cls: String, isLauncher: Boolean, exitTransition: Boolean?, displayId: Int?) {
        val isRecents = isLauncher && isRecentsOverviewClass(cls)
        val previous = foregroundPackage

        // Stale launcher events can arrive while an app (or a pending cover) is really in front.
        if (isLauncher && !isRecents) {
            val shieldPkg = shield.targetPackage
            if (shield.isShowing && shieldPkg != null && LockStateTracker.isRegularLockPending(shieldPkg)) return
        }

        // Swiping to Overview from a lock that was never passed: don't leave the app a tap away.
        if (isRecents && previous != null && previous != packageName &&
            vault.isAppLocked(previous) && !LockStateTracker.isAppUnlocked(previous, vault.getTimeoutMode(previous))
        ) {
            Log.i(TAG, "Overview opened from locked $previous; going Home")
            foregroundPackage = target
            pressHome()
            shield.hide()
            return
        }

        if (!SelfProtectionDetector.isHostPackage(target)) {
            mainHandler.removeCallbacks(revokeSelfProtectionAfterExitRunnable)
            LockStateTracker.clearSelfProtection()
            if (LockStateTracker.isExternalNavigationActive()) {
                LockStateTracker.lockOwnApp()
            }
        }

        foregroundPackage = target

        // The first app in front after an unlock: if BioKey's lock screen closed onto a helper the
        // unlocked app had open over itself, its session has arrived and hops there (compatibility
        // mode does the same). Anything else, and it must still reach the screen in time.
        rules.noteInFront(target, SystemClock.elapsedRealtime())

        if (isLauncher || !vault.isAppLocked(target)) {
            // A tap-predicted cover outlives launcher events (the app is still on its way) but
            // not a different app taking the screen.
            val keep = LockStateTracker.isRegularLockPending(shield.targetPackage.orEmpty()) ||
                (shield.isPredicted && isLauncher)
            if (!keep) shield.hide()
            return
        }

        if (LockStateTracker.isAppUnlocked(target, vault.getTimeoutMode(target))) {
            LockStateTracker.onAppVisible(target)
            rules.noteOnScreen(target)
            shield.hide()
            return
        }

        if (LockStateTracker.isRecentlyAbandoned(target)) {
            // Its lock screen went a moment ago and it is back in front already (a quick switch,
            // which sends no tap to clear the cooldown, or an app that put itself over its own
            // lock screen). Only covered again at once where its lock screen was displaced rather
            // than left (an app opening its main screen from its launch one, as it starts): it
            // mustn't stay usable either, so otherwise Home, as compatibility mode does. On the
            // way out of a real exit a second Home changes nothing; other displays are left to the
            // checks when the cooldown ends.
            if (isPhoneScreen(displayId) && isInFront(target) && !isExitTransition(target)) {
                if (LockStateTracker.coverAgainAfterDisplaced(target)) {
                    lock(target, "came back over its lock screen", displayId)
                    return
                }
                pressHome()
            }
            shield.hide()
            return
        }

        if (SecurityEngine.INSTANCE.isAuthenticatingFor(target) || LockStateTracker.isRegularLockPending(target)) {
            if (!LockScreenActivity.isLockScreenResumed || LockScreenActivity.activeLockedTarget != target) {
                if (isPhoneScreen(displayId)) shield.show(target) { launchLockScreen(target, isSelfProtection = false) }
                launchLockScreen(target, isSelfProtection = false, displayId)
            }
            return
        }
        if (exitTransition ?: isExitTransition(target)) return
        lock(target, "opened", displayId)
    }

    /** A locked app was swiped away in Recents: its session ends now, whatever its re-lock delay. */
    private fun onClosedFromRecents(appName: String) {
        for (pkg in lockedPackagesNamed(appName)) {
            Log.i(TAG, "$pkg closed from Recents; session ended")
            LockStateTracker.lockPackage(pkg, "closed from Recents")
        }
    }

    /** "Clear all" in Recents closes every app: every session ends. */
    private fun onClearAllRecents() {
        Log.i(TAG, "Recents cleared; all sessions ended")
        LockStateTracker.lockAll(reason = "Recents cleared")
    }

    private fun isRecentsOverviewClass(cls: String): Boolean {
        if (cls.isBlank()) return false
        val lower = cls.lowercase()
        // Primary Home activities must NEVER be classified as Overview/Recents:
        if (lower.endsWith("nexuslauncheractivity") ||
            lower.endsWith("launcheractivity") ||
            lower.endsWith("quicksteplauncher") ||
            lower.endsWith(".launcher")
        ) {
            return false
        }
        return lower.contains("overview") ||
            lower.contains("recents") ||
            lower.contains("taskstack") ||
            (lower.contains("quickstep") && !lower.contains("launcher"))
    }

    /**
     * [pkg] was in front as the screen went off and is locked now: its lock screen starts behind
     * the keyguard (no shield: that would show over it). Without this, the keyguard's exit
     * animation shows the app for a moment before the phone-unlocked broadcast covers it.
     */
    private fun coverLeftOnScreen(pkg: String) {
        if (!isRunning || !vault.isProtectionActive() || !needsCover(pkg)) return
        DiagnosticLog.log("Covered $pkg (on screen as the phone locked)")
        LockStateTracker.setRegularLockPending(pkg)
        launchLockScreen(pkg, isSelfProtection = false)
    }

    /** Covers [target] and starts its lock screen, on [displayId] when known (the display its window is on). */
    private fun lock(target: String, why: String, displayId: Int? = null) {
        foregroundPackage = target
        if (LockStateTracker.isRegularLockPending(target) &&
            LockScreenActivity.isLockScreenResumed &&
            LockScreenActivity.activeLockedTarget == target
        ) return
        // Once per cover, not once per event of an app still starting under it.
        if (!isLockScreenOnItsWay(target, displayId)) DiagnosticLog.log("Covered $target ($why)")
        LockStateTracker.setRegularLockPending(target)
        // The shield covers the phone's own screen; on another display the app's patch is its
        // cover, put up by the visibility pass: brought forward, so it doesn't wait for one.
        if (isPhoneScreen(displayId)) {
            shield.show(target) { launchLockScreen(target, isSelfProtection = false) }
        } else {
            scheduleReconcile(0L)
        }
        launchLockScreen(target, isSelfProtection = false, displayId)
    }

    private fun reevaluateForeground() {
        // Also posted with a delay: the service may have stopped meanwhile.
        if (!isRunning || !::vault.isInitialized) return
        // Before setup (and the explanation it shows first), nothing on screen is looked at.
        if (!vault.isSetupCompleted()) return
        try {
            // The window in front, from the window list: its app is known from its events, and only
            // a window never seen is asked (an IPC into the app, which waits for its UI thread),
            // at the very moment a locked app left on screen is to be covered.
            val front = windowList()?.let(::frontWindow)
            val pkg: String?
            val windowId: Int
            if (front != null) {
                pkg = packageOf(front)
                windowId = front.id
            } else {
                val root = activeRoot()
                pkg = root?.packageName?.toString()
                windowId = root?.windowId ?: -1
                root?.release()
            }
            if (pkg == null) {
                // Unreadable (an app hiding its windows, the keyguard on its way out): the window list
                // still says what is on screen.
                scheduleReconcile(0L)
                return
            }
            if (SelfProtectionDetector.isHostPackage(pkg) && !isConfirmingDeviceCredential) {
                activeSelfProtectionHost = pkg
                updateContentMonitoring(needsSelfProtectionMonitoring())
                if (shouldGuardSelf() && (selfProtection?.isGuardedScreen(pkg, null) == true)) {
                    triggerSelfProtection()
                    return
                }
            }
            // A window says which package, not which screen: Credential Manager or Play services
            // is the password manager only if a window event of its own said so. Otherwise it is a
            // sign-in sheet over another app.
            val target = if (pkg == PasswordManager.CREDENTIAL_MANAGER || pkg == PasswordManager.GMS) {
                if (windowPackages[windowId] == PasswordManager.VIRTUAL_PACKAGE || foregroundPackage == PasswordManager.VIRTUAL_PACKAGE) {
                    PasswordManager.VIRTUAL_PACKAGE
                } else pkg
            } else pkg
            // No activity class: neither a window nor a root (whose class is a view's) has one.
            handleForeground(target, cls = "")
            scheduleReconcile()
        } catch (t: Throwable) {
            Log.e(TAG, "Foreground check failed: ${describe(t)}")
            // Whatever it should have covered is looked for in the window list instead.
            scheduleReconcile(0L)
        }
    }

    // -------------------------------------------------------------------------------------------
    // Self-protection
    // -------------------------------------------------------------------------------------------

    private fun shouldGuardSelf(): Boolean =
        // A constant false in the Play build: R8 drops every guard behind it.
        SecurityVault.selfProtectionAvailable &&
            vault.isSetupCompleted() &&
            vault.isProtectionActive() &&
            vault.isAccessibilitySelfProtectionEnabled() &&
            !LockStateTracker.isSelfProtectionGranted() &&
            !SecurityEngine.INSTANCE.isAuthenticatingFor(SELF_PROTECTION_PENDING_KEY)

    /** A guarded page is on screen: fine if already authorised, else challenged. Returns whether a challenge started. */
    private fun onGuardedScreen(): Boolean {
        if (!SecurityVault.selfProtectionAvailable) return false
        mainHandler.removeCallbacks(revokeSelfProtectionAfterExitRunnable)
        if (LockStateTracker.isSelfProtectionGranted()) {
            LockStateTracker.markSelfProtectionVisitedGuardedScreen()
            updateContentMonitoring(false)
            return false
        }
        if (!shouldGuardSelf()) return false
        triggerSelfProtection()
        return true
    }

    private fun triggerSelfProtection() {
        if (LockStateTracker.isRegularLockPending(SELF_PROTECTION_PENDING_KEY) &&
            LockScreenActivity.isLockScreenResumed &&
            LockScreenActivity.activeLockedTarget == SELF_PROTECTION_PENDING_KEY
        ) return
        LockStateTracker.setRegularLockPending(SELF_PROTECTION_PENDING_KEY)
        Log.i(TAG, "Guarding a BioKey system settings screen")
        DiagnosticLog.log("Guarding a page that could switch BioKey off")
        shield.show(SELF_PROTECTION_PENDING_KEY) {
            launchLockScreen(SELF_PROTECTION_PENDING_KEY, isSelfProtection = true)
        }
        launchLockScreen(SELF_PROTECTION_PENDING_KEY, isSelfProtection = true)
    }

    private fun navigateBackFromGuardedSelfProtection() {
        if (!SecurityVault.selfProtectionAvailable) return
        LockStateTracker.markLockAbandoned(SELF_PROTECTION_PENDING_KEY)
        LockStateTracker.clearSelfProtection()
        mainHandler.postDelayed({
            guarded("Leaving a guarded page") {
                performGlobalAction(GLOBAL_ACTION_BACK)
                shield.hide()
                mainHandler.postDelayed({
                    guarded("Leaving a guarded page") {
                        val host = activeSelfProtectionHost
                        if (host != null && shouldGuardSelf() && (selfProtection?.isGuardedScreen(host, null) == true)) {
                            pressHome()
                        }
                    }
                }, 220L)
            }
        }, 60L)
    }

    // -------------------------------------------------------------------------------------------
    // Foreground resolution and classification
    // -------------------------------------------------------------------------------------------

    /**
     * The app an event's window belongs to. Google Password Manager's own screens get its virtual
     * package ([PasswordManager.resolve]); a sign-in or passkey sheet of Credential Manager keeps
     * its own package, which [isSystemOrOverlay] treats as an overlay over the app below.
     */
    private fun resolveForegroundPackage(event: AccessibilityEvent): String? {
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return PasswordManager.resolve(pkg, event.className?.toString(), event.text.joinToString(" ")) ?: pkg
    }

    private fun isLauncher(pkg: String): Boolean = ForegroundRules.isLauncher(this, pkg)

    /** The PIN, pattern or password screen BioKey's prompt hands over to; the classes are the detector's, so both agree. */
    private fun isDeviceCredentialScreen(pkg: String, cls: String): Boolean {
        if (!SecurityEngine.INSTANCE.isAuthenticating()) return false
        if (pkg != "com.android.settings" && pkg != "com.miui.securitycenter" && pkg != "com.android.systemui") return false
        return SelfProtectionDetector.isCredentialConfirmationClass(cls)
    }

    private fun isSystemOrOverlay(pkg: String, cls: String): Boolean {
        if (pkg == PasswordManager.VIRTUAL_PACKAGE) return false
        // A floating system window, unless its app is one the user locked (Digital Wellbeing, with
        // its icon turned on): then its screens are that app's, covered from their first event.
        if (pkg in ForegroundRules.SYSTEM_OVERLAYS) return !vault.isAppLocked(pkg)
        // Anything of Credential Manager's that isn't the password manager is a passkey/sign-in
        // sheet over another app.
        if (pkg == PasswordManager.CREDENTIAL_MANAGER) return true
        if (pkg in ForegroundRules.inputMethods(this)) return true
        if (isDeviceCredentialScreen(pkg, cls)) return true
        return false
    }

    // -------------------------------------------------------------------------------------------
    // Lock screen launching
    // -------------------------------------------------------------------------------------------

    /** Shield timed out: relaunch the lock if the locked app is still on screen, else let it go. */
    private fun onShieldTimeout(target: String, attempt: Int): Boolean {
        try {
            val stillExposed = if (target == SELF_PROTECTION_PENDING_KEY) {
                val foreground = activeRoot()?.let { root -> root.packageName?.toString().also { root.release() } }
                    ?: foregroundPackage
                foreground != null && SelfProtectionDetector.isHostPackage(foreground) && shouldGuardSelf() && (selfProtection?.isGuardedScreen(foreground, null) == true)
            } else {
                !LockStateTracker.isAppUnlocked(target, vault.getTimeoutMode(target)) && isUnderShield(target)
            }
            if (!stillExposed) return false
            if (attempt > 0) Log.w(TAG, "Lock screen missing after shield timeout (attempt $attempt); relaunching")
            LockStateTracker.setRegularLockPending(target)
            launchLockScreen(target, isSelfProtection = target == SELF_PROTECTION_PENDING_KEY, force = true)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Shield check failed: ${describe(t)}")
            // Keep the cover and look again; after its last attempt the shield fails closed (Home).
            return true
        }
    }

    /**
     * Whether [target] is still on the phone's screen under the shield: in front, or any of its
     * windows still there (split screen, freeform).
     */
    private fun isUnderShield(target: String): Boolean {
        val list = windowList()
        val front = list?.let(::frontWindow)?.let(::packageOf)
            ?: activeRoot()?.let { root -> root.packageName?.toString().also { root.release() } }
            ?: foregroundPackage
        if (mayBe(target, front)) return true
        return list?.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.displayId == Display.DEFAULT_DISPLAY &&
                !w.isInPictureInPictureMode && mayBe(target, packageOf(w))
        } == true
    }

    /**
     * Starts [targetPkg]'s lock screen. An app still starting sends several window events before
     * the lock screen it already has on its way resumes, and each would start it again (a pending
     * intent made and sent, then the lock screen's onNewIntent on the main thread drawing the
     * cover): a start for the same app and display within [LAUNCH_REPEAT_MS] is skipped. The
     * shield's checks start it again whenever it hasn't appeared, with [force], as does a start
     * asked for on purpose ([reprompt]).
     */
    private fun launchLockScreen(
        targetPkg: String,
        isSelfProtection: Boolean,
        displayId: Int? = null,
        reprompt: Boolean = false,
        force: Boolean = false
    ) {
        if (!force && !reprompt && isLockScreenOnItsWay(targetPkg, displayId)) return
        if (LockLauncher.launch(this, targetPkg, isSelfProtection, displayId, reprompt)) {
            lastLaunch = Triple(targetPkg, displayId ?: Display.DEFAULT_DISPLAY, SystemClock.uptimeMillis())
        } else {
            lastLaunch = null
            // Could not cover the app: fail closed by leaving it (on another display its patch stays over it).
            if (isPhoneScreen(displayId)) {
                pressHome()
                shield.hide()
            }
        }
    }

    /** The last lock screen started (app, display, when), for [isLockScreenOnItsWay]. */
    private var lastLaunch: Triple<String, Int, Long>? = null

    /**
     * [targetPkg]'s lock screen was started on [displayId] moments ago and no lock screen has
     * resumed since: one that came up and was pushed away (an app opening over it) must start again.
     */
    private fun isLockScreenOnItsWay(targetPkg: String, displayId: Int?): Boolean {
        val (pkg, display, at) = lastLaunch ?: return false
        if (pkg != targetPkg || display != (displayId ?: Display.DEFAULT_DISPLAY)) return false
        if (LockScreenActivity.isLockScreenResumed || LockScreenActivity.resumedAt >= at) return false
        return SystemClock.uptimeMillis() - at < LAUNCH_REPEAT_MS
    }

    // -------------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------------

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::shield.isInitialized) shield.onConfigurationChanged()
        if (::maskControl.isInitialized) maskControl.refreshPalette()
        if (::vault.isInitialized && AppInfoCache.onConfiguration(newConfig)) AppInfoCache.reload(this, vault.getLockedPackages())
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        onStopped()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        onStopped()
        userUnlockedReceiver?.let {
            runCatching { unregisterReceiver(it) }
            userUnlockedReceiver = null
        }
        runCatching { unregisterReceiver(deviceStateReceiver) }
        super.onDestroy()
    }

    private fun onStopped() {
        if (!isRunning) return
        isRunning = false
        activeService = null
        isConfirmingDeviceCredential = false
        mainHandler.removeCallbacks(reconcileRunnable)
        reconcileScheduled = false
        reconcileDueAt = 0L
        mainHandler.removeCallbacks(trailingSelfProtectionRunnable)
        mainHandler.removeCallbacks(revokeSelfProtectionAfterExitRunnable)
        if (::maskControl.isInitialized) maskControl.stop()
        mainHandler.removeCallbacksAndMessages(ABANDON_TOKEN)
        // A Recents look still pending would end a session after the service has gone.
        recents.reset()
        LockStateTracker.removeLockAllListener(onLockAll)
        LockStateTracker.removeLockAbandonedListener(onLockAbandoned)
        LockStateTracker.removeAppUnlockListener(onAppUnlocked)
        LockStateTracker.removeAppLockListener(onAppLocked)
        runCatching { contentResolver.unregisterContentObserver(inputMethodObserver) }
        // Nothing enforces sessions while the service is gone, so none may outlive it.
        LockStateTracker.lockAll(reason = "accessibility engine stopped")
        if (::shield.isInitialized) shield.destroy()
        if (::maskControl.isInitialized) maskControl.clear()
        if (::labels.isInitialized) labels.shutdown()
        if (::vault.isInitialized && vault.isSetupCompleted()) {
            // Hand over to compatibility mode if it's set up (e.g. Advanced Protection was just turned on).
            ProtectionEngine.refresh(this)
            if (!UsageWatchService.isRunning) ProtectionAlerts.serviceStopped(this)
        }
        Log.w(TAG, "Accessibility service stopped")
    }
}
