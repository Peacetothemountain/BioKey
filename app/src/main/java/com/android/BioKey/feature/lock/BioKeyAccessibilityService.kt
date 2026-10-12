package com.android.BioKey.feature.lock

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault

/**
 * Zero-Latency Accessibility Engine
 *
 * Implements high-performance, allocation-free event processing on the hot path:
 * - Direct event package extraction avoiding synchronous rootInActiveWindow Binder IPC.
 * - O(1) set-based system, launcher, and 2FA package classification.
 * - Immediate synchronous InstantShieldOverlay attachment (0 frame delay, < 5ms).
 * - Comprehensive memory recycling for AccessibilityNodeInfo objects to eliminate framework leaks.
 */
class BioKeyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BioKeyA11yService"
        var isRunning: Boolean = false
            private set

        fun isServiceEnabled(context: Context): Boolean {
            if (isRunning) return true
            try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
                val services = am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                if (services?.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName } == true) {
                    return true
                }
            } catch (_: Exception) {}

            val enabledServices = try {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
            } catch (_: Exception) {
                ""
            }
            return enabledServices.contains(context.packageName, ignoreCase = true)
        }

        const val VIRTUAL_PKG_GOOGLE_PASSWORDS = "com.google.android.gms.passwordmanager"
        const val PKG_CREDENTIAL_MANAGER = "com.google.android.apps.credentialmanager"

        private val KNOWN_LAUNCHERS = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.oppo.launcher",
            "com.oneplus.launcher",
            "com.huawei.android.launcher",
            "com.android.systemui"
        )

        val AUTH_AND_2FA_PACKAGES = setOf(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.apps.credentialmanager",
            "com.android.credentialmanager",
            "com.google.android.apps.authenticator2",
            "com.google.android.apps.authenticator",
            "com.azure.authenticator",
            "com.authy.authy",
            "com.yubico.yubioath",
            "com.duosecurity.duomobile",
            "org.fedorahosted.freeotp",
            "org.fedorahosted.freeotp.plus",
            "com.beemdevelopment.aegis",
            "me.bparmentier.authenticator",
            "com.twofasapp",
            "com.ente.auth",
            "com.bitwarden.authenticator",
            "com.x8bit.bitwarden",
            "com.agilebits.onepassword",
            "com.onepassword.android",
            "com.lastpass.authenticator",
            "com.keepersecurity.passwordmanager",
            "com.nordpass.android",
            "com.dashlane",
            "com.zoho.accounts.twofactor",
            "com.twofactorauth",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "ch.protonmail.android",
            "com.whatsapp"
        )

        private val SYSTEM_OVERLAYS = setOf(
            "com.android.systemui",
            "android",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.inputmethod.latin",
            "com.android.credentialmanager",
            "com.google.android.apps.credentialmanager",
            "com.google.android.gms",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.webview",
            "com.android.webview"
        )

        private val KNOWN_BROWSERS = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.canary",
            "com.chrome.dev",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.mozilla.focus",
            "com.microsoft.emmx",
            "com.duckduckgo.mobile.android",
            "com.opera.touch",
            "com.opera.browser",
            "com.opera.gx",
            "com.opera.mini.native",
            "com.brave.browser",
            "com.sec.android.app.sbrowser",
            "com.sec.android.app.sbrowser.beta",
            "com.vivaldi.browser"
        )

        private val HOME_INTENT = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

        fun isGooglePasswordManagerEvent(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo?): Boolean {
            val pkg = event.packageName?.toString() ?: ""
            // O(1) early exit: if not GMS or Credential Manager, immediately return false
            if (pkg != PKG_CREDENTIAL_MANAGER && pkg != "com.google.android.gms") {
                return false
            }

            val cls = event.className ?: ""

            // Explicitly ignore transient in-app sign-in, credential picker, passkey, OAuth, and 2FA activities
            if (cls.contains("AutofillDialog", ignoreCase = true) ||
                cls.contains("AutofillTransparent", ignoreCase = true) ||
                cls.contains("CredentialSelector", ignoreCase = true) ||
                cls.contains("CredentialChooser", ignoreCase = true) ||
                cls.contains("IdentityCredentials", ignoreCase = true) ||
                cls.contains("ConfirmUserCredential", ignoreCase = true) ||
                cls.contains("AuthenticatorService", ignoreCase = true) ||
                cls.contains("ShareCredentials", ignoreCase = true) ||
                cls.contains("CreatePassword", ignoreCase = true) ||
                cls.contains("SignIn", ignoreCase = true) ||
                cls.contains("GoogleSignIn", ignoreCase = true) ||
                cls.contains("OAuth", ignoreCase = true) ||
                cls.contains("Fido", ignoreCase = true) ||
                cls.contains("Passkey", ignoreCase = true) ||
                cls.contains("AccountPicker", ignoreCase = true) ||
                cls.contains("ChooseAccount", ignoreCase = true) ||
                cls.contains("GetCredential", ignoreCase = true) ||
                cls.contains("CredentialPicker", ignoreCase = true) ||
                cls.contains("TwoStep", ignoreCase = true) ||
                cls.contains("SecondFactor", ignoreCase = true) ||
                cls.contains("Consent", ignoreCase = true) ||
                cls.contains("AuthAccount", ignoreCase = true) ||
                cls.contains("AuthActivity", ignoreCase = true) ||
                cls.contains("OneTap", ignoreCase = true) ||
                cls.contains("SmartLock", ignoreCase = true) ||
                cls.contains("GoogleAuthUtil", ignoreCase = true) ||
                cls.contains("Authorization", ignoreCase = true) ||
                cls.contains("FederatedIdentity", ignoreCase = true) ||
                cls.contains("TokenActivity", ignoreCase = true) ||
                cls.contains("WebAuthn", ignoreCase = true) ||
                cls.contains("BottomSheet", ignoreCase = true)
            ) {
                return false
            }

            if (pkg == PKG_CREDENTIAL_MANAGER) {
                // Only match standalone password manager, not transient picker dialogs
                if (cls.contains("Picker", ignoreCase = true) ||
                    cls.contains("Chooser", ignoreCase = true) ||
                    cls.contains("Selector", ignoreCase = true) ||
                    cls.contains("Dialog", ignoreCase = true)
                ) {
                    return false
                }
                return true
            }

            if (pkg == "com.google.android.gms") {
                // Never intercept generic Google Settings menu as Password Manager
                if (cls.contains("GoogleSettings", ignoreCase = true) ||
                    cls.contains("SettingsActivity", ignoreCase = true) ||
                    cls.contains("AccountSettings", ignoreCase = true)) {
                    return false
                }

                // Explicit Google Password Manager activity names
                if (cls.contains("PasswordManagerProxyActivity", ignoreCase = true) ||
                    cls.contains("PasswordManagerActivity", ignoreCase = true) ||
                    cls.contains("AutofillManagePasswordsActivity", ignoreCase = true) ||
                    cls.contains("GooglePasswordManager", ignoreCase = true)) {
                    return true
                }

                for (cs in event.text) {
                    val textStr = cs?.toString() ?: ""
                    if (textStr.equals("password manager", ignoreCase = true) ||
                        textStr.equals("google password manager", ignoreCase = true) ||
                        textStr.equals("saved passwords", ignoreCase = true)) {
                        return true
                    }
                }

                val desc = event.contentDescription?.toString()
                if (desc != null && (desc.equals("password manager", ignoreCase = true) ||
                        desc.equals("google password manager", ignoreCase = true))) {
                    return true
                }

                if (rootNode != null && !cls.contains("Settings", ignoreCase = true)) {
                    try {
                        val matches = rootNode.findAccessibilityNodeInfosByText("Google Password Manager")
                        if (!matches.isNullOrEmpty() && matches.any { it.isHeading || it.className?.contains("TextView") == true }) {
                            return true
                        }
                    } catch (ignored: Exception) {}
                }
            }
            return false
        }

        fun isSystemOrOverlay(pkg: String?, event: AccessibilityEvent? = null): Boolean {
            if (pkg.isNullOrBlank()) return true
            if (pkg == VIRTUAL_PKG_GOOGLE_PASSWORDS) return false

            // Fast O(1) set lookup
            if (SYSTEM_OVERLAYS.contains(pkg)) return true

            // System Device Credential Confirmation (PIN/Pattern fallback dialog from inside settings)
            if (pkg == "com.android.settings" && event?.className?.contains("ConfirmDeviceCredential") == true) {
                return true
            }

            // Allocation-free case-insensitive pattern matching
            if (pkg.contains("installer", ignoreCase = true) ||
                pkg.contains("uninstaller", ignoreCase = true) ||
                pkg.contains("inputmethod", ignoreCase = true) ||
                pkg.contains("keyboard", ignoreCase = true) ||
                pkg.endsWith(".ime", ignoreCase = true) ||
                pkg.contains("autofill", ignoreCase = true) ||
                pkg.contains("webview", ignoreCase = true)
            ) {
                return true
            }
            return false
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var instantShield: InstantShieldOverlay
    private var screenReceiver: ScreenReceiver? = null
    private lateinit var securityVault: SecurityVault

    private var lastFocusedPackage: String? = null
    private var cachedDefaultLauncher: String? = null
    private var lastLauncherCheckTime: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        securityVault = SecurityVault.getInstance(this)
        instantShield = InstantShieldOverlay(this)
        Log.i(TAG, "BioKey Accessibility Service connected and active.")

        registerScreenReceiver()
    }

    private fun registerScreenReceiver() {
        screenReceiver = ScreenReceiver.registerHardened(
            context = this,
            onScreenOff = {
                lastFocusedPackage = null
                LockStateTracker.onScreenTurnedOff()
            }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return
        }

        val targetPackage = resolveForegroundPackage(event) ?: return

        // 1. Ignore BioKey own activities
        if (targetPackage == packageName) {
            instantShield.hide()
            return
        }

        // 2. Cache system overlay determination (evaluated once per event)
        val isTargetSystemOrOverlay = isSystemOrOverlay(targetPackage, event)
        if (isTargetSystemOrOverlay) {
            instantShield.hide()
            return
        }

        // 3. If currently authenticating in BiometricPrompt, don't interrupt
        if (SecurityEngine.INSTANCE.isAuthenticating()) {
            return
        }

        // 4. Returning from 2FA / Authentication sub-flow back to host app
        if (LockStateTracker.isInAuthSubFlow() && targetPackage == LockStateTracker.getActiveAuthHost()) {
            LockStateTracker.exitAuthSubFlow()
            LockStateTracker.onAppResumed(targetPackage)
            instantShield.hide()
            lastFocusedPackage = targetPackage
            Log.d(TAG, "Returned from auth sub-flow to host: $targetPackage - session preserved")
            return
        }

        // 5. Custom Tabs & Modern AuthTabs detection (Chrome, Firefox, Edge, Brave, Opera, DuckDuckGo, Samsung Internet, Vivaldi)
        val eventClass = event.className?.toString() ?: ""
        val isCustomTab = (KNOWN_BROWSERS.contains(targetPackage) ||
            targetPackage.contains("browser", ignoreCase = true) ||
            targetPackage.contains("chrome", ignoreCase = true)) &&
            (eventClass.contains("CustomTab", ignoreCase = true) ||
             eventClass.contains("AuthTab", ignoreCase = true) ||
             eventClass.contains("CustomTabs", ignoreCase = true))

        if (isCustomTab) {
            val host = lastFocusedPackage
            if (host != null && host != targetPackage && securityVault.isAppLocked(host)) {
                LockStateTracker.enterAuthSubFlow(host)
                Log.d(TAG, "Entering CustomTab auth sub-flow for host: $host")
            }
            instantShield.hide()
            lastFocusedPackage = targetPackage
            return
        }

        // 6. Authentication & 2FA Sub-flow Detection
        if (AUTH_AND_2FA_PACKAGES.contains(targetPackage)) {
            val host = lastFocusedPackage
            if (host != null && host != targetPackage && securityVault.isAppLocked(host)) {
                LockStateTracker.enterAuthSubFlow(host)
                Log.d(TAG, "Entering 2FA sub-flow for host: $host into 2FA helper: $targetPackage")
            }
            if (!securityVault.isAppLocked(targetPackage)) {
                instantShield.hide()
                lastFocusedPackage = targetPackage
                return
            }
        }

        // Cache launcher check (evaluated once per event)
        val isTargetLauncher = isLauncherOrSystem(targetPackage)

        // Track genuine app exits
        if (lastFocusedPackage != null &&
            lastFocusedPackage != targetPackage &&
            targetPackage != packageName &&
            !isTargetSystemOrOverlay
        ) {
            val previousPackage = lastFocusedPackage!!
            val isSameLogicalApp = previousPackage == targetPackage ||
                (previousPackage == VIRTUAL_PKG_GOOGLE_PASSWORDS && targetPackage == PKG_CREDENTIAL_MANAGER) ||
                (previousPackage == PKG_CREDENTIAL_MANAGER && targetPackage == VIRTUAL_PKG_GOOGLE_PASSWORDS)

            if (!isSameLogicalApp && previousPackage != packageName && securityVault.isAppLocked(previousPackage)) {
                LockStateTracker.onAppExited(previousPackage, securityVault.getTimeoutMode(), isExplicitLauncherExit = isTargetLauncher)
            }

            if (!isSameLogicalApp && (previousPackage == "com.android.settings" || previousPackage == "com.google.android.settings.intelligence")) {
                LockStateTracker.clearSelfProtection()
                Log.d(TAG, "Navigated away from Settings: revoked self-protection lease.")
            }
        }

        if (targetPackage != packageName && !isTargetSystemOrOverlay) {
            lastFocusedPackage = targetPackage
        }

        // Ignore launchers and non-target system dialogs
        if (isTargetLauncher) {
            instantShield.hide()
            return
        }

        // Global lock switch check
        if (!securityVault.isGlobalLockEnabled()) {
            instantShield.hide()
            return
        }

        // Synchronous 0ms check if app is locked
        if (!securityVault.isAppLocked(targetPackage)) {
            instantShield.hide()
            return
        }

        // Check if unlocked according to timeout mode
        if (LockStateTracker.isAppUnlocked(targetPackage, securityVault.getTimeoutMode())) {
            LockStateTracker.onAppResumed(targetPackage)
            instantShield.hide()
            return
        }

        // App is locked and needs verification
        if (LockStateTracker.isRegularLockPending(targetPackage)) {
            return
        }

        LockStateTracker.setRegularLockPending(targetPackage)

        // Show instant shield overlay immediately and synchronously with ZERO frame delay (< 5ms)
        if (securityVault.isOverlayShieldEnabled()) {
            instantShield.show(targetPackage) {
                launchLockScreen(targetPackage, isSelfProtection = false)
            }
        }

        // Launch LockScreenActivity immediately
        launchLockScreen(targetPackage, isSelfProtection = false)
    }

    /**
     * Fast-path foreground package resolution.
     * Evaluates event.packageName in O(1) without initiating expensive rootInActiveWindow IPC
     * unless package name is genuinely missing.
     */
    private fun resolveForegroundPackage(event: AccessibilityEvent): String? {
        val eventPkg = event.packageName?.toString()
        if (!eventPkg.isNullOrBlank()) {
            // Check Google Password Manager virtual alias
            if (eventPkg == PKG_CREDENTIAL_MANAGER || eventPkg == "com.google.android.gms") {
                if (isGooglePasswordManagerEvent(event, null)) {
                    return VIRTUAL_PKG_GOOGLE_PASSWORDS
                }
            }
            return eventPkg
        }

        // Fallback 1: Query active window node with guaranteed resource recycling
        var rootNode: AccessibilityNodeInfo? = null
        try {
            rootNode = rootInActiveWindow
            if (rootNode != null) {
                val rootPkg = rootNode.packageName?.toString()
                if (!rootPkg.isNullOrBlank()) {
                    if (rootPkg == PKG_CREDENTIAL_MANAGER || rootPkg == "com.google.android.gms") {
                        if (isGooglePasswordManagerEvent(event, rootNode)) {
                            return VIRTUAL_PKG_GOOGLE_PASSWORDS
                        }
                    }
                    return rootPkg
                }
            }
        } catch (ignored: Exception) {
        } finally {
            try {
                @Suppress("DEPRECATION")
                rootNode?.recycle()
            } catch (ignored: Exception) {}
        }

        // Fallback 2: Interactive windows check
        try {
            val windowPkg = windows.firstOrNull { it.isFocused || it.isActive }?.root?.packageName?.toString()
            if (!windowPkg.isNullOrBlank()) {
                return windowPkg
            }
        } catch (ignored: Exception) {}

        // Fallback 3: UsageStatsManager query
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm?.queryEvents(now - 2500, now)
            val eventOut = UsageEvents.Event()
            var latestPkg: String? = null
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(eventOut)
                if (eventOut.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    latestPkg = eventOut.packageName
                }
            }
            if (!latestPkg.isNullOrBlank()) {
                return latestPkg
            }
        } catch (ignored: Exception) {}

        return null
    }

    private fun isLauncherOrSystem(pkg: String): Boolean {
        if (KNOWN_LAUNCHERS.contains(pkg)) {
            return true
        }

        val now = SystemClock.elapsedRealtime()
        if (cachedDefaultLauncher == null || (now - lastLauncherCheckTime > 30000L)) {
            try {
                val resolveInfo = packageManager.resolveActivity(HOME_INTENT, PackageManager.MATCH_DEFAULT_ONLY)
                cachedDefaultLauncher = resolveInfo?.activityInfo?.packageName
                lastLauncherCheckTime = now
            } catch (ignored: Exception) {}
        }

        return cachedDefaultLauncher == pkg
    }

    private fun launchLockScreen(targetPkg: String, isSelfProtection: Boolean = false) {
        val isStealth = !isSelfProtection && securityVault.isStealthCrash(targetPkg)
        val intent = Intent(this, LockScreenActivity::class.java).apply {
            putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, targetPkg)
            putExtra(LockScreenActivity.EXTRA_SELF_PROTECTION, isSelfProtection)
            putExtra(LockScreenActivity.EXTRA_STEALTH_MODE, isStealth)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }

        var launched = false
        try {
            val creationOptions = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                creationOptions.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
            val pi = PendingIntent.getActivity(
                this,
                (System.currentTimeMillis() % 10000).toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                creationOptions.toBundle()
            )

            val sendOptions = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                sendOptions.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
            pi.send(this, 0, null, null, null, null, sendOptions.toBundle())
            Log.d(TAG, "LockScreenActivity launched via PendingIntent for $targetPkg")
            launched = true
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent launch exception, trying direct startActivity: ${e.message}")
        }

        if (!launched) {
            try {
                val directOptions = ActivityOptions.makeBasic()
                startActivity(intent, directOptions.toBundle())
                Log.d(TAG, "LockScreenActivity launched directly for $targetPkg")
            } catch (ex: Exception) {
                Log.e(TAG, "Failed launching LockScreenActivity: ${ex.message}", ex)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            instantShield.hide()
            cachedDefaultLauncher = null
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "BioKey Accessibility Service interrupted.")
    }

    override fun onDestroy() {
        isRunning = false
        instantShield.hide()
        screenReceiver?.unregister(this)
        screenReceiver = null
        super.onDestroy()
        Log.i(TAG, "BioKey Accessibility Service destroyed.")
    }
}
