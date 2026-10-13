package com.android.BioKey.feature.lock

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
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
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityEventLog
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

        /** Without the overlay shield, how long the lock screen gets to appear before a relaunch. */
        private const val LOCK_VERIFY_DELAY_MS = 2_500L

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
                }
                // The keyguard just went away: whatever is on screen must be re-checked even if
                // no window event arrives (e.g. a locked app was left open when the screen went off).
                Intent.ACTION_USER_PRESENT -> mainHandler.postDelayed(::reevaluateForeground, 250)
            }
        }
    }

    private var userUnlockedReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::vault.isInitialized) return
        try {
            val type = event.eventType

            // Self-protection runs first: installers count as system overlays below.
            val eventPkg = event.packageName?.toString()
            if (eventPkg != null && selfProtection.isHost(eventPkg) && shouldGuardSelf() &&
                selfProtection.isGuardedScreen(eventPkg, event)
            ) {
                triggerSelfProtection()
                return
            }

            if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                checkPipWindows()
                return
            }

            // Only explicit activity window transitions trigger foreground app evaluation.
            // Window changes (TYPE_WINDOWS_CHANGED) and content changes (TYPE_WINDOW_CONTENT_CHANGED)
            // must never trigger app locking, preventing false authentication prompts when exiting,
            // closing apps, or swiping home.
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                return
            }

            val target = resolveForegroundPackage(event) ?: return

            // Inspect the active focused window to eliminate ghost lock prompts during app exit/closing
            val activeRoot = try { rootInActiveWindow } catch (e: Exception) { null }
            val activePkg = activeRoot?.packageName?.toString()
            if (activeRoot != null) release(activeRoot)

            if (activePkg != null && activePkg != target && !sameApp(activePkg, target)) {
                if (isLauncher(activePkg)) {
                    // The user transitioned to Home/Launcher; this is a lingering teardown event from the target.
                    if (vault.isAppLocked(target)) {
                        LockStateTracker.onAppExited(target, isLauncherExit = true)
                    }
                    shield.hide()
                    return
                }
            }

            if (vault.isAppLocked(target) && LockStateTracker.isRecentlyExited(target, 1_500L) && !isLauncher(target)) {
                if (activePkg == null || isLauncher(activePkg)) {
                    shield.hide()
                    return
                }
            }

            handleForeground(target, event.className?.toString().orEmpty())
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error processing accessibility event: ${t.message}", t)
        }
    }

    private fun checkPipWindows() {
        if (!::vault.isInitialized || !vault.isGlobalLockEnabled()) return
        val currentWindows = try { windows } catch (e: Exception) { null } ?: return
        for (win in currentWindows) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && win.isInPictureInPictureMode) {
                val root = win.root ?: continue
                val pkg = root.packageName?.toString()
                release(root)
                if (pkg != null && vault.isAppLocked(pkg) && !LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode())) {
                    Log.i(TAG, "Locked app $pkg detected in PiP mode without unlock; closing PiP")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    lock(pkg)
                    break
                }
            }
        }
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
            // The lock screen is part of unlocking what's behind it, not an app switch. Opening
            // BioKey's main UI is: the previous app's session starts ageing, and any round trip
            // through Settings that self-protection allowed is over.
            if (cls == MainActivity::class.java.name) {
                lastFocusedPackage?.let { previous ->
                    if (vault.isAppLocked(previous)) LockStateTracker.onAppExited(previous)
                    if (selfProtection.isHost(previous)) LockStateTracker.clearSelfProtection()
                }
                lastFocusedPackage = target
            }
            return
        }
        // Keyboards, permission dialogs, the shade etc. float over the current app: no app switch.
        // The shield deliberately stays up so these can't reveal a locked app.
        if (isSystemOrOverlay(target, cls)) return

        val timeoutMode = vault.getTimeoutMode()
        val isLauncher = isLauncher(target)

        LockStateTracker.getActiveAuthHost()?.let { host ->
            when {
                sameApp(target, host) -> {
                    LockStateTracker.completeAuthSubFlow(host)
                    authHelperPackage = null
                }
                target != authHelperPackage && !isAuthHelper(target, cls) -> {
                    LockStateTracker.abandonAuthSubFlow(host, isLauncherExit = isLauncher)
                    authHelperPackage = null
                }
            }
        }

        val previous = lastFocusedPackage
        if (previous != null && !sameApp(previous, target)) {
            // Hopping from an unlocked app to an auth helper keeps that app's session.
            if (isAuthHelper(target, cls) && vault.isAppLocked(previous) && LockStateTracker.isAppUnlocked(previous, timeoutMode)) {
                LockStateTracker.enterAuthSubFlow(previous)
                authHelperPackage = target
            }
            if (vault.isAppLocked(previous)) LockStateTracker.onAppExited(previous, isLauncherExit = isLauncher)
            if (selfProtection.isHost(previous) && !selfProtection.isHost(target)) LockStateTracker.clearSelfProtection()
        }
        lastFocusedPackage = target

        if (isLauncher || !vault.isAppLocked(target)) {
            // Recents protection: if user exited from a locked app that locked immediately,
            // and opened Overview/Recents, route them to clean Home to prevent task thumbnail leak.
            val isRecents = isLauncher && isRecentsOverviewClass(cls)
            if (isRecents && previous != null &&
                vault.isAppLocked(previous) && !LockStateTracker.isAppUnlocked(previous, timeoutMode)
            ) {
                Log.i(TAG, "Recents shield active: routing to Home from Overview for $previous")
                performGlobalAction(GLOBAL_ACTION_HOME)
                mainHandler.postDelayed({ shield.hide() }, 150)
                return
            }

            shield.hide()
            return
        }
        if (SecurityEngine.INSTANCE.isAuthenticatingFor(target)) return
        if (LockStateTracker.isAppUnlocked(target, timeoutMode)) {
            LockStateTracker.onAppResumed(target)
            shield.hide()
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

    private fun getTargetWindowBounds(target: String): Rect? {
        try {
            val currentWindows = windows
            if (!currentWindows.isNullOrEmpty()) {
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels
                for (win in currentWindows) {
                    if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val root = win.root ?: continue
                    val pkg = root.packageName?.toString()
                    release(root)
                    if (pkg != null && (pkg == target || sameApp(pkg, target))) {
                        val bounds = Rect()
                        win.getBoundsInScreen(bounds)
                        val isSplit = !bounds.isEmpty && (
                            bounds.width() <= (screenWidth * 0.85f).toInt() ||
                            bounds.height() <= (screenHeight * 0.85f).toInt()
                        )
                        if (isSplit) {
                            Log.d(TAG, "Multi-window split bounds detected for $target: $bounds")
                            return bounds
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking window bounds for $target: ${e.message}")
        }
        return null
    }

    private fun lock(target: String) {
        if (LockStateTracker.isRegularLockPending(target)) return
        LockStateTracker.setRegularLockPending(target)
        val bounds = getTargetWindowBounds(target)
        shield.show(target, bounds) { launchLockScreen(target, isSelfProtection = false, bounds = bounds) }
        launchLockScreen(target, isSelfProtection = false, bounds = bounds)
    }

    private fun reevaluateForeground() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        release(root)
        if (pkg != null) {
            lastFocusedPackage = null
            handleForeground(pkg, "")
        }
    }

    // -------------------------------------------------------------------------------------------
    // Self-protection
    // -------------------------------------------------------------------------------------------

    private fun shouldGuardSelf(): Boolean =
        vault.isSetupCompleted() &&
            !LockStateTracker.isSelfProtectionGranted() &&
            !SecurityEngine.INSTANCE.isAuthenticatingFor(SELF_PROTECTION_PENDING_KEY)

    private fun triggerSelfProtection() {
        if (LockStateTracker.isRegularLockPending(SELF_PROTECTION_PENDING_KEY)) return
        LockStateTracker.setRegularLockPending(SELF_PROTECTION_PENDING_KEY)
        Log.i(TAG, "Guarding a BioKey system settings screen")
        shield.show(SELF_PROTECTION_PENDING_KEY) {
            launchLockScreen(SELF_PROTECTION_PENDING_KEY, isSelfProtection = true)
        }
        launchLockScreen(SELF_PROTECTION_PENDING_KEY, isSelfProtection = true)
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

    private fun isSystemOrOverlay(pkg: String, cls: String): Boolean {
        if (pkg == VIRTUAL_PKG_GOOGLE_PASSWORDS) return false
        if (pkg in SYSTEM_OVERLAYS) return true
        refreshSystemLists()
        if (pkg in imePackages) return true
        // The device credential screen BiometricPrompt opens for PIN/pattern fallback (Pixel, Samsung, Xiaomi)
        if (pkg == "com.android.settings" || pkg == "com.miui.securitycenter") {
            val lower = cls.lowercase()
            return lower.contains("confirmdevicecredential") ||
                lower.contains("confirmlockpassword") ||
                lower.contains("chooselockpassword") ||
                lower.contains("confirmcommonpassword")
        }
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
        // No readable active window isn't proof the app left: fall back to the last app seen in
        // front (BioKey's lock screen never replaces it).
        val foreground = rootInActiveWindow?.let { root -> root.packageName?.toString().also { release(root) } }
            ?: lastFocusedPackage
        val stillExposed = foreground != null && (sameApp(foreground, target) || foreground == PKG_GMS && target == VIRTUAL_PKG_GOOGLE_PASSWORDS)
        if (!stillExposed || LockStateTracker.isAppUnlocked(target, vault.getTimeoutMode())) return false
        Log.w(TAG, "Lock screen missing after shield timeout (attempt $attempt); relaunching")
        LockStateTracker.clearRegularLockPending(target)
        LockStateTracker.setRegularLockPending(target)
        val bounds = getTargetWindowBounds(target)
        launchLockScreen(target, isSelfProtection = false, bounds = bounds)
        return true
    }

    private fun launchLockScreen(targetPkg: String, isSelfProtection: Boolean, bounds: Rect? = null) {
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

        try {
            val creatorOptions = ActivityOptions.makeBasic()
            val sendOptions = ActivityOptions.makeBasic()
            if (bounds != null && !bounds.isEmpty) {
                creatorOptions.setLaunchBounds(bounds)
                sendOptions.setLaunchBounds(bounds)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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
            Log.w(TAG, "PendingIntent launch failed, trying direct start: ${e.message}")
        }

        try {
            val options = if (bounds != null && !bounds.isEmpty) {
                ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle()
            } else null
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
        LockStateTracker.removeLockAllListener(onLockAll)
        // Nothing enforces sessions while the service is gone, so none may outlive it.
        LockStateTracker.lockAll()
        if (::shield.isInitialized) shield.hide()
        if (::vault.isInitialized && vault.isSetupCompleted()) {
            SecurityEventLog.record(this, SecurityEventLog.Type.ACCESSIBILITY_STOPPED)
            ProtectionAlerts.serviceStopped(this)
        }
        Log.w(TAG, "Accessibility service stopped")
    }
}
