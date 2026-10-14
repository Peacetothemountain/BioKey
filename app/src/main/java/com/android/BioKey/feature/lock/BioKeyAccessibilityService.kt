package com.android.BioKey.feature.lock

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
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
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.MainActivity

/**
 * Enforcement engine: watches which app is in the foreground and covers locked apps with
 * [LockScreenActivity] until the owner authenticates.
 *
 * Also guards BioKey itself (self-protection): Settings/installer screens that could disable or
 * uninstall BioKey require authentication while self-protection is on.
 */
class BioKeyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BioKeyA11yService"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Whether the service is enabled in system settings (exact component match). */
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

        fun isPackageCurrentlyInForeground(packageName: String): Boolean {
            val svc = activeService ?: return false
            val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return svc.lastFocusedPackage == packageName
            val activePkg = root.packageName?.toString()
            svc.release(root)
            return activePkg != null && (svc.sameApp(activePkg, packageName) ||
                (activePkg == PKG_GMS && packageName == VIRTUAL_PKG_GOOGLE_PASSWORDS))
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

        private val KNOWN_LAUNCHERS = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.oppo.launcher",
            "com.oneplus.launcher",
            "com.huawei.android.launcher"
        )

        /**
         * Apps people hop to mid-sign-in (authenticators, SMS/email for codes, account pickers,
         * file/photo pickers). Visiting one straight from an unlocked app and coming straight
         * back doesn't end that app's session; going anywhere else does.
         */
        private val AUTH_HELPER_PACKAGES = setOf(
            PKG_GMS,
            "com.google.android.gsf",
            "com.google.android.apps.credentialmanager",
            "com.android.credentialmanager",
            "com.google.android.apps.authenticator2",
            "com.azure.authenticator",
            "com.authy.authy",
            "com.yubico.yubioath",
            "com.duosecurity.duomobile",
            "org.fedorahosted.freeotp",
            "org.liberty.android.freeotpplus",
            "com.beemdevelopment.aegis",
            "com.twofasapp",
            "io.ente.auth",
            "com.bitwarden.authenticator",
            "com.x8bit.bitwarden",
            "com.onepassword.android",
            "com.lastpass.authenticator",
            "com.callpod.android_apps.keeper",
            "com.dashlane",
            "com.zoho.accounts.oneauth",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "ch.protonmail.android",
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.google.android.providers.media.module",
            "com.android.providers.media.module"
        )

        /** Windows that float over apps without meaning the user switched apps. Exact names only. */
        private val SYSTEM_OVERLAYS = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.android.credentialmanager",
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.as",
            "com.google.android.apps.wellbeing",
            "com.google.android.intentresolver",
            "com.android.intentresolver",
            "com.google.android.markup",
            "com.android.companiondevicemanager"
        )

        private val KNOWN_BROWSERS = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.canary", "com.chrome.dev",
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
            "com.microsoft.emmx", "com.duckduckgo.mobile.android", "com.opera.browser", "com.opera.gx",
            "com.opera.mini.native", "com.brave.browser", "com.sec.android.app.sbrowser",
            "com.sec.android.app.sbrowser.beta", "com.vivaldi.browser"
        )

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

    private var lastFocusedPackage: String? = null
    private var authHelperPackage: String? = null

    private var launcherPackages: Set<String> = KNOWN_LAUNCHERS
    private var imePackages: Set<String> = emptySet()
    private var systemListsRefreshedAt = 0L

    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    lastFocusedPackage = null
                    authHelperPackage = null
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
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            return
        }
        initServiceComponents()
    }

    private fun initServiceComponents() {
        if (!isRunning) return
        vault = SecurityVault.getInstance(this)
        AppInfoCache.preWarm(this, vault.getLockedPackages())
        shield = InstantShieldOverlay(this, ::onShieldTimeout)
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
            if (isRunning && getSystemService(PowerManager::class.java)?.isInteractive == true) reevaluateForeground()
        }
    }

    // -------------------------------------------------------------------------------------------
    // Event handling
    // -------------------------------------------------------------------------------------------

    @Volatile private var hasMultipleAppWindows = false
    private var contentMonitoringEnabled = false
    private var activeSelfProtectionHost: String? = null

    private val checkWindowsRunnable = Runnable { checkVisibleWindows() }
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
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } else {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
        }
        info.notificationTimeout = if (enable) 100L else 0L
        runCatching { serviceInfo = info }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::vault.isInitialized) return
        try {
            val type = event.eventType
            val eventPkg = event.packageName?.toString()
            val eventCls = event.className?.toString().orEmpty()

            if (!SecurityEngine.INSTANCE.isAuthenticating()) {
                isConfirmingDeviceCredential = false
            }

            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && eventPkg != null) {
                if (isDeviceCredentialScreen(eventPkg, eventCls)) {
                    isConfirmingDeviceCredential = true
                } else if (
                    isConfirmingDeviceCredential &&
                    eventPkg != packageName &&
                    eventPkg != "com.android.settings" &&
                    eventPkg != "com.android.systemui" &&
                    eventPkg !in imePackages &&
                    eventPkg !in SYSTEM_OVERLAYS
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

            if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                if (!shield.isShowing && !LockScreenActivity.isLockScreenResumed && !SecurityEngine.INSTANCE.isAuthenticating()) {
                    mainHandler.removeCallbacks(checkWindowsRunnable)
                    mainHandler.postDelayed(checkWindowsRunnable, 250L)
                }
                return
            }

            // Only explicit activity window transitions trigger foreground app evaluation.
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                return
            }

            val target = resolveForegroundPackage(event) ?: return
            handleForeground(target, eventCls)
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error processing accessibility event: ${t.message}", t)
        }
    }

    private fun checkVisibleWindows() {
        if (!::vault.isInitialized || !vault.isGlobalLockEnabled() ||
            shield.isShowing || LockScreenActivity.isLockScreenResumed || SecurityEngine.INSTANCE.isAuthenticating()
        ) {
            hasMultipleAppWindows = false
            return
        }
        val currentWindows = try { windows } catch (e: Exception) { null } ?: return
        val appWindows = currentWindows.filter {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && it.isInPictureInPictureMode)
        }
        hasMultipleAppWindows = appWindows.size > 1
        if (!hasMultipleAppWindows && appWindows.none { Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && it.isInPictureInPictureMode }) {
            return
        }

        for (win in appWindows) {
            val isPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && win.isInPictureInPictureMode
            if (!isPip && !hasMultipleAppWindows) continue
            val root = win.root ?: continue
            val pkg = root.packageName?.toString()
            release(root)
            if (pkg != null && selfProtection.isHost(pkg) && shouldGuardSelf() &&
                !LockStateTracker.isRecentlyAbandoned(SELF_PROTECTION_PENDING_KEY) &&
                selfProtection.isGuardedScreen(pkg, null)
            ) {
                triggerSelfProtection()
                break
            }
            if (pkg != null && pkg != packageName && vault.isAppLocked(pkg) &&
                !LockStateTracker.isRecentlyExited(pkg) &&
                !LockStateTracker.isRecentlyAbandoned(pkg) &&
                !SecurityEngine.INSTANCE.isAuthenticatingFor(pkg) &&
                !LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))
            ) {
                if (isPip) {
                    Log.i(TAG, "Locked app $pkg detected in PiP mode without unlock; covering and exiting PiP")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } else {
                    Log.i(TAG, "Locked app $pkg detected in split-screen/multi-window without unlock; locking")
                }
                lock(pkg)
                break
            }
        }
    }

    private fun findVisibleLockedWindowPackage(): String? {
        if (!hasMultipleAppWindows || !::vault.isInitialized || !vault.isGlobalLockEnabled()) return null
        val currentWindows = try { windows } catch (e: Exception) { null } ?: return null
        for (win in currentWindows) {
            if (win.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = win.root ?: continue
            val pkg = root.packageName?.toString()
            release(root)
            if (pkg != null && pkg != packageName && vault.isAppLocked(pkg) &&
                !LockStateTracker.isRecentlyExited(pkg) &&
                !LockStateTracker.isRecentlyAbandoned(pkg) &&
                !SecurityEngine.INSTANCE.isAuthenticatingFor(pkg) &&
                !LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))
            ) {
                return pkg
            }
        }
        return null
    }

    private fun sameApp(a: String, b: String): Boolean {
        if (a == b) return true
        val pm = setOf(VIRTUAL_PKG_GOOGLE_PASSWORDS, PKG_CREDENTIAL_MANAGER)
        return a in pm && b in pm
    }

    private fun isAuthHelper(pkg: String, cls: String): Boolean =
        pkg in AUTH_HELPER_PACKAGES ||
            (pkg in KNOWN_BROWSERS && (cls.contains("CustomTab", ignoreCase = true) || cls.contains("AuthTab", ignoreCase = true)))

    private fun handleForeground(target: String, cls: String) {
        // BioKey's own windows (lock screen, main UI) have their own authentication.
        if (target == packageName) {
            if (cls == MainActivity::class.java.name) {
                lastFocusedPackage?.let { previous ->
                    if (vault.isAppLocked(previous)) LockStateTracker.onAppExited(previous, timeoutMode = vault.getTimeoutMode(previous))
                }
                LockStateTracker.clearSelfProtection()
                lastFocusedPackage = target
            }
            return
        }
        // Keyboards, permission dialogs, the shade etc. float over the current app: no app switch.
        if (isSystemOrOverlay(target, cls)) return

        val isLauncher = isLauncher(target)
        val isRecents = isLauncher && isRecentsOverviewClass(cls)
        val previous = lastFocusedPackage

        // Guard against stale background launcher window-state events when an app or shield is actually in front:
        if (isLauncher && !isRecents) {
            val shieldPkg = shield.targetPackage
            if (shield.isShowing && shieldPkg != null) {
                val shieldStillActive = LockStateTracker.isRegularLockPending(shieldPkg) ||
                    (shieldPkg != SELF_PROTECTION_PENDING_KEY && isPackageCurrentlyInForeground(shieldPkg))
                if (shieldStillActive) {
                    return
                }
            }
            if (previous != null && !isLauncher(previous) && isPackageCurrentlyInForeground(previous)) {
                return
            }
        }

        // Recents protection: evaluate BEFORE onAppExited locks IMMEDIATE-mode apps.
        // If user entered Overview directly from a locked app that was NOT unlocked, route to Home.
        if (isRecents && previous != null &&
            vault.isAppLocked(previous) && !LockStateTracker.isAppUnlocked(previous, vault.getTimeoutMode(previous))
        ) {
            Log.i(TAG, "Recents shield active: routing to Home from Overview for $previous")
            LockStateTracker.onAppExited(previous, isLauncherExit = true, timeoutMode = vault.getTimeoutMode(previous))
            lastFocusedPackage = target
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

        LockStateTracker.getActiveAuthHost()?.let { host ->
            when {
                sameApp(target, host) -> {
                    LockStateTracker.completeAuthSubFlow(host)
                    authHelperPackage = null
                }
                target != authHelperPackage && !isAuthHelper(target, cls) -> {
                    LockStateTracker.abandonAuthSubFlow(
                        hostPackage = null,
                        isLauncherExit = isLauncher,
                        timeoutModeProvider = vault::getTimeoutMode
                    )
                    authHelperPackage = null
                }
            }
        }

        if (previous != null && !sameApp(previous, target)) {
            val previousTimeout = vault.getTimeoutMode(previous)
            // Hopping from an unlocked app to an auth helper keeps that app's session.
            if (isAuthHelper(target, cls) && vault.isAppLocked(previous) && LockStateTracker.isAppUnlocked(previous, previousTimeout)) {
                LockStateTracker.enterAuthSubFlow(previous)
                authHelperPackage = target
            }
            if (vault.isAppLocked(previous)) LockStateTracker.onAppExited(previous, isLauncherExit = isLauncher, timeoutMode = previousTimeout)
        }

        val targetTimeout = vault.getTimeoutMode(target)
        if (isLauncher || !vault.isAppLocked(target)) {
            lastFocusedPackage = target

            // Multi-window / split-screen guard: if an adjacent visible pane contains a locked app, lock it
            val splitLockedPkg = if (!isLauncher) findVisibleLockedWindowPackage() else null
            if (splitLockedPkg != null) {
                lock(splitLockedPkg)
                return
            }

            if (!LockStateTracker.isRegularLockPending(shield.targetPackage.orEmpty())) {
                shield.hide()
            }
            return
        }

        if (LockStateTracker.isAppUnlocked(target, targetTimeout)) {
            lastFocusedPackage = target
            LockStateTracker.onAppResumed(target)
            shield.hide()
            return
        }

        if (LockStateTracker.isRecentlyAbandoned(target)) {
            return
        }

        // Target is locked and NOT unlocked.
        lastFocusedPackage = target
        if (SecurityEngine.INSTANCE.isAuthenticatingFor(target) || LockStateTracker.isRegularLockPending(target)) {
            if (!LockScreenActivity.isLockScreenResumed || LockScreenActivity.activeLockedTarget != target) {
                shield.show(target) { launchLockScreen(target, isSelfProtection = false) }
                launchLockScreen(target, isSelfProtection = false)
            }
            return
        }
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
        // Match dedicated Recents/Overview UI components:
        return lower.contains("overview") ||
            lower.contains("recents") ||
            lower.contains("taskstack") ||
            (lower.contains("quickstep") && !lower.contains("launcher"))
    }

    private fun lock(target: String) {
        lastFocusedPackage = target
        if (LockStateTracker.isRegularLockPending(target) &&
            LockScreenActivity.isLockScreenResumed &&
            LockScreenActivity.activeLockedTarget == target
        ) return
        LockStateTracker.setRegularLockPending(target)
        // Instant non-blocking full-screen launch (< 1.5ms execution time)
        shield.show(target) { launchLockScreen(target, isSelfProtection = false) }
        launchLockScreen(target, isSelfProtection = false)
    }

    private fun reevaluateForeground() {
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
            lastFocusedPackage = null
            handleForeground(target, cls)
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
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PackageManager.MATCH_ALL else PackageManager.MATCH_DEFAULT_ONLY
            val homes = packageManager.queryIntentActivities(homeIntent, flags)
                .mapNotNull { it.activityInfo?.packageName }
                .filter { it != "com.android.settings" } // Settings' fallback home is not a launcher.
            launcherPackages = KNOWN_LAUNCHERS + homes
        } catch (e: Exception) {
            Log.w(TAG, "Launcher query failed: ${e.message}")
        }
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
        return pkg in launcherPackages
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
        if (pkg in SYSTEM_OVERLAYS) return true
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
            ?: lastFocusedPackage
        if (target == SELF_PROTECTION_PENDING_KEY) {
            val stillExposed = foreground != null && selfProtection.isHost(foreground) &&
                shouldGuardSelf() && selfProtection.isGuardedScreen(foreground, null)
            if (!stillExposed) return false
            if (attempt > 0) {
                Log.w(TAG, "Self-protection lock screen missing after shield timeout (attempt $attempt); relaunching")
            }
            LockStateTracker.clearRegularLockPending(target)
            LockStateTracker.setRegularLockPending(target)
            launchLockScreen(target, isSelfProtection = true)
            return true
        }
        val stillExposed = foreground != null && (sameApp(foreground, target) || foreground == PKG_GMS && target == VIRTUAL_PKG_GOOGLE_PASSWORDS)
        if (!stillExposed || LockStateTracker.isAppUnlocked(target, vault.getTimeoutMode(target))) return false
        if (attempt > 0) {
            Log.w(TAG, "Lock screen missing after shield timeout (attempt $attempt); relaunching")
        }
        LockStateTracker.clearRegularLockPending(target)
        LockStateTracker.setRegularLockPending(target)
        launchLockScreen(target, isSelfProtection = false)
        return true
    }

    private fun launchLockScreen(targetPkg: String, isSelfProtection: Boolean) {
        val intent = Intent(this, LockScreenActivity::class.java).apply {
            putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, targetPkg)
            putExtra(LockScreenActivity.EXTRA_SELF_PROTECTION, isSelfProtection)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }

        // Primary launch path: PendingIntent with explicit BAL exemption + 0-animation options
        // (bypasses Android 14-17 stopAppSwitches() / BAL 1513 silent blocks)
        try {
            val creatorOptions = ActivityOptions.makeCustomAnimation(this, 0, 0)
            val sendOptions = ActivityOptions.makeCustomAnimation(this, 0, 0)
            if (Build.VERSION.SDK_INT >= 36) {
                creatorOptions.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
                sendOptions.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                creatorOptions.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                @Suppress("DEPRECATION")
                sendOptions.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            PendingIntent.getActivity(
                this,
                targetPkg.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                creatorOptions.toBundle()
            ).send(this, 0, null, null, null, null, sendOptions.toBundle())
            return
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent lock screen start failed, trying startActivity: ${e.message}")
        }

        try {
            val options = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
            startActivity(intent, options)
        } catch (e: Exception) {
            // Could not cover the app: fail closed by leaving it.
            Log.e(TAG, "Lock screen launch failed; sending user home", e)
            performGlobalAction(GLOBAL_ACTION_HOME)
            shield.hide()
        }
    }

    // -------------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------------

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::shield.isInitialized) {
            shield.onConfigurationChanged()
        }
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
        mainHandler.removeCallbacks(checkWindowsRunnable)
        mainHandler.removeCallbacks(trailingSelfProtectionRunnable)
        LockStateTracker.removeLockAllListener(onLockAll)
        // Nothing enforces sessions while the service is gone, so none may outlive it.
        LockStateTracker.lockAll()
        if (::shield.isInitialized) shield.destroy()
        if (::vault.isInitialized && vault.isSetupCompleted()) {
            ProtectionAlerts.serviceStopped(this)
        }
        Log.w(TAG, "Accessibility service stopped")
    }
}
