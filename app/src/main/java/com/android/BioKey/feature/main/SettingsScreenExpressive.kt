package com.android.BioKey.feature.main

import android.content.Intent
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
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
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.BioKey.BuildConfig
import com.android.BioKey.R
import com.android.BioKey.core.data.openFor
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.NewAppSuggestions
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.feature.lock.SelfProtectionDetector
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.ui.theme.BioKeyMessages
import com.android.BioKey.ui.theme.Group
import com.android.BioKey.ui.theme.GroupInset
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.Haptics
import com.android.BioKey.ui.theme.MaxContentWidth
import com.android.BioKey.ui.theme.SectionHeader
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
    val goes: Goes = Goes.ON
)

/** Where tapping a row takes you: on (a chevron), out of BioKey (open-in-new), or nowhere (an action). */
private enum class Goes { ON, OUT, NOWHERE }

/**
 * Settings' trip to Android's notification access page, kept with the activity rather than the
 * screen: a message's "Open settings" can be tapped after a rotation, once the screen that raised
 * it is gone, and the screen on view then must still know to check on coming back.
 */
internal class NotificationAccessTrip : ViewModel() {
    /** Sent to Android's notification access page: on coming back, say if it's still off. */
    var awaiting = false
}

/**
 * BioKey's settings, over the home screen. [mode] is the engine protecting apps, as the home
 * screen has it (MainActivity refreshes it on every resume).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenExpressive(mode: ProtectionEngine.Mode, onNavigateBack: () -> Unit, onPauseRequested: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val view = LocalView.current
    val vault = remember { SecurityVault.getInstance(context) }

    val globalLockEnabled by vault.globalLockEnabledFlow.collectAsStateWithLifecycle()
    val pausedUntil by vault.pausedUntilFlow.collectAsStateWithLifecycle()
    val defaultTimeout by vault.timeoutModeFlow.collectAsStateWithLifecycle()
    val selfProtectionEnabled by vault.accessibilitySelfProtectionFlow.collectAsStateWithLifecycle()
    val notificationShieldEnabled by vault.notificationShieldFlow.collectAsStateWithLifecycle()
    val autoSortProfiles by vault.autoSortProfilesFlow.collectAsStateWithLifecycle()
    val attemptAlerts by UnlockAttempts.enabled.collectAsStateWithLifecycle()
    val screenLockUnlocks by UnlockAttempts.screenLockUnlocks.collectAsStateWithLifecycle()
    val biometricOnly by vault.biometricOnlyFlow.collectAsStateWithLifecycle()
    val lockedApps by vault.lockedAppsFlow.collectAsStateWithLifecycle()
    var strongBiometric by remember { mutableStateOf(SecurityEngine.hasStrongBiometric(context)) }
    LifecycleResumeEffect(Unit) {
        strongBiometric = SecurityEngine.hasStrongBiometric(context)
        onPauseOrDispose { }
    }
    val newAppOffers by NewAppSuggestions.enabled.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        // Usually loaded already, as BioKey started.
        withContext(Dispatchers.IO) {
            UnlockAttempts.load(context)
            NewAppSuggestions.load(context)
        }
    }

    var notifListenerActive by remember { mutableStateOf(BioKeyNotificationShieldService.isNotificationAccessGranted(context)) }
    /** An accessibility shortcut switches BioKey's service off without passing any screen self-protection guards. */
    var shortcutAssigned by remember { mutableStateOf((SecurityVault.selfProtectionAvailable && SelfProtectionDetector.isShortcutAssigned(context))) }
    var showRelockSheet by remember { mutableStateOf(false) }
    val activity = LocalActivity.current as ComponentActivity
    val notificationAccessTrip = remember(activity) { ViewModelProvider(activity)[NotificationAccessTrip::class.java] }
    val disclosures = rememberDisclosureGate()
    val notifications = rememberNotificationPermission()
    val protecting = ProtectionEngine.isProtecting(mode)

    // Both were read just now, as Settings opened: read again on every later resume, back from
    // the system pages that change them, not on the one that follows straight away.
    val readOnOpen = remember { booleanArrayOf(true) }
    LifecycleResumeEffect(Unit) {
        if (readOnOpen[0]) {
            readOnOpen[0] = false
        } else {
            notifListenerActive = BioKeyNotificationShieldService.isNotificationAccessGranted(context)
            shortcutAssigned = (SecurityVault.selfProtectionAvailable && SelfProtectionDetector.isShortcutAssigned(context))
        }
        if (notificationAccessTrip.awaiting) {
            notificationAccessTrip.awaiting = false
            if (!notifListenerActive) {
                // The action may run after a rotation, once this screen and its activity are gone.
                val appContext = context.applicationContext
                BioKeyMessages.show(res.getString(R.string.settings_notification_access_still_off), Icons.Default.NotificationsOff, actionLabel = res.getString(R.string.settings_open_settings)) {
                    notificationAccessTrip.awaiting = true
                    IntentNavigationHelper.openNotificationListenerSettings(if (activity.isDestroyed) appContext else activity)
                }
            } else {
                // Just switched on: without BioKey's own notifications it can hide notifications but
                // can't stand in for them.
                notifications.request()
            }
        }
        onPauseOrDispose { }
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
                    biometricOnly && strongBiometric -> stringResource(R.string.settings_lock_apps_biometric_only)
                    else -> stringResource(R.string.settings_lock_apps_any)
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
                subtitle = if (biometricOnly && !strongBiometric) stringResource(R.string.settings_biometric_only_none_set_up)
                else stringResource(R.string.settings_biometric_only_summary),
                checked = biometricOnly,
                // Either way, a face or fingerprint first: proof it works before relying on it, and no
                // switching it off for someone who only knows the PIN.
                onChange = { on ->
                    confirmOwner(activity, res.getString(if (on) R.string.settings_confirm_biometric_only_on else R.string.settings_confirm_biometric_only_off)) {
                        vault.setBiometricOnly(on)
                        // Home asks about locking Android's Settings again, even if put away before.
                        if (on) homePrefs(context).edit { remove(KEY_LOCK_SETTINGS_DISMISSED) }
                    }
                }
            ) else null,
            // A suggestion, the same as home's card: where face or fingerprint only is on.
            if (biometricOnly && strongBiometric && SETTINGS_PACKAGE !in lockedApps) SettingItem(
                icon = Icons.Default.Lock,
                title = stringResource(R.string.settings_lock_settings_title),
                subtitle = stringResource(R.string.settings_lock_settings_summary),
                onClick = {
                    val undo = vault.lockSettingsApp()
                    BioKeyMessages.show(res.getString(R.string.settings_lock_settings_done), Icons.Default.Lock, actionLabel = res.getString(R.string.common_undo), onAction = undo)
                },
                goes = Goes.NOWHERE
            ) else null
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
        add(
            SettingItem(
                icon = if (notifications.allowed) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                title = stringResource(R.string.alerts_channel_name),
                subtitle = if (notifications.allowed) stringResource(R.string.settings_alerts_on)
                else stringResource(R.string.settings_alerts_off),
                onClick = if (notifications.allowed) null else notifications::requestOrOpenSettings
            )
        )
    }
    val serviceLabel = stringResource(R.string.accessibility_service_label)
    val privacy = listOfNotNull(
        SettingItem(
            icon = Icons.Default.NotificationsOff,
            title = stringResource(R.string.settings_hide_notifications_title),
            subtitle = when {
                !notificationShieldEnabled -> stringResource(R.string.settings_hide_notifications_off)
                !notifListenerActive -> stringResource(R.string.settings_hide_notifications_needs_access)
                notificationShieldEnabled && !notifications.allowed ->
                    stringResource(R.string.settings_hide_notifications_no_stand_in)
                notificationShieldEnabled -> stringResource(R.string.settings_hide_notifications_on)
                else -> stringResource(R.string.settings_hide_notifications_full)
            },
            checked = notificationShieldEnabled && notifListenerActive,
            onChange = { on ->
                if (!on) {
                    vault.setNotificationShield(false)
                    ProtectionGaps.recordIfScreenLock(context, "Hide notification content switched off")
                } else {
                    // Always the explanation first, even with notification access already granted some
                    // other way. Saved only on Agree: "Not now" leaves it off.
                    disclosures.request(Disclosure.NOTIFICATIONS) {
                        vault.setNotificationShield(true)
                        if (!notifListenerActive) {
                            notificationAccessTrip.awaiting = true
                            IntentNavigationHelper.openNotificationListenerSettings(context)
                        } else {
                            notifications.request()
                        }
                    }
                }
            }
        ),
        SettingItem(
            icon = Icons.Default.GppBad,
            title = stringResource(R.string.settings_failed_alerts_title),
            subtitle = stringResource(R.string.settings_failed_alerts_summary),
            checked = attemptAlerts,
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
                if (on) apply() else confirmOwner(activity, res.getString(R.string.settings_confirm_failed_alerts_off), ::apply)
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
            onChange = { on ->
                if (on) UnlockAttempts.setScreenLockUnlocks(context, true)
                else confirmOwner(activity, res.getString(R.string.settings_confirm_screen_lock_unlocks_off)) {
                    UnlockAttempts.setScreenLockUnlocks(context, false)
                }
            }
        ),
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
                    !on -> vault.setAccessibilitySelfProtectionEnabled(false)
                    // Switched on already (running or not): saved now, guarding once it runs.
                    mode == ProtectionEngine.Mode.ACCESSIBILITY || mode == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING -> {
                        vault.setAccessibilitySelfProtectionEnabled(true)
                        shortcutAssigned = (SecurityVault.selfProtectionAvailable && SelfProtectionDetector.isShortcutAssigned(context))
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
        if (selfProtectionEnabled && mode == ProtectionEngine.Mode.ACCESSIBILITY && shortcutAssigned) {
            SettingItem(
                icon = Icons.Default.Warning,
                title = stringResource(R.string.settings_shortcut_warning_title),
                subtitle = stringResource(R.string.settings_shortcut_warning_summary, serviceLabel),
                warning = true,
                onClick = { IntentNavigationHelper.openAccessibilitySettings(context) }
            )
        } else null
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
        SettingItem(
            icon = Icons.Default.Save,
            title = stringResource(R.string.backup_title),
            subtitle = stringResource(R.string.settings_back_up_summary),
            onClick = backupActions.backUp
        ),
        SettingItem(
            icon = Icons.Default.Restore,
            title = stringResource(R.string.backup_restore_title),
            subtitle = stringResource(R.string.settings_restore_summary),
            onClick = backupActions.restore
        )
    )

    var logEnabled by remember { mutableStateOf(DiagnosticLog.isEnabled()) }
    var showLog by remember { mutableStateOf(false) }
    val logVersion by DiagnosticLog.version.collectAsStateWithLifecycle()
    val logCount = remember(logVersion) { DiagnosticLog.entries().size }
    val diagnostics = listOf(
        SettingItem(
            icon = Icons.Default.BugReport,
            title = stringResource(R.string.diagnostics_title),
            subtitle = stringResource(R.string.settings_log_summary),
            checked = logEnabled,
            onChange = { on ->
                fun apply() {
                    DiagnosticLog.setEnabled(context, on)
                    logEnabled = on
                }
                // Off deletes it, and with it a trace of who unlocked what.
                if (on) apply() else confirmOwner(activity, res.getString(R.string.settings_confirm_log_off), ::apply)
            }
        ),
        SettingItem(
            icon = Icons.Default.Description,
            title = stringResource(R.string.settings_view_log_title),
            subtitle = if (logCount == 0) stringResource(R.string.diagnostics_empty)
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
            subtitle = stringResource(R.string.settings_help_summary, BuildConfig.VERSION_NAME),
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
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = { Text(settingsTitle) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack, modifier = Modifier.springPress()) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    scrollBehavior = scrollBehavior
                )
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
                item {
                    SettingsSection(
                        stringResource(R.string.settings_section_privacy),
                        privacy,
                        // Snoozed notifications can only be brought back while BioKey still has access.
                        footer = if (notifListenerActive) {
                            stringResource(R.string.settings_hide_notifications_footer)
                        } else null
                    ) { Haptics.toggle(view, it) }
                }
                item { SettingsSection(stringResource(R.string.settings_section_profiles), profiles) { Haptics.toggle(view, it) } }
                item { SettingsSection(stringResource(R.string.settings_section_backup), backup) { Haptics.toggle(view, it) } }
                item { SettingsSection(stringResource(R.string.settings_section_diagnostics), diagnostics) { Haptics.toggle(view, it) } }
                item { SettingsSection(stringResource(R.string.settings_section_about), about) { Haptics.toggle(view, it) } }
                item {
                    Text(
                        text = stringResource(R.string.settings_footer_no_internet),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp)
                    )
                }
            }
        }
    }

    if (showLog) DiagnosticLogScreen(onDismiss = { showLog = false })

    if (showRelockSheet) {
        RelockSheet(
            title = stringResource(R.string.settings_relock_title),
            current = defaultTimeout,
            onSelect = { mode ->
                if (mode != null) {
                    if (mode.openFor > defaultTimeout.openFor) {
                        ProtectionGaps.recordIfScreenLock(context, "Default re-lock delay made longer: ${mode.shortLabel.lowercase()}")
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
    Group(count = items.size) { i ->
        val item = items[i]
        val checked = item.checked
        if (checked != null) {
            GroupRow(
                index = i,
                count = items.size,
                title = item.title,
                subtitle = item.subtitle,
                icon = item.icon,
                // A row that can't be used now looks it, not only its switch (Material's 38%).
                modifier = if (item.enabled) Modifier else Modifier.alpha(0.38f),
                interaction = Modifier.toggleable(value = checked, enabled = item.enabled, role = Role.Switch) { next ->
                    onToggled(next)
                    item.onChange(next)
                }
            ) {
                Switch(checked = checked, onCheckedChange = null, enabled = item.enabled)
            }
        } else {
            GroupRow(
                index = i,
                count = items.size,
                title = item.title,
                subtitle = item.subtitle,
                icon = item.icon,
                iconTint = if (item.warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                iconContainer = if (item.warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                onClick = item.onClick,
                trailing = if (item.onClick == null || item.goes == Goes.NOWHERE) null else {
                    {
                        Icon(
                            if (item.goes == Goes.OUT) Icons.AutoMirrored.Filled.OpenInNew else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(if (item.goes == Goes.OUT) 20.dp else 24.dp)
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

