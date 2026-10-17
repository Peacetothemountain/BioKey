package com.android.BioKey.feature.lock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
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
 *   only counts as left once its windows are gone for [LEAVE_CONFIRM_MS].
 *
 * Also guards BioKey itself (self-protection): Settings/installer screens that could disable or
 * uninstall BioKey require authentication while self-protection is on.
 */
class BioKeyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BioKeyA11yService"

        /** Coalesces window churn (animations, transitions) into one visibility pass. */
        private const val RECONCILE_DELAY_MS = 150L

        /** An app must be off screen this long before it counts as left (trampolines, relaunches). */
        private const val LEAVE_CONFIRM_MS = 400L

        /** How long a cover raised by a tap waits for the tapped app's window (cold starts). */
        private const val PREDICTION_WAIT_MS = 3_000L

        /** How long a just-left app's window must stay on screen with the launcher before it counts as back. */
        private const val EXIT_SETTLE_MS = 500L

        /** Stands for an app window whose owner can't be read; never locked, never a helper. */
        private const val UNIDENTIFIED_APP = "biokey:unidentified-app"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile private var activeService: BioKeyAccessibilityService? = null

        @Volatile
        var isConfirmingDeviceCredential: Boolean = false
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

        const val VIRTUAL_PKG_GOOGLE_PASSWORDS = "com.google.android.gms.passwordmanager"
        const val PKG_CREDENTIAL_MANAGER = "com.google.android.apps.credentialmanager"
        private const val PKG_GMS = "com.google.android.gms"

        const val SELF_PROTECTION_PENDING_KEY = "biokey:self-protection"

        private val GPM_IGNORED_CLASS_HINTS = listOf(
            "Autofill", "Credential", "ConfirmUser", "Authenticator", "SignIn", "OAuth", "Fido", "Passkey",
            "AccountPicker", "ChooseAccount", "TwoStep", "SecondFactor", "Consent", "AuthAccount",
            "AuthActivity", "OneTap", "SmartLock", "Authorization", "FederatedIdentity", "TokenActivity",
            "WebAuthn", "BottomSheet", "CreatePassword", "ShareCredentials"
        )

        private val GPM_ACTIVITY_HINTS = listOf(
            "PasswordManagerProxyActivity", "PasswordManagerActivity", "AutofillManagePasswordsActivity", "GooglePasswordManager"
        )

        /**
         * Google Password Manager lives inside Play services / Credential Manager, which also host
         * sign-in sheets that must not be locked. Map only the standalone manager to a virtual package.
         */
        fun isGooglePasswordManagerEvent(event: AccessibilityEvent): Boolean {
            val pkg = event.packageName?.toString() ?: return false
            if (pkg != PKG_CREDENTIAL_MANAGER && pkg != PKG_GMS) return false
            val cls = event.className?.toString().orEmpty()

            if (pkg == PKG_CREDENTIAL_MANAGER) {
                return listOf("Picker", "Chooser", "Selector", "Dialog").none { cls.contains(it, ignoreCase = true) } &&
                    GPM_IGNORED_CLASS_HINTS.none { cls.contains(it, ignoreCase = true) }
            }
            if (GPM_ACTIVITY_HINTS.any { cls.contains(it, ignoreCase = true) }) return true
            if (GPM_IGNORED_CLASS_HINTS.any { cls.contains(it, ignoreCase = true) } || cls.contains("Settings", ignoreCase = true)) {
                return false
            }
            val title = event.text.joinToString(" ").trim()
            return title.equals("Password Manager", ignoreCase = true) ||
                title.equals("Google Password Manager", ignoreCase = true) ||
                title.equals("Saved passwords", ignoreCase = true)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var vault: SecurityVault
    private lateinit var shield: InstantShieldOverlay
    private lateinit var selfProtection: SelfProtectionDetector

    /** The app most recently brought to the front by a window event. */
    private var foregroundPackage: String? = null

    /** Accessibility window id -> owning package, learned from window events. */
    private val windowPackages = HashMap<Int, String>()
    /** Last activity class seen per package (to recognise custom tabs as helpers). */
    private val lastClassByPackage = HashMap<String, String>()
    /** Locked apps last seen on screen while unlocked: the sessions [reconcile] watches. */
    private val onScreenSessions = HashSet<String>()
    /** Apps that have dropped off screen, and since when, awaiting [LEAVE_CONFIRM_MS]. */
    private val pendingLeaves = HashMap<String, Long>()
    /** Just-left apps seen on screen again alongside the launcher, and since when ([EXIT_SETTLE_MS]). */
    private val returningSince = HashMap<String, Long>()

    private var imePackages: Set<String> = emptySet()
    private var systemListsRefreshedAt = 0L

    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    foregroundPackage = null
                    onScreenSessions.clear()
                    pendingLeaves.clear()
                    returningSince.clear()
                    isConfirmingDeviceCredential = false
                    LockStateTracker.onScreenTurnedOff()
                }
                // The keyguard just went away: whatever is on screen must be re-checked even if
                // no window event arrives (e.g. a locked app was left open when the screen went off).
                Intent.ACTION_USER_PRESENT -> {
                    reevaluateForeground()
                    mainHandler.postDelayed(::reevaluateForeground, 250)
                }
            }
        }
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
        shield = InstantShieldOverlay(
            this,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            goHome = { performGlobalAction(GLOBAL_ACTION_HOME) },
            onTimeout = ::onShieldTimeout
        )
        selfProtection = SelfProtectionDetector(this)
        refreshSystemLists(force = true)
        ContextCompat.registerReceiver(
            this,
            deviceStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        LockStateTracker.addLockAllListener(onLockAll)
        ProtectionAlerts.serviceRestored(this)
        // The accessibility engine is the better one: compatibility mode stands down.
        UsageWatchService.stop(this)
        // A locked app may already be in front (service restarted, or enabled over it).
        mainHandler.postDelayed(::reevaluateForeground, 250)
        Log.i(TAG, "Accessibility service connected & components initialized")
    }

    /**
     * Sessions just ended (protection resumed from the tile or a timer, lock-all) while an app may
     * be on screen: cover it now rather than at the next window event. Skipped with the screen off,
     * where nothing is visible and launching the lock screen would wake the display.
     */
    private val onLockAll: () -> Unit = {
        mainHandler.post {
            onScreenSessions.clear()
            pendingLeaves.clear()
            returningSince.clear()
            if (isRunning && isInteractive()) reevaluateForeground()
        }
    }

    private fun isInteractive() = getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun isDeviceLocked() = getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    // -------------------------------------------------------------------------------------------
    // Event handling
    // -------------------------------------------------------------------------------------------

    private var contentMonitoringEnabled = false
    private var activeSelfProtectionHost: String? = null

    private val reconcileRunnable = Runnable { reconcile() }
    private var reconcileScheduled = false

    private val revokeSelfProtectionAfterExitRunnable = Runnable {
        if (shield.isShowing || LockScreenActivity.isLockScreenResumed || SecurityEngine.INSTANCE.isAuthenticating()) {
            return@Runnable
        }
        if (LockStateTracker.hasVisitedGuardedSelfProtectionScreen()) {
            val host = activeSelfProtectionHost
            val stillGuarded = host != null && selfProtection.isGuardedScreen(host, null)
            if (!stillGuarded) {
                LockStateTracker.clearSelfProtection()
            }
        }
    }
    private val trailingSelfProtectionRunnable = Runnable {
        val host = activeSelfProtectionHost ?: return@Runnable
        if (!::vault.isInitialized) return@Runnable
        if (shield.isShowing || LockScreenActivity.isLockScreenResumed || SecurityEngine.INSTANCE.isAuthenticating()) {
            return@Runnable
        }
        val guarded = selfProtection.isGuardedScreen(host, null)
        if (guarded) {
            mainHandler.removeCallbacks(revokeSelfProtectionAfterExitRunnable)
            if (LockStateTracker.isSelfProtectionGranted()) {
                LockStateTracker.markSelfProtectionVisitedGuardedScreen()
                updateContentMonitoring(false)
            } else if (shouldGuardSelf()) {
                triggerSelfProtection()
            }
        } else if (LockStateTracker.hasVisitedGuardedSelfProtectionScreen()) {
            mainHandler.removeCallbacks(revokeSelfProtectionAfterExitRunnable)
            mainHandler.postDelayed(revokeSelfProtectionAfterExitRunnable, 1_500L)
        }
    }

    private fun updateContentMonitoring(enable: Boolean) {
        if (contentMonitoringEnabled == enable) return
        val info = serviceInfo ?: return
        contentMonitoringEnabled = enable
        info.eventTypes = if (enable) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } else {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
        }
        info.notificationTimeout = if (enable) 100L else 0L
        runCatching { serviceInfo = info }
    }

    /** A locked app that isn't unlocked and should be covered right now. */
    private fun needsCover(pkg: String): Boolean =
        pkg != packageName &&
            vault.isAppLocked(pkg) &&
            !LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg)) &&
            !LockStateTracker.isRecentlyAbandoned(pkg) &&
            !(LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::vault.isInitialized) return
        try {
            val type = event.eventType
            val eventPkg = event.packageName?.toString()
            val eventCls = event.className?.toString().orEmpty()

            if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                if (eventPkg != null && isLauncher(eventPkg)) predictLaunch(event)
                return
            }
            if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                onWindowsChanged(event)
                return
            }

            if (!SecurityEngine.INSTANCE.isAuthenticating()) {
                isConfirmingDeviceCredential = false
            }

            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && eventPkg != null) {
                if (event.windowId >= 0) windowPackages[event.windowId] = eventPkg
                if (eventCls.isNotEmpty()) lastClassByPackage[eventPkg] = eventCls

                // Fast path: cover a locked app before any other work.
                val target = resolveForegroundPackage(event)
                if (target != null && !isSystemOrOverlay(target, eventCls) && needsCover(target) && !isExitTransition(target)) {
                    shield.show(target) { launchLockScreen(target, isSelfProtection = false) }
                }

                if (isDeviceCredentialScreen(eventPkg, eventCls)) {
                    isConfirmingDeviceCredential = true
                } else if (
                    isConfirmingDeviceCredential &&
                    eventPkg != packageName &&
                    eventPkg != "com.android.settings" &&
                    eventPkg != "com.android.systemui" &&
                    eventPkg !in imePackages &&
                    eventPkg !in ForegroundRules.SYSTEM_OVERLAYS
                ) {
                    isConfirmingDeviceCredential = false
                }
                val inSelfProtHost = selfProtection.isHost(eventPkg) && !isConfirmingDeviceCredential
                if (inSelfProtHost) {
                    activeSelfProtectionHost = eventPkg
                    val needContentMonitoring = vault.isSetupCompleted() &&
                        vault.isProtectionActive() &&
                        vault.isAccessibilitySelfProtectionEnabled() &&
                        !LockStateTracker.hasVisitedGuardedSelfProtectionScreen()
                    updateContentMonitoring(needContentMonitoring)
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
                        mainHandler.removeCallbacks(revokeSelfProtectionAfterExitRunnable)
                        if (LockStateTracker.isSelfProtectionGranted()) {
                            LockStateTracker.markSelfProtectionVisitedGuardedScreen()
                            updateContentMonitoring(false)
                        } else if (shouldGuardSelf()) {
                            triggerSelfProtection()
                            return
                        }
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
            handleForeground(target, eventCls)
            scheduleReconcile()
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error processing accessibility event: ${t.message}", t)
        }
    }

    // -------------------------------------------------------------------------------------------
    // Launch prediction
    // -------------------------------------------------------------------------------------------

    /** Lower-cased label -> locked packages with that label, rebuilt when the locked set changes. */
    private var lockedLabelIndex: Map<String, List<String>> = emptyMap()
    private var lockedLabelIndexKey: Set<String>? = null

    private fun lockedPackagesForLabel(label: String): List<String> {
        val locked = vault.getLockedPackages()
        if (locked != lockedLabelIndexKey) {
            lockedLabelIndex = locked.groupBy { AppInfoCache.quickLabel(this, it).trim().lowercase() }
            lockedLabelIndexKey = locked
        }
        return lockedLabelIndex[label].orEmpty()
    }

    /**
     * A tap on a launcher icon, a Recents card or a search result is the earliest sign an app is
     * about to open: earlier than any window it creates. If what was tapped is a locked app, the
     * cover goes up right now, so not one frame of the app is drawn uncovered. The cover then
     * waits for the app's window and the normal lock flow takes over.
     */
    private fun predictLaunch(event: AccessibilityEvent) {
        if (!vault.isGlobalLockEnabled() || shield.isShowing || LockScreenActivity.isLockScreenResumed) return
        val spoken = buildList {
            event.contentDescription?.let(::add)
            addAll(event.text)
        }
        for (raw in spoken) {
            // "Messages, 3 notifications", "Work Messages", multi-line cards: keep the name part.
            val name = raw.toString().lineSequence().firstOrNull()?.substringBefore(',')?.trim()?.lowercase() ?: continue
            if (name.isEmpty()) continue
            val target = (lockedPackagesForLabel(name) + lockedPackagesForLabel(name.removePrefix("work ").trim()))
                .firstOrNull { needsCover(it) } ?: continue
            shield.showPredicted(
                targetPackage = target,
                waitMs = PREDICTION_WAIT_MS,
                // A tap before the app appears means nothing is opening (e.g. a folder that
                // shares the app's name): stand down. If the app is there, lock it.
                onTapped = { onPredictionExpired(target) },
                onExpired = ::onPredictionExpired
            )
            return
        }
    }

    /** The predicted app never showed up in time: cover it if it's there now, else stand down. */
    private fun onPredictionExpired(target: String) {
        val front = rootInActiveWindow?.let { root -> root.packageName?.toString().also { release(root) } }
        val arrived = front == target ||
            (target == VIRTUAL_PKG_GOOGLE_PASSWORDS && (front == PKG_GMS || front == PKG_CREDENTIAL_MANAGER))
        if (arrived && needsCover(target)) lock(target) else shield.hide()
    }

    private fun onWindowsChanged(event: AccessibilityEvent) {
        // The lock screen coming up (Lockdown, a lock with the screen on) is a window change too.
        LockStateTracker.onDeviceLockState(isDeviceLocked())
        val changes = event.windowChanges
        val surfaced = AccessibilityEvent.WINDOWS_CHANGE_ADDED or
            AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
            AccessibilityEvent.WINDOWS_CHANGE_FOCUSED
        // A window we already know came (back) to the front, e.g. a locked app resumed from
        // Recents: cover it now instead of waiting for its state event. Only if it really is in
        // front, though: a window can be "added" or "focused" for a moment on its way out.
        if (changes and surfaced != 0) {
            val pkg = windowPackages[event.windowId]
            if (pkg != null && !isSystemOrOverlay(pkg, "") && !isLauncher(pkg) && isCoverableByVisibility(pkg) &&
                needsCover(pkg) && isFrontWindow(event.windowId) && !isExitTransition(pkg)
            ) {
                lock(pkg)
            }
        }
        scheduleReconcile()
    }

    private fun isFrontWindow(windowId: Int): Boolean {
        val w = (try { windows } catch (_: Exception) { null })?.firstOrNull { it.id == windowId } ?: return false
        return w.type == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isActive || w.isFocused)
    }

    /**
     * Going Home or dismissing an app's card in Recents animates the app's window away, and
     * Android can hand that window focus (and send its events) once more on the way out. Right
     * after an app left, its window turning up while the launcher is still on screen is that exit,
     * not the app coming back. [reconcile] covers it if it stays ([EXIT_SETTLE_MS]); a real return
     * is covered at once anyway, by the tap on its icon or card ([predictLaunch]), or because
     * the launcher isn't on screen (switching straight back to it).
     */
    private fun isExitTransition(pkg: String): Boolean {
        if (!LockStateTracker.justLeft(pkg)) return false
        val list = try { windows } catch (_: Exception) { null } ?: return false
        val launcherOnScreen = list.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION && windowPackages[w.id]?.let(::isLauncher) == true
        }
        if (launcherOnScreen) {
            Log.i(TAG, "$pkg surfaced while leaving; waiting for it to settle")
            scheduleReconcile(EXIT_SETTLE_MS)
        }
        return launcherOnScreen
    }

    private fun scheduleReconcile(delayMs: Long = RECONCILE_DELAY_MS) {
        if (reconcileScheduled) return
        reconcileScheduled = true
        mainHandler.postDelayed(reconcileRunnable, delayMs)
    }

    /**
     * Credential Manager and Play services host sign-in sheets for every app, so they are only
     * ever covered when a window event identified them as the password manager itself.
     */
    private fun isCoverableByVisibility(pkg: String) =
        pkg != packageName && pkg != PKG_CREDENTIAL_MANAGER && pkg != PKG_GMS && pkg != VIRTUAL_PKG_GOOGLE_PASSWORDS

    /**
     * Brings sessions in line with what is actually on screen. See the class comment.
     */
    private fun reconcile() {
        reconcileScheduled = false
        if (!isRunning || !::vault.isInitialized || !isInteractive()) return
        val windowList = try { windows } catch (_: Exception) { null } ?: return

        val visible = LinkedHashSet<String>()
        val pip = HashSet<String>()
        val liveIds = HashSet<Int>()
        var front: String? = null
        for (w in windowList) {
            liveIds += w.id
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            // Windows that hide from non-tool accessibility services (accessibility-data-sensitive,
            // e.g. BioKey's own) can't be identified: count them as some other app.
            val pkg = windowPackages[w.id] ?: windowPackage(w)?.also { windowPackages[w.id] = it } ?: UNIDENTIFIED_APP
            visible += pkg
            if (w.isInPictureInPictureMode) pip += pkg
            if (front == null && (w.isActive || w.isFocused)) front = pkg
        }
        windowPackages.keys.retainAll(liveIds)
        // Nothing app-like on screen is a transition, not an answer.
        if (visible.isEmpty()) return

        if (foregroundPackage == VIRTUAL_PKG_GOOGLE_PASSWORDS && (PKG_GMS in visible || PKG_CREDENTIAL_MANAGER in visible)) {
            visible += VIRTUAL_PKG_GOOGLE_PASSWORDS
        }
        val frontApp = (front ?: visible.first()).let {
            if (it == PKG_GMS && foregroundPackage == VIRTUAL_PKG_GOOGLE_PASSWORDS) VIRTUAL_PKG_GOOGLE_PASSWORDS else it
        }
        val now = SystemClock.elapsedRealtime()

        // 1. On screen: in use, nothing ages.
        for (pkg in visible) {
            pendingLeaves.remove(pkg)
            if (vault.isAppLocked(pkg)) LockStateTracker.onAppVisible(pkg)
        }

        // 2. Gone from the screen long enough: the user left.
        for (pkg in onScreenSessions.toList()) {
            if (pkg in visible) continue
            // An app that hides its windows from accessibility services can't be seen: while the
            // screen in front is unreadable and no other app has come forward, it is still in use.
            if (frontApp == UNIDENTIFIED_APP && foregroundPackage == pkg) {
                pendingLeaves.remove(pkg)
                continue
            }
            val goneSince = pendingLeaves.getOrPut(pkg) { now }
            if (now - goneSince < LEAVE_CONFIRM_MS) {
                mainHandler.postDelayed({ scheduleReconcile(0L) }, LEAVE_CONFIRM_MS - (now - goneSince) + 20L)
                continue
            }
            pendingLeaves.remove(pkg)
            onScreenSessions.remove(pkg)
            val hop = frontApp.takeIf { it != pkg && !isLauncher(it) && isHopTarget(it) }
            LockStateTracker.onAppLeft(pkg, vault.getTimeoutMode(pkg), hop)
        }

        // 3. A helper hop that moved on to somewhere else ends.
        LockStateTracker.onForegroundChanged(frontApp, vault::getTimeoutMode, ::isHopTarget)

        // 4. Locked content on screen without a session (split screen, PiP, freeform, missed
        // events): cover it.
        if (vault.isGlobalLockEnabled() && !shield.isShowing && !LockScreenActivity.isLockScreenResumed &&
            !SecurityEngine.INSTANCE.isAuthenticating()
        ) {
            for (pkg in visible) {
                if (!isCoverableByVisibility(pkg) || isLauncher(pkg) || !needsCover(pkg)) continue
                if (LockStateTracker.isRegularLockPending(pkg)) continue
                if (pkg !in pip && !hasSettled(pkg, visible, now)) continue
                if (pkg in pip) {
                    Log.i(TAG, "Locked app $pkg in picture-in-picture without unlock; leaving PiP")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
                lock(pkg)
                return
            }
            if (visible.size > 1) {
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

        // 5. Remember which unlocked apps are on screen so step 2 can notice them leave.
        for (pkg in visible) {
            if (vault.isAppLocked(pkg) && LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))) {
                onScreenSessions += pkg
            }
        }
        returningSince.keys.retainAll(visible)
    }

    /**
     * Whether a locked app's window is really back rather than flickering on its way out (see
     * [isExitTransition]): a just-left app next to the launcher must stay for [EXIT_SETTLE_MS].
     */
    private fun hasSettled(pkg: String, visible: Set<String>, now: Long): Boolean {
        if (!LockStateTracker.justLeft(pkg) || visible.none(::isLauncher)) {
            returningSince.remove(pkg)
            return true
        }
        val since = returningSince.getOrPut(pkg) { now }
        if (now - since >= EXIT_SETTLE_MS) {
            returningSince.remove(pkg)
            return true
        }
        mainHandler.postDelayed({ scheduleReconcile(0L) }, EXIT_SETTLE_MS - (now - since) + 20L)
        return false
    }

    /** Fallback when a window appeared without a state event: ask the window itself (an IPC). */
    private fun windowPackage(window: AccessibilityWindowInfo): String? {
        val root = try { window.root } catch (_: Exception) { null } ?: return null
        return root.packageName?.toString().also { release(root) }
    }

    /** See [ForegroundRules.isHopTarget]; keyboards and unreadable windows also leave a hop running. */
    private fun isHopTarget(pkg: String): Boolean =
        pkg == UNIDENTIFIED_APP || pkg in imePackages || ForegroundRules.isHopTarget(this, pkg, lastClassByPackage[pkg])

    private fun handleForeground(target: String, cls: String) {
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

        val isLauncher = isLauncher(target)
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
            pendingLeaves.remove(target)
            onScreenSessions += target
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
        if (isExitTransition(target)) return
        lock(target)
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

    private fun lock(target: String) {
        foregroundPackage = target
        if (LockStateTracker.isRegularLockPending(target) &&
            LockScreenActivity.isLockScreenResumed &&
            LockScreenActivity.activeLockedTarget == target
        ) return
        LockStateTracker.setRegularLockPending(target)
        shield.show(target) { launchLockScreen(target, isSelfProtection = false) }
        launchLockScreen(target, isSelfProtection = false)
    }

    private fun reevaluateForeground() {
        if (!::vault.isInitialized) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        val cls = root.className?.toString().orEmpty()
        release(root)
        if (pkg != null) {
            if (selfProtection.isHost(pkg) && !isConfirmingDeviceCredential) {
                activeSelfProtectionHost = pkg
                val needContentMonitoring = vault.isSetupCompleted() &&
                    vault.isProtectionActive() &&
                    vault.isAccessibilitySelfProtectionEnabled() &&
                    !LockStateTracker.hasVisitedGuardedSelfProtectionScreen()
                updateContentMonitoring(needContentMonitoring)
                if (shouldGuardSelf() && selfProtection.isGuardedScreen(pkg, null)) {
                    triggerSelfProtection()
                    return
                }
            }
            val target = if (pkg == PKG_CREDENTIAL_MANAGER || (pkg == PKG_GMS && cls.contains("PasswordManager", ignoreCase = true))) {
                VIRTUAL_PKG_GOOGLE_PASSWORDS
            } else pkg
            handleForeground(target, cls)
        }
        scheduleReconcile()
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

    private fun triggerSelfProtection() {
        if (LockStateTracker.isRegularLockPending(SELF_PROTECTION_PENDING_KEY) &&
            LockScreenActivity.isLockScreenResumed &&
            LockScreenActivity.activeLockedTarget == SELF_PROTECTION_PENDING_KEY
        ) return
        LockStateTracker.setRegularLockPending(SELF_PROTECTION_PENDING_KEY)
        Log.i(TAG, "Guarding a BioKey system settings screen")
        shield.show(SELF_PROTECTION_PENDING_KEY) {
            launchLockScreen(SELF_PROTECTION_PENDING_KEY, isSelfProtection = true)
        }
        launchLockScreen(SELF_PROTECTION_PENDING_KEY, isSelfProtection = true)
    }

    private fun navigateBackFromGuardedSelfProtection() {
        LockStateTracker.markLockAbandoned(SELF_PROTECTION_PENDING_KEY)
        LockStateTracker.clearSelfProtection()
        mainHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            shield.hide()
            mainHandler.postDelayed({
                val host = activeSelfProtectionHost
                if (host != null && shouldGuardSelf() && selfProtection.isGuardedScreen(host, null)) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }, 220L)
        }, 60L)
    }

    // -------------------------------------------------------------------------------------------
    // Foreground resolution and classification
    // -------------------------------------------------------------------------------------------

    private fun resolveForegroundPackage(event: AccessibilityEvent): String? {
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return null
        if ((pkg == PKG_CREDENTIAL_MANAGER || pkg == PKG_GMS) && isGooglePasswordManagerEvent(event)) {
            return VIRTUAL_PKG_GOOGLE_PASSWORDS
        }
        return pkg
    }

    private fun refreshSystemLists(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - systemListsRefreshedAt < 30_000L) return
        systemListsRefreshedAt = now
        try {
            imePackages = getSystemService(InputMethodManager::class.java).enabledInputMethodList
                .map { it.packageName }
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "IME query failed: ${e.message}")
        }
    }

    private fun isLauncher(pkg: String): Boolean {
        refreshSystemLists()
        return ForegroundRules.isLauncher(this, pkg)
    }

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
        refreshSystemLists()
        if (pkg in imePackages) return true
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

    /** Shield timed out: relaunch the lock if the locked app is still in front, else let it go. */
    private fun onShieldTimeout(target: String, attempt: Int): Boolean {
        val foreground = rootInActiveWindow?.let { root -> root.packageName?.toString().also { release(root) } }
            ?: foregroundPackage
        val stillExposed = if (target == SELF_PROTECTION_PENDING_KEY) {
            foreground != null && selfProtection.isHost(foreground) && shouldGuardSelf() && selfProtection.isGuardedScreen(foreground, null)
        } else {
            foreground != null &&
                (foreground == target || (target == VIRTUAL_PKG_GOOGLE_PASSWORDS && (foreground == PKG_GMS || foreground == PKG_CREDENTIAL_MANAGER))) &&
                !LockStateTracker.isAppUnlocked(target, vault.getTimeoutMode(target))
        }
        if (!stillExposed) return false
        if (attempt > 0) Log.w(TAG, "Lock screen missing after shield timeout (attempt $attempt); relaunching")
        LockStateTracker.setRegularLockPending(target)
        launchLockScreen(target, isSelfProtection = target == SELF_PROTECTION_PENDING_KEY)
        return true
    }

    private fun launchLockScreen(targetPkg: String, isSelfProtection: Boolean) {
        if (!LockLauncher.launch(this, targetPkg, isSelfProtection)) {
            // Could not cover the app: fail closed by leaving it.
            performGlobalAction(GLOBAL_ACTION_HOME)
            shield.hide()
        }
    }

    // -------------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------------

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::shield.isInitialized) shield.onConfigurationChanged()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        onStopped()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        activeService = null
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
        isConfirmingDeviceCredential = false
        mainHandler.removeCallbacks(reconcileRunnable)
        mainHandler.removeCallbacks(trailingSelfProtectionRunnable)
        LockStateTracker.removeLockAllListener(onLockAll)
        // Nothing enforces sessions while the service is gone, so none may outlive it.
        LockStateTracker.lockAll()
        if (::shield.isInitialized) shield.destroy()
        if (::vault.isInitialized && vault.isSetupCompleted()) {
            // Hand over to compatibility mode if it's set up (e.g. Advanced Protection was just turned on).
            ProtectionEngine.refresh(this)
            if (!UsageWatchService.isRunning) ProtectionAlerts.serviceStopped(this)
        }
        Log.w(TAG, "Accessibility service stopped")
    }
}
