package com.android.BioKey.feature.main

import android.content.Intent
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddModerator
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Unarchive
import com.android.BioKey.core.security.UnlockMethods
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.BioKey.BuildConfig
import com.android.BioKey.R
import com.android.BioKey.core.data.openFor
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.NewAppSuggestions
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.feature.lock.SelfProtectionDetector
import com.android.BioKey.ui.disclosure.Disclosure
import com.android.BioKey.ui.disclosure.rememberDisclosureGate
import com.android.BioKey.ui.disclosure.rememberNotificationPermission
import com.android.BioKey.ui.navigation.IntentNavigationHelper
import com.android.BioKey.ui.security.ProtectDialogWindow
import com.android.BioKey.ui.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.BioKeyMessages
import com.android.BioKey.ui.theme.Group
import com.android.BioKey.ui.theme.GroupInset
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.Haptics
import com.android.BioKey.ui.theme.IconBadge
import com.android.BioKey.ui.theme.MaxContentWidth
import com.android.BioKey.ui.theme.SectionHeader
import com.android.BioKey.ui.theme.groupColor
import com.android.BioKey.ui.format.attemptTime
import com.android.BioKey.ui.theme.groupShape
import com.android.BioKey.ui.theme.scaledWithText
import com.android.BioKey.ui.theme.springPress
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/** One row of a settings group. */
private class SettingItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val checked: Boolean? = null,
    /** False greys the switch out: it can't be changed right now. */
    val enabled: Boolean = true,
    val onChange: (Boolean) -> Unit = {},
    val onClick: (() -> Unit)? = null,
    /** Something the user should fix: drawn in the error colours. */
    val warning: Boolean = false,
    /** What a tappable row shows at its end: where it goes. */
    val goes: Goes = Goes.ON,
    /**
     * What it does waits until then ([UnlockAttempts.heldUntil]): the row says so in place of its
     * line and looks inactive, before it's tapped; a tap still says why ([confirmOwner]).
     */
    val heldUntil: Long? = null
)

/** Where tapping a row takes you: on (a chevron), or out of BioKey (open-in-new). */
private enum class Goes { ON, OUT }

/**
 * BioKey's settings, over the home screen. [mode] is the engine protecting apps, as the home
 * screen has it (MainActivity refreshes it on every resume).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenExpressive(
    mode: ProtectionEngine.Mode,
    onNavigateBack: () -> Unit,
    onPauseRequested: () -> Unit,
    /** "Face or fingerprint only" just turned on: home brings back its Lock Settings card. */
    onBiometricOnlyOn: () -> Unit = {},
    /** Shown as a pane beside home (tablets): it closes rather than goes back. */
    asPane: Boolean = false
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val view = LocalView.current
    val vault = remember { SecurityVault.getInstance(context) }

    val globalLockEnabled by vault.globalLockEnabledFlow.collectAsStateWithLifecycle()
    val pausedUntil by vault.pausedUntilFlow.collectAsStateWithLifecycle()
    val defaultTimeout by vault.timeoutModeFlow.collectAsStateWithLifecycle()
    val selfProtectionEnabled by vault.accessibilitySelfProtectionFlow.collectAsStateWithLifecycle()
    val autoSortProfiles by vault.autoSortProfilesFlow.collectAsStateWithLifecycle()
    val attemptAlerts by UnlockAttempts.enabled.collectAsStateWithLifecycle()
    val screenLockUnlocks by UnlockAttempts.screenLockUnlocks.collectAsStateWithLifecycle()
    val biometricOnly by vault.biometricOnlyFlow.collectAsStateWithLifecycle()
    val keyguardCamera by vault.keyguardCameraFlow.collectAsStateWithLifecycle()
    val lockedApps by vault.lockedAppsFlow.collectAsStateWithLifecycle()
    // Seeded with what's already known (no call into Android on the main thread), then read afresh.
    var strongBiometric by remember { mutableStateOf(SecurityEngine.lastKnownStrongBiometric() ?: biometricOnlyAtOpen(vault)) }
    val newAppOffers by NewAppSuggestions.enabled.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        // Usually loaded already, as BioKey started.
        withContext(Dispatchers.IO) {
            UnlockAttempts.load(context)
            NewAppSuggestions.load(context)
        }
    }

    /** An accessibility shortcut switches BioKey's service off without passing any screen self-protection guards. */
    var shortcutAssigned by remember { mutableStateOf(false) }
    var showRelockSheet by remember { mutableStateOf(false) }
    /** "Let the camera open over the lock screen" is being switched on: what it lets through, first. */
    var confirmCamera by remember { mutableStateOf(false) }
    /** Private space's page can be opened from here (Android 15+, where Settings allows it). */
    var privateSpace by remember { mutableStateOf(false) }
    /**
     * Which of BioKey's two alert channels is switched off in Android's settings, if one is: the
     * alert that app locking stopped, or the one saying afterwards when it was off. The alerts'
     * row promises both, so it says so, as home does, and opens that channel's page.
     */
    var mutedAlertChannel by remember { mutableStateOf<String?>(null) }
    val activity = LocalActivity.current as ComponentActivity
    val disclosures = rememberDisclosureGate()
    val notifications = rememberNotificationPermission()
    val protecting = ProtectionEngine.isProtecting(mode)
    val passphrase = rememberPassphraseSetting(activity)

    // Read on every visit (back from the system pages that change them), off the main thread:
    // each is a call into Android's services.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            withContext(Dispatchers.IO) {
                strongBiometric = SecurityEngine.hasStrongBiometric(context)
                shortcutAssigned = SelfProtectionDetector.isShortcutAssigned(context)
                privateSpace = IntentNavigationHelper.hasPrivateSpacePage(context)
                mutedAlertChannel = UnlockAttempts.mutedChannel(context)
            }
        }
    }

    /** Sends the user to whatever lets BioKey watch apps, after the matching disclosure. */
    fun enableEngine() {
        when (mode) {
            ProtectionEngine.Mode.NEEDS_COMPATIBILITY_SETUP ->
                disclosures.request(Disclosure.COMPATIBILITY) { IntentNavigationHelper.openNextCompatibilityPermission(context) }
            // Off, or on but not running (turning it off and on grants it again): disclosure first.
            else -> disclosures.request(Disclosure.ACCESSIBILITY) { IntentNavigationHelper.openAccessibilitySettings(context) }
        }
    }

    // The day's hold after a face or fingerprint change or a reset: read again as the records
    // change, and as it ends while the page is open.
    val gapsForHold by ProtectionGaps.gaps.collectAsStateWithLifecycle()
    var holdCheck by remember { mutableStateOf(0) }
    val heldUntil = remember(gapsForHold, holdCheck) { UnlockAttempts.heldUntil(context) }
    LaunchedEffect(heldUntil) {
        val until = heldUntil ?: return@LaunchedEffect
        kotlinx.coroutines.delay((until - System.currentTimeMillis()).coerceAtLeast(0L) + 500L)
        holdCheck++
    }

    val serviceLabel = stringResource(R.string.accessibility_service_label)
    /** What opens a locked app now ("your face or fingerprint"), worked out again when what decides it changes. */
    val asksFor = remember(biometricOnly, strongBiometric, passphrase.isSet) { UnlockMethods.asksFor(context) }
    val appLock = buildList {
        add(
            SettingItem(
                icon = Icons.Default.Shield,
                title = stringResource(R.string.settings_lock_apps_title),
                subtitle = when {
                    mode == ProtectionEngine.Mode.NEEDS_COMPATIBILITY_SETUP -> stringResource(R.string.settings_lock_apps_needs_compat_setup)
                    mode == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING ->
                        stringResource(R.string.settings_lock_apps_not_running)
                    !protecting -> stringResource(R.string.settings_lock_apps_off)
                    !globalLockEnabled && pausedUntil > System.currentTimeMillis() ->
                        stringResource(R.string.home_paused_until, android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(pausedUntil)))
                    !globalLockEnabled -> stringResource(R.string.common_paused)
                    mode == ProtectionEngine.Mode.COMPATIBILITY -> stringResource(R.string.settings_lock_apps_compat)
                    // Its state alone: what opens a locked app is the rows under it to say.
                    else -> stringResource(R.string.home_protected)
                },
                checked = globalLockEnabled && protecting,
                onChange = { on ->
                    if (!on) {
                        // Same choices as the home screen (15 minutes, 1 hour, until resumed), never
                        // an open-ended pause from a single tap.
                        onPauseRequested()
                    } else {
                        vault.setGlobalLockEnabled(true)
                        if (!protecting) enableEngine()
                    }
                }
            )
        )
        addAll(listOfNotNull(
            // Only where a strong face or fingerprint is set up: without one, nothing could unlock.
            if (strongBiometric || biometricOnly) SettingItem(
                icon = Icons.Default.Fingerprint,
                title = stringResource(R.string.settings_biometric_only_title),
                subtitle = when {
                    // What really opens locked apps meanwhile: the passphrase where one is set, else the screen lock.
                    biometricOnly && !strongBiometric && passphrase.isSet -> stringResource(R.string.settings_biometric_only_none_set_up_passphrase)
                    biometricOnly && !strongBiometric -> stringResource(R.string.settings_biometric_only_none_set_up)
                    else -> stringResource(R.string.settings_biometric_only_summary)
                },
                checked = biometricOnly,
                // Either way, a face or fingerprint first: proof it works before relying on it, and no
                // switching it off for someone who only knows the PIN.
                onChange = { on ->
                    confirmOwner(activity, res.getString(if (on) R.string.settings_confirm_biometric_only_on else R.string.settings_confirm_biometric_only_off)) {
                        vault.setBiometricOnly(on)
                        // Within a day of a face or fingerprint change, the new one doesn't prove the owner: recorded then.
                        if (!on) ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.BIOMETRIC_ONLY_OFF)
                        // Home asks about locking Android's Settings again, even if put away before.
                        if (on) onBiometricOnlyOn()
                    }
                }
            ) else null,
            // BioKey's own fallback for "Face or fingerprint only" (PassphraseSetting.kt).
            passphrase.row(biometricOnly, strongBiometric)?.let { SettingItem(icon = it.icon, title = it.title, subtitle = it.subtitle, onClick = it.onClick, heldUntil = heldUntil) },
            // Where face or fingerprint only is on: a switch like any other, locked or not (home's
            // card says why it matters; this says what it does). On, it's said with an Undo, as on home.
            if (biometricOnly && strongBiometric) {
                val settingsLocked = SETTINGS_PACKAGE in lockedApps
                SettingItem(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.settings_lock_settings_title),
                    subtitle = if (settingsLocked) stringResource(R.string.settings_lock_settings_on, asksFor)
                        else stringResource(R.string.common_not_locked),
                    checked = settingsLocked,
                    onChange = { on ->
                        if (on) {
                            val undo = vault.lockSettingsApp()
                            BioKeyMessages.show(res.getString(R.string.settings_lock_settings_done), Icons.Default.Lock, actionLabel = res.getString(R.string.common_undo), onAction = undo)
                        } else {
                            // As an app's own switch on home: its lock taken off, recorded, with Undo.
                            val label = settingsAppLabel(context)
                            vault.setAppLock(SETTINGS_PACKAGE, false)
                            ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.LOCK_OFF, label)
                            BioKeyMessages.show(res.getString(R.string.home_lock_removed, label), Icons.Default.LockOpen, actionLabel = res.getString(R.string.common_undo)) {
                                vault.setAppLock(SETTINGS_PACKAGE, true)
                            }
                        }
                    }
                )
            } else null,
            // Guarding BioKey itself: beside the other ways in, not among the records.
            // Not in the Play build (see SecurityVault.selfProtectionAvailable).
            if (SecurityVault.selfProtectionAvailable) SettingItem(
                icon = Icons.Default.AdminPanelSettings,
                title = stringResource(R.string.settings_self_protection_title),
                subtitle = when {
                    mode == ProtectionEngine.Mode.ACCESSIBILITY ->
                        stringResource(R.string.settings_self_protection_optional)
                    mode == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING && selfProtectionEnabled ->
                        stringResource(R.string.settings_self_protection_on_not_running)
                    mode == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING -> stringResource(R.string.settings_self_protection_needs_running)
                    mode == ProtectionEngine.Mode.OFF && selfProtectionEnabled -> stringResource(R.string.settings_self_protection_on_service_off)
                    mode == ProtectionEngine.Mode.OFF -> stringResource(R.string.settings_self_protection_needs_service)
                    selfProtectionEnabled -> stringResource(R.string.settings_self_protection_inactive_compat)
                    else -> stringResource(R.string.settings_self_protection_unavailable_compat)
                },
                // Shows what's saved, so it's never on without the user seeing it. In compatibility
                // mode it can only be turned off; turning it on needs the Accessibility service.
                checked = selfProtectionEnabled,
                enabled = mode == ProtectionEngine.Mode.ACCESSIBILITY || mode == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING ||
                    mode == ProtectionEngine.Mode.OFF || selfProtectionEnabled,
                onChange = { on ->
                    when {
                        !on -> {
                            // Less protected: recorded when it's maybe someone who only knows the PIN.
                            ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.SELF_PROTECTION_OFF)
                            vault.setAccessibilitySelfProtectionEnabled(false)
                        }
                        // Switched on already (running or not): saved now, guarding once it runs.
                        mode == ProtectionEngine.Mode.ACCESSIBILITY || mode == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING -> {
                            vault.setAccessibilitySelfProtectionEnabled(true)
                            shortcutAssigned = SelfProtectionDetector.isShortcutAssigned(context)
                        }
                        // Saved only on Agree: "Not now" leaves it off.
                        mode == ProtectionEngine.Mode.OFF -> disclosures.request(Disclosure.ACCESSIBILITY) {
                            vault.setAccessibilitySelfProtectionEnabled(true)
                            IntentNavigationHelper.openAccessibilitySettings(context)
                        }
                    }
                }
            ) else null,
            // The shortcut only matters while the service is on: it switches it off at once.
            if (mode == ProtectionEngine.Mode.ACCESSIBILITY && shortcutAssigned) {
                SettingItem(
                    icon = Icons.Default.Warning,
                    title = stringResource(R.string.settings_shortcut_warning_title),
                    subtitle = stringResource(R.string.settings_shortcut_warning_summary, serviceLabel),
                    warning = true,
                    onClick = { IntentNavigationHelper.openAccessibilitySettings(context) }
                )
            } else null
        ))
        add(
            SettingItem(
                icon = Icons.Default.LockClock,
                title = stringResource(R.string.settings_relock_title),
                subtitle = stringResource(R.string.settings_relock_summary, stringResource(defaultTimeout.shortLabelRes)),
                onClick = { showRelockSheet = true }
            )
        )
        add(
            SettingItem(
                icon = Icons.Default.PhotoCamera,
                title = stringResource(R.string.settings_keyguard_camera_title),
                subtitle = stringResource(if (keyguardCamera) R.string.settings_keyguard_camera_on else R.string.settings_keyguard_camera_off),
                checked = keyguardCamera,
                onChange = { on ->
                    // Less locked: what that lets through is said first, where the choice is made.
                    if (on) confirmCamera = true else vault.setKeyguardCameraAllowed(false)
                }
            )
        )
        add(
            SettingItem(
                icon = Icons.Default.AddModerator,
                title = stringResource(R.string.settings_new_apps_title),
                subtitle = stringResource(R.string.settings_new_apps_summary),
                checked = newAppOffers,
                onChange = { on ->
                    NewAppSuggestions.setEnabled(context, on)
                    if (on && !notifications.allowed) notifications.request()
                }
            )
        )
        // Off when BioKey may post nothing at all, or when one of the two channels is off: either
        // way an alert doesn't come. The first is asked for here; a channel is switched back on its own page.
        val alertsOn = notifications.allowed && mutedAlertChannel == null
        add(
            SettingItem(
                icon = if (alertsOn) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                title = stringResource(R.string.alerts_channel_name),
                subtitle = when {
                    alertsOn -> stringResource(R.string.settings_alerts_on)
                    notifications.allowed -> stringResource(R.string.settings_alerts_channel_off)
                    else -> stringResource(R.string.settings_alerts_off)
                },
                onClick = if (notifications.allowed) {
                    { IntentNavigationHelper.openChannelSettings(context, mutedAlertChannel ?: ProtectionAlerts.CHANNEL_ID); Unit }
                } else notifications::requestOrOpenSettings,
                goes = Goes.OUT
            )
        )
        // A lock covers an app; it doesn't hide its icon. Private space does, where it can be opened from here.
        if (privateSpace) add(
            SettingItem(
                icon = Icons.Default.VisibilityOff,
                title = stringResource(R.string.settings_private_space_title),
                subtitle = stringResource(R.string.settings_private_space_summary),
                onClick = { IntentNavigationHelper.openPrivateSpace(context); Unit },
                goes = Goes.OUT
            )
        )
    }
    val activity_ = listOf(
        SettingItem(
            icon = FailedAlertsIcon,
            title = stringResource(R.string.settings_failed_alerts_title),
            subtitle = stringResource(R.string.settings_failed_alerts_summary),
            checked = attemptAlerts,
            // Switching them off waits; switching them on doesn't.
            heldUntil = heldUntil.takeIf { attemptAlerts },
            onChange = { on ->
                fun apply() {
                    val removed = UnlockAttempts.setEnabled(context, on)
                    if (on && !notifications.allowed) notifications.request()
                    if (removed.isNotEmpty()) {
                        BioKeyMessages.show(res.getString(R.string.settings_failed_alerts_deleted), Icons.Default.Delete, actionLabel = res.getString(R.string.common_undo)) {
                            UnlockAttempts.setEnabled(context, true)
                            UnlockAttempts.restore(context, removed)
                        }
                    }
                }
                // Switching them off deletes the record: not for someone who only knows the PIN.
                if (on) apply() else confirmOwner(activity, res.getString(R.string.settings_confirm_failed_alerts_off), silencesRecords = true, action = ::apply)
            }
        ),
        // For an owner who never uses the PIN: anyone else who knows it shows up.
        SettingItem(
            icon = Icons.Default.Password,
            title = stringResource(R.string.settings_screen_lock_unlocks_title),
            subtitle = if (attemptAlerts) stringResource(R.string.settings_screen_lock_unlocks_summary)
            else stringResource(R.string.settings_screen_lock_unlocks_needs_alerts),
            checked = screenLockUnlocks && attemptAlerts,
            enabled = attemptAlerts,
            heldUntil = heldUntil.takeIf { screenLockUnlocks && attemptAlerts },
            onChange = { on ->
                if (on) UnlockAttempts.setScreenLockUnlocks(context, true)
                else confirmOwner(activity, res.getString(R.string.settings_confirm_screen_lock_unlocks_off), silencesRecords = true) {
                    UnlockAttempts.setScreenLockUnlocks(context, false)
                }
            }
        ),
    )
    val profiles = listOf(
        SettingItem(
            icon = Icons.Default.AutoAwesome,
            title = stringResource(R.string.settings_auto_sort_title),
            subtitle = stringResource(R.string.settings_auto_sort_summary),
            checked = autoSortProfiles,
            onChange = vault::setAutoSortProfilesEnabled
        )
    )

    val backupActions = rememberBackupActions()
    val backup = listOf(
        // Back up and restore as a pair: a file put away, a file taken out.
        SettingItem(
            icon = Icons.Default.Archive,
            title = stringResource(R.string.backup_title),
            subtitle = stringResource(R.string.settings_back_up_summary),
            onClick = backupActions.backUp
        ),
        SettingItem(
            icon = Icons.Default.Unarchive,
            title = stringResource(R.string.backup_restore_title),
            subtitle = stringResource(R.string.settings_restore_summary),
            onClick = backupActions.restore,
            // During the day's hold after a face or fingerprint change or a reset, a restore waits: said here, not only on a tap.
            heldUntil = heldUntil
        )
    )

    var showLog by remember { mutableStateOf(false) }
    val logVersion by DiagnosticLog.version.collectAsStateWithLifecycle()
    // Read again whenever the log changes: a restore can switch it.
    val logEnabled = remember(logVersion) { DiagnosticLog.isEnabled() }
    val logCount = remember(logVersion) { DiagnosticLog.entries().size }
    val diagnostics = listOf(
        SettingItem(
            icon = Icons.Default.BugReport,
            title = stringResource(R.string.diagnostics_title),
            subtitle = stringResource(R.string.settings_log_summary),
            checked = logEnabled,
            heldUntil = heldUntil.takeIf { logEnabled },
            onChange = { on ->
                fun apply() = DiagnosticLog.setEnabled(context, on)
                // Off deletes it, and with it a trace of who unlocked what.
                if (on) apply() else confirmOwner(activity, res.getString(R.string.settings_confirm_log_off), silencesRecords = true, action = ::apply)
            }
        ),
        SettingItem(
            icon = Icons.Default.Description,
            title = stringResource(R.string.settings_view_log_title),
            subtitle = if (logCount == 0) stringResource(if (logEnabled) R.string.diagnostics_empty else R.string.diagnostics_off)
            else pluralStringResource(R.plurals.settings_view_log_entries, logCount, logCount),
            onClick = { showLog = true }
        )
    )

    // The privacy policy and help, in the browser (Play asks for the policy inside the app too).
    fun openPage(url: String) = IntentNavigationHelper.openWebPage(context, url)
    val about = listOf(
        SettingItem(
            icon = Icons.Default.Policy,
            title = stringResource(R.string.settings_privacy_policy_title),
            subtitle = stringResource(R.string.settings_privacy_policy_summary),
            onClick = { openPage(IntentNavigationHelper.PRIVACY_POLICY_URL) },
            goes = Goes.OUT
        ),
        SettingItem(
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            title = stringResource(R.string.settings_help_title),
            subtitle = stringResource(R.string.settings_help_summary),
            onClick = { openPage(IntentNavigationHelper.SUPPORT_URL) },
            goes = Goes.OUT
        )
    )

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val settingsTitle = stringResource(R.string.settings_title)
    PredictiveBackPage(onNavigateBack) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                // Screen readers announce it as it slides in over the home screen.
                .semantics { paneTitle = settingsTitle }
                // Opaque to touches: taps on empty space must not reach the home screen underneath.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .rejectObscuredTouches()
                .then(if (asPane) Modifier else Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)),
            topBar = {
                // Beside home (a pane), it closes, and its title sits level with home's header.
                val navigationIcon: @Composable () -> Unit = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.springPress()) {
                        if (asPane) Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                        else Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                }
                val barColors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
                if (asPane) {
                    TopAppBar(title = { Text(settingsTitle) }, navigationIcon = navigationIcon, colors = barColors)
                } else {
                    LargeTopAppBar(title = { Text(settingsTitle) }, navigationIcon = navigationIcon, colors = barColors, scrollBehavior = scrollBehavior)
                }
            }
        ) { padding ->
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyColumn(
                // Phones use the full width; on tablets and in landscape the page keeps a readable width.
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentWidth()
                    .widthIn(max = MaxContentWidth),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp + navBottom)
            ) {
                item { SettingsSection(stringResource(R.string.settings_section_app_lock), appLock) { Haptics.toggle(view, it) } }
                item { SettingsSection(stringResource(R.string.settings_section_activity), activity_) { Haptics.toggle(view, it) } }
                item { SettingsSection(stringResource(R.string.settings_section_profiles), profiles) { Haptics.toggle(view, it) } }
                item { SettingsSection(stringResource(R.string.settings_section_backup), backup) { Haptics.toggle(view, it) } }
                item { SettingsSection(stringResource(R.string.settings_section_diagnostics), diagnostics) { Haptics.toggle(view, it) } }
                item { SettingsSection(stringResource(R.string.settings_section_about), about) { Haptics.toggle(view, it) } }
                item {
                    Text(
                        text = stringResource(R.string.settings_footer_no_internet),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = GroupInset + 16.dp, end = GroupInset + 16.dp, top = 24.dp)
                    )
                }
                // Which BioKey this is: a quiet line of its own at the foot, for when help asks.
                item {
                    Text(
                        text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = GroupInset + 16.dp, end = GroupInset + 16.dp, top = 12.dp, bottom = 8.dp)
                    )
                }
            }
        }
    }

    if (showLog) DiagnosticLogScreen(onDismiss = { showLog = false })
    passphrase.Dialogs()

    if (confirmCamera) {
        AlertDialog(
            onDismissRequest = { confirmCamera = false },
            icon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_keyguard_camera_confirm_title)) },
            text = {
                Column {
                    ProtectDialogWindow()
                    Text(stringResource(R.string.settings_keyguard_camera_confirm_text))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCamera = false
                    // Less locked: recorded when it's maybe someone who only knows the PIN.
                    ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.KEYGUARD_CAMERA_ON)
                    vault.setKeyguardCameraAllowed(true)
                }) { Text(stringResource(R.string.common_turn_on)) }
            },
            dismissButton = { TextButton(onClick = { confirmCamera = false }) { Text(stringResource(R.string.prompt_cancel)) } }
        )
    }

    if (showRelockSheet) {
        RelockSheet(
            title = stringResource(R.string.settings_relock_title),
            current = defaultTimeout,
            onSelect = { mode ->
                if (mode != null) {
                    if (mode.openFor > defaultTimeout.openFor) {
                        ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.DEFAULT_DELAY_LONGER, mode.name)
                    }
                    vault.setTimeoutMode(mode)
                }
                showRelockSheet = false
            },
            onDismiss = { showRelockSheet = false }
        )
    }
}

/** A titled group of settings, with an optional [footer] note under it. */
@Composable
private fun SettingsSection(title: String, items: List<SettingItem>, footer: String? = null, onToggled: (Boolean) -> Unit) {
    SectionHeader(title)
    val context = LocalContext.current
    Group(count = items.size) { i ->
        val item = items[i]
        val checked = item.checked
        // Held: "Not until …" in place of its line, and the inactive look of a row that can't be
        // used now; still tappable, the tap saying why.
        val held = item.heldUntil != null
        val subtitle = item.heldUntil?.let { stringResource(R.string.settings_restore_held, attemptTime(context, it)) } ?: item.subtitle
        if (checked != null) {
            GroupRow(
                index = i,
                count = items.size,
                title = item.title,
                subtitle = subtitle,
                icon = item.icon,
                // A row that can't be used now looks it, not only its switch (Material's 38%).
                modifier = if (item.enabled && !held) Modifier else Modifier.alpha(0.38f),
                interaction = Modifier.toggleable(value = checked, enabled = item.enabled, role = Role.Switch) { next ->
                    onToggled(next)
                    item.onChange(next)
                }
            ) {
                Switch(checked = checked, onCheckedChange = null, enabled = item.enabled && !held)
            }
        } else {
            GroupRow(
                index = i,
                count = items.size,
                title = item.title,
                subtitle = subtitle,
                icon = item.icon,
                modifier = if (held) Modifier.alpha(0.38f) else Modifier,
                iconTint = if (item.warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                iconContainer = if (item.warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                onClick = item.onClick,
                trailing = if (item.onClick == null) null else {
                    {
                        Icon(
                            if (item.goes == Goes.OUT) Icons.AutoMirrored.Filled.OpenInNew else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Keeps up with the text beside it, as the leading icon does.
                            modifier = Modifier.size((if (item.goes == Goes.OUT) 20.dp else 24.dp).scaledWithText())
                        )
                    }
                }
            )
        }
    }
    if (footer != null) {
        Text(
            text = footer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = GroupInset + 16.dp, end = GroupInset + 16.dp, top = 12.dp)
        )
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * Predictive back: the page follows the back gesture (shrinks, rounds, shifts) and reveals the
 * home screen underneath; releasing completes it, swiping back cancels it. Only this wrapper
 * recomposes on each gesture frame; [content] is left alone and the progress is read while drawing.
 */
@Composable
private fun PredictiveBackPage(onNavigateBack: () -> Unit, content: @Composable () -> Unit) {
    var backProgress by remember { mutableFloatStateOf(0f) }
    var backFromLeft by remember { mutableStateOf(true) }
    PredictiveBackHandler { gesture ->
        try {
            gesture.collect { event ->
                backProgress = event.progress
                backFromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
            }
            onNavigateBack()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }
    val shownProgress = animateFloatAsState(backProgress, label = "predictive_back")
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = shownProgress.value
                scaleX = 1f - 0.1f * p
                scaleY = 1f - 0.1f * p
                translationX = (if (backFromLeft) 1f else -1f) * size.width * 0.04f * p
                shape = RoundedCornerShape(32.dp * p)
                clip = p > 0f
            }
    ) {
        content()
    }
}

/** Android Settings' own name on this phone, for the record and the message when its lock is taken off. */
private fun settingsAppLabel(context: android.content.Context): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(SETTINGS_PACKAGE, 0)).toString()
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "Settings"

/**
 * Before Android is asked: whether a strong face or fingerprint is likely there. "Face or
 * fingerprint only" can only have been turned on with one.
 */
private fun biometricOnlyAtOpen(vault: SecurityVault): Boolean = vault.isBiometricOnly()
