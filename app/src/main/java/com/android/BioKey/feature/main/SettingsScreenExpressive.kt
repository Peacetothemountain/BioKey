package com.android.BioKey.feature.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.HardwareKeyManager
import com.android.BioKey.core.security.SecurityEventLog
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.DeviceAdminHelper
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.ui.theme.ExpressiveTokens
import com.android.BioKey.ui.theme.springPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BioKey settings. Turning anything *on* takes effect immediately; anything that weakens
 * protection is attempted first and, when [SecurityVault] refuses it for lack of a recent
 * authentication, retried after a step-up prompt (see [StepUpAuth]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenExpressive(
    onBack: (() -> Unit)? = null,
    onOpenIntruderVault: (() -> Unit)? = null,
    onOpenPermissions: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember { SecurityVault.getInstance(context) }
    val stepUp = LocalStepUpAuth.current

    val globalLockEnabled by vault.globalLockEnabledFlow.collectAsStateWithLifecycle()
    val timeoutMode by vault.timeoutModeFlow.collectAsStateWithLifecycle()
    val stealthMode by vault.stealthModeGlobalFlow.collectAsStateWithLifecycle()
    val intruderDetection by vault.intruderDetectionFlow.collectAsStateWithLifecycle()
    val overlayShield by vault.overlayShieldFlow.collectAsStateWithLifecycle()
    val selfProtection by vault.selfProtectionFlow.collectAsStateWithLifecycle()
    val autoSortProfiles by vault.autoSortProfilesFlow.collectAsStateWithLifecycle()
    val notificationShield by vault.notificationShieldFlow.collectAsStateWithLifecycle()
    val identityCheck by vault.identityCheckFlow.collectAsStateWithLifecycle()
    val eventLogVersion by SecurityEventLog.version.collectAsStateWithLifecycle()

    var showTimeoutSheet by remember { mutableStateOf(false) }
    var showPauseDialog by remember { mutableStateOf(false) }
    var showEventLog by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
    var reKeyProof by remember { mutableStateOf<HardwareKeyManager.ReKeyProof?>(null) }

    // Permission-dependent state, refreshed whenever the user returns from system settings.
    var a11yActive by remember { mutableStateOf(BioKeyAccessibilityService.isServiceEnabled(context)) }
    var adminActive by remember { mutableStateOf(DeviceAdminHelper.isAdminActive(context)) }
    var hasCamera by remember { mutableStateOf(false) }
    var notificationAccess by remember { mutableStateOf(false) }
    var canPostNotifications by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        a11yActive = BioKeyAccessibilityService.isServiceEnabled(context)
        adminActive = DeviceAdminHelper.isAdminActive(context)
        hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        notificationAccess = BioKeyNotificationShieldService.isNotificationAccessGranted(context)
        canPostNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }

    val diagnostics by produceState<HardwareKeyManager.KeystoreDiagnostics?>(null, reKeyProof) {
        value = withContext(Dispatchers.IO) { HardwareKeyManager.getDiagnostics(context) }
    }

    /** Try the change; if the vault refuses it (protection-reducing), authenticate and retry. */
    fun weaken(reason: String, change: () -> Boolean) {
        if (!change()) stepUp.require(reason) { change() }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        if (granted) {
            vault.setIntruderDetection(true)
        } else {
            Toast.makeText(context, "Intruder photos need camera access", Toast.LENGTH_SHORT).show()
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canPostNotifications = granted
        if (granted && !notificationAccess) {
            context.openSystemSettings(BioKeyNotificationShieldService.getNotificationSettingsIntent())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (onBack != null) {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    Surface(
                        onClick = onBack,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(40.dp)
                            .springPress()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                StatusHero(
                    protecting = globalLockEnabled && a11yActive,
                    title = when {
                        !a11yActive -> "App lock service is off"
                        !globalLockEnabled -> "Protection paused"
                        else -> "Protection active"
                    },
                    subtitle = when {
                        !a11yActive -> "Locked apps open without a check until the service is turned back on"
                        !globalLockEnabled -> "Locked apps open without authentication"
                        else -> "Locked apps require your fingerprint, face, or screen lock"
                    },
                    actionText = when {
                        !a11yActive -> "Turn on"
                        !globalLockEnabled -> "Resume"
                        else -> null
                    },
                    onAction = {
                        if (!a11yActive) {
                            context.openSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } else {
                            vault.resumeProtection()
                        }
                    }
                )
            }

            item { SectionHeaderExpressive("PROTECTION") }

            item {
                IdentityCheckCardExpressive(
                    checked = identityCheck,
                    keyStorage = diagnostics?.securityLevel,
                    onCheckedChange = { checked ->
                        ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON)
                        if (checked) {
                            val status = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                            if (status != BiometricManager.BIOMETRIC_SUCCESS) {
                                Toast.makeText(
                                    context,
                                    "Enroll a fingerprint or face (Class 3) in system settings first",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                vault.setIdentityCheckEnabled(true)
                            }
                        } else {
                            weaken("Turn off Identity Check") { vault.setIdentityCheckEnabled(false) }
                        }
                    }
                )
            }

            item {
                SettingsGroup {
                    DynamicSettingRowExpressive(
                        icon = Icons.Default.Shield,
                        title = "Protection",
                        description = "Require authentication to open locked apps",
                        checked = globalLockEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) vault.resumeProtection() else showPauseDialog = true
                        }
                    )
                    GroupDivider()
                    DynamicSettingRowExpressive(
                        icon = Icons.Default.Layers,
                        title = "Instant cover",
                        description = "Covers a locked app the moment it opens, before the unlock screen appears",
                        checked = overlayShield,
                        onCheckedChange = { checked ->
                            if (checked) vault.setOverlayShield(true)
                            else weaken("Turn off instant cover") { vault.setOverlayShield(false) }
                        }
                    )
                    GroupDivider()
                    DynamicSettingRowExpressive(
                        icon = Icons.Default.AdminPanelSettings,
                        title = "Self-protection",
                        description = if (adminActive) {
                            "Requires authentication on BioKey's App info, accessibility, and uninstall screens"
                        } else {
                            "Uninstall protection (device admin) is off. Tap to turn it on."
                        },
                        checked = selfProtection && adminActive,
                        onCheckedChange = { checked ->
                            when {
                                checked && !adminActive -> {
                                    vault.setSelfProtection(true)
                                    DeviceAdminHelper.launchActivation(context)
                                }
                                checked -> vault.setSelfProtection(true)
                                else -> weaken("Turn off self-protection") { vault.setSelfProtection(false) }
                            }
                        }
                    )
                    GroupDivider()
                    NavigationRow(
                        icon = Icons.Default.LockClock,
                        title = "Re-lock",
                        description = timeoutMode.title,
                        onClick = { showTimeoutSheet = true }
                    )
                }
            }

            item { SectionHeaderExpressive("PRIVACY") }

            item {
                SettingsGroup {
                    DynamicSettingRowExpressive(
                        icon = Icons.Default.NotificationsOff,
                        title = "Notification shield",
                        description = if (notificationAccess) {
                            "Hides notifications from locked apps until you unlock them"
                        } else {
                            "Needs notification access"
                        },
                        checked = notificationShield && notificationAccess,
                        onCheckedChange = { checked ->
                            if (checked) {
                                vault.setNotificationShield(true)
                                when {
                                    !canPostNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    !notificationAccess ->
                                        context.openSystemSettings(BioKeyNotificationShieldService.getNotificationSettingsIntent())
                                }
                            } else {
                                weaken("Turn off notification shield") { vault.setNotificationShield(false) }
                            }
                        }
                    )
                    GroupDivider()
                    DynamicSettingRowExpressive(
                        icon = Icons.Default.RemoveRedEye,
                        title = "Intruder photos",
                        description = "Takes an encrypted front-camera photo after two failed unlocks",
                        checked = intruderDetection && hasCamera,
                        onCheckedChange = { checked ->
                            when {
                                checked && !hasCamera -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                checked -> vault.setIntruderDetection(true)
                                else -> weaken("Turn off intruder photos") { vault.setIntruderDetection(false) }
                            }
                        }
                    )
                    if (onOpenIntruderVault != null) {
                        GroupDivider()
                        NavigationRow(
                            icon = Icons.Default.Shield,
                            title = "View intruder photos",
                            description = "Decrypted on demand, only inside BioKey",
                            onClick = onOpenIntruderVault
                        )
                    }
                    GroupDivider()
                    DynamicSettingRowExpressive(
                        icon = Icons.Default.VisibilityOff,
                        title = "Crash disguise",
                        description = "Shows a fake \"app keeps stopping\" dialog instead of the lock screen. Double-tap it to unlock.",
                        checked = stealthMode,
                        onCheckedChange = { vault.setStealthCrashMode(it) }
                    )
                }
            }

            item { SectionHeaderExpressive("ORGANIZATION") }

            item {
                SettingsGroup {
                    DynamicSettingRowExpressive(
                        icon = Icons.Default.Tune,
                        title = "Suggest profile members",
                        description = "Adds installed apps to matching profiles by name. Never unlocks anything.",
                        checked = autoSortProfiles,
                        onCheckedChange = { checked ->
                            vault.setAutoSortProfilesEnabled(checked)
                            if (checked) {
                                scope.launch(Dispatchers.IO) {
                                    val pm = context.packageManager
                                    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                    val apps = pm.queryIntentActivities(launcher, 0)
                                        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                                        .filter { it.first != context.packageName }
                                        .distinctBy { it.first }
                                    vault.autoSortAppsIntoProfiles(apps, pm)
                                }
                            }
                        }
                    )
                }
            }

            item { SectionHeaderExpressive("SECURITY") }

            item {
                SettingsGroup {
                    NavigationRow(
                        icon = Icons.Default.History,
                        title = "Security log",
                        description = "Pauses, tampering, failed unlocks, and blocked attempts to switch BioKey off",
                        onClick = { showEventLog = true }
                    )
                    if (onOpenPermissions != null) {
                        GroupDivider()
                        NavigationRow(
                            icon = Icons.Default.AdminPanelSettings,
                            title = "Permissions",
                            description = "Protection alerts, camera, notification access",
                            onClick = onOpenPermissions
                        )
                    }
                }
            }

            item {
                KeyStorageCard(
                    diagnostics = diagnostics,
                    onRotate = { confirmRotate = true }
                )
            }
        }
    }

    if (showPauseDialog) {
        PauseProtectionDialog(
            onPause = { duration ->
                showPauseDialog = false
                stepUp.require("Pause protection") { vault.pauseProtection(duration) }
            },
            onDismiss = { showPauseDialog = false }
        )
    }

    if (showTimeoutSheet) {
        TimeoutSheet(
            current = timeoutMode,
            onSelect = { mode ->
                showTimeoutSheet = false
                weaken("Lengthen re-lock delay") { vault.setTimeoutMode(mode) }
            },
            onDismiss = { showTimeoutSheet = false }
        )
    }

    if (showEventLog) {
        EventLogSheet(
            version = eventLogVersion,
            onClear = {
                stepUp.require("Clear security log") { SecurityEventLog.clear(context) }
            },
            onDismiss = { showEventLog = false }
        )
    }

    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text("Rotate authentication keys?") },
            text = {
                Text(
                    "BioKey deletes its unlock keys and creates new ones in the Android Keystore. " +
                        "Intruder photos and the security log stay readable. Use this if you think " +
                        "someone else enrolled a fingerprint or face on this device."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmRotate = false
                    stepUp.require("Rotate keys") {
                        scope.launch {
                            reKeyProof = withContext(Dispatchers.IO) { HardwareKeyManager.performReKeyWithProof(context) }
                        }
                    }
                }) { Text("Rotate") }
            },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text("Cancel") } },
            shape = ExpressiveTokens.Shapes.BentoCardLarge
        )
    }

    reKeyProof?.let { proof ->
        AlertDialog(
            onDismissRequest = { reKeyProof = null },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Keys rotated") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Stored in: ${proof.securityLevel}", style = MaterialTheme.typography.bodyMedium)
                    Text("Created: ${proof.creationTimestamp}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "Fingerprint: ${proof.verificationHash}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { Button(onClick = { reKeyProof = null }) { Text("Done") } },
            shape = ExpressiveTokens.Shapes.BentoCardLarge
        )
    }
}

@Composable
private fun StatusHero(
    protecting: Boolean,
    title: String,
    subtitle: String,
    actionText: String?,
    onAction: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ExpressiveTokens.Shapes.BentoCardLarge,
        color = if (protecting) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (protecting) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (protecting) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (protecting) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actionText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) { Text(actionText, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        shape = ExpressiveTokens.Shapes.BentoCardLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column { content() }
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
}

@Composable
private fun SectionHeaderExpressive(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            letterSpacing = 1.2.sp,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
private fun RowIcon(icon: ImageVector, highlighted: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun DynamicSettingRowExpressive(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val toggle = { value: Boolean ->
        ExpressiveTokens.triggerHaptic(
            context,
            if (value) ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON else ExpressiveTokens.HapticFeedbackStyle.TOGGLE_OFF
        )
        onCheckedChange(value)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { toggle(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon, highlighted = checked)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = toggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        )
    }
}

@Composable
private fun NavigationRow(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE)
                onClick()
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon, highlighted = true)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun KeyStorageCard(diagnostics: HardwareKeyManager.KeystoreDiagnostics?, onRotate: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ExpressiveTokens.Shapes.BentoCardLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowIcon(Icons.Default.Memory, highlighted = diagnostics?.isHardwareKeystoreBacked == true)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Key storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (diagnostics == null) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = diagnostics.securityLevel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "BioKey's keys live in the Android Keystore and can't be exported. Unlock keys only " +
                    "work right after a successful biometric or screen-lock check, and stop working if " +
                    "enrolled biometrics change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onRotate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                shape = CircleShape
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Rotate keys", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeoutSheet(current: LockTimeoutMode, onSelect: (LockTimeoutMode) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.reportsInteraction(),
        shape = ExpressiveTokens.Shapes.BentoCardLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Re-lock apps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "How long an unlocked app stays unlocked after you leave it. Turning the screen off always locks everything.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LockTimeoutMode.entries.forEach { mode ->
                val selected = mode == current
                Surface(
                    onClick = { onSelect(mode) },
                    shape = ExpressiveTokens.Shapes.BentoCardMedium,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mode.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventLogSheet(version: Int, onClear: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val events by produceState<List<SecurityEventLog.Event>?>(null, version) {
        value = withContext(Dispatchers.IO) { SecurityEventLog.events(context) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.reportsInteraction(),
        shape = ExpressiveTokens.Shapes.BentoCardLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Security log", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Encrypted on this device. Newest first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!events.isNullOrEmpty()) {
                    TextButton(onClick = {
                        val uri = SecurityEventLog.exportAuditLog(context)
                        if (uri != null) {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Export Security Audit Log"))
                        } else {
                            Toast.makeText(context, "Could not export audit log", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("Export")
                    }
                    TextButton(onClick = onClear) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            val list = events
            when {
                list == null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                list.isEmpty() -> Text(
                    text = "Nothing recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 36.dp)
                ) {
                    items(list) { event -> EventRow(event) }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: SecurityEventLog.Event) {
    val alarming = event.type in setOf(
        SecurityEventLog.Type.SETTINGS_TAMPERED,
        SecurityEventLog.Type.BIOMETRICS_CHANGED,
        SecurityEventLog.Type.ACCESSIBILITY_STOPPED,
        SecurityEventLog.Type.ADMIN_REMOVED,
        SecurityEventLog.Type.UNLOCK_FAILED
    )
    Surface(
        shape = ExpressiveTokens.Shapes.BentoCardSmall,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (alarming) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (alarming) Icons.Default.Warning else Icons.Default.Security,
                contentDescription = null,
                tint = if (alarming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(event.type.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (event.detail.isNotBlank()) {
                    Text(event.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = DateUtils.getRelativeTimeSpanString(event.timestamp).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IdentityCheckCardExpressive(
    checked: Boolean,
    keyStorage: String?,
    onCheckedChange: (Boolean) -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = ExpressiveTokens.Springs.fast(),
        label = "identity_check_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
        animationSpec = ExpressiveTokens.Springs.fast(),
        label = "identity_check_border"
    )

    Surface(
        shape = ExpressiveTokens.Shapes.BentoCardLarge,
        color = containerColor,
        border = BorderStroke(1.5.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RowIcon(Icons.Default.Security, highlighted = checked)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Identity Check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Biometrics only, no PIN fallback",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    thumbContent = if (checked) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                    } else null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Protects against someone who has seen or forced your PIN: locked apps (and BioKey " +
                    "itself) only accept a Class 3 fingerprint or face. If a fingerprint or face is added or " +
                    "removed, they stay locked for 30 minutes and you're alerted; then your screen lock " +
                    "confirms the change. If none are left, that also turns Identity Check off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            if (keyStorage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Keys: $keyStorage",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
