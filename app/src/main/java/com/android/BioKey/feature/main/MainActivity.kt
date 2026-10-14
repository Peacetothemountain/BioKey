package com.android.BioKey.feature.main

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.BioKey.core.security.IdentityCheckManager
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.LockScreenUIExpressive
import com.android.BioKey.feature.setup.SetupScreenExpressive
import kotlinx.coroutines.delay

/**
 * BioKey's main UI. Requires authentication before showing locked apps or settings.
 */
class MainActivity : FragmentActivity() {

    companion object {
        const val ACTION_PAUSE_PROTECTION = "com.android.BioKey.action.PAUSE_PROTECTION"
        private const val IDLE_CHECK_INTERVAL_MS = 5_000L
    }

    private val vault by lazy { SecurityVault.getInstance(this) }
    private val engine get() = SecurityEngine.INSTANCE

    private var isAuthenticated by mutableStateOf(false)
    private var promptAutoLaunched = false
    private var lockMessage by mutableStateOf<String?>(null)
    private var isPermanentLockout by mutableStateOf(false)
    private var a11yActive by mutableStateOf(false)
    private var pauseRequested by mutableStateOf(false)
    private var isSetupAuthenticating by mutableStateOf(false)
    private var setupAuthError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        IdentityCheckManager.applyWindowProtections(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pauseRequested = intent?.action == ACTION_PAUSE_PROTECTION

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = remember(darkTheme) {
                if (darkTheme) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            }
            val setupCompleted by vault.setupCompletedFlow.collectAsStateWithLifecycle()
            val pausedUntil by vault.pausedUntilFlow.collectAsStateWithLifecycle()

            LaunchedEffect(pausedUntil) {
                if (pausedUntil > 0L) {
                    val remaining = maxOf(0L, pausedUntil - System.currentTimeMillis())
                    delay(remaining)
                    vault.isGlobalLockEnabled()
                }
            }

            androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                refreshPermissions()
                onPauseOrDispose { }
            }

            LaunchedEffect(isAuthenticated) {
                while (isAuthenticated) {
                    delay(IDLE_CHECK_INTERVAL_MS)
                    if (!LockStateTracker.isOwnAppUnlocked()) lockForInactivity()
                }
            }

            MaterialTheme(
                colorScheme = colorScheme,
                shapes = com.android.BioKey.ui.theme.M3XShapes.toMaterial3Shapes(),
                typography = com.android.BioKey.ui.theme.M3XDefaultTypography.toMaterial3Typography()
            ) {
                com.android.BioKey.ui.theme.ProvideExpressiveHaptics {
                    when {
                        !setupCompleted -> SetupScreenExpressive(
                            isAuthenticating = isSetupAuthenticating,
                            authError = setupAuthError,
                            onSetupComplete = ::completeSetup
                        )
                        !isAuthenticated -> LockScreenUIExpressive(
                            appName = "BioKey",
                            appIcon = null,
                            onUnlockClicked = { authenticate() },
                            onCancelClicked = { finishAffinity() },
                            isBioKeyLock = true,
                            statusMessage = lockMessage,
                            isPermanentLockout = isPermanentLockout,
                            onLockDeviceClicked = {
                                if (!BioKeyAccessibilityService.lockDevice()) finishAffinity()
                            }
                        )
                        else -> MainScreenExpressive(
                            a11yActive = a11yActive
                        )
                    }

                    if (setupCompleted && isAuthenticated && pauseRequested) {
                        PauseProtectionDialog(
                            onPause = { duration ->
                                pauseRequested = false
                                if (vault.pauseProtection(duration)) {
                                    Toast.makeText(this, "Protection paused", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDismiss = { pauseRequested = false }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pauseRequested = intent.action == ACTION_PAUSE_PROTECTION
        if (pauseRequested && !LockStateTracker.hasActiveOwnerSession()) {
            LockStateTracker.lockOwnApp()
            isAuthenticated = false
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()

        if (!vault.isSetupCompleted()) return

        if (LockStateTracker.isOwnAppUnlocked()) {
            LockStateTracker.onOwnAppResumed()
            isAuthenticated = true
            promptAutoLaunched = true
            return
        }
        isAuthenticated = false
        if (!promptAutoLaunched && !isPermanentLockout && !engine.isAuthenticatingFor(packageName)) {
            promptAutoLaunched = true
            window.decorView.post {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !isAuthenticated && !isPermanentLockout) {
                    authenticate()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations || BioKeyAccessibilityService.isConfirmingDeviceCredential) return
        engine.resetAuthenticatingState(packageName)
        promptAutoLaunched = false
        if (LockStateTracker.isExternalNavigationActive()) {
            LockStateTracker.onOwnAppStopped()
        } else {
            LockStateTracker.lockOwnApp()
            isAuthenticated = false
        }
    }

    override fun onDestroy() {
        if (isFinishing) LockStateTracker.lockOwnApp()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (IdentityCheckManager.isMotionEventObscured(ev)) {
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && isAuthenticated && !LockStateTracker.onOwnAppInteraction()) {
            lockForInactivity()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun lockForInactivity() {
        if (!isAuthenticated) return
        LockStateTracker.lockOwnApp()
        isAuthenticated = false
        lockMessage = "Locked after ${LockStateTracker.OWN_APP_IDLE_MS / 60_000} minutes without activity"
    }

    private fun refreshPermissions() {
        a11yActive = BioKeyAccessibilityService.isServiceEnabled(this)
    }

    private fun completeSetup() {
        if (engine.isAuthenticating()) return
        refreshPermissions()
        isSetupAuthenticating = true
        setupAuthError = null

        engine.showBiometricPrompt(
            activity = this,
            appName = "BioKey",
            packageName = packageName,
            promptTitle = "Confirm identity to finish setup",
            onSuccess = {
                isSetupAuthenticating = false
                setupAuthError = null
                refreshPermissions()
                com.android.BioKey.ui.theme.BioKeyHaptics.onUnlockSuccess(this@MainActivity)
                vault.setSetupCompletedSync(true)
                vault.resumeProtection("Setup completed")
                LockStateTracker.onOwnAppUnlocked()
                isAuthenticated = true
            },
            onError = { code, message ->
                isSetupAuthenticating = false
                setupAuthError = when {
                    isUserCancel(code) -> "Biometric confirmation was cancelled. Tap to retry."
                    code == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL || code == BiometricPrompt.ERROR_NO_BIOMETRICS ->
                        "Set a screen lock (PIN, pattern, or password) in system settings first."
                    else -> message.toString()
                }
            }
        )
    }

    private fun authenticate(onAuthenticated: () -> Unit = {}) {
        if (engine.isAuthenticating()) return
        lockMessage = null
        isPermanentLockout = false
        engine.showBiometricPrompt(
            activity = this,
            appName = "BioKey",
            packageName = packageName,
            onSuccess = {
                LockStateTracker.onOwnAppUnlocked()
                isAuthenticated = true
                onAuthenticated()
            },
            onError = { code, message ->
                if (code == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                    isPermanentLockout = true
                    lockMessage = "Biometric sensor locked by Titan M2 hardware policy. Please lock your device screen and unlock with PIN to reset."
                } else {
                    isPermanentLockout = false
                    lockMessage = when {
                        isUserCancel(code) -> null
                        code == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL || code == BiometricPrompt.ERROR_NO_BIOMETRICS ->
                            "Set a screen lock (PIN, pattern, or password) in system settings to use BioKey."
                        else -> message.toString()
                    }
                }
            }
        )
    }

    private fun isUserCancel(code: Int) = code == BiometricPrompt.ERROR_USER_CANCELED ||
        code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
        code == BiometricPrompt.ERROR_CANCELED
}

@Composable
internal fun PauseProtectionDialog(onPause: (Long?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pause protection?") },
        text = {
            Column {
                Text(
                    "Locked apps open without authentication while protection is paused. Timed pauses resume automatically.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                listOf(
                    "For 15 minutes" to 15 * 60_000L,
                    "For 1 hour" to 60 * 60_000L,
                    "Until I turn it back on" to null
                ).forEach { (label, duration) ->
                    TextButton(onClick = { onPause(duration) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
