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
import android.graphics.Rect
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
import androidx.core.util.forEach
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.MainActivity

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

        /** How long a locked app tapped in picture-in-picture has to come out of it before its lock screen goes up anyway. */
        private const val PIP_EXPAND_WAIT_MS = 1_000L

        /** After an unlock, how long an unreadable window that opens is taken to be the unlocked app's. */
        private const val UNLOCK_ATTRIBUTION_MS = 2_000L

        /** How many unreadable windows are remembered as some app's ([attributedWindows]). */
        private const val MAX_ATTRIBUTED_WINDOWS = 16

        /** How often masks hidden for a prompt look whether it has gone. */
        private const val MASK_RECHECK_MS = 400L

        /** Window events, always on. Clicks and content changes only while needed: see [applyEventTypes]. */
        private const val BASE_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED

        /** The system file picker: the one trip BioKey's own session survives ([LockStateTracker.beginOwnPicker]). */
        private val DOCUMENTS_UI = setOf("com.android.documentsui", "com.google.android.documentsui")

        /** Tags the checks that run when an abandoned lock's cooldown ends. */
        private val ABANDON_TOKEN = Any()

        @Volatile
        var isRunning: Boolean = false
            private set

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

        private const val VIRTUAL_PKG_GOOGLE_PASSWORDS = PasswordManager.VIRTUAL_PACKAGE
        private const val PKG_CREDENTIAL_MANAGER = PasswordManager.CREDENTIAL_MANAGER
        private const val PKG_GMS = PasswordManager.GMS

        const val SELF_PROTECTION_PENDING_KEY = "biokey:self-protection"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var vault: SecurityVault
    private lateinit var shield: InstantShieldOverlay
    private lateinit var masks: WindowMask
    private lateinit var labels: AppLabels
    private lateinit var selfProtection: SelfProtectionDetector
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
    /** Titles seen in locked apps' own window events: an unreadable screen with one of them is the app's own. */
    private val screenTitles = HashMap<String, MutableSet<String>>()

    /** A launch predicted from a tap (package, when), until its window shows up: see [attributeUnreadable]. */
    private var expectedLaunch: Pair<String, Long>? = null
    /** The app unlocked last (package, when), for an unreadable window it opens: see [attributeUnreadable]. */
    private var recentUnlock: Pair<String, Long>? = null
    /** Apps whose every window on screen has a [WindowMask] patch: covered already. */
    private var maskedApps: Set<String> = emptySet()

    /** Which sessions are on screen, which have left, which locked apps to cover: see [SessionRules]. */
    private val rules = SessionRules(object : SessionRules.Env {
        override fun isLocked(pkg: String) = vault.isAppLocked(pkg)
        override fun timeoutOf(pkg: String) = vault.getTimeoutMode(pkg)
        override fun isLauncher(pkg: String) = this@BioKeyAccessibilityService.isLauncher(pkg)
        override fun isHopTarget(pkg: String) = this@BioKeyAccessibilityService.isHopTarget(pkg)
        override fun mayCover(pkg: String) = isCoverableByVisibility(pkg) && !isLauncher(pkg) && pkg !in maskedApps &&
            needsCover(pkg) && !LockStateTracker.isRegularLockPending(pkg)
        override fun appTitled(title: String) = labels.appTitled(title, vault.getLockedPackages())?.takeIf { it != packageName }
        override fun isTitleOf(pkg: String, title: String) =
            labels.isTitleOf(pkg, title, vault.getLockedPackages()) ||
                AppLabels.key(title)?.let { it in screenTitles[pkg].orEmpty() } == true
    })

    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // First, and whatever goes wrong below: the screen going off ends every session.
            if (intent.action == Intent.ACTION_SCREEN_OFF) LockStateTracker.onScreenTurnedOff()
            if (!isRunning) return
            guarded("Screen change") {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        watchLauncher(false)
                        foregroundPackage = null
                        expectedLaunch = null
                        recentUnlock = null
                        rules.reset()
                        isConfirmingDeviceCredential = false
                        armMasks()
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
                        windowList()?.let { updateMasks(it, full = false) }
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        isRunning = true
        val userManager = getSystemService(UserManager::class.java)
        if (userManager?.isUserUnlocked == false) {
            Log.w(TAG, "Device in Direct Boot (before first unlock): deferring initialization")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                        runCatching { this@BioKeyAccessibilityService.unregisterReceiver(this) }
                        userUnlockedReceiver = null
                        initServiceComponents()
                    }
                }
            }
            userUnlockedReceiver = receiver
            ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
            return
        }
        initServiceComponents()
    }

    private fun initServiceComponents() {
        if (!isRunning) return
        vault = SecurityVault.getInstance(this)
        AppInfoCache.preWarm(this, vault.getLockedPackages())
        labels = AppLabels(this).apply { warmUp(vault.getLockedPackages()) }
        ownTitle = runCatching { applicationInfo.loadLabel(packageManager).toString() }.getOrNull()
        shield = InstantShieldOverlay(
            this,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            goHome = { performGlobalAction(GLOBAL_ACTION_HOME) },
            onTimeout = ::onShieldTimeout
        )
        masks = WindowMask(this, ::onMaskTapped)
        selfProtection = SelfProtectionDetector(this)
        // Read once now rather than on the first event.
        ForegroundRules.inputMethods(this)
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
                guarded("Masks after lock-all") { windowList()?.let { updateMasks(it, full = false) } }
                if (launcherInFront) watchLauncher(true)
                reevaluateForeground()
            }
        }
    }

    /**
     * A lock screen was left without unlocking. Its app isn't covered again during the cooldown
     * (its window can flicker back while the lock screen leaves), so it is looked at once more
     * when the cooldown ends: an app re-opened meanwhile must not stay open.
     */
    private val onLockAbandoned: (String) -> Unit = { pkg ->
        mainHandler.postDelayed({ onAbandonCooldownOver(pkg) }, ABANDON_TOKEN, LockStateTracker.ABANDONED_LOCK_COOLDOWN_MS + 20L)
    }

    private val onAppUnlocked: (String) -> Unit = { pkg ->
        mainHandler.post {
            if (!isRunning || !::vault.isInitialized) return@post
            // Its windows are the user's again.
            for (alias in PasswordManager.aliasesOf(pkg)) masks.forget(alias)
            if (pkg != SELF_PROTECTION_PENDING_KEY) recentUnlock = pkg to SystemClock.elapsedRealtime()
            if (launcherInFront) watchLauncher(true)
        }
    }

    /** A session ended: with the launcher in front, Recents may have nothing left to watch for. */
    private val onAppLocked: (String) -> Unit = {
        mainHandler.post { if (isRunning && ::vault.isInitialized && launcherInFront) watchLauncher(true) }
    }

    private fun isInteractive() = power?.isInteractive == true

    private fun isDeviceLocked() = keyguard?.isDeviceLocked == true

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
    private val maskRecheckRunnable = Runnable { guarded("Mask check") { windowList()?.let { updateMasks(it, full = false) } } }

    /** Runs a Handler task, where an exception would crash the service: it is logged instead. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "$what failed", t)
        }
    }

    private fun revokeSelfProtectionAfterExit() {
        if (shield.isShowing || LockScreenActivity.isLockScreenResumed || SecurityEngine.INSTANCE.isAuthenticating()) return
        if (LockStateTracker.hasVisitedGuardedSelfProtectionScreen()) {
            val host = activeSelfProtectionHost
            val stillGuarded = host != null && selfProtection.isGuardedScreen(host, null)
            if (!stillGuarded) {
                LockStateTracker.clearSelfProtection()
            }
        }
    }

    private fun checkSelfProtectionAgain() {
        val host = activeSelfProtectionHost ?: return
        if (!::vault.isInitialized) return
        if (shield.isShowing || LockScreenActivity.isLockScreenResumed || SecurityEngine.INSTANCE.isAuthenticating() ||
            LockStateTracker.isRecentlyAbandoned(SELF_PROTECTION_PENDING_KEY)
        ) return
        if (selfProtection.isGuardedScreen(host, null)) {
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

    private val recents by lazy { RecentsWatcher(this, ::onClosedFromRecents, ::onClearAllRecents) }

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
        vault.isSetupCompleted() &&
            vault.isProtectionActive() &&
            vault.isAccessibilitySelfProtectionEnabled() &&
            !LockStateTracker.hasVisitedGuardedSelfProtectionScreen()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::vault.isInitialized) return
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
                recents.onLauncherChanged(eventPkg)
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
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && eventPkg != null) {
                // Fast path: cover a locked app before any other work.
                val target = resolveForegroundPackage(event)
                if (target != null && !isSystemOrOverlay(target, eventCls) && needsCover(target)) {
                    exitTransition = isExitTransition(target)
                    if (exitTransition == false) shield.show(target) { launchLockScreen(target, isSelfProtection = false) }
                }
                if (event.windowId >= 0) windowPackages[event.windowId] = target ?: eventPkg
                if (eventCls.isNotEmpty()) lastClassByPackage[eventPkg] = eventCls
                if (target != null && vault.isAppLocked(target)) rememberScreenTitle(target, event)

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
                val inSelfProtHost = selfProtection.isHost(eventPkg) && !isConfirmingDeviceCredential
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
            if (eventPkg != null && selfProtection.isHost(eventPkg) && !isConfirmingDeviceCredential) {
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
                if (vault.isSetupCompleted() && vault.isProtectionActive() && vault.isAccessibilitySelfProtectionEnabled() &&
                    !LockStateTracker.isRecentlyAbandoned(SELF_PROTECTION_PENDING_KEY)
                ) {
                    val guarded = selfProtection.isGuardedScreen(eventPkg, event) { delayMs ->
                        mainHandler.removeCallbacks(trailingSelfProtectionRunnable)
                        mainHandler.postDelayed(trailingSelfProtectionRunnable, delayMs)
                    }
                    if (guarded) {
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
            handleForeground(target, eventCls, exitTransition)
            scheduleReconcile()
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error processing accessibility event: ${t.message}", t)
            // Whatever this event should have covered is looked for in the window list instead.
            scheduleReconcile()
        }
    }

    /** A locked app's own window event names one of its screens. */
    private fun rememberScreenTitle(pkg: String, event: AccessibilityEvent) {
        val title = AppLabels.key(event.text.firstOrNull()) ?: return
        val titles = screenTitles.getOrPut(pkg, ::HashSet)
        if (titles.size < 32) titles += title
    }

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
        if (!vault.isGlobalLockEnabled() || shield.isShowing || LockScreenActivity.isLockScreenResumed) return
        val spoken = buildList {
            event.contentDescription?.let(::add)
            addAll(event.text)
        }
        for (raw in spoken) {
            // A lock screen left a moment ago doesn't count: tapping the app again is on purpose.
            val target = lockedPackagesNamed(raw).firstOrNull { needsCover(it, ignoreAbandoned = true) } ?: continue
            LockStateTracker.clearAbandoned(target)
            expectedLaunch = target to SystemClock.elapsedRealtime()
            DiagnosticLog.log("Tap on $target: covering it before it opens")
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
            Log.e(TAG, "Tap-predicted cover check failed", t)
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
        val masksNeedLook = !masks.isEmpty || changes and AccessibilityEvent.WINDOWS_CHANGE_PIP != 0
        if (!surfacedNeedsLook && !masksNeedLook) {
            scheduleReconcile()
            return
        }
        val list = windowList()
        if (list != null) {
            if (surfacedNeedsLook) list.firstOrNull { it.id == event.windowId }?.let(::onWindowSurfaced)
            updateMasks(list, full = false)
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

    /** The window the user is using: the active one, else a focused one. */
    private fun frontWindow(list: List<AccessibilityWindowInfo>): AccessibilityWindowInfo? {
        val apps = list.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused }
    }

    /** Whether a window read as [pkg] can be [target]'s: Google Password Manager's read as Play services or Credential Manager. */
    private fun mayBe(target: String, pkg: String?): Boolean =
        pkg != null && (pkg == target || (target in PasswordManager.ALIASES && (pkg in PasswordManager.ALIASES || pkg == PKG_GMS)))

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
        pkg != packageName && pkg != PKG_CREDENTIAL_MANAGER && pkg != PKG_GMS

    /** Every window on every display, the phone's own screen first (each display's top first); null if they can't be read. */
    private fun windowList(): List<AccessibilityWindowInfo>? = try {
        val byDisplay = windowsOnAllDisplays
        buildList {
            byDisplay[Display.DEFAULT_DISPLAY]?.let { addAll(it) }
            byDisplay.forEach { display, windows -> if (display != Display.DEFAULT_DISPLAY) addAll(windows) }
        }
    } catch (_: Exception) {
        null
    }

    /** Whose window [w] is: learned from its events, attributed, or read from the window itself (an IPC). */
    private fun packageOf(w: AccessibilityWindowInfo): String? =
        windowPackages[w.id] ?: attributedWindows[w.id] ?: windowPackage(w)?.also { windowPackages[w.id] = it }

    /**
     * Brings sessions in line with what is actually on screen. See the class comment. A pass that
     * fails is tried again shortly (never crashing the service, which would stop all covering).
     */
    private fun reconcile() {
        reconcileScheduled = false
        reconcileDueAt = 0L
        if (!isRunning || !::vault.isInitialized) return
        try {
            reconcileNow()
            reconcileFailures = 0
        } catch (t: Throwable) {
            reconcileFailures++
            Log.e(TAG, "Visibility pass failed ($reconcileFailures)", t)
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

        updateMasks(windowList, full = true)

        if (foregroundPackage == VIRTUAL_PKG_GOOGLE_PASSWORDS && (PKG_GMS in visible || PKG_CREDENTIAL_MANAGER in visible)) {
            visible += VIRTUAL_PKG_GOOGLE_PASSWORDS
        }
        val inFront = active ?: focused
        val frontApp = (inFront?.first ?: visible.first()).let {
            if ((it == PKG_GMS || it == PKG_CREDENTIAL_MANAGER) && foregroundPackage == VIRTUAL_PKG_GOOGLE_PASSWORDS) VIRTUAL_PKG_GOOGLE_PASSWORDS else it
        }
        // The launcher's own events don't always say it has come or gone (taps there are only
        // delivered while it is in front).
        val launcherNowInFront = isLauncher(frontApp)
        if (launcherInFront != launcherNowInFront) watchLauncher(launcherNowInFront)
        val coverAllowed = vault.isGlobalLockEnabled() && !shield.isShowing && !LockScreenActivity.isLockScreenResumed &&
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
                coverFromPip(cover, pipWindows[cover])
            } else {
                lock(cover, "on screen without a session", displayOf[cover])
            }
            return
        }
        // BioKey's own settings pages in split screen or a window event that was missed.
        if (coverAllowed && visible.size > 1) {
            for (pkg in visible) {
                if (selfProtection.isHost(pkg) && shouldGuardSelf() &&
                    !LockStateTracker.isRecentlyAbandoned(SELF_PROTECTION_PENDING_KEY) &&
                    selfProtection.isGuardedScreen(pkg, null)
                ) {
                    triggerSelfProtection()
                    return
                }
            }
        }
    }

    /** Fallback when a window appeared without a state event: ask the window itself (an IPC). */
    private fun windowPackage(window: AccessibilityWindowInfo): String? {
        val root = try { window.root } catch (_: Exception) { null } ?: return null
        return root.packageName?.toString().also { release(root) }
    }

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

    // -------------------------------------------------------------------------------------------
    // Masks: picture-in-picture, windows beside the lock screen, other displays
    // -------------------------------------------------------------------------------------------

    /**
     * Puts [WindowMask] patches where a full-screen cover can't stay: over picture-in-picture
     * windows of locked apps without a session, and over their windows beside the lock screen
     * (split screen, freeform) or on another display. The latter only on [full] passes: right
     * after a window change, such a window may still be on its way under the lock screen. A patch
     * stays until its app has a session or stops being locked, or its window has gone. While the
     * phone is locked or a prompt is up, patches are only hidden (they would sit over the
     * keyguard or the prompt), never removed.
     */
    private fun updateMasks(list: List<AccessibilityWindowInfo>, full: Boolean) {
        if (!vault.isGlobalLockEnabled()) {
            masks.clear()
            maskedApps = emptySet()
            return
        }
        val apps = list.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val targets = apps.mapNotNull { maskTarget(it, list, full) }
        if (targets.isEmpty() && masks.isEmpty) {
            maskedApps = emptySet()
            return
        }
        val authenticating = SecurityEngine.INSTANCE.isAuthenticating()
        if (!isInteractive() || keyguard?.isKeyguardLocked == true || authenticating || apps.isEmpty()) {
            masks.setHidden(true)
            masks.add(targets)
            if (authenticating) {
                mainHandler.removeCallbacks(maskRecheckRunnable)
                mainHandler.postDelayed(maskRecheckRunnable, MASK_RECHECK_MS)
            }
            return
        }
        val failed = masks.show(targets)
        masks.setHidden(false)
        val unmasked = apps.filterNot { masks.isMasking(it.id) }.mapNotNullTo(HashSet()) { windowPackages[it.id] ?: attributedWindows[it.id] }
        maskedApps = targets.filter { masks.isMasking(it.windowId) }.mapTo(HashSet()) { it.pkg } - unmasked
        // A patch that couldn't be put up: cover the app the usual way instead (fail closed).
        for (target in failed) {
            if (shield.isShowing || LockStateTracker.isRegularLockPending(target.pkg)) continue
            if (target.inPip) coverFromPip(target.pkg, target.windowId) else lock(target.pkg, "its window couldn't be masked", target.displayId)
        }
    }

    /** The patch window [w] needs, if any: see [updateMasks]. */
    private fun maskTarget(w: AccessibilityWindowInfo, list: List<AccessibilityWindowInfo>, full: Boolean): WindowMask.Target? {
        val pkg = windowPackages[w.id] ?: attributedWindows[w.id] ?: (if (w.isInPictureInPictureMode) packageOf(w) else null) ?: return null
        if (pkg == packageName || !isCoverableByVisibility(pkg) || !vault.isAppLocked(pkg) ||
            LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))
        ) return null
        val bounds = Rect().also(w::getBoundsInScreen)
        val elsewhere = w.displayId != Display.DEFAULT_DISPLAY
        // A full-screen window on the phone's own screen is for the shield and the lock screen to cover.
        val fullScreen = !elsewhere && coversScreen(bounds)
        val wanted = w.isInPictureInPictureMode ||
            (!fullScreen && (masks.isMasking(w.id) || (full && (elsewhere || besideLockScreen(w, bounds, list)))))
        if (!wanted) return null
        val overlapped = list.any { other ->
            other.type == AccessibilityWindowInfo.TYPE_SYSTEM && other.displayId == w.displayId && other.layer > w.layer &&
                (other.isActive || other.isFocused) && Rect.intersects(bounds, Rect().also(other::getBoundsInScreen))
        }
        return WindowMask.Target(w.id, pkg, w.displayId, bounds, w.isInPictureInPictureMode, touchable = !overlapped)
    }

    /**
     * While the lock screen is up, a window still listed is beside it, not under it (Android
     * leaves windows that are wholly covered out of the list), unless BioKey's own window over it
     * contains it.
     */
    private fun besideLockScreen(w: AccessibilityWindowInfo, bounds: Rect, list: List<AccessibilityWindowInfo>): Boolean {
        if (!LockScreenActivity.isLockScreenResumed) return false
        return list.none { own ->
            own.type == AccessibilityWindowInfo.TYPE_APPLICATION && own.displayId == w.displayId && own.layer > w.layer &&
                isOwnWindow(own) && Rect().also(own::getBoundsInScreen).contains(bounds)
        }
    }

    private fun coversScreen(bounds: Rect): Boolean {
        val screen = runCatching { getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds }.getOrNull() ?: return true
        return bounds.width() >= screen.width() && bounds.height() >= screen.height()
    }

    /**
     * The screen is going off, and every session with it. Locked apps in picture-in-picture are on
     * screen again the moment the phone is unlocked, so their patches go up now, hidden.
     */
    private fun armMasks() {
        masks.setHidden(true)
        if (!vault.isGlobalLockEnabled()) return
        val list = windowList() ?: return
        val pips = list.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isInPictureInPictureMode }.mapNotNull { w ->
            val pkg = packageOf(w) ?: return@mapNotNull null
            if (pkg == packageName || !isCoverableByVisibility(pkg) || !vault.isAppLocked(pkg)) return@mapNotNull null
            WindowMask.Target(w.id, pkg, w.displayId, Rect().also(w::getBoundsInScreen), inPip = true)
        }
        masks.add(pips)
    }

    /** A patch was tapped: the user wants that app. */
    private fun onMaskTapped(target: WindowMask.Target) {
        guarded("Mask tap") {
            val pkg = target.pkg
            // Asked for on purpose, like a tap on its icon.
            LockStateTracker.clearAbandoned(pkg)
            when {
                !target.inPip -> lock(pkg, "its window was tapped", target.displayId)
                // Its lock screen is already up, under the picture-in-picture window: bring it back.
                LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg ->
                    launchLockScreen(pkg, isSelfProtection = false, displayId = target.displayId)
                else -> coverFromPip(pkg, target.windowId)
            }
        }
    }

    /**
     * Brings a locked app out of picture-in-picture so it can be locked: the screen is covered
     * first, then the app is expanded (or started, which expands it), and the usual lock flow
     * takes over once it is in front. Its lock screen can't go first: picture-in-picture floats
     * above it, and expanding the app afterwards would push the lock screen away.
     */
    private fun coverFromPip(pkg: String, windowId: Int?) {
        if (shield.isShowing) return
        shield.showPredicted(
            targetPackage = pkg,
            waitMs = PIP_EXPAND_WAIT_MS,
            onTapped = { onPipExpandOver(pkg) },
            onExpired = ::onPipExpandOver
        )
        val window = windowId?.let { id -> windowList()?.firstOrNull { it.id == id } }
        val root = window?.let { runCatching { it.root }.getOrNull() }
        val expanded = root?.let { node -> runCatching { node.performAction(AccessibilityNodeInfo.ACTION_EXPAND) }.getOrDefault(false).also { release(node) } } == true
        if (!expanded) startApp(pkg)
    }

    /** The app tapped in picture-in-picture had its time to come out of it. */
    private fun onPipExpandOver(pkg: String) {
        try {
            if (!needsCover(pkg, ignoreAbandoned = true)) {
                shield.hide()
                return
            }
            val list = windowList()
            val stillInPip = list?.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isInPictureInPictureMode && packageOf(it) == pkg } == true
            val inFront = list?.let(::frontWindow)?.let(::packageOf)
            when {
                // It wouldn't leave: its lock screen goes up under it, its patch stays over it.
                stillInPip -> lock(pkg, "in picture-in-picture without a session")
                mayBe(pkg, inFront) -> lock(pkg, "opened from picture-in-picture")
                else -> shield.hide()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Picture-in-picture check failed", t)
            // Nothing would take this cover down again: lock the app (it fails closed from there), or stand down.
            if (vault.isAppLocked(pkg)) lock(pkg, "in picture-in-picture") else shield.hide()
        }
    }

    private fun startApp(pkg: String) {
        val real = if (pkg in PasswordManager.ALIASES) PKG_CREDENTIAL_MANAGER else pkg
        val launch = packageManager.getLaunchIntentForPackage(real) ?: return
        runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "Couldn't bring $pkg out of picture-in-picture: ${it.message}") }
    }

    // -------------------------------------------------------------------------------------------
    // Foreground changes
    // -------------------------------------------------------------------------------------------

    /** [target] came to the front; [exitTransition], if already known, is [isExitTransition] for it. */
    private fun handleForeground(target: String, cls: String, exitTransition: Boolean? = null) {
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
        if (LockStateTracker.isOwnPickerActive() && target !in DOCUMENTS_UI) LockStateTracker.lockOwnApp()

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
        onAppInFront(target, cls, isLauncher, exitTransition)
        if (!isLauncher && launcherInFront) watchLauncher(false)
    }

    private fun onAppInFront(target: String, cls: String, isLauncher: Boolean, exitTransition: Boolean?) {
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
            performGlobalAction(GLOBAL_ACTION_HOME)
            shield.hide()
            return
        }

        if (!selfProtection.isHost(target)) {
            mainHandler.removeCallbacks(revokeSelfProtectionAfterExitRunnable)
            LockStateTracker.clearSelfProtection()
            if (LockStateTracker.isExternalNavigationActive()) {
                LockStateTracker.lockOwnApp()
            }
        }

        foregroundPackage = target

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
            shield.hide()
            return
        }

        if (SecurityEngine.INSTANCE.isAuthenticatingFor(target) || LockStateTracker.isRegularLockPending(target)) {
            if (!LockScreenActivity.isLockScreenResumed || LockScreenActivity.activeLockedTarget != target) {
                shield.show(target) { launchLockScreen(target, isSelfProtection = false) }
                launchLockScreen(target, isSelfProtection = false)
            }
            return
        }
        if (exitTransition ?: isExitTransition(target)) return
        lock(target, "opened")
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

    /** Covers [target] and starts its lock screen, on [displayId] when known (the display its window is on). */
    private fun lock(target: String, why: String, displayId: Int? = null) {
        foregroundPackage = target
        DiagnosticLog.log("Covered $target ($why)")
        if (LockStateTracker.isRegularLockPending(target) &&
            LockScreenActivity.isLockScreenResumed &&
            LockScreenActivity.activeLockedTarget == target
        ) return
        LockStateTracker.setRegularLockPending(target)
        // The shield covers the phone's own screen; on another display the app's patch is its cover.
        if (displayId == null || displayId == Display.DEFAULT_DISPLAY) {
            shield.show(target) { launchLockScreen(target, isSelfProtection = false) }
        }
        launchLockScreen(target, isSelfProtection = false, displayId)
    }

    private fun reevaluateForeground() {
        // Also posted with a delay: the service may have stopped meanwhile.
        if (!isRunning || !::vault.isInitialized) return
        try {
            val root = rootInActiveWindow
            if (root == null) {
                // Unreadable (an app hiding its windows, the keyguard on its way out): the window list
                // still says what is on screen.
                scheduleReconcile(0L)
                return
            }
            val pkg = root.packageName?.toString()
            val cls = root.className?.toString().orEmpty()
            val windowId = root.windowId
            release(root)
            if (pkg != null) {
                if (selfProtection.isHost(pkg) && !isConfirmingDeviceCredential) {
                    activeSelfProtectionHost = pkg
                    updateContentMonitoring(needsSelfProtectionMonitoring())
                    if (shouldGuardSelf() && selfProtection.isGuardedScreen(pkg, null)) {
                        triggerSelfProtection()
                        return
                    }
                }
                // A root says which package, not which screen ([cls] is a view class here): Credential
                // Manager or Play services is the password manager only if a window event of its own
                // said so. Otherwise it is a sign-in sheet over another app.
                val target = if (pkg == PKG_CREDENTIAL_MANAGER || pkg == PKG_GMS) {
                    if (windowPackages[windowId] == VIRTUAL_PKG_GOOGLE_PASSWORDS || foregroundPackage == VIRTUAL_PKG_GOOGLE_PASSWORDS) {
                        VIRTUAL_PKG_GOOGLE_PASSWORDS
                    } else pkg
                } else pkg
                handleForeground(target, cls)
            }
            scheduleReconcile()
        } catch (t: Throwable) {
            Log.e(TAG, "Foreground check failed", t)
            // Whatever it should have covered is looked for in the window list instead.
            scheduleReconcile(0L)
        }
    }

    // -------------------------------------------------------------------------------------------
    // Self-protection
    // -------------------------------------------------------------------------------------------

    private fun shouldGuardSelf(): Boolean =
        vault.isSetupCompleted() &&
            vault.isProtectionActive() &&
            vault.isAccessibilitySelfProtectionEnabled() &&
            !LockStateTracker.isSelfProtectionGranted() &&
            !SecurityEngine.INSTANCE.isAuthenticatingFor(SELF_PROTECTION_PENDING_KEY)

    /** A guarded page is on screen: fine if already authorised, else challenged. Returns whether a challenge started. */
    private fun onGuardedScreen(): Boolean {
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
        LockStateTracker.markLockAbandoned(SELF_PROTECTION_PENDING_KEY)
        LockStateTracker.clearSelfProtection()
        mainHandler.postDelayed({
            guarded("Leaving a guarded page") {
                performGlobalAction(GLOBAL_ACTION_BACK)
                shield.hide()
                mainHandler.postDelayed({
                    guarded("Leaving a guarded page") {
                        val host = activeSelfProtectionHost
                        if (host != null && shouldGuardSelf() && selfProtection.isGuardedScreen(host, null)) {
                            performGlobalAction(GLOBAL_ACTION_HOME)
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

    private fun isDeviceCredentialScreen(pkg: String, cls: String): Boolean {
        if (!SecurityEngine.INSTANCE.isAuthenticating()) return false
        if (pkg != "com.android.settings" && pkg != "com.miui.securitycenter" && pkg != "com.android.systemui") return false
        val lower = cls.lowercase()
        return lower.contains("confirmdevicecredential") ||
            lower.contains("confirmlockpassword") ||
            lower.contains("confirmlockpattern") ||
            lower.contains("confirmcommonpassword") ||
            lower.contains("biometricprompt")
    }

    private fun isSystemOrOverlay(pkg: String, cls: String): Boolean {
        if (pkg == VIRTUAL_PKG_GOOGLE_PASSWORDS) return false
        if (pkg in ForegroundRules.SYSTEM_OVERLAYS) return true
        // Anything of Credential Manager's that isn't the password manager is a passkey/sign-in
        // sheet over another app.
        if (pkg == PKG_CREDENTIAL_MANAGER) return true
        if (pkg in ForegroundRules.inputMethods(this)) return true
        if (isDeviceCredentialScreen(pkg, cls)) return true
        return false
    }

    private fun release(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Lock screen launching
    // -------------------------------------------------------------------------------------------

    /** Shield timed out: relaunch the lock if the locked app is still on screen, else let it go. */
    private fun onShieldTimeout(target: String, attempt: Int): Boolean {
        try {
            val stillExposed = if (target == SELF_PROTECTION_PENDING_KEY) {
                val foreground = rootInActiveWindow?.let { root -> root.packageName?.toString().also { release(root) } }
                    ?: foregroundPackage
                foreground != null && selfProtection.isHost(foreground) && shouldGuardSelf() && selfProtection.isGuardedScreen(foreground, null)
            } else {
                !LockStateTracker.isAppUnlocked(target, vault.getTimeoutMode(target)) && isUnderShield(target)
            }
            if (!stillExposed) return false
            if (attempt > 0) Log.w(TAG, "Lock screen missing after shield timeout (attempt $attempt); relaunching")
            LockStateTracker.setRegularLockPending(target)
            launchLockScreen(target, isSelfProtection = target == SELF_PROTECTION_PENDING_KEY)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Shield check failed", t)
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
            ?: rootInActiveWindow?.let { root -> root.packageName?.toString().also { release(root) } }
            ?: foregroundPackage
        if (mayBe(target, front)) return true
        return list?.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.displayId == Display.DEFAULT_DISPLAY &&
                !w.isInPictureInPictureMode && mayBe(target, packageOf(w))
        } == true
    }

    private fun launchLockScreen(targetPkg: String, isSelfProtection: Boolean, displayId: Int? = null) {
        if (!LockLauncher.launch(this, targetPkg, isSelfProtection, displayId)) {
            // Could not cover the app: fail closed by leaving it (on another display its patch stays over it).
            if (displayId == null || displayId == Display.DEFAULT_DISPLAY) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                shield.hide()
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------------

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::shield.isInitialized) shield.onConfigurationChanged()
        if (::masks.isInitialized) masks.refreshPalette()
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
        mainHandler.removeCallbacks(maskRecheckRunnable)
        mainHandler.removeCallbacksAndMessages(ABANDON_TOKEN)
        LockStateTracker.removeLockAllListener(onLockAll)
        LockStateTracker.removeLockAbandonedListener(onLockAbandoned)
        LockStateTracker.removeAppUnlockListener(onAppUnlocked)
        LockStateTracker.removeAppLockListener(onAppLocked)
        runCatching { contentResolver.unregisterContentObserver(inputMethodObserver) }
        // Nothing enforces sessions while the service is gone, so none may outlive it.
        LockStateTracker.lockAll(reason = "accessibility engine stopped")
        if (::shield.isInitialized) shield.destroy()
        if (::masks.isInitialized) masks.clear()
        if (::labels.isInitialized) labels.shutdown()
        if (::vault.isInitialized && vault.isSetupCompleted()) {
            // Hand over to compatibility mode if it's set up (e.g. Advanced Protection was just turned on).
            ProtectionEngine.refresh(this)
            if (!UsageWatchService.isRunning) ProtectionAlerts.serviceStopped(this)
        }
        Log.w(TAG, "Accessibility service stopped")
    }
}
