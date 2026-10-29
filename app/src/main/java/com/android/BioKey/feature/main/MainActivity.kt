package com.android.BioKey.feature.main

import android.content.Context
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.BioKey.R
import com.android.BioKey.core.security.BiometricEnrollment
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectDialogWindow
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.WindowProtection
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.BioKeyLockScreen
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
        /**
         * The Quick Settings tile's way in to the pause sheet: an alias of this activity that isn't
         * exported, so no other app can open the sheet (this activity is, as the launcher entry).
         */
        const val PAUSE_PROTECTION_ALIAS = "com.android.BioKey.feature.main.PauseProtection"
        private const val IDLE_CHECK_INTERVAL_MS = 5_000L

        /** Whether [intent] asks for the pause sheet, which only BioKey's own tile can. */
        internal fun isPauseRequest(intent: Intent?): Boolean =
            intent?.action == ACTION_PAUSE_PROTECTION && intent.component?.className == PAUSE_PROTECTION_ALIAS

        /** An app's label to search for as home opens (the new-app offer): harmless from anyone, it only fills the search. */
        const val EXTRA_SEARCH = "com.android.BioKey.extra.SEARCH"

        /** Opens the pause sheet (after BioKey's own check): the tile's way in, through the alias. */
        internal fun pauseIntent(context: Context): Intent =
            Intent(ACTION_PAUSE_PROTECTION).setClassName(context, PAUSE_PROTECTION_ALIAS)
    }

    private val vault by lazy { SecurityVault.getInstance(this) }
    private val engine get() = SecurityEngine.INSTANCE

    private var isAuthenticated by mutableStateOf(false)
    private var promptAutoLaunched = false
    private var lockMessage by mutableStateOf<String?>(null)
    private var isPermanentLockout by mutableStateOf(false)
    private var protectionMode by mutableStateOf(ProtectionEngine.Mode.OFF)
    private var pauseRequested by mutableStateOf(false)
    private var searchRequest by mutableStateOf<String?>(null)
    private var isSetupAuthenticating by mutableStateOf(false)
    private var setupAuthError by mutableStateOf<String?>(null)
    /** Setup's final check failed for want of a screen lock: setup offers to open its settings. */
    private var setupNeedsScreenLock by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowProtection.applyWindowProtections(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            pauseRequested = isPauseRequest(intent)
            searchRequest = intent?.getStringExtra(EXTRA_SEARCH)?.take(80)
            // As in onNewIntent: the sheet follows a fresh check unless BioKey is already open and unlocked.
            if (pauseRequested && !LockStateTracker.hasActiveOwnerSession()) LockStateTracker.lockOwnApp()
        }
        WindowProtection.countAccessibilityInteractions(window.decorView)

        setContent {
            val setupCompleted by vault.setupCompletedFlow.collectAsStateWithLifecycle()
            // The accessibility service connecting (just after BioKey starts) or stopping changes
            // what Home and Settings say, with no resume to show it.
            val accessibilityRunning by BioKeyAccessibilityService.running.collectAsStateWithLifecycle()
            LaunchedEffect(accessibilityRunning) { protectionMode = ProtectionEngine.mode(this@MainActivity) }

            // Only while BioKey is on screen: stopped on a trip of its own, it needn't wake every
            // few seconds. A session that ran out meanwhile is found as BioKey comes back (onResume).
            LaunchedEffect(isAuthenticated) {
                if (!isAuthenticated) return@LaunchedEffect
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isAuthenticated) {
                        delay(IDLE_CHECK_INTERVAL_MS)
                        if (!LockStateTracker.isOwnAppUnlocked()) lockForInactivity()
                    }
                }
            }

            // No messages behind BioKey's own lock (the idle lock comes up with BioKey on screen).
            BioKeyTheme(showMessages = !setupCompleted || isAuthenticated) {
                when {
                    !setupCompleted -> SetupScreenExpressive(
                        isAuthenticating = isSetupAuthenticating,
                        authError = setupAuthError,
                        needsScreenLock = setupNeedsScreenLock,
                        onSetupComplete = ::completeSetup
                    )
                    !isAuthenticated -> BioKeyLockScreen(
                        onUnlockClicked = { authenticate() },
                        onCancelClicked = { finishAffinity() },
                        statusMessage = lockMessage,
                        isPermanentLockout = isPermanentLockout,
                        // Only the accessibility service can lock the phone: in compatibility
                        // mode the lockout leaves Cancel, and the power button the message names.
                        canLockPhone = accessibilityRunning,
                        onLockDeviceClicked = {
                            if (!BioKeyAccessibilityService.lockDevice()) finishAffinity()
                        }
                    )
                    else -> MainScreenExpressive(
                        mode = protectionMode,
                        onPauseRequested = { pauseRequested = true },
                        searchRequest = searchRequest,
                        onSearchRequestTaken = { searchRequest = null }
                    )
                }

                if (setupCompleted && isAuthenticated && pauseRequested) {
                    PauseProtectionDialog(
                        onPause = { duration ->
                            pauseRequested = false
                            // BioKey was opened with the screen lock, on a phone with a face or
                            // fingerprint: maybe not the owner. Recorded, and told at the next unlock.
                            ProtectionGaps.recordIfScreenLock(this@MainActivity, "Protection paused")
                            vault.pauseProtection(duration)
                            BioKeyMessages.show("Protection paused", Icons.Default.PauseCircle)
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
        pauseRequested = isPauseRequest(intent)
        searchRequest = intent.getStringExtra(EXTRA_SEARCH)?.take(80)
        if (pauseRequested && !LockStateTracker.hasActiveOwnerSession()) {
            LockStateTracker.lockOwnApp()
            isAuthenticated = false
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        BiometricEnrollment.checkSoon(this)

        if (!vault.isSetupCompleted()) return

        // Back from another app's lock screen brought up over BioKey: only within that trip's lease,
        // which can run out while that lock screen is up (a lapsed one ends the session it kept).
        LockStateTracker.isOwnLockScreenHopActive()
        if (LockStateTracker.isOwnAppUnlocked()) {
            LockStateTracker.onOwnAppResumed()
            isAuthenticated = true
            promptAutoLaunched = true
            return
        }
        // Still unlocked here only after a trip of BioKey's own, whose session ran out while it
        // was away: the lock says why, as the idle check did when it ran in the background.
        if (isAuthenticated) lockForInactivity()
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
        if (isChangingConfigurations) return
        // BioKey's own prompt, still running, handed over to the phone's PIN screen (some phones
        // open it as an activity): that isn't leaving BioKey. Never once BioKey is unlocked.
        if (!isAuthenticated && engine.isAuthenticatingFor(packageName) && BioKeyAccessibilityService.isConfirmingDeviceCredential) return
        engine.resetAuthenticatingState(packageName)
        // The cancelled prompt never reports back: nothing may wait for it.
        isSetupAuthenticating = false
        promptAutoLaunched = false
        // Leaving to lock the phone, as the lockout message asks, ends the lockout: the next
        // visit prompts again.
        isPermanentLockout = false
        lockMessage = null
        // A trip BioKey started itself (a settings page, the backup file picker, another app's lock
        // screen brought up over it) keeps its session; anything else ends it.
        if (LockStateTracker.isOwnTripActive()) {
            LockStateTracker.onOwnAppStopped()
        } else {
            LockStateTracker.lockOwnApp()
            isAuthenticated = false
        }
    }

    override fun onDestroy() {
        if (isFinishing) LockStateTracker.lockOwnApp()
        // Recreated (rotation, a fold, dark mode) with the prompt up: its answer would reach this
        // destroyed instance. Cancel it; the new one asks again.
        if (isChangingConfigurations) engine.resetAuthenticatingStateFor(this)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (WindowProtection.isMotionEventObscured(ev)) {
            return true
        }
        // A touch after BioKey's session ran out locks it rather than counting as activity.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && isAuthenticated && !LockStateTracker.isOwnAppUnlocked()) {
            lockForInactivity()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Touches and key presses keep BioKey's session alive. Dialogs and sheets count their own
     * ([com.android.BioKey.core.security.ProtectDialogWindow]); screen readers are counted by
     * [WindowProtection.countAccessibilityInteractions].
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        LockStateTracker.onOwnAppInteraction()
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
        setupNeedsScreenLock = false

        engine.showBiometricPrompt(
            activity = this,
            appName = getString(R.string.brand_name),
            packageName = packageName,
            promptTitle = "Confirm identity to finish setup",
            onSuccess = {
                isSetupAuthenticating = false
                setupAuthError = null
                setupNeedsScreenLock = false
                Haptics.confirm(window.decorView)
                vault.setSetupCompletedSync(true)
                vault.resumeProtection()
                // Only now, set up, does refresh start compatibility mode where it is the engine:
                // waiting for the next resume would leave apps uncovered under "Protected".
                refreshPermissions()
                LockStateTracker.onOwnAppUnlocked()
                isAuthenticated = true
            },
            onError = { code, message ->
                isSetupAuthenticating = false
                val noScreenLock = code == SecurityEngine.ERROR_NO_DEVICE_CREDENTIAL || code == SecurityEngine.ERROR_NO_BIOMETRICS
                setupNeedsScreenLock = noScreenLock
                setupAuthError = when {
                    SecurityEngine.isUserCancel(code) -> "Biometric confirmation was cancelled. Tap to retry."
                    noScreenLock -> "Set a screen lock (PIN, pattern, or password) in system settings first."
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
            appName = getString(R.string.brand_name),
            packageName = packageName,
            onSuccess = {
                LockStateTracker.onOwnAppUnlocked()
                isAuthenticated = true
                onAuthenticated()
            },
            onError = { code, message ->
                if (code == SecurityEngine.ERROR_LOCKOUT_PERMANENT) {
                    isPermanentLockout = true
                    lockMessage = SecurityEngine.LOCKOUT_PERMANENT_MESSAGE
                } else {
                    isPermanentLockout = false
                    lockMessage = when {
                        SecurityEngine.isUserCancel(code) -> null
                        code == SecurityEngine.ERROR_NO_DEVICE_CREDENTIAL || code == SecurityEngine.ERROR_NO_BIOMETRICS ->
                            "Set a screen lock (PIN, pattern, or password) in system settings to use BioKey."
                        else -> message.toString()
                    }
                }
            }
        )
    }
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
                modifier = Modifier
                    .padding(horizontal = 28.dp)
                    .semantics { heading() }
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
