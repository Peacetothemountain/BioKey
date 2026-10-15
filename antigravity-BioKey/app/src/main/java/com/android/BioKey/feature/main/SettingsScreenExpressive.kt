package com.android.BioKey.feature.main

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.TitanM3SecurityManager
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.BioKeyDeviceAdminReceiver
import com.android.BioKey.feature.lock.DeviceAdminHelper
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.ui.theme.ExpressiveTokens
import com.android.BioKey.ui.theme.expressiveClickable
import com.android.BioKey.ui.theme.springPress
import kotlinx.coroutines.launch

/**
 * Modern Material 3 Expressive Settings Screen (Android Canal / 16 / 17 Standards).
 * Features:
 * - Grouped Bento Containers for cohesive security configuration
 * - Dynamic tactile switches with spring-interpolated container states
 * - Expressive Lock Delay Protocol selection sheet
 * - Silicon Hardware Security telemetry card with living EAL6+ attestation badge
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

    val globalLockEnabled by vault.globalLockEnabledFlow.collectAsState()
    val timeoutMode by vault.timeoutModeFlow.collectAsState()
    val stealthMode by vault.stealthModeGlobalFlow.collectAsState()
    val intruderDetection by vault.intruderDetectionFlow.collectAsState()
    val overlayShield by vault.overlayShieldFlow.collectAsState()
    val selfProtection by vault.selfProtectionFlow.collectAsState()
    val autoSortProfiles by vault.autoSortProfilesFlow.collectAsState()
    val notificationShield by vault.notificationShieldFlow.collectAsState()
    val recentsShield by vault.recentsShieldFlow.collectAsState()
    val identityCheck by vault.identityCheckFlow.collectAsState()

    var showTimeoutSheet by remember { mutableStateOf(false) }
    var showReKeyDialog by remember { mutableStateOf(false) }
    var reKeyProof by remember { mutableStateOf<TitanM3SecurityManager.ReKeyProof?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vault.setIntruderDetection(true)
        } else {
            Toast.makeText(context, "Camera permission required for Intruder Detection", Toast.LENGTH_SHORT).show()
        }
    }

    val postNotificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (!BioKeyNotificationShieldService.isNotificationAccessGranted(context)) {
                context.startActivity(BioKeyNotificationShieldService.getNotificationSettingsIntent())
            }
        } else {
            Toast.makeText(context, "Notification permission required for Privacy Shield", Toast.LENGTH_SHORT).show()
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
                actions = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Security & Permissions Center Quick Entry
            if (onOpenPermissions != null) {
                item {
                    Surface(
                        onClick = onOpenPermissions,
                        shape = ExpressiveTokens.Shapes.BentoCardMedium,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .springPress()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.AdminPanelSettings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Security & Permissions Center",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Manage Accessibility, 0ms Overlay, Device Admin & App Usage",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Shield Engine Status Bento Hero
            item {
                val a11yActive = BioKeyAccessibilityService.isRunning
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveTokens.Shapes.BentoCardLarge,
                    color = if (a11yActive) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (a11yActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (a11yActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = "Status",
                                    tint = if (a11yActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (a11yActive) "Shield Engine Online" else "Accessibility Guard Offline",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (a11yActive) "BioKey Accessibility Service intercepting secured targets" else "Enable BioKey in Accessibility Settings to lock applications",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!a11yActive) {
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.springPress()
                            ) {
                                Text("Enable", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Section 1: Protection Protocols Grouped Bento
            item {
                SectionHeaderExpressive("PROTECTION PROTOCOLS")
            }

            // Android 18 Identity Check Hero Bento Card
            item {
                val titanDiagnostics = remember { TitanM3SecurityManager.INSTANCE.getDiagnostics(context) }
                Android18IdentityCheckCardExpressive(
                    checked = identityCheck,
                    onCheckedChange = { checked ->
                        ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON)
                        if (checked) {
                            val status = com.android.BioKey.core.security.Android18IdentityCheckManager.checkBiometricStrongStatus(context)
                            if (status.statusCode == androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                                Toast.makeText(context, "Enroll Fingerprint or Face Unlock in Android Settings to activate Identity Check", Toast.LENGTH_LONG).show()
                            }
                        }
                        vault.setIdentityCheckEnabled(checked)
                    },
                    diagnostics = titanDiagnostics
                )
            }

            item {
                Surface(
                    shape = ExpressiveTokens.Shapes.BentoCardLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        DynamicSettingRowExpressive(
                            icon = Icons.Default.Shield,
                            title = "Master Shield Protection",
                            description = "Enforces biometric lock on all designated secured apps",
                            checked = globalLockEnabled,
                            onCheckedChange = {
                                ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON)
                                vault.setGlobalLockEnabled(it)
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                        val canOverlay = Settings.canDrawOverlays(context)
                        DynamicSettingRowExpressive(
                            icon = Icons.Default.Layers,
                            title = "0ms Instant Shield Overlay",
                            description = "Instantaneous window manager barrier eliminating switch latency",
                            checked = overlayShield && canOverlay,
                            onCheckedChange = { checked ->
                                ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON)
                                if (checked && !canOverlay) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                                    context.startActivity(intent)
                                } else {
                                    vault.setOverlayShield(checked)
                                }
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                        DynamicSettingRowExpressive(
                            icon = Icons.Default.Tune,
                            title = "Auto-Sort Apps into Profiles",
                            description = "Automatically organizes installed apps into matching security profiles",
                            checked = autoSortProfiles,
                            onCheckedChange = { checked ->
                                ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON)
                                vault.setAutoSortProfilesEnabled(checked)
                                if (checked) {
                                    scope.launch {
                                        val pm = context.packageManager
                                        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                                            .filter { appInfo ->
                                                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                                                val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                                                !isSystem || isUpdatedSystem || appInfo.packageName == "com.android.chrome"
                                            }
                                            .map { it.packageName to (pm.getApplicationLabel(it).toString()) }
                                        vault.autoSortAppsIntoProfiles(apps, pm)
                                    }
                                }
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                        // Lock Delay Protocol Selection Tile
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
                                .expressiveClickable(
                                    hapticStyle = ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE,
                                    onClick = { showTimeoutSheet = true }
                                )
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.LockClock,
                                        contentDescription = "Timeout",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Lock Delay Protocol",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${timeoutMode.title} — ${timeoutMode.description}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Section 2: Stealth & Intruder Defense Grouped Bento
            item {
                SectionHeaderExpressive("STEALTH & INTRUDER DEFENSE")
            }

            item {
                Surface(
                    shape = ExpressiveTokens.Shapes.BentoCardLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        DynamicSettingRowExpressive(
                            icon = Icons.Default.VisibilityOff,
                            title = "Stealth Crash Disguise",
                            description = "Disguises lock screen with fake crash dialog. Double-tap unlocks.",
                            checked = stealthMode,
                            onCheckedChange = {
                                ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON)
                                vault.setStealthCrashMode(it)
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        DynamicSettingRowExpressive(
                            icon = Icons.Default.RemoveRedEye,
                            title = "Intruder Detection",
                            description = "Captures silent photo trap after failed biometric or PIN breaches",
                            checked = intruderDetection && hasCamera,
                            onCheckedChange = { checked ->
                                ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON)
                                vault.setIntruderDetection(checked)
                                if (checked && !hasCamera) {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        )

                        if (onOpenIntruderVault != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .springPress()
                                    .clickable {
                                        ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE)
                                        onOpenIntruderVault()
                                    }
                                    .padding(vertical = 14.dp, horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Shield,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "View Intruder Breach Vault",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Inspect encrypted photo traps and unauthorized breach attempts",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                        val adminComponent = ComponentName(context, BioKeyDeviceAdminReceiver::class.java)
                        val isAdminActive = dpm?.isAdminActive(adminComponent) == true

                        DynamicSettingRowExpressive(
                            icon = Icons.Default.AdminPanelSettings,
                            title = "Device Admin Self-Protection",
                            description = "Prevents malicious force stop or uninstall without authentication",
                            checked = isAdminActive && selfProtection,
                            onCheckedChange = { checked ->
                                ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON)
                                vault.setSelfProtection(checked)
                                if (checked && !isAdminActive) {
                                    DeviceAdminHelper.launchActivation(context)
                                }
                            }
                        )
                    }
                }
            }

            // Section 3: System Privacy Defense Grouped Bento
            item {
                SectionHeaderExpressive("SYSTEM PRIVACY & NOTIFICATION DEFENSE")
            }

            item {
                Surface(
                    shape = ExpressiveTokens.Shapes.BentoCardLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        val notifAccessGranted = BioKeyNotificationShieldService.isNotificationAccessGranted(context)
                        val postNotifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        } else true

                        DynamicSettingRowExpressive(
                            icon = Icons.Default.NotificationsOff,
                            title = "Notification Privacy Shield",
                            description = "Redacts previews, sensitive messages, and OTPs until unlocked",
                            checked = notificationShield && notifAccessGranted && postNotifGranted,
                            onCheckedChange = { checked ->
                                ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON)
                                if (checked) {
                                    vault.setNotificationShield(true)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !postNotifGranted) {
                                        postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else if (!notifAccessGranted) {
                                        context.startActivity(BioKeyNotificationShieldService.getNotificationSettingsIntent())
                                    }
                                } else {
                                    vault.setNotificationShield(false)
                                }
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                        DynamicSettingRowExpressive(
                            icon = Icons.Default.VisibilityOff,
                            title = "App Switcher Thumbnail Defense",
                            description = "Blanks task snapshot previews in Android Recents carousel",
                            checked = recentsShield,
                            onCheckedChange = { checked ->
                                ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON)
                                vault.setRecentsShield(checked)
                            }
                        )
                    }
                }
            }

            // Section 4: Hardware Security (Titan M3 StrongBox)
            item {
                SectionHeaderExpressive("TITAN M3 HARDWARE SECURITY")
            }

            item {
                val titanDiagnostics = remember { TitanM3SecurityManager.INSTANCE.getDiagnostics(context) }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveTokens.Shapes.BentoCardLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(46.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Memory,
                                        contentDescription = "Titan M3",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (titanDiagnostics.isStrongBoxSupported) "Titan M3 StrongBox" else "Hardware TEE Module",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "EAL6+",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = titanDiagnostics.securityLevel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Surface(
                            shape = ExpressiveTokens.Shapes.BentoCardSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Isolated Silicon Cryptographic Co-Processor",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Hardware-bound keys resistant against OS memory dumps and physical probing attacks.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CONFIRM_SUCCESS)
                                val proof = TitanM3SecurityManager.INSTANCE.performReKeyWithProof(context)
                                reKeyProof = proof
                                showReKeyDialog = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .springPress(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            shape = CircleShape
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Rotate Keys & Verify Attestation",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Bottom Sheet for Lock Delay Protocol
    if (showTimeoutSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showTimeoutSheet = false },
            sheetState = sheetState,
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
                Text(
                    text = "Select Lock Delay Protocol",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Defines how quickly secured applications seal after switching or screen off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                LockTimeoutMode.entries.forEach { mode ->
                    val isSelected = mode == timeoutMode
                    Surface(
                        onClick = {
                            ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CONFIRM_SUCCESS)
                            vault.setTimeoutMode(mode)
                            showTimeoutSheet = false
                        },
                        shape = ExpressiveTokens.Shapes.BentoCardMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .springPress()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showReKeyDialog && reKeyProof != null) {
        val proof = reKeyProof!!
        AlertDialog(
            onDismissRequest = { showReKeyDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Hardware Attestation Verified",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "New cryptographic keypair generated inside isolated secure silicon:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = ExpressiveTokens.Shapes.BentoCardSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Security Level: ${proof.securityLevel}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Provider: ${proof.provider}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Timestamp: ${proof.creationTimestamp}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Verification Digest: ${proof.verificationHash}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showReKeyDialog = false },
                    shape = CircleShape,
                    modifier = Modifier.springPress()
                ) {
                    Text("Done")
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = ExpressiveTokens.Shapes.BentoCardLarge
        )
    }
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
private fun DynamicSettingRowExpressive(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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
private fun Android18IdentityCheckCardExpressive(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    diagnostics: TitanM3SecurityManager.TitanM3Diagnostics
) {
    val containerColor by animateColorAsState(
        targetValue = if (checked)
            MaterialTheme.colorScheme.surfaceContainer
        else
            MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = ExpressiveTokens.Springs.fast(),
        label = "identity_check_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        else
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
        animationSpec = ExpressiveTokens.Springs.fast(),
        label = "identity_check_border"
    )

    Surface(
        shape = ExpressiveTokens.Shapes.BentoCardLarge,
        color = containerColor,
        border = BorderStroke(1.5.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .springPress(pressScale = 0.985f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Header Row: Icon, Title + Subtitle, and Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Android 18 Identity Check",
                            tint = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Android 18 Identity Check",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Anti-Theft PIN Protection",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    thumbContent = if (checked) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    } else null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Titan M3 StrongBox Hardware Attestation Badge
            Surface(
                shape = CircleShape,
                color = if (diagnostics.isStrongBoxSupported)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = "Silicon Hardware Chip",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (diagnostics.isStrongBoxSupported) "TITAN M3 STRONGBOX ATTESTED" else "HARDWARE TEE MODULE ATTESTED",
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 0.8.sp,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "EAL6+",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Threat model copy
            Text(
                text = "Guards against PIN shoulder-surfing and theft coercion. Disallows device PIN, pattern, or password fallback on all locked apps, mandating Class 3 hardware biometrics (Fingerprint & 3D Face Unlock).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Bento Security Specs Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = ExpressiveTokens.Shapes.BentoCardSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Class 3",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "Biometric Only",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = ExpressiveTokens.Shapes.BentoCardSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Zero Fallback",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "No PIN / Pattern",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = ExpressiveTokens.Shapes.BentoCardSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Lockout",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "5-Fail Defense",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
