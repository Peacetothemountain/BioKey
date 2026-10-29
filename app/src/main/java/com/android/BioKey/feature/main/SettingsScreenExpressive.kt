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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.BioKey.BuildConfig
import com.android.BioKey.R
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
    val warning: Boolean = false
)

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
                BioKeyMessages.show("Notification access is still off", Icons.Default.NotificationsOff, actionLabel = "Open settings") {
                    notificationAccessTrip.awaiting = true
                    IntentNavigationHelper.openNotificationListenerSettings(if (activity.isDestroyed) appContext else activity)
                }
            } else {
                // The shield is on by default, so this may be the only moment to ask: without the
                // permission it can hide notifications but can't stand in for them.
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
                title = "Lock apps",
                subtitle = when {
                    mode == ProtectionEngine.Mode.NEEDS_COMPATIBILITY_SETUP -> "Advanced Protection is on · tap to set up compatibility mode"
                    mode == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING ->
                        "Not running · BioKey's accessibility service is on but stopped: turn it off and on again"
                    !protecting -> "Off · turn on BioKey in Accessibility settings"
                    !globalLockEnabled && pausedUntil > System.currentTimeMillis() ->
                        "Paused until " + android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(pausedUntil))
                    !globalLockEnabled -> "Paused"
                    mode == ProtectionEngine.Mode.COMPATIBILITY -> "On · compatibility mode (works with Advanced Protection)"
                    biometricOnly && strongBiometric -> "Locked apps need your face or fingerprint"
                    else -> "Locked apps need your face, fingerprint or screen lock"
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
        add(
            SettingItem(
                icon = Icons.Default.LockClock,
                title = "Default re-lock delay",
                subtitle = "${defaultTimeout.shortLabel} · unless a profile has its own",
                onClick = { showRelockSheet = true }
            )
        )
        add(
            SettingItem(
                icon = if (notifications.allowed) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                title = "Protection alerts",
                subtitle = if (notifications.allowed) "Tells you if app locking stops, and afterwards when it was off"
                else "Off: BioKey can't tell you if app locking stops · tap to allow notifications",
                onClick = if (notifications.allowed) null else notifications::requestOrOpenSettings
            )
        )
    }
    val serviceLabel = stringResource(R.string.accessibility_service_label)
    val privacy = listOfNotNull(
        // Only where a strong face or fingerprint is set up: without one, nothing could unlock.
        if (strongBiometric || biometricOnly) SettingItem(
            icon = Icons.Default.Fingerprint,
            title = "Face or fingerprint only",
            subtitle = if (biometricOnly && !strongBiometric) "On, but no face or fingerprint is set up: your screen lock works meanwhile"
            else "Locked apps and BioKey won't take your PIN, so someone who knows it can't open them",
            checked = biometricOnly,
            // Either way, a face or fingerprint first: proof it works before relying on it, and no
            // switching it off for someone who only knows the PIN.
            onChange = { on ->
                confirmOwner(activity, if (on) "Use only your face or fingerprint" else "Allow your screen lock again") {
                    vault.setBiometricOnly(on)
                    // Settings is where someone with the PIN would add their own fingerprint.
                    if (on && SETTINGS_PACKAGE !in vault.selectedAppsFlow.value) {
                        BioKeyMessages.show("Lock Settings too? It's where a fingerprint can be added", Icons.Default.Lock, actionLabel = "Lock") {
                            vault.setAppLock(SETTINGS_PACKAGE, true)
                        }
                    }
                }
            }
        ) else null,
        if ((biometricOnly || attemptAlerts) && strongBiometric && SETTINGS_PACKAGE !in lockedApps) SettingItem(
            icon = Icons.Default.Warning,
            title = "Settings isn't locked",
            subtitle = "With your PIN, someone could add their own fingerprint there · tap to lock Settings",
            warning = true,
            onClick = { vault.setAppLock(SETTINGS_PACKAGE, true) }
        ) else null,
        SettingItem(
            icon = Icons.Default.NotificationsOff,
            title = "Hide notification content",
            subtitle = when {
                !notifListenerActive -> "Needs notification access · tap to grant"
                notificationShieldEnabled && !notifications.allowed ->
                    "Locked apps' notifications are hidden, with no stand-in until you allow BioKey's notifications"
                notificationShieldEnabled -> "Locked apps' notifications only say that something arrived"
                else -> "Notifications from locked apps show in full"
            },
            checked = notificationShieldEnabled && notifListenerActive,
            onChange = { on ->
                if (!on) {
                    vault.setNotificationShield(false)
                    ProtectionGaps.recordIfScreenLock(context, "Hide notification content switched off")
                } else if (!notifListenerActive) {
                    // Saved only on Agree: "Not now" leaves it off.
                    disclosures.request(Disclosure.NOTIFICATIONS) {
                        vault.setNotificationShield(true)
                        notificationAccessTrip.awaiting = true
                        IntentNavigationHelper.openNotificationListenerSettings(context)
                    }
                } else {
                    vault.setNotificationShield(true)
                    notifications.request()
                }
            }
        ),
        SettingItem(
            icon = Icons.Default.GppBad,
            title = "Failed unlock alerts",
            subtitle = "When a face or fingerprint fails on a locked app. Kept until you've seen them, then 7 days; off deletes them",
            checked = attemptAlerts,
            onChange = { on ->
                fun apply() {
                    val removed = UnlockAttempts.setEnabled(context, on)
                    if (on && !notifications.allowed) notifications.request()
                    if (removed.isNotEmpty()) {
                        BioKeyMessages.show("Failed unlocks deleted", Icons.Default.Delete, actionLabel = "Undo") {
                            UnlockAttempts.setEnabled(context, true)
                            UnlockAttempts.restore(context, removed)
                        }
                    }
                }
                // Switching them off deletes the record: not for someone who only knows the PIN.
                if (on) apply() else confirmOwner(activity, "Turn off failed unlock alerts", ::apply)
            }
        ),
        // For an owner who never uses the PIN: anyone else who knows it shows up.
        SettingItem(
            icon = Icons.Default.Password,
            title = "Also note screen lock unlocks",
            subtitle = if (attemptAlerts) "Each locked app opened with your PIN instead of a face or fingerprint"
            else "Needs failed unlock alerts",
            checked = screenLockUnlocks && attemptAlerts,
            enabled = attemptAlerts,
            onChange = { on ->
                if (on) UnlockAttempts.setScreenLockUnlocks(context, true)
                else confirmOwner(activity, "Stop noting screen lock unlocks") {
                    UnlockAttempts.setScreenLockUnlocks(context, false)
                }
            }
        ),
        SettingItem(
            icon = Icons.Default.AddModerator,
            title = "Offer to lock new apps",
            subtitle = "After you install an app that auto-sort doesn't lock",
            checked = newAppOffers,
            onChange = { on ->
                NewAppSuggestions.setEnabled(context, on)
                if (on && !notifications.allowed) notifications.request()
            }
        ),
        // Not in the Play build (see SecurityVault.selfProtectionAvailable).
        if (SecurityVault.selfProtectionAvailable) SettingItem(
            icon = Icons.Default.AdminPanelSettings,
            title = "Protect BioKey's settings",
            subtitle = when {
                mode == ProtectionEngine.Mode.ACCESSIBILITY ->
                    "Optional. Ask for your authentication before BioKey can be switched off, force-stopped or uninstalled"
                mode == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING && selfProtectionEnabled ->
                    "On · works once the Accessibility service runs again"
                mode == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING -> "Optional. Needs the Accessibility service, which isn't running"
                mode == ProtectionEngine.Mode.OFF && selfProtectionEnabled -> "On · works once the Accessibility service is on"
                mode == ProtectionEngine.Mode.OFF -> "Optional. Needs the Accessibility service, which is off"
                selfProtectionEnabled -> "On, but inactive in compatibility mode · tap to turn off"
                else -> "Not available in compatibility mode"
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
                title = "An accessibility shortcut can switch BioKey off",
                subtitle = "Without authentication. Remove it in Accessibility > $serviceLabel > shortcut · tap to open",
                warning = true,
                onClick = { IntentNavigationHelper.openAccessibilitySettings(context) }
            )
        } else null
    )
    val profiles = listOf(
        SettingItem(
            icon = Icons.Default.AutoAwesome,
            title = "Auto-sort into profiles",
            subtitle = "Puts recognised banking, social and work apps into matching profiles, which locks them",
            checked = autoSortProfiles,
            onChange = vault::setAutoSortProfilesEnabled
        )
    )

    val backupActions = rememberBackupActions()
    val backup = listOf(
        SettingItem(
            icon = Icons.Default.Save,
            title = "Back up settings",
            subtitle = "Your locked apps, profiles and settings, in a file sealed with a password",
            onClick = backupActions.backUp
        ),
        SettingItem(
            icon = Icons.Default.Restore,
            title = "Restore from a backup",
            subtitle = "After a new phone or a reinstall: replaces your locked apps, profiles and settings",
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
            title = "Diagnostic log",
            subtitle = "BioKey's lock decisions about your locked apps, kept 3 days on this phone, to trace a problem",
            checked = logEnabled,
            onChange = { on ->
                fun apply() {
                    DiagnosticLog.setEnabled(context, on)
                    logEnabled = on
                }
                // Off deletes it, and with it a trace of who unlocked what.
                if (on) apply() else confirmOwner(activity, "Turn off the diagnostic log", ::apply)
            }
        ),
        SettingItem(
            icon = Icons.Default.Description,
            title = "View diagnostic log",
            subtitle = if (logCount == 0) "Nothing recorded yet" else "$logCount entries from the last 3 days · share it when reporting a problem",
            onClick = { showLog = true }
        )
    )

    // The privacy policy and help, in the browser (Play asks for the policy inside the app too).
    fun openPage(url: String) = IntentNavigationHelper.openWebPage(context, url)
    val about = listOf(
        SettingItem(
            icon = Icons.Default.Policy,
            title = "Privacy policy",
            subtitle = "What BioKey sees, keeps, and never does · opens in your browser",
            onClick = { openPage(IntentNavigationHelper.PRIVACY_POLICY_URL) }
        ),
        SettingItem(
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            title = "Help and support",
            subtitle = "Answers to common questions, and how to reach NB Designs · version ${BuildConfig.VERSION_NAME}",
            onClick = { openPage(IntentNavigationHelper.SUPPORT_URL) }
        )
    )

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    PredictiveBackPage(onNavigateBack) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                // Screen readers announce it as it slides in over the home screen.
                .semantics { paneTitle = "Settings" }
                // Opaque to touches: taps on empty space must not reach the home screen underneath.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .rejectObscuredTouches()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack, modifier = Modifier.springPress()) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                item { SettingsSection("App lock", appLock) { Haptics.toggle(view, it) } }
                item {
                    SettingsSection(
                        "Privacy & protection",
                        privacy,
                        // Snoozed notifications can only be brought back while BioKey still has access.
                        footer = if (notifListenerActive) {
                            "To bring hidden notifications back, switch off “Hide notification content” here. " +
                                "Revoking notification access instead can leave them hidden for up to a day."
                        } else null
                    ) { Haptics.toggle(view, it) }
                }
                item { SettingsSection("Profiles", profiles) { Haptics.toggle(view, it) } }
                item { SettingsSection("Backup", backup) { Haptics.toggle(view, it) } }
                item { SettingsSection("Diagnostics", diagnostics) { Haptics.toggle(view, it) } }
                item { SettingsSection("About", about) { Haptics.toggle(view, it) } }
                item {
                    Text(
                        text = "BioKey has no internet access. Nothing it sees leaves this phone, unless you share the " +
                            "diagnostic log or save a backup.",
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
            title = "Default re-lock delay",
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
                onClick = item.onClick
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

private const val SETTINGS_PACKAGE = "com.android.settings"
