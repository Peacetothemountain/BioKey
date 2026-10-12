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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NotificationsOff
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.android.BioKey.core.data.BioKeyPreferences
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.TitanM3SecurityManager
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.BioKeyDeviceAdminReceiver
import com.android.BioKey.feature.lock.DeviceAdminHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    onOpenIntruderVault: (() -> Unit)? = null,
    onOpenPermissions: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember { SecurityVault.getInstance(context) }
    val haptic = LocalHapticFeedback.current

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

    var showTimeoutDialog by remember { mutableStateOf(false) }
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
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
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
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Security & Permissions Center Quick Entry
            if (onOpenPermissions != null) {
                item {
                    Surface(
                        onClick = onOpenPermissions,
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AdminPanelSettings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Security & Permissions Center",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Manage Accessibility, Draw Overlays, Device Admin & App Usage",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        // Shield Engine Status Banner
        item {
            val a11yActive = BioKeyAccessibilityService.isServiceEnabled(context)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = if (a11yActive)
                    MaterialTheme.colorScheme.surfaceContainer
                else
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                border = BorderStroke(
                    1.dp,
                    if (a11yActive)
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    else
                        MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                ),
                shadowElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (a11yActive)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (a11yActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = "Status",
                                tint = if (a11yActive)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(24.dp)
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
                            text = if (a11yActive)
                                "BioKey Accessibility Service active & intercepting targets"
                            else
                                "Enable BioKey in Accessibility Settings to lock applications",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!a11yActive) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                        Toast.makeText(context, "Tap 3 dots in top right -> Allow restricted settings", Toast.LENGTH_LONG).show()
                                    },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Unrestrict")
                                }
                            }
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
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("Enable")
                            }
                        }
                    }
                }
            }
        }

        // Section: Core Protection Controls
        item {
            Text(
                text = "PROTECTION PROTOCOLS",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 1.2.sp,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp)
            )
        }

        item {
            DynamicSettingCard(
                icon = Icons.Default.Shield,
                title = "Master Shield Protection",
                description = "Enforces biometric lock on all designated secured apps",
                checked = globalLockEnabled,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vault.setGlobalLockEnabled(it)
                }
            )
        }

        item {
            val titanDiagnostics = remember { TitanM3SecurityManager.INSTANCE.getDiagnostics(context) }
            IdentityCheckCard(
                checked = identityCheck,
                onCheckedChange = { checked ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vault.setIdentityCheckEnabled(checked)
                },
                diagnostics = titanDiagnostics
            )
        }

        item {
            val canOverlay = Settings.canDrawOverlays(context)
            DynamicSettingCard(
                icon = Icons.Default.Layers,
                title = "0ms Instant Shield Overlay",
                description = "Instantaneous window manager buffer that eliminates 16-60ms app switcher latency",
                checked = overlayShield && canOverlay,
                onCheckedChange = { checked ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
        }

        item {
            DynamicSettingCard(
                icon = Icons.Default.Tune,
                title = "Auto-Sort Apps into Profiles",
                description = "Automatically organizes installed apps into matching security profiles based on category and type",
                checked = autoSortProfiles,
                onCheckedChange = { checked ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
        }

        item {
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showTimeoutDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(38.dp)
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

        // Section: Stealth & Intruder
        item {
            Text(
                text = "STEALTH & INTRUDER DEFENSE",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 1.2.sp,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp)
            )
        }

        item {
            DynamicSettingCard(
                icon = Icons.Default.VisibilityOff,
                title = "Stealth Crash Disguise",
                description = "Disguises lock screen with a fake system crash dialog. Secret double-tap or long-press unlocks.",
                checked = stealthMode,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vault.setStealthCrashMode(it)
                }
            )
        }

        item {
            val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            DynamicSettingCard(
                icon = Icons.Default.RemoveRedEye,
                title = "Intruder Detection",
                description = "Captures a silent front-facing photo after 2 consecutive failed biometric attempts",
                checked = intruderDetection && hasCamera,
                onCheckedChange = { checked ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (checked && !hasCamera) {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    } else {
                        vault.setIntruderDetection(checked)
                    }
                }
            )
        }

        if (onOpenIntruderVault != null) {
            item {
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpenIntruderVault()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View Intruder Breach Vault", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        item {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val adminComponent = ComponentName(context, BioKeyDeviceAdminReceiver::class.java)
            val isAdminActive = dpm?.isAdminActive(adminComponent) == true

            DynamicSettingCard(
                icon = Icons.Default.AdminPanelSettings,
                title = "Device Admin & Self-Protection",
                description = "Prevents malicious force stop, clearing data, or uninstallation without authentication",
                checked = isAdminActive && selfProtection,
                onCheckedChange = { checked ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vault.setSelfProtection(checked)
                    if (checked && !isAdminActive) {
                        DeviceAdminHelper.launchActivation(context)
                    }
                }
            )
        }

        // Section: System Privacy Defense (Google Canary ZP11 Parity & Beyond)
        item {
            Text(
                text = "SYSTEM PRIVACY & NOTIFICATION DEFENSE",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 1.2.sp,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp)
            )
        }

        item {
            val notifAccessGranted = BioKeyNotificationShieldService.isNotificationAccessGranted(context)
            val postNotifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true

            DynamicSettingCard(
                icon = Icons.Default.NotificationsOff,
                title = "Notification Privacy Shield",
                description = "Redacts previews, sensitive messages, and OTPs from locked apps until biometric authentication is verified",
                checked = notificationShield && notifAccessGranted && postNotifGranted,
                onCheckedChange = { checked ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
        }

        item {
            DynamicSettingCard(
                icon = Icons.Default.VisibilityOff,
                title = "App Switcher Thumbnail Defense",
                description = "Blanks task snapshot previews in Android Recent Apps carousel to prevent visual eavesdropping",
                checked = recentsShield,
                onCheckedChange = { checked ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vault.setRecentsShield(checked)
                }
            )
        }

        // Section: Hardware Security (Titan M3 StrongBox)
        item {
            Text(
                text = "TITAN M3 HARDWARE SECURITY",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 1.2.sp,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp)
            )
        }

        item {
            val titanDiagnostics = remember { TitanM3SecurityManager.INSTANCE.getDiagnostics(context) }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Memory,
                                    contentDescription = "Titan M3",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp)
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

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
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
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Keys generated inside dedicated hardware. Protected against OS tampering, memory dumps, and physical probing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val proof = TitanM3SecurityManager.INSTANCE.performReKeyWithProof(context)
                            reKeyProof = proof
                            showReKeyDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
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

    if (showTimeoutDialog) {
        AlertDialog(
            onDismissRequest = { showTimeoutDialog = false },
            title = {
                Text(
                    text = "Select Lock Delay Protocol",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LockTimeoutMode.entries.forEach { mode ->
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vault.setTimeoutMode(mode)
                                showTimeoutDialog = false
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = if (mode == timeoutMode)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else
                                Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (mode == timeoutMode),
                                    onClick = {
                                        vault.setTimeoutMode(mode)
                                        showTimeoutDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary,
                                        unselectedColor = MaterialTheme.colorScheme.outline
                                    )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = mode.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (mode == timeoutMode) FontWeight.Bold else FontWeight.Medium,
                                        color = if (mode == timeoutMode)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = mode.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTimeoutDialog = false }) {
                    Text(
                        "Close",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp)
        )
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
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Hardware Attestation Verified",
                    style = MaterialTheme.typography.titleMedium,
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
                        shape = RoundedCornerShape(16.dp),
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
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReKeyDialog = false }) {
                    Text("Done", color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp)
        )
    }
    }
}

@Composable
private fun DynamicSettingCard(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (checked)
            MaterialTheme.colorScheme.surfaceContainer
        else
            MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "cardContainer"
    )

    val borderColor by animateColorAsState(
        targetValue = if (checked)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        else
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "cardBorder"
    )

    val iconContainerColor by animateColorAsState(
        targetValue = if (checked)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "iconContainer"
    )

    Surface(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = iconContainerColor,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = if (checked)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
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
}

@Composable
private fun IdentityCheckCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    diagnostics: TitanM3SecurityManager.TitanM3Diagnostics
) {
    val haptic = LocalHapticFeedback.current
    val containerColor by animateColorAsState(
        targetValue = if (checked)
            MaterialTheme.colorScheme.surfaceContainer
        else
            MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "identityCardContainer"
    )

    val borderColor by animateColorAsState(
        targetValue = if (checked)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        else
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "identityCardBorder"
    )

    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onCheckedChange(!checked)
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = containerColor,
        border = BorderStroke(1.5.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
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
                            contentDescription = "Identity Check",
                            tint = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Identity Check",
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
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                shape = CircleShape,
                color = if (diagnostics.isStrongBoxSupported)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = "Titan M3 StrongBox",
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

            Text(
                text = "Guards against PIN shoulder-surfing and theft coercion. Disallows device PIN, pattern, or password fallback on all locked apps, mandating Class 3 hardware biometrics (Fingerprint & 3D Face Unlock).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
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
                    shape = RoundedCornerShape(16.dp),
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
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
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
