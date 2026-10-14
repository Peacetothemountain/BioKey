package com.android.BioKey.feature.lock

import android.app.ActivityOptions
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.android.BioKey.core.security.IdentityCheckManager
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Covers a locked app (or a BioKey system-settings page) until the owner authenticates.
 *
 * Not exported: only BioKey's own components can start it.
 */
class LockScreenActivity : FragmentActivity() {

    companion object {
        private const val TAG = "LockScreenActivity"
        const val EXTRA_LOCKED_PACKAGE = "extra_locked_package"
        const val EXTRA_SELF_PROTECTION = "extra_self_protection"
        /** Launch the locked app after a successful unlock (used by redacted notifications). */
        const val EXTRA_LAUNCH_AFTER_UNLOCK = "extra_launch_after_unlock"

        @Volatile
        var isLockScreenResumed: Boolean = false
            private set

        @Volatile
        var activeLockedTarget: String? = null
            private set
    }

    private lateinit var vault: SecurityVault

    private var lockedPackage: String = ""
    private var isSelfProtection = false
    private var launchAfterUnlock = false
    private var promptAutoLaunched = false
    private var shieldDismissTrigger by mutableStateOf(0)
    private var packageInfoJob: Job? = null

    private var appLabelState by mutableStateOf("")
    private var appIconState by mutableStateOf<ImageBitmap?>(null)
    private var statusMessage by mutableStateOf<String?>(null)
    private var isPermanentLockout by mutableStateOf(false)

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null && IdentityCheckManager.isMotionEventObscured(ev)) {
            Log.w(TAG, "Blocked obscured touch event on LockScreenActivity")
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun resolveDynamicScheme() =
        if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
            dynamicDarkColorScheme(this)
        else
            dynamicLightColorScheme(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        IdentityCheckManager.applyWindowProtections(this)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashScreenView ->
                splashScreenView.remove()
            }
        }
        setShowWhenLocked(true)
        window.setBackgroundDrawable(ColorDrawable(resolveDynamicScheme().surface.toArgb()))
        vault = SecurityVault.getInstance(this)

        if (!readIntent(intent)) {
            finishSilently()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHomeAndFinish()
        })

        setContent {
            val isDark = isSystemInDarkTheme()
            val colorScheme = if (isDark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            LaunchedEffect(lockedPackage, shieldDismissTrigger) {
                withFrameNanos { }
                window.decorView.post {
                    InstantShieldOverlay.dismiss(lockedPackage)
                }
            }
            MaterialTheme(
                colorScheme = colorScheme,
                shapes = com.android.BioKey.ui.theme.M3XShapes.toMaterial3Shapes(),
                typography = com.android.BioKey.ui.theme.M3XDefaultTypography.toMaterial3Typography()
            ) {
                com.android.BioKey.ui.theme.ProvideExpressiveHaptics {
                    LockScreenUIExpressive(
                        appName = appLabelState,
                        appIcon = appIconState,
                        onUnlockClicked = ::showPrompt,
                        onCancelClicked = ::goHomeAndFinish,
                        isBioKeyLock = isSelfProtection,
                        statusMessage = statusMessage,
                        isPermanentLockout = isPermanentLockout,
                        onLockDeviceClicked = ::lockDeviceOrHome
                    )
                }
            }
        }
    }

    /** Applies an intent's target. Returns false if there is nothing left to protect. */
    private fun readIntent(intent: Intent): Boolean {
        val pkg = intent.getStringExtra(EXTRA_LOCKED_PACKAGE).orEmpty()
        val selfProtection = intent.getBooleanExtra(EXTRA_SELF_PROTECTION, false)
        if (pkg.isBlank()) return false
        if (!selfProtection && (!vault.isGlobalLockEnabled() || LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg)))) {
            // Nothing to unlock; still honour a tap on a hidden-notification stand-in.
            if (intent.getBooleanExtra(EXTRA_LAUNCH_AFTER_UNLOCK, false)) sendPendingDeepLink(pkg)
            return false
        }
        lockedPackage = pkg
        activeLockedTarget = pkg
        isSelfProtection = selfProtection
        launchAfterUnlock = intent.getBooleanExtra(EXTRA_LAUNCH_AFTER_UNLOCK, false)
        promptAutoLaunched = false
        statusMessage = null
        isPermanentLockout = false
        shieldDismissTrigger++

        if (isSelfProtection) {
            appLabelState = "BioKey"
            appIconState = null
        } else {
            val cached = AppInfoCache.get(pkg) ?: AppInfoCache.loadAndCache(this, packageManager, pkg)
            if (cached != null) {
                appLabelState = cached.label
                appIconState = cached.iconBitmap
            } else {
                loadPackageInfo()
            }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        isLockScreenResumed = true
        activeLockedTarget = lockedPackage
        if (!isSelfProtection && (!vault.isGlobalLockEnabled() || LockStateTracker.isAppUnlocked(lockedPackage, vault.getTimeoutMode(lockedPackage)))) {
            finishSilently()
            return
        }
        if (!promptAutoLaunched && !isPermanentLockout && !SecurityEngine.INSTANCE.isAuthenticatingFor(lockedPackage)) {
            promptAutoLaunched = true
            schedulePrompt()
        }
    }

    override fun onPause() {
        isLockScreenResumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !promptAutoLaunched && !isPermanentLockout && !SecurityEngine.INSTANCE.isAuthenticatingFor(lockedPackage)) {
            promptAutoLaunched = true
            schedulePrompt()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newPkg = intent.getStringExtra(EXTRA_LOCKED_PACKAGE).orEmpty()
        val newSelfProt = intent.getBooleanExtra(EXTRA_SELF_PROTECTION, false)
        if (newPkg == lockedPackage && newSelfProt == isSelfProtection) {
            // Same target re-ordered to front (e.g., over a trampoline activity): keep active prompt alive
            launchAfterUnlock = launchAfterUnlock || intent.getBooleanExtra(EXTRA_LAUNCH_AFTER_UNLOCK, false)
            shieldDismissTrigger++
            window.decorView.post { InstantShieldOverlay.dismiss(lockedPackage) }
            if (!SecurityEngine.INSTANCE.isAuthenticatingFor(lockedPackage) && !promptAutoLaunched && !isPermanentLockout) {
                promptAutoLaunched = true
                schedulePrompt()
            }
            return
        }
        // Switched to a different locked target: cancel previous prompt and retarget cleanly
        SecurityEngine.INSTANCE.resetAuthenticatingState(lockedPackage)
        setIntent(intent)
        if (!readIntent(intent)) {
            finishSilently()
            return
        }
        promptAutoLaunched = true
        schedulePrompt()
    }

    override fun onStop() {
        isLockScreenResumed = false
        super.onStop()
        if (isFinishing || isChangingConfigurations) return
        // Do not destroy the lock screen while system ConfirmDeviceCredentialActivity (PIN/Pattern fallback) is in front
        if (BioKeyAccessibilityService.isConfirmingDeviceCredential) return
        // Keep LockScreenActivity alive on top when the screen turns off so zero frames of the locked target are exposed on wake
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm?.isInteractive == false) {
            SecurityEngine.INSTANCE.resetAuthenticatingState(lockedPackage)
            promptAutoLaunched = false
            return
        }
        SecurityEngine.INSTANCE.resetAuthenticatingState(lockedPackage)
        val unlocked = if (isSelfProtection) LockStateTracker.isSelfProtectionGranted()
        else LockStateTracker.isAppUnlocked(lockedPackage, vault.getTimeoutMode(lockedPackage))
        if (!unlocked) {
            abandon()
            finishSilently()
        }
    }

    override fun onDestroy() {
        isLockScreenResumed = false
        if (activeLockedTarget == lockedPackage) activeLockedTarget = null
        if (!isChangingConfigurations) {
            InstantShieldOverlay.dismiss(lockedPackage)
        }
        SecurityEngine.INSTANCE.resetAuthenticatingState(lockedPackage)
        super.onDestroy()
    }

    private fun schedulePrompt() {
        window.decorView.post {
            if (!isFinishing && !isDestroyed && !isPermanentLockout && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                showPrompt()
            }
        }
    }

    private fun showPrompt() {
        val pkg = lockedPackage
        val selfProtection = isSelfProtection
        val launchAfter = launchAfterUnlock
        statusMessage = null
        SecurityEngine.INSTANCE.showBiometricPrompt(
            activity = this,
            appName = if (selfProtection) "BioKey" else appLabelState,
            packageName = pkg,
            onSuccess = {
                if (selfProtection) {
                    LockStateTracker.setSelfProtectionGranted(granted = true, visitedGuardedScreen = true)
                } else {
                    val deepLinkTarget = if (launchAfter) {
                        com.android.BioKey.feature.notification.BioKeyNotificationShieldService.cachedPendingIntents.remove(pkg)
                    } else null
                    LockStateTracker.onAppUnlocked(pkg)
                    if (launchAfter) {
                        sendPendingDeepLink(pkg, deepLinkTarget)
                    }
                }
                LockStateTracker.clearRegularLockPending(pkg)
                finishSilently()
            },
            onError = { code, message ->
                if (code == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                    isPermanentLockout = true
                    statusMessage = "Biometric sensor locked by Titan M2 hardware policy. Please lock your device screen and unlock with PIN to reset."
                } else if (code == BiometricPrompt.ERROR_LOCKOUT) {
                    isPermanentLockout = false
                    statusMessage = message.takeIf { it.isNotBlank() }?.toString()
                        ?: "Too many attempts. Sensor temporarily locked for 30 seconds. Tap Unlock to try again or use PIN."
                } else {
                    statusMessage = when (code) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> null
                        else -> message.toString()
                    }
                }
            }
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.setBackgroundDrawable(ColorDrawable(resolveDynamicScheme().surface.toArgb()))
    }

    private fun lockDeviceOrHome() {
        abandon()
        val locked = BioKeyAccessibilityService.lockDevice()
        if (!locked) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finishSilently()
    }

    private fun launchApp(pkg: String) {
        val realPkg = if (pkg == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS) BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER else pkg
        packageManager.getLaunchIntentForPackage(realPkg)?.let { launch ->
            runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun sendPendingDeepLink(
        pkg: String,
        preExtractedMap: Map<String, com.android.BioKey.feature.notification.BioKeyNotificationShieldService.CachedPendingIntent>? = null
    ) {
        val intentsMap = preExtractedMap
            ?: com.android.BioKey.feature.notification.BioKeyNotificationShieldService.cachedPendingIntents.remove(pkg)
        val target = intentsMap?.values
            ?.filter { !it.isSummary }
            ?.maxByOrNull { it.postTime }
            ?: intentsMap?.values?.maxByOrNull { it.postTime }
        val pending = target?.pendingIntent
        val realPkg = if (pkg == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS) BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER else pkg
        val creator = pending?.creatorPackage
        val validCreator = creator == null || creator == realPkg || creator == pkg ||
            (pkg == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS && creator == "com.google.android.gms")

        if (pending != null && validCreator) {
            val options = if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.makeBasic().apply {
                    pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                }.toBundle()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic().apply {
                    @Suppress("DEPRECATION")
                    pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }.toBundle()
            } else null
            runCatching {
                if (options != null) {
                    pending.send(this, 0, null, null, null, null, options)
                } else {
                    pending.send(this, 0, null)
                }
            }.onFailure {
                Log.w(TAG, "Failed sending deep link PendingIntent for $pkg, falling back to launch: ${it.message}")
                launchApp(pkg)
            }
        } else {
            launchApp(pkg)
        }
    }

    /** Bookkeeping when the user walks away from the lock without authenticating. */
    private fun abandon() {
        LockStateTracker.markLockAbandoned(lockedPackage)
        if (isSelfProtection) {
            LockStateTracker.clearSelfProtection()
        }
    }

    private fun goHomeAndFinish() {
        abandon()
        if (!isSelfProtection || !BioKeyAccessibilityService.dismissSelfProtection()) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finishSilently()
    }

    private fun finishSilently() {
        InstantShieldOverlay.dismiss(lockedPackage)
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    // ---------------------------------------------------------------------------------------
    // App label and icon
    // ---------------------------------------------------------------------------------------

    private fun loadPackageInfo() {
        packageInfoJob?.cancel()
        appIconState = null
        if (isSelfProtection) {
            appLabelState = "BioKey"
            return
        }
        val pkg = lockedPackage
        val isPasswordManager = pkg == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS ||
            pkg == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER
        appLabelState = if (isPasswordManager) "Google Password Manager" else ""

        packageInfoJob = lifecycleScope.launch(Dispatchers.IO) {
            val loaded = AppInfoCache.loadAndCache(this@LockScreenActivity, packageManager, pkg)
            withContext(Dispatchers.Main) {
                if (pkg != lockedPackage) return@withContext
                if (loaded != null) {
                    appLabelState = loaded.label
                    appIconState = loaded.iconBitmap
                } else if (appLabelState.isBlank()) {
                    appLabelState = "Protected Application"
                }
            }
        }
    }
}
