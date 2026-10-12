package com.android.BioKey.feature.lock

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import com.android.BioKey.core.data.BioKeyPreferences
import com.android.BioKey.core.intruder.IntruderManager
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LockScreenActivity : FragmentActivity() {

    companion object {
        const val EXTRA_LOCKED_PACKAGE = "extra_locked_package"
        const val EXTRA_SELF_PROTECTION = "extra_self_protection"
        const val EXTRA_STEALTH_MODE = "extra_stealth_mode"
    }

    private var lockedPackage: String = ""
    private var isSelfProtection: Boolean = false

    private var appLabelState by mutableStateOf("")
    private var appIconState by mutableStateOf<ImageBitmap?>(null)
    private var auraColorState by mutableStateOf(Color(0xFF3865BA))
    private var isStealthModeState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Window security & tapjacking protections
        com.android.BioKey.core.security.IdentityCheckManager.applyWindowProtections(this)
        window.decorView.filterTouchesWhenObscured = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        lockedPackage = intent.getStringExtra(EXTRA_LOCKED_PACKAGE) ?: ""
        isSelfProtection = intent.getBooleanExtra(EXTRA_SELF_PROTECTION, false)
        isStealthModeState = intent.getBooleanExtra(EXTRA_STEALTH_MODE, false)

        val vault = com.android.BioKey.core.security.SecurityVault.getInstance(this)
        if (!isSelfProtection && (!vault.isGlobalLockEnabled() || (lockedPackage.isNotBlank() && LockStateTracker.isAppUnlocked(lockedPackage, vault.getTimeoutMode())))) {
            InstantShieldOverlay.dismiss()
            finish()
            compatOverridePendingTransition(0, 0)
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHomeAndFinish()
            }
        })

        loadPackageInfo()
        loadPreferences()

        setContent {
            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(this)
            } else {
                darkColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                if (isStealthModeState) {
                    StealthCrashDialog(
                        appName = appLabelState,
                        onSecretUnlockTrigger = { showPrompt() },
                        onCloseApp = { goHomeAndFinish() }
                    )
                } else {
                    LockScreenUIExpressive(
                        appName = appLabelState,
                        appIcon = appIconState,
                        auraColor = auraColorState,
                        onUnlockClicked = { showPrompt() },
                        onCancelClicked = { goHomeAndFinish() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        InstantShieldOverlay.dismiss()
        val vault = com.android.BioKey.core.security.SecurityVault.getInstance(this)
        if (!isSelfProtection && (!vault.isGlobalLockEnabled() || (lockedPackage.isNotBlank() && LockStateTracker.isAppUnlocked(lockedPackage, vault.getTimeoutMode())))) {
            finish()
            compatOverridePendingTransition(0, 0)
            return
        }
        if (!isStealthModeState && !SecurityEngine.INSTANCE.isAuthenticating()) {
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed && !isStealthModeState && !SecurityEngine.INSTANCE.isAuthenticating()) {
                    showPrompt()
                }
            }, 150)
        }
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        InstantShieldOverlay.dismiss()
        val newPkg = intent.getStringExtra(EXTRA_LOCKED_PACKAGE) ?: ""
        val newSelfProtection = intent.getBooleanExtra(EXTRA_SELF_PROTECTION, false)
        val vault = com.android.BioKey.core.security.SecurityVault.getInstance(this)
        if (!newSelfProtection && (!vault.isGlobalLockEnabled() || (newPkg.isNotBlank() && LockStateTracker.isAppUnlocked(newPkg, vault.getTimeoutMode())))) {
            finish()
            compatOverridePendingTransition(0, 0)
            return
        }
        if (newPkg == lockedPackage && SecurityEngine.INSTANCE.isAuthenticating()) {
            return
        }
        lockedPackage = newPkg
        isSelfProtection = newSelfProtection
        isStealthModeState = intent.getBooleanExtra(EXTRA_STEALTH_MODE, false)
        loadPackageInfo()
        loadPreferences()
        if (!isStealthModeState && !SecurityEngine.INSTANCE.isAuthenticating()) {
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed && !isStealthModeState && !SecurityEngine.INSTANCE.isAuthenticating()) {
                    showPrompt()
                }
            }, 150)
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        InstantShieldOverlay.dismiss()
        val vault = com.android.BioKey.core.security.SecurityVault.getInstance(this)
        if (!LockStateTracker.isAppUnlocked(lockedPackage, vault.getTimeoutMode()) && !SecurityEngine.INSTANCE.isAuthenticating()) {
            // User exited lock screen without authenticating (e.g. Home gesture or task switch)
            if (LockStateTracker.getFailedAttempts(lockedPackage) > 0) {
                IntruderManager.captureIntruderPhotoNow(applicationContext, lockedPackage)
            }
            LockStateTracker.clearRegularLockPending(lockedPackage)
            if (isSelfProtection) {
                LockStateTracker.clearSelfProtection()
            }
            if (!isFinishing) {
                finish()
                compatOverridePendingTransition(0, 0)
            }
        }
    }

    override fun onDestroy() {
        InstantShieldOverlay.dismiss()
        SecurityEngine.INSTANCE.resetAuthenticatingState()
        super.onDestroy()
    }

    private fun loadPreferences() {
        val vault = com.android.BioKey.core.security.SecurityVault.getInstance(this)
        isStealthModeState = isStealthModeState || vault.isStealthModeGlobal() || vault.isStealthCrash(lockedPackage)
    }

    private fun loadPackageInfo() {
        if (lockedPackage.isBlank() || isSelfProtection) {
            appLabelState = "BioKey"
            auraColorState = Color(0xFF3865BA)
            return
        }

        if (lockedPackage == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS ||
            lockedPackage == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER
        ) {
            appLabelState = "Google Password Manager"
            auraColorState = Color(0xFF4285F4)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val pm = packageManager
                    val pkgToLoad = try {
                        pm.getApplicationInfo(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER, 0)
                        BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER
                    } catch (e: Exception) {
                        "com.google.android.gms"
                    }
                    val appInfo = pm.getApplicationInfo(pkgToLoad, 0)
                    val drawable = pm.getApplicationIcon(appInfo)
                    val bitmap = drawableToBitmap(drawable)
                    withContext(Dispatchers.Main) {
                        appIconState = bitmap.asImageBitmap()
                    }
                } catch (e: Exception) {
                    // Fallback will use default lock icon
                }
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(lockedPackage, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                val drawable = pm.getApplicationIcon(appInfo)
                val bitmap = drawableToBitmap(drawable)
                val imageBitmap = bitmap.asImageBitmap()

                // Extract vibrant aura color using Palette
                val palette = Palette.from(bitmap).generate()
                val dominantColor = palette.getVibrantColor(
                    palette.getDominantColor(0xFF3865BA.toInt())
                )

                withContext(Dispatchers.Main) {
                    appLabelState = label
                    appIconState = imageBitmap
                    auraColorState = Color(dominantColor)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                withContext(Dispatchers.Main) {
                    appLabelState = lockedPackage
                    auraColorState = Color(0xFF3865BA)
                }
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 192
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 192
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun showPrompt() {
        SecurityEngine.INSTANCE.showBiometricPrompt(
            activity = this,
            appName = appLabelState,
            packageName = lockedPackage,
            onSuccess = {
                if (isSelfProtection) {
                    LockStateTracker.setSelfProtectionGranted(true)
                } else {
                    LockStateTracker.onAppUnlocked(lockedPackage)
                    LockStateTracker.clearRegularLockPending(lockedPackage)
                }
                finish()
                compatOverridePendingTransition(0, 0)
            },
            onFailure = {
                // Haptic error or UI feedback
            },
            onError = { _, _ ->
                // Keep LockScreenUIExpressive active and interactive
            }
        )
    }

    private fun goHomeAndFinish() {
        if (LockStateTracker.getFailedAttempts(lockedPackage) > 0) {
            IntruderManager.captureIntruderPhotoNow(applicationContext, lockedPackage)
        }
        if (isSelfProtection) {
            LockStateTracker.clearSelfProtection()
        } else {
            LockStateTracker.clearRegularLockPending(lockedPackage)
        }
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
        compatOverridePendingTransition(0, 0)
    }

    private fun compatOverridePendingTransition(enterAnim: Int, exitAnim: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, enterAnim, exitAnim)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(enterAnim, exitAnim)
        }
    }
}
