package com.android.BioKey.feature.main

import android.content.Context
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.ui.theme.ExpressiveTokens
import com.android.BioKey.ui.theme.springPress
import kotlinx.coroutines.launch

/**
 * Pure Google Native Settings for BioKey.
 * Clean, seamless flat rows without squircle container boxes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenExpressive(
    onNavigateBack: () -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    val context = LocalContext.current
    val vault = remember { SecurityVault.getInstance(context) }

    val globalLockEnabled by vault.globalLockEnabledFlow.collectAsStateWithLifecycle()
    val pausedUntil by vault.pausedUntilFlow.collectAsStateWithLifecycle()
    val timeoutMode by vault.timeoutModeFlow.collectAsStateWithLifecycle()
    val selfProtectionEnabled by vault.accessibilitySelfProtectionFlow.collectAsStateWithLifecycle()
    val notificationShieldEnabled by vault.notificationShieldFlow.collectAsStateWithLifecycle()
    val autoSortProfiles by vault.autoSortProfilesFlow.collectAsStateWithLifecycle()

    var a11yActive by remember { mutableStateOf(BioKeyAccessibilityService.isServiceEnabled(context)) }
    var notifListenerActive by remember { mutableStateOf(BioKeyNotificationShieldService.isNotificationAccessGranted(context)) }
    var isBatteryExempt by remember {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        mutableStateOf(pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false)
    }

    var showTimeoutSheet by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        a11yActive = BioKeyAccessibilityService.isServiceEnabled(context)
        notifListenerActive = BioKeyNotificationShieldService.isNotificationAccessGranted(context)
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        isBatteryExempt = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        onPauseOrDispose { }
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .rejectObscuredTouches()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            },
            navigationIcon = {
                IconButton(onClick = onNavigateBack, modifier = Modifier.springPress()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp + navBottom)
        ) {
            // Section 1: APP LOCK
            item {
                SettingsSectionHeader("APP LOCK")
                val appLockSubtitle = when {
                    !a11yActive -> "Requires Accessibility service • Tap to enable"
                    globalLockEnabled -> "Require biometric or screen lock to open protected apps"
                    pausedUntil > System.currentTimeMillis() -> {
                        val timeText = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(pausedUntil))
                        "Protection paused until $timeText • Tap to resume"
                    }
                    else -> "Protection is currently paused • Tap to resume"
                }
                SettingSwitchRow(
                    icon = Icons.Default.Shield,
                    title = "App lock",
                    subtitle = appLockSubtitle,
                    checked = globalLockEnabled && a11yActive,
                    onCheckedChange = { enabled ->
                        if (enabled && !a11yActive) {
                            vault.setGlobalLockEnabled(true)
                            IntentNavigationHelper.openAccessibilitySettings(context)
                        } else {
                            vault.setGlobalLockEnabled(enabled)
                        }
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )
                SettingClickableRow(
                    icon = Icons.Default.LockClock,
                    title = "Default re-lock delay",
                    subtitle = when (timeoutMode) {
                        LockTimeoutMode.IMMEDIATE -> "Immediately after leaving · for apps not in a profile"
                        LockTimeoutMode.ON_SCREEN_OFF -> "When screen turns off"
                        LockTimeoutMode.GRACE_30_SECONDS -> "30 seconds"
                        LockTimeoutMode.GRACE_1_MINUTE -> "1 minute"
                        LockTimeoutMode.GRACE_5_MINUTES -> "5 minutes"
                        LockTimeoutMode.GRACE_15_MINUTES -> "15 minutes"
                    },
                    onClick = { showTimeoutSheet = true }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )
                SettingSwitchRow(
                    icon = Icons.Default.AdminPanelSettings,
                    title = "Accessibility self-protection",
                    subtitle = if (!a11yActive) {
                        "Requires Accessibility service • Tap to enable"
                    } else if (selfProtectionEnabled) {
                        "Guarding BioKey against uninstallation or tampering in system settings"
                    } else {
                        "Self-protection in system settings is disabled"
                    },
                    checked = selfProtectionEnabled && a11yActive,
                    onCheckedChange = { enabled ->
                        if (enabled && !a11yActive) {
                            vault.setAccessibilitySelfProtectionEnabled(true)
                            IntentNavigationHelper.openAccessibilitySettings(context)
                        } else {
                            vault.setAccessibilitySelfProtectionEnabled(enabled)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Section 2: PRIVACY
            item {
                SettingsSectionHeader("PRIVACY")
                SettingSwitchRow(
                    icon = Icons.Default.NotificationsOff,
                    title = "Notification shield",
                    subtitle = if (!notifListenerActive) {
                        "Requires Notification access • Tap to grant permission"
                    } else if (notificationShieldEnabled) {
                        "Hide notification content for locked apps until unlocked"
                    } else {
                        "Notification content is shown normally"
                    },
                    checked = notificationShieldEnabled && notifListenerActive,
                    onCheckedChange = { enabled ->
                        if (enabled && !notifListenerActive) {
                            vault.setNotificationShield(true)
                            IntentNavigationHelper.openNotificationListenerSettings(context)
                            Toast.makeText(context, "Turn on notification access for BioKey", Toast.LENGTH_SHORT).show()
                        } else {
                            vault.setNotificationShield(enabled)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Section 3: SYSTEM & PREFERENCES
            item {
                SettingsSectionHeader("SYSTEM & PREFERENCES")
                SettingClickableRow(
                    icon = Icons.Default.Security,
                    title = "App lock service",
                    subtitle = if (a11yActive) "Active" else "Disabled • Tap to enable in Accessibility",
                    onClick = {
                        IntentNavigationHelper.openAccessibilitySettings(context)
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )
                SettingClickableRow(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "Background reliability",
                    subtitle = if (isBatteryExempt) "Unrestricted background usage enabled" else "Optimized • Tap to allow unrestricted background execution",
                    onClick = {
                        IntentNavigationHelper.openBatteryOptimization(context)
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )
                SettingSwitchRow(
                    icon = Icons.Default.Tune,
                    title = "Auto-sort into profiles",
                    subtitle = "Puts recognised banking, social and work apps into matching profiles (which locks them)",
                    checked = autoSortProfiles,
                    onCheckedChange = { enabled ->
                        vault.setAutoSortProfilesEnabled(enabled)
                    }
                )
            }
        }
    }

    if (showTimeoutSheet) {
        TimeoutSheet(
            currentMode = timeoutMode,
            onSelectMode = { mode ->
                vault.setTimeoutMode(mode)
                showTimeoutSheet = false
            },
            onDismiss = { showTimeoutSheet = false }
        )
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = { next ->
                    ExpressiveTokens.triggerHaptic(
                        context,
                        if (next) ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON
                        else ExpressiveTokens.HapticFeedbackStyle.TOGGLE_OFF
                    )
                    onCheckedChange(next)
                }
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun SettingClickableRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeoutSheet(
    currentMode: LockTimeoutMode,
    onSelectMode: (LockTimeoutMode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .rejectObscuredTouches()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Re-lock delay",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Apps stay unlocked while you use them. This sets when apps that aren't in a profile lock again after you leave. Profiles set their own.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))

            val options: List<Pair<LockTimeoutMode, String>> = listOf(
                LockTimeoutMode.IMMEDIATE to "Immediately",
                LockTimeoutMode.ON_SCREEN_OFF to "When screen turns off",
                LockTimeoutMode.GRACE_30_SECONDS to "30 seconds",
                LockTimeoutMode.GRACE_1_MINUTE to "1 minute",
                LockTimeoutMode.GRACE_5_MINUTES to "5 minutes",
                LockTimeoutMode.GRACE_15_MINUTES to "15 minutes"
            )

            options.forEach { (mode, label) ->
                val selected = mode == currentMode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch { sheetState.hide() }.invokeOnCompletion { onSelectMode(mode) }
                        }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
