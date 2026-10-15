package com.android.BioKey.feature.main

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.ui.theme.Haptics
import com.android.BioKey.ui.theme.springPress

/** [android.provider.Settings.ACTION_APP_LOCK_SETTINGS] (API 37.2), as a literal for older SDKs. */
private const val SYSTEM_APP_LOCK_SETTINGS = "android.settings.APP_LOCK_SETTINGS"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenExpressive(onNavigateBack: () -> Unit) {
    BackHandler(onBack = onNavigateBack)

    val context = LocalContext.current
    val vault = remember { SecurityVault.getInstance(context) }

    val globalLockEnabled by vault.globalLockEnabledFlow.collectAsStateWithLifecycle()
    val pausedUntil by vault.pausedUntilFlow.collectAsStateWithLifecycle()
    val defaultTimeout by vault.timeoutModeFlow.collectAsStateWithLifecycle()
    val selfProtectionEnabled by vault.accessibilitySelfProtectionFlow.collectAsStateWithLifecycle()
    val notificationShieldEnabled by vault.notificationShieldFlow.collectAsStateWithLifecycle()
    val autoSortProfiles by vault.autoSortProfilesFlow.collectAsStateWithLifecycle()

    var a11yActive by remember { mutableStateOf(BioKeyAccessibilityService.isServiceEnabled(context)) }
    var notifListenerActive by remember { mutableStateOf(BioKeyNotificationShieldService.isNotificationAccessGranted(context)) }
    var showRelockSheet by remember { mutableStateOf(false) }

    // Android 17 QPR2 (API 37.2) ships its own App Lock. Only the launcher may switch it on per
    // app (LOCK_APPS), but its settings page is open to everyone.
    val systemAppLockAvailable = remember {
        context.packageManager.resolveActivity(Intent(SYSTEM_APP_LOCK_SETTINGS), 0) != null
    }

    LifecycleResumeEffect(Unit) {
        a11yActive = BioKeyAccessibilityService.isServiceEnabled(context)
        notifListenerActive = BioKeyNotificationShieldService.isNotificationAccessGranted(context)
        onPauseOrDispose { }
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .rejectObscuredTouches()
            .statusBarsPadding()
    ) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onNavigateBack, modifier = Modifier.springPress()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp + navBottom)
        ) {
            item {
                SectionLabel("App lock")
                SwitchRow(
                    icon = Icons.Default.Shield,
                    title = "App lock",
                    subtitle = when {
                        !a11yActive -> "Off · turn on BioKey in Accessibility settings"
                        globalLockEnabled -> "Locked apps need your face, fingerprint or screen lock"
                        pausedUntil > System.currentTimeMillis() ->
                            "Paused until " + android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(pausedUntil))
                        else -> "Paused"
                    },
                    checked = globalLockEnabled && a11yActive,
                    onCheckedChange = { on ->
                        vault.setGlobalLockEnabled(on)
                        if (on && !a11yActive) IntentNavigationHelper.openAccessibilitySettings(context)
                    }
                )
                ClickRow(
                    icon = Icons.Default.LockClock,
                    title = "Default re-lock delay",
                    subtitle = "${defaultTimeout.shortLabel} · for apps not in a profile",
                    onClick = { showRelockSheet = true }
                )
                SwitchRow(
                    icon = Icons.Default.AdminPanelSettings,
                    title = "Protect BioKey's settings",
                    subtitle = "Ask for authentication before BioKey can be switched off, force-stopped or uninstalled",
                    checked = selfProtectionEnabled && a11yActive,
                    onCheckedChange = { on ->
                        vault.setAccessibilitySelfProtectionEnabled(on)
                        if (on && !a11yActive) IntentNavigationHelper.openAccessibilitySettings(context)
                    }
                )
                if (systemAppLockAvailable) {
                    ClickRow(
                        icon = Icons.Default.Security,
                        title = "Android App Lock",
                        subtitle = "Android 17's built-in lock stops an app before it draws anything, so apps " +
                            "locked there never flash on screen. Only Android can switch it on.",
                        trailing = Icons.AutoMirrored.Filled.OpenInNew,
                        onClick = { IntentNavigationHelper.launchFallbackCascade(context, listOf(Intent(SYSTEM_APP_LOCK_SETTINGS))) }
                    )
                }
            }

            item {
                SectionLabel("Privacy")
                SwitchRow(
                    icon = Icons.Default.NotificationsOff,
                    title = "Hide notification content",
                    subtitle = when {
                        !notifListenerActive -> "Needs notification access · tap to grant"
                        notificationShieldEnabled -> "Locked apps' notifications only say that something arrived"
                        else -> "Notifications from locked apps show in full"
                    },
                    checked = notificationShieldEnabled && notifListenerActive,
                    onCheckedChange = { on ->
                        vault.setNotificationShield(on)
                        if (on && !notifListenerActive) {
                            IntentNavigationHelper.openNotificationListenerSettings(context)
                            Toast.makeText(context, "Turn on notification access for BioKey", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            item {
                SectionLabel("Profiles")
                SwitchRow(
                    icon = Icons.Default.AutoAwesome,
                    title = "Auto-sort into profiles",
                    subtitle = "Puts recognised banking, social and work apps into matching profiles (which locks them)",
                    checked = autoSortProfiles,
                    onCheckedChange = vault::setAutoSortProfilesEnabled
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
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val view = LocalView.current
    SettingRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch) { next ->
            Haptics.toggle(view, next)
            onCheckedChange(next)
        }
    ) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ClickRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: ImageVector? = null
) {
    SettingRow(icon, title, subtitle, Modifier.clickable(onClick = onClick)) {
        if (trailing != null) {
            Icon(trailing, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier,
    trailingContent: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        trailingContent()
    }
}
