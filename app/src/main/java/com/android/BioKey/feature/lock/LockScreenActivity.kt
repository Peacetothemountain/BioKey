package com.android.BioKey.feature.lock

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import com.android.BioKey.core.intruder.IntruderManager
import com.android.BioKey.core.security.IdentityCheckManager
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityEventLog
import com.android.BioKey.core.security.SecurityVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        const val EXTRA_STEALTH_MODE = "extra_stealth_mode"
        /** Launch the locked app after a successful unlock (used by redacted notifications). */
        const val EXTRA_LAUNCH_AFTER_UNLOCK = "extra_launch_after_unlock"
        private const val DEFAULT_AURA_ARGB = 0xFF3865BA.toInt()
        private val DEFAULT_AURA = Color(DEFAULT_AURA_ARGB)
    }

    private lateinit var vault: SecurityVault

    private var lockedPackage: String = ""
    private var isSelfProtection = false
    private var launchAfterUnlock = false
    private var deferredIntent: Intent? = null
    private var packageInfoJob: Job? = null

    private var appLabelState by mutableStateOf("")
    private var appIconState by mutableStateOf<ImageBitmap?>(null)
    private var auraColorState by mutableStateOf(DEFAULT_AURA)
    private var isStealthModeState by mutableStateOf(false)
    private var statusMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        IdentityCheckManager.applyWindowProtections(this)
        setShowWhenLocked(true)
        vault = SecurityVault.getInstance(this)

        if (!readIntent(intent)) {
            finishSilently()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHomeAndFinish()
        })

        dismissShieldAfterFirstFrame()

        setContent {
            val colorScheme = remember { dynamicDarkColorScheme(this) }
            MaterialTheme(colorScheme = colorScheme) {
                if (isStealthModeState) {
                    StealthCrashDialog(
                        appName = appLabelState,
                        onSecretUnlockTrigger = ::showPrompt,
                        onCloseApp = ::goHomeAndFinish
                    )
                } else {
                    LockScreenUIExpressive(
                        appName = appLabelState,
                        appIcon = appIconState,
                        auraColor = auraColorState,
                        onUnlockClicked = ::showPrompt,
                        onCancelClicked = ::goHomeAndFinish,
                        isBioKeyLock = isSelfProtection,
                        statusMessage = statusMessage
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
        if (!selfProtection && (!vault.isGlobalLockEnabled() || LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode()))) {
            // Nothing to unlock; still honour a tap on a hidden-notification stand-in.
            if (intent.getBooleanExtra(EXTRA_LAUNCH_AFTER_UNLOCK, false)) launchApp(pkg)
            return false
        }
        lockedPackage = pkg
        isSelfProtection = selfProtection
        launchAfterUnlock = intent.getBooleanExtra(EXTRA_LAUNCH_AFTER_UNLOCK, false)
        isStealthModeState = !selfProtection &&
            (intent.getBooleanExtra(EXTRA_STEALTH_MODE, false) || vault.isStealthCrash(pkg))
        statusMessage = null
        loadPackageInfo()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (!isSelfProtection && (!vault.isGlobalLockEnabled() || LockStateTracker.isAppUnlocked(lockedPackage, vault.getTimeoutMode()))) {
            finishSilently()
            return
        }
        schedulePrompt()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newPkg = intent.getStringExtra(EXTRA_LOCKED_PACKAGE).orEmpty()
        // Never retarget a prompt that's already showing: a success for one app must not unlock another.
        if (SecurityEngine.INSTANCE.isAuthenticating()) {
            if (newPkg != lockedPackage) deferredIntent = intent
            return
        }
        setIntent(intent)
        if (!readIntent(intent)) {
            finishSilently()
            return
        }
        dismissShieldAfterFirstFrame()
        schedulePrompt()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing || isChangingConfigurations) return
        val unlocked = if (isSelfProtection) LockStateTracker.isSelfProtectionGranted()
        else LockStateTracker.isAppUnlocked(lockedPackage, vault.getTimeoutMode())
        if (!unlocked) {
            if (SecurityEngine.INSTANCE.isAuthenticatingFor(lockedPackage)) {
                SecurityEngine.INSTANCE.resetAuthenticatingState()
            }
            abandon()
            finishSilently()
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            InstantShieldOverlay.dismiss()
            if (SecurityEngine.INSTANCE.isAuthenticatingFor(lockedPackage)) {
                SecurityEngine.INSTANCE.resetAuthenticatingState()
            }
        }
        super.onDestroy()
    }

    /** Remove the shield only once this window has actually drawn, so nothing leaks in between. */
    private fun dismissShieldAfterFirstFrame() {
        val decor = window.decorView
        if (decor.isAttachedToWindow && decor.width > 0 && decor.height > 0) {
            decor.postOnAnimation { InstantShieldOverlay.dismiss() }
            return
        }
        val listener = object : ViewTreeObserver.OnDrawListener {
            private var done = false
            override fun onDraw() {
                if (done) return
                done = true
                decor.postOnAnimation {
                    InstantShieldOverlay.dismiss()
                    decor.viewTreeObserver.removeOnDrawListener(this)
                }
            }
        }
        decor.viewTreeObserver.addOnDrawListener(listener)
        decor.invalidate()
    }

    private fun schedulePrompt() {
        if (isStealthModeState || SecurityEngine.INSTANCE.isAuthenticating()) return
        lifecycleScope.launch {
            delay(150)
            if (!isFinishing && !isDestroyed && !isStealthModeState && !SecurityEngine.INSTANCE.isAuthenticating()) {
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
                    LockStateTracker.setSelfProtectionGranted(true)
                } else {
                    LockStateTracker.onAppUnlocked(pkg)
                    if (launchAfter) {
                        val pending = com.android.BioKey.feature.notification.BioKeyNotificationShieldService.cachedPendingIntents.remove(pkg)
                        if (pending != null) {
                            runCatching { pending.send() }.onFailure { launchApp(pkg) }
                        } else {
                            launchApp(pkg)
                        }
                    }
                }
                LockStateTracker.clearRegularLockPending(pkg)
                if (!processDeferredIntent()) finishSilently()
            },
            onError = { code, message ->
                if (processDeferredIntent()) return@showBiometricPrompt
                statusMessage = when (code) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> null
                    else -> message.toString()
                }
            }
        )
    }

    private fun processDeferredIntent(): Boolean {
        val next = deferredIntent ?: return false
        deferredIntent = null
        setIntent(next)
        if (!readIntent(next)) return false
        schedulePrompt()
        return true
    }

    private fun launchApp(pkg: String) {
        val realPkg = if (pkg == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS) BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER else pkg
        packageManager.getLaunchIntentForPackage(realPkg)?.let { launch ->
            runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Bookkeeping when the user walks away from the lock without authenticating. */
    private fun abandon() {
        if (LockStateTracker.getFailedAttempts(lockedPackage) > 0) {
            IntruderManager.captureIntruderPhotoNow(applicationContext, lockedPackage)
        }
        LockStateTracker.clearRegularLockPending(lockedPackage)
        if (isSelfProtection) {
            LockStateTracker.clearSelfProtection()
            SecurityEventLog.record(this, SecurityEventLog.Type.SELF_PROTECTION_CHALLENGE, "Challenge dismissed without authenticating")
        }
    }

    private fun goHomeAndFinish() {
        abandon()
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finishSilently()
    }

    private fun finishSilently() {
        InstantShieldOverlay.dismiss()
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    // ---------------------------------------------------------------------------------------
    // App label, icon and accent colour
    // ---------------------------------------------------------------------------------------

    private fun loadPackageInfo() {
        packageInfoJob?.cancel()
        appIconState = null
        auraColorState = DEFAULT_AURA
        if (isSelfProtection) {
            appLabelState = "BioKey"
            return
        }
        val pkg = lockedPackage
        val isPasswordManager = pkg == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS ||
            pkg == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER
        appLabelState = if (isPasswordManager) "Google Password Manager" else ""

        packageInfoJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pm = packageManager
                val realPkg = if (isPasswordManager) {
                    runCatching { pm.getApplicationInfo(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER, 0) }
                        .map { BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER }
                        .getOrDefault("com.google.android.gms")
                } else {
                    pkg
                }
                val appInfo = pm.getApplicationInfo(realPkg, 0)
                val label = if (isPasswordManager) "Google Password Manager" else pm.getApplicationLabel(appInfo).toString()
                val bitmap = drawableToBitmap(pm.getApplicationIcon(appInfo))
                val palette = Palette.from(bitmap).generate()
                val accent = palette.getVibrantColor(palette.getDominantColor(DEFAULT_AURA_ARGB))
                withContext(Dispatchers.Main) {
                    if (pkg != lockedPackage) return@withContext
                    appLabelState = label
                    appIconState = bitmap.asImageBitmap()
                    auraColorState = Color(accent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "App info unavailable: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (pkg == lockedPackage && appLabelState.isBlank()) appLabelState = "Protected app"
                }
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val size = 192
        val width = drawable.intrinsicWidth.takeIf { it in 1..512 } ?: size
        val height = drawable.intrinsicHeight.takeIf { it in 1..512 } ?: size
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }
}
