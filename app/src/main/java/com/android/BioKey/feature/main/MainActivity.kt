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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.BioKey.R
import com.android.BioKey.core.FeatureLinks
import com.android.BioKey.core.security.BiometricEnrollment
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.NewAppSuggestions
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.BioKeyLockScreen
import com.android.BioKey.feature.setup.SetupScreenExpressive
import com.android.BioKey.ui.security.ProtectDialogWindow
import com.android.BioKey.ui.security.WindowProtection
import com.android.BioKey.ui.theme.BioKeyMessages
import com.android.BioKey.ui.theme.BioKeyTheme
import com.android.BioKey.ui.theme.Group
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.Haptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        const val EXTRA_SEARCH = FeatureLinks.EXTRA_SEARCH

        /** Opens the pause sheet (after BioKey's own check): the tile's way in, through the alias. */
        internal fun pauseIntent(context: Context): Intent =
            Intent(ACTION_PAUSE_PROTECTION).setClassName(context, PAUSE_PROTECTION_ALIAS)
    }

    private val vault by lazy { SecurityVault.getInstance(this) }
    private val engine get() = SecurityEngine.INSTANCE

    private var isAuthenticated by mutableStateOf(false)
    private var promptAutoLaunched = false
    private var lockMessage by mutableStateOf<String?>(null)
    /** Why BioKey locked: shown, unless a prompt's error ([lockMessage]) is, until it's unlocked or left. */
    private var lockReason by mutableStateOf<String?>(null)
    private var isPermanentLockout by mutableStateOf(false)
    private var protectionMode by mutableStateOf(ProtectionEngine.Mode.OFF)
    private var pauseRequested by mutableStateOf(false)
    private var searchRequest by mutableStateOf<String?>(null)
    private var isSetupAuthenticating by mutableStateOf(false)
    private var setupAuthError by mutableStateOf<String?>(null)
    /** Setup's final check failed for want of a screen lock: setup offers to open its settings. */
    private var setupNeedsScreenLock by mutableStateOf(false)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        SetupWait(setupNeedsScreenLock, setupAuthError).saveTo(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowProtection.applyWindowProtections(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Setup waiting for a screen lock, through a recreation (a font or language change, say).
        SetupWait.from(savedInstanceState).let {
            setupNeedsScreenLock = it.needsScreenLock
            setupAuthError = it.authError
        }
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
                    // Locked from elsewhere ("lock all" from a tile, the shortcut, the status
                    // sheet, the screen turning off): BioKey's screens go at once.
                    launch { LockStateTracker.ownLocks.collect { if (!LockStateTracker.isOwnAppUnlocked()) onOwnSessionEnded() } }
                    while (isAuthenticated) {
                        delay(IDLE_CHECK_INTERVAL_MS)
                        if (!LockStateTracker.isOwnAppUnlocked()) onOwnSessionEnded()
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
                        // Followed: the check runs as BioKey starts, maybe after setup first shows.
                        cleared = ProtectionGaps.cleared.collectAsStateWithLifecycle().value,
                        onSetupComplete = ::completeSetup
                    )
                    !isAuthenticated -> BioKeyLockScreen(
                        onUnlockClicked = { authenticate() },
                        onCancelClicked = { finishAffinity() },
                        statusMessage = lockMessage ?: lockReason,
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
                            ProtectionGaps.recordIfScreenLock(this@MainActivity, ProtectionGaps.Change.PAUSED)
                            vault.pauseProtection(duration)
                            BioKeyMessages.show(getString(R.string.home_protection_paused), Icons.Default.PauseCircle)
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
        // A new-app offer tapped to come here is gone: its summary counts one fewer.
        NewAppSuggestions.refreshSummarySoon(this)
        // Reset and not set up again, notifications allowed again (setup asks): the reminders start.
        if (!vault.isSetupCompleted()) appScope.launch(Dispatchers.Default) { ProtectionAlerts.resumeStorageClearedReminders(application) }

        // Back from setting a screen lock during setup: that step's done, and setup can finish.
        if (setupNeedsScreenLock && getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true) {
            // It failed at the last step for want of one: that step now, without another tap, once
            // resumed (as the other prompts are shown). The state is cleared only then, so a post
            // that finds BioKey gone from the front leaves it for the next resume.
            window.decorView.post {
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && !vault.isSetupCompleted()) {
                    setupNeedsScreenLock = false
                    setupAuthError = null
                    completeSetup()
                }
            }
        }

        if (!vault.isSetupCompleted()) return

        // Back from a trip of BioKey's own (a settings page, the file picker, another app's lock
        // screen brought up over it): only within that trip's lease, which can run out while it's
        // away (a lapsed one ends the session it kept, as having run out).
        LockStateTracker.isOwnTripActive()
        if (LockStateTracker.isOwnAppUnlocked()) {
            LockStateTracker.onOwnAppResumed()
            isAuthenticated = true
            promptAutoLaunched = true
            return
        }
        // Still unlocked here only after a trip of BioKey's own, whose session ended while it was
        // away: the lock says why, as the idle check would have with BioKey on screen.
        onOwnSessionEnded(away = true)
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
        lockReason = null
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
            onOwnSessionEnded()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Touches and key presses keep BioKey's session alive. Dialogs and sheets count their own
     * ([com.android.BioKey.ui.security.ProtectDialogWindow]); screen readers are counted by
     * [WindowProtection.countAccessibilityInteractions].
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        LockStateTracker.onOwnAppInteraction()
    }

    /**
     * BioKey's session is over: its screens lock. The lock says why when the session ran out
     * ([LockStateTracker.ownSessionTimedOut]) or ended while BioKey was [away]; locked on purpose
     * with BioKey on screen ("lock all"), it says nothing.
     */
    private fun onOwnSessionEnded(away: Boolean = false) {
        if (!isAuthenticated) return
        isAuthenticated = false
        val minutes = (LockStateTracker.OWN_APP_IDLE_MS / 60_000).toInt()
        lockReason = when {
            LockStateTracker.ownTripRanOut -> getString(R.string.activity_locked_trip)
            LockStateTracker.ownSessionTimedOut -> resources.getQuantityString(R.plurals.activity_locked_idle, minutes, minutes)
            // Ended meanwhile some other way (another app came forward, every app was locked):
            // coming back to a lock unasked for, the owner is told why.
            away -> getString(R.string.activity_locked_away)
            else -> null
        }
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
            promptTitle = getString(R.string.activity_setup_prompt_title),
            onSuccess = {
                isSetupAuthenticating = false
                setupAuthError = null
                setupNeedsScreenLock = false
                Haptics.confirm(window.decorView)
                vault.setSetupCompletedSync(true)
                // A clearing of BioKey's storage before this setup: recorded now, its alert gone.
                ProtectionGaps.onSetUp(this)
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
                    SecurityEngine.isUserCancel(code) -> getString(R.string.activity_setup_cancelled)
                    noScreenLock -> getString(R.string.activity_setup_needs_screen_lock)
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
                lockReason = null
                onAuthenticated()
            },
            onError = { code, message ->
                if (code == SecurityEngine.ERROR_LOCKOUT_PERMANENT) {
                    isPermanentLockout = true
                    lockMessage = getString(R.string.prompt_lockout_permanent)
                } else {
                    isPermanentLockout = false
                    lockMessage = when {
                        SecurityEngine.isUserCancel(code) -> null
                        code == SecurityEngine.ERROR_NO_DEVICE_CREDENTIAL || code == SecurityEngine.ERROR_NO_BIOMETRICS ->
                            getString(R.string.activity_needs_screen_lock)
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
        Triple(Icons.Default.Timer, stringResource(R.string.activity_pause_15_minutes), 15 * 60_000L),
        Triple(Icons.Default.Schedule, stringResource(R.string.activity_pause_1_hour), 60 * 60_000L),
        Triple(Icons.Default.PauseCircle, stringResource(R.string.activity_pause_until_resumed), null)
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ProtectDialogWindow()
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            Text(
                stringResource(R.string.home_pause_protection),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .padding(horizontal = 28.dp)
                    .semantics { heading() }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.activity_pause_text),
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

/**
 * What setup was waiting on, kept through a recreation (a font or language change, which
 * configChanges doesn't cover): a screen lock to be set, and the last prompt's error.
 */
internal data class SetupWait(val needsScreenLock: Boolean = false, val authError: String? = null) {
    fun saveTo(out: Bundle) {
        out.putBoolean(KEY_NEEDS_SCREEN_LOCK, needsScreenLock)
        authError?.let { out.putString(KEY_AUTH_ERROR, it) }
    }

    companion object {
        private const val KEY_NEEDS_SCREEN_LOCK = "setup_needs_screen_lock"
        private const val KEY_AUTH_ERROR = "setup_auth_error"

        /** Nothing saved (a fresh start): waiting on nothing. */
        fun from(saved: Bundle?) = SetupWait(saved?.getBoolean(KEY_NEEDS_SCREEN_LOCK, false) ?: false, saved?.getString(KEY_AUTH_ERROR))
    }
}
