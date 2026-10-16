package com.android.BioKey.feature.main

import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.NotificationsOff
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
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.ui.theme.Group
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.Haptics
import com.android.BioKey.ui.theme.SectionHeader
import com.android.BioKey.ui.theme.springPress
import kotlin.coroutines.cancellation.CancellationException


/** One row of a settings group. */
private class SettingItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val checked: Boolean? = null,
    val external: Boolean = false,
    val onChange: (Boolean) -> Unit = {},
    val onClick: () -> Unit = {}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenExpressive(onNavigateBack: () -> Unit) {
    // Predictive back: the page follows the back gesture (shrinks, rounds, shifts) and reveals the
    // home screen underneath; releasing completes it, swiping back cancels it.
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
    val shownProgress by animateFloatAsState(backProgress, label = "predictive_back")

    val context = LocalContext.current
    val view = LocalView.current
    val vault = remember { SecurityVault.getInstance(context) }

    val globalLockEnabled by vault.globalLockEnabledFlow.collectAsStateWithLifecycle()
    val pausedUntil by vault.pausedUntilFlow.collectAsStateWithLifecycle()
    val defaultTimeout by vault.timeoutModeFlow.collectAsStateWithLifecycle()
    val selfProtectionEnabled by vault.accessibilitySelfProtectionFlow.collectAsStateWithLifecycle()
    val notificationShieldEnabled by vault.notificationShieldFlow.collectAsStateWithLifecycle()
    val autoSortProfiles by vault.autoSortProfilesFlow.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf(ProtectionEngine.mode(context)) }
    var notifListenerActive by remember { mutableStateOf(BioKeyNotificationShieldService.isNotificationAccessGranted(context)) }
    var showRelockSheet by remember { mutableStateOf(false) }
    val disclosures = rememberDisclosureGate()
    val protecting = mode == ProtectionEngine.Mode.ACCESSIBILITY || mode == ProtectionEngine.Mode.COMPATIBILITY

    LifecycleResumeEffect(Unit) {
        ProtectionEngine.refresh(context)
        mode = ProtectionEngine.mode(context)
        notifListenerActive = BioKeyNotificationShieldService.isNotificationAccessGranted(context)
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
                    vault.setGlobalLockEnabled(on)
                    if (on && !protecting) enableEngine()
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
    }
    val privacy = listOf(
        SettingItem(
            icon = Icons.Default.NotificationsOff,
            title = "Hide notification content",
            subtitle = when {
                !notifListenerActive -> "Needs notification access · tap to grant"
                notificationShieldEnabled -> "Locked apps' notifications only say that something arrived"
                else -> "Notifications from locked apps show in full"
            },
            checked = notificationShieldEnabled && notifListenerActive,
            onChange = { on ->
                vault.setNotificationShield(on)
                if (on && !notifListenerActive) {
                    disclosures.request(Disclosure.NOTIFICATIONS) {
                        IntentNavigationHelper.openNotificationListenerSettings(context)
                        Toast.makeText(context, "Turn on notification access for BioKey", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        ),
        SettingItem(
            icon = Icons.Default.AdminPanelSettings,
            title = "Protect BioKey's settings",
            subtitle = when (mode) {
                ProtectionEngine.Mode.ACCESSIBILITY ->
                    "Optional. Ask for your authentication before BioKey can be switched off, force-stopped or uninstalled"
                else -> "Optional. Needs the Accessibility service, so it isn't available in compatibility mode"
            },
            checked = selfProtectionEnabled && mode == ProtectionEngine.Mode.ACCESSIBILITY,
            onChange = { on ->
                vault.setAccessibilitySelfProtectionEnabled(on)
                if (on && mode == ProtectionEngine.Mode.OFF) enableEngine()
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

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = shownProgress
                scaleX = 1f - 0.1f * p
                scaleY = 1f - 0.1f * p
                translationX = (if (backFromLeft) 1f else -1f) * size.width * 0.04f * p
                shape = RoundedCornerShape(32.dp * p)
                clip = p > 0f
            }
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp + navBottom)
        ) {
            item { SettingsSection("App lock", appLock) { Haptics.toggle(view, it) } }
            item { SettingsSection("Privacy & protection", privacy) { Haptics.toggle(view, it) } }
            item { SettingsSection("Profiles", profiles) { Haptics.toggle(view, it) } }
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
                interaction = Modifier.toggleable(value = checked, role = Role.Switch) { next ->
                    onToggled(next)
                    item.onChange(next)
                }
            ) {
                Switch(checked = checked, onCheckedChange = null)
            }
        } else {
            GroupRow(
                index = i,
                count = items.size,
                title = item.title,
                subtitle = item.subtitle,
                icon = item.icon,
                onClick = item.onClick
            ) {
                if (item.external) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
