package com.android.BioKey.feature.main

import androidx.activity.BackEventCompat
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
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.ui.theme.BioKeyMessages
import com.android.BioKey.ui.theme.Group
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.Haptics
import com.android.BioKey.ui.theme.MaxContentWidth
import com.android.BioKey.ui.theme.SectionHeader
import com.android.BioKey.ui.theme.springPress
import kotlin.coroutines.cancellation.CancellationException


/** One row of a settings group. */
private class SettingItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val checked: Boolean? = null,
    /** False greys the switch out: it can't be changed right now. */
    val enabled: Boolean = true,
    val onChange: (Boolean) -> Unit = {},
    val onClick: (() -> Unit)? = null
)

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

    var notifListenerActive by remember { mutableStateOf(BioKeyNotificationShieldService.isNotificationAccessGranted(context)) }
    var showRelockSheet by remember { mutableStateOf(false) }
    /** Sent to Android's notification access page: on coming back, say if it's still off. */
    var awaitingNotificationAccess by remember { mutableStateOf(false) }
    val disclosures = rememberDisclosureGate()
    val notifications = rememberNotificationPermission()
    val protecting = mode == ProtectionEngine.Mode.ACCESSIBILITY || mode == ProtectionEngine.Mode.COMPATIBILITY

    LifecycleResumeEffect(Unit) {
        notifListenerActive = BioKeyNotificationShieldService.isNotificationAccessGranted(context)
        if (awaitingNotificationAccess) {
            awaitingNotificationAccess = false
            if (!notifListenerActive) {
                BioKeyMessages.show("Notification access is still off", Icons.Default.NotificationsOff, actionLabel = "Open settings") {
                    awaitingNotificationAccess = true
                    IntentNavigationHelper.openNotificationListenerSettings(context)
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
        if (mode == ProtectionEngine.Mode.NEEDS_COMPATIBILITY_SETUP) {
            disclosures.request(Disclosure.COMPATIBILITY) { IntentNavigationHelper.openNextCompatibilityPermission(context) }
        } else {
            disclosures.request(Disclosure.ACCESSIBILITY) { IntentNavigationHelper.openAccessibilitySettings(context) }
        }
    }

    val appLock = buildList {
        add(
            SettingItem(
                icon = Icons.Default.Shield,
                title = "App lock",
                subtitle = when {
                    mode == ProtectionEngine.Mode.NEEDS_COMPATIBILITY_SETUP -> "Advanced Protection is on · tap to set up compatibility mode"
                    !protecting -> "Off · turn on BioKey in Accessibility settings"
                    !globalLockEnabled && pausedUntil > System.currentTimeMillis() ->
                        "Paused until " + android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(pausedUntil))
                    !globalLockEnabled -> "Paused"
                    mode == ProtectionEngine.Mode.COMPATIBILITY -> "On · compatibility mode (works with Advanced Protection)"
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
                subtitle = "${defaultTimeout.shortLabel} · for apps not in a profile",
                onClick = { showRelockSheet = true }
            )
        )
        add(
            SettingItem(
                icon = if (notifications.allowed) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                title = "Protection alerts",
                subtitle = if (notifications.allowed) "Tells you if app locking stops"
                else "Off: BioKey can't tell you if app locking stops · tap to allow notifications",
                onClick = if (notifications.allowed) null else notifications::requestOrOpenSettings
            )
        )
    }
    val privacy = listOf(
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
                vault.setNotificationShield(on)
                if (on && !notifListenerActive) {
                    disclosures.request(Disclosure.NOTIFICATIONS) {
                        awaitingNotificationAccess = true
                        IntentNavigationHelper.openNotificationListenerSettings(context)
                    }
                } else if (on) {
                    notifications.request()
                }
            }
        ),
        SettingItem(
            icon = Icons.Default.AdminPanelSettings,
            title = "Protect BioKey's settings",
            subtitle = when {
                mode == ProtectionEngine.Mode.ACCESSIBILITY ->
                    "Optional. Ask for your authentication before BioKey can be switched off, force-stopped or uninstalled"
                mode == ProtectionEngine.Mode.OFF && selfProtectionEnabled -> "On · works once the Accessibility service is on"
                mode == ProtectionEngine.Mode.OFF -> "Optional. Needs the Accessibility service, which is off"
                selfProtectionEnabled -> "On, but inactive in compatibility mode · tap to turn off"
                else -> "Not available in compatibility mode"
            },
            // Shows what's saved, so it's never on without the user seeing it. In compatibility
            // mode it can only be turned off; turning it on needs the Accessibility service.
            checked = selfProtectionEnabled,
            enabled = mode == ProtectionEngine.Mode.ACCESSIBILITY || mode == ProtectionEngine.Mode.OFF || selfProtectionEnabled,
            onChange = { on ->
                when {
                    !on -> vault.setAccessibilitySelfProtectionEnabled(false)
                    mode == ProtectionEngine.Mode.ACCESSIBILITY -> vault.setAccessibilitySelfProtectionEnabled(true)
                    // Saved only on Agree: "Not now" leaves it off.
                    mode == ProtectionEngine.Mode.OFF -> disclosures.request(Disclosure.ACCESSIBILITY) {
                        vault.setAccessibilitySelfProtectionEnabled(true)
                        IntentNavigationHelper.openAccessibilitySettings(context)
                    }
                }
            }
        )
    )
    val profiles = listOf(
        SettingItem(
            icon = Icons.Default.AutoAwesome,
            title = "Auto-sort into profiles",
            subtitle = "Puts recognised banking, social and work apps into matching profiles (which locks them)",
            checked = autoSortProfiles,
            onChange = vault::setAutoSortProfilesEnabled
        )
    )

    val backupActions = rememberBackupActions()
    val backup = listOf(
        SettingItem(
            icon = Icons.Default.Save,
            title = "Back up settings",
            subtitle = "Saves your locked apps, profiles and settings to a file protected by a password",
            onClick = backupActions.backUp
        ),
        SettingItem(
            icon = Icons.Default.Restore,
            title = "Restore from a backup",
            subtitle = "Replaces your locked apps, profiles and settings with a backup's, after a new phone or a reinstall",
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
            subtitle = "Keeps BioKey's lock decisions about your locked apps on this phone for 3 days, to help track down a problem",
            checked = logEnabled,
            onChange = { on ->
                DiagnosticLog.setEnabled(context, on)
                logEnabled = on
            }
        ),
        SettingItem(
            icon = Icons.Default.Description,
            title = "View diagnostic log",
            subtitle = if (logCount == 0) "Nothing recorded yet" else "$logCount entries from the last 3 days · share it when reporting a problem",
            onClick = { showLog = true }
        )
    )

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    PredictiveBackPage(onNavigateBack) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
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
                item { SettingsSection("Privacy & protection", privacy) { Haptics.toggle(view, it) } }
                item { SettingsSection("Profiles", profiles) { Haptics.toggle(view, it) } }
                item { SettingsSection("Backup", backup) { Haptics.toggle(view, it) } }
                item { SettingsSection("Diagnostics", diagnostics) { Haptics.toggle(view, it) } }
                item {
                    Text(
                        text = "BioKey has no internet access. Nothing it sees leaves this phone.",
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
                vault.setTimeoutMode(mode)
                showRelockSheet = false
            },
            onDismiss = { showRelockSheet = false }
        )
    }
}

@Composable
private fun SettingsSection(title: String, items: List<SettingItem>, onToggled: (Boolean) -> Unit) {
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
                onClick = item.onClick
            )
        }
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
