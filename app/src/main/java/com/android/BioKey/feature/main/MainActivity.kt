package com.android.BioKey.feature.main

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.BioKey.R
import com.android.BioKey.core.security.IdentityCheckManager
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.DeviceAdminHelper
import com.android.BioKey.feature.setup.SetupScreenExpressive
import com.android.BioKey.ui.theme.ExpressiveTokens
import com.android.BioKey.ui.theme.springPress
import kotlinx.coroutines.delay

/**
 * BioKey's own UI. Once setup is complete it always requires authentication; neither a paused
 * shield nor missing permissions skip the lock (both are exactly when an attacker would want in).
 */
class MainActivity : FragmentActivity() {

    companion object {
        /** Sent by the Quick Settings tile: authenticate, then offer pause options. */
        const val ACTION_PAUSE_PROTECTION = "com.android.BioKey.action.PAUSE_PROTECTION"

        private const val IDLE_CHECK_INTERVAL_MS = 5_000L
    }

    private val vault by lazy { SecurityVault.getInstance(this) }
    private val engine get() = SecurityEngine.INSTANCE

    private var isAuthenticated by mutableStateOf(false)
    private var lockMessage by mutableStateOf<String?>(null)
    private var a11yActive by mutableStateOf(false)
    private var adminActive by mutableStateOf(false)
    private var pauseRequested by mutableStateOf(false)

    /** Gate for protection-reducing changes; see [StepUpAuth]. */
    private val stepUpAuth = StepUpAuth { reason, onAuthorized ->
        if (LockStateTracker.hasActiveStepUpLease()) {
            onAuthorized()
            return@StepUpAuth
        }
        engine.showBiometricPrompt(
            activity = this,
            appName = "BioKey",
            packageName = packageName,
            promptTitle = reason,
            onSuccess = {
                LockStateTracker.onStepUpPromptSuccess()
                onAuthorized()
            },
            onError = { code, message ->
                if (!isUserCancel(code)) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        )
    }

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
            val tamperDetected by vault.tamperDetectedFlow.collectAsStateWithLifecycle()
            val pausedUntil by vault.pausedUntilFlow.collectAsStateWithLifecycle()

            // A timed pause ends the next time anything asks the vault. Ask on time while BioKey is
            // on screen, so its own screens don't keep showing "paused" after the timer is up.
            LaunchedEffect(pausedUntil) {
                if (pausedUntil > 0L) {
                    delay(pausedUntil - System.currentTimeMillis())
                    vault.isGlobalLockEnabled()
                }
            }

            // Poll permissions continuously while BioKey is resumed in foreground
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            LaunchedEffect(lifecycleOwner) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        refreshPermissions()
                        delay(1000L)
                    }
                }
            }

            // Ends the session once BioKey has sat idle on screen past the limit.
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
                    CompositionLocalProvider(LocalStepUpAuth provides stepUpAuth) {
                    when {
                        !setupCompleted -> SetupScreenExpressive(
                            repairMode = false,
                            onSetupComplete = ::completeSetup
                        )
                        !isAuthenticated -> BioKeyLockGate(
                            message = lockMessage,
                            onAuthenticate = { authenticate() },
                            onExit = { finishAffinity() }
                        )
                        !a11yActive -> SetupScreenExpressive(
                            repairMode = true,
                            onSetupComplete = { refreshPermissions() }
                        )
                        else -> MainScreenExpressive()
                    }

                    if (setupCompleted && isAuthenticated) {
                        if (tamperDetected) {
                            TamperDialog(onAcknowledge = vault::acknowledgeTamper)
                        } else if (pauseRequested) {
                            PauseProtectionDialog(
                                onPause = { duration ->
                                    pauseRequested = false
                                    stepUpAuth.require("Pause protection") {
                                        if (vault.pauseProtection(duration)) {
                                            Toast.makeText(this, "Protection paused", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onDismiss = { pauseRequested = false }
                            )
                        }
                    }
                }
            }
        }
    }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_PAUSE_PROTECTION) pauseRequested = true
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()

        // Before setup there's nothing to protect yet; setup itself ends with an authentication.
        if (!vault.isSetupCompleted()) return

        // Coming back from a system page, a prompt, or another app within the session needs no
        // new authentication; see LockStateTracker for when the session ends.
        if (LockStateTracker.isOwnAppUnlocked()) {
            LockStateTracker.onOwnAppResumed()
            isAuthenticated = true
            return
        }
        isAuthenticated = false
        // Let the window settle before showing the prompt (BiometricPrompt needs a resumed host).
        window.decorView.post {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !isAuthenticated) authenticate()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isAuthenticated) LockStateTracker.onOwnAppStopped()
    }

    override fun onDestroy() {
        // Swiped away from Recents or exited: the session ends with the app. (Not on rotation.)
        if (isFinishing) LockStateTracker.lockOwnApp()
        super.onDestroy()
    }

    /**
     * Touches on this window count as activity. One that arrives after the idle limit locks
     * BioKey instead of reaching the screen underneath. Sheets and dialogs live in their own
     * windows and report through [reportsInteraction].
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && isAuthenticated && !LockStateTracker.onOwnAppInteraction()) {
            lockForInactivity()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Shows the lock gate without prompting: the owner may not be there. */
    private fun lockForInactivity() {
        if (!isAuthenticated) return
        LockStateTracker.lockOwnApp()
        isAuthenticated = false
        lockMessage = "Locked after ${LockStateTracker.OWN_APP_IDLE_MS / 60_000} minutes without activity"
    }

    private fun refreshPermissions() {
        a11yActive = BioKeyAccessibilityService.isServiceEnabled(this)
        adminActive = DeviceAdminHelper.isAdminActive(this)
    }

    private fun completeSetup() {
        refreshPermissions()
        authenticate {
            refreshPermissions()
            vault.setSetupCompleted(true)
            // Setup always ends with protection on (resumeProtection() also ends any app sessions).
            vault.resumeProtection("Setup completed")
            LockStateTracker.onOwnAppUnlocked()
        }
    }

    private fun authenticate(onAuthenticated: () -> Unit = {}) {
        if (engine.isAuthenticating()) return
        lockMessage = null
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
                lockMessage = when {
                    isUserCancel(code) -> null
                    code == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ->
                        "Set a screen lock (PIN, pattern, or password) in system settings to use BioKey."
                    else -> message.toString()
                }
                if (!vault.isSetupCompleted() && lockMessage != null) {
                    Toast.makeText(this, lockMessage, Toast.LENGTH_LONG).show()
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
                    "Locked apps open without authentication while protection is paused. " +
                        "Timed pauses resume automatically.",
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

@Composable
private fun TamperDialog(onAcknowledge: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Default.GppMaybe, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Settings were changed outside BioKey") },
        text = {
            Text(
                "BioKey's stored settings no longer match their integrity seal, which usually means " +
                    "another app or a backup restore edited them. Protection and self-protection were " +
                    "switched back on. Check your locked apps and timeout before continuing.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = { Button(onClick = onAcknowledge) { Text("Review settings") } }
    )
}

@Composable
private fun BioKeyLockGate(
    message: String?,
    onAuthenticate: () -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = ExpressiveTokens.Shapes.BentoCardLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .padding(28.dp)
                .fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 36.dp)
            ) {
                Box(
                    modifier = Modifier
                        .height(96.dp)
                        .padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_title_logo_letters),
                        contentDescription = "BioKey",
                        modifier = Modifier.fillMaxHeight(),
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary, BlendMode.Modulate)
                    )
                    Image(
                        painter = painterResource(R.drawable.ic_title_logo_tentacles),
                        contentDescription = null,
                        modifier = Modifier.fillMaxHeight(),
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.tertiary, BlendMode.Modulate)
                    )
                }

                Text(
                    text = "BioKey is locked",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (message != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(36.dp))

                Button(
                    onClick = onAuthenticate,
                    shape = ExpressiveTokens.Shapes.Pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .springPress()
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = "Unlock", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(14.dp))

                TextButton(onClick = onExit, modifier = Modifier.springPress()) {
                    Text(
                        text = "Exit",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
