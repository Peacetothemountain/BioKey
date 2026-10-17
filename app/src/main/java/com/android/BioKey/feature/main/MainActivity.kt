package com.android.BioKey.feature.main

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectDialogWindow
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.WindowProtection
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.LockScreenUIExpressive
import com.android.BioKey.feature.setup.SetupScreenExpressive
import com.android.BioKey.ui.theme.BioKeyMessages
import com.android.BioKey.ui.theme.BioKeyTheme
import com.android.BioKey.ui.theme.Group
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.Haptics
import kotlinx.coroutines.delay

/**
 * BioKey's main UI. Requires authentication before showing locked apps or settings.
 */
class MainActivity : ComponentActivity() {

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
    private var protectionMode by mutableStateOf(ProtectionEngine.Mode.OFF)
    private var pauseRequested by mutableStateOf(false)
    private var isSetupAuthenticating by mutableStateOf(false)
    private var setupAuthError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowProtection.applyWindowProtections(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pauseRequested = intent?.action == ACTION_PAUSE_PROTECTION

        setContent {
            val setupCompleted by vault.setupCompletedFlow.collectAsStateWithLifecycle()

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

            BioKeyTheme {
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
                    else -> MainScreenExpressive(mode = protectionMode, onPauseRequested = { pauseRequested = true })
                }

                if (setupCompleted && isAuthenticated && pauseRequested) {
                    PauseProtectionDialog(
                        onPause = { duration ->
                            pauseRequested = false
                            if (vault.pauseProtection(duration)) {
                                BioKeyMessages.show("Protection paused", Icons.Default.PauseCircle)
                            }
                        },
                        onDismiss = { pauseRequested = false }
                    )
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
        if (WindowProtection.isMotionEventObscured(ev)) {
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

    /** Starts or stops compatibility mode as needed, then records which engine protects apps. */
    private fun refreshPermissions() {
        if (vault.isSetupCompleted()) ProtectionEngine.refresh(this)
        protectionMode = ProtectionEngine.mode(this)
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
                Haptics.confirm(window.decorView)
                vault.setSetupCompletedSync(true)
                vault.resumeProtection()
                LockStateTracker.onOwnAppUnlocked()
                isAuthenticated = true
            },
            onError = { code, message ->
                isSetupAuthenticating = false
                setupAuthError = when {
                    isUserCancel(code) -> "Biometric confirmation was cancelled. Tap to retry."
                    code == SecurityEngine.ERROR_NO_DEVICE_CREDENTIAL || code == SecurityEngine.ERROR_NO_BIOMETRICS ->
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
                if (code == SecurityEngine.ERROR_LOCKOUT_PERMANENT) {
                    isPermanentLockout = true
                    lockMessage = "Too many attempts. Lock your phone, then unlock it with your PIN, pattern or password to use biometrics again."
                } else {
                    isPermanentLockout = false
                    lockMessage = when {
                        isUserCancel(code) -> null
                        code == SecurityEngine.ERROR_NO_DEVICE_CREDENTIAL || code == SecurityEngine.ERROR_NO_BIOMETRICS ->
                            "Set a screen lock (PIN, pattern, or password) in system settings to use BioKey."
                        else -> message.toString()
                    }
                }
            }
        )
    }

    private fun isUserCancel(code: Int) = SecurityEngine.isUserCancel(code)
}

/** Pause choices, as a sheet matching the rest of BioKey. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PauseProtectionDialog(onPause: (Long?) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        Triple(Icons.Default.Timer, "For 15 minutes", 15 * 60_000L),
        Triple(Icons.Default.Schedule, "For 1 hour", 60 * 60_000L),
        Triple(Icons.Default.PauseCircle, "Until I turn it back on", null)
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ProtectDialogWindow()
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            Text(
                "Pause protection",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 28.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Locked apps open without a check while paused. Timed pauses resume by themselves.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp)
            )
            Spacer(Modifier.height(20.dp))
            Group(count = options.size) { i ->
                val (icon, label, duration) = options[i]
                GroupRow(index = i, count = options.size, title = label, icon = icon, onClick = { onPause(duration) })
            }
        }
    }
}
