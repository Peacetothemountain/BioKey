package com.android.BioKey.feature.setup

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.core.security.SecurityVault
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.DeviceAdminHelper

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var a11yActive by remember { mutableStateOf(BioKeyAccessibilityService.isRunning) }
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isAdminActive by remember { mutableStateOf(DeviceAdminHelper.isAdminActive(context)) }
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
    var hasUsage by remember {
        mutableStateOf(
            appOps?.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        )
    }
    var hasNotifShield by remember {
        mutableStateOf(
            BioKeyNotificationShieldService.isNotificationAccessGranted(context) &&
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true)
        )
    }

    // Refresh permission states on resume when returning from Android Settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                a11yActive = BioKeyAccessibilityService.isRunning
                canDrawOverlays = Settings.canDrawOverlays(context)
                isAdminActive = DeviceAdminHelper.isAdminActive(context)
                hasCamera = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                hasUsage = appOps?.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                ) == AppOpsManager.MODE_ALLOWED
                hasNotifShield = BioKeyNotificationShieldService.isNotificationAccessGranted(context) &&
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    } else true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val deviceAdminLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isAdminActive = DeviceAdminHelper.isAdminActive(context)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCamera = granted
        if (!granted) {
            Toast.makeText(context, "Camera permission needed for Intruder Selfie", Toast.LENGTH_SHORT).show()
        }
    }

    val postNotificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (!BioKeyNotificationShieldService.isNotificationAccessGranted(context)) {
                context.startActivity(BioKeyNotificationShieldService.getNotificationSettingsIntent())
            } else {
                hasNotifShield = true
            }
        } else {
            Toast.makeText(context, "Notification permission needed for Privacy Shield", Toast.LENGTH_SHORT).show()
        }
    }

    val isRequiredReady = a11yActive && canDrawOverlays && isAdminActive
    val requiredCount = (if (a11yActive) 1 else 0) + (if (canDrawOverlays) 1 else 0) + (if (isAdminActive) 1 else 0)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 48.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Section
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(68.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "BioKey",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Initial Security Setup",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Configure defense protocols to enable hardware-backed application locking.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Progress Status Chip
                    Surface(
                        shape = CircleShape,
                        color = if (isRequiredReady)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        border = BorderStroke(
                            1.dp,
                            if (isRequiredReady) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isRequiredReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isRequiredReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRequiredReady) "Core Protection Configured (3/3)"
                                else "Required Steps: $requiredCount of 3 Granted",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isRequiredReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Section 1: Required Core Defenses
            item {
                Text(
                    text = "REQUIRED DEFENSE PROTOCOLS (3/3)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            // 1. Accessibility Service
            item {
                SetupPermissionCard(
                    icon = Icons.Default.Shield,
                    title = "Accessibility Shield Service",
                    subtitle = "Foreground application detection to intercept secured apps instantly",
                    isActive = a11yActive,
                    badgeText = "REQUIRED",
                    isMandatory = true,
                    actionText = if (a11yActive) "Active" else "Enable Service",
                    onAction = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    },
                    secondaryActionText = if (!a11yActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "Unrestrict" else null,
                    onSecondaryAction = if (!a11yActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                            Toast.makeText(context, "Tap 3 dots in top right -> Allow restricted settings", Toast.LENGTH_LONG).show()
                        }
                    } else null
                )
            }

            // 2. 0ms Overlay Shield
            item {
                SetupPermissionCard(
                    icon = Icons.Default.Layers,
                    title = "0ms Instant Overlay Shield",
                    subtitle = "Draws hardware biometric lock screen immediately over protected apps",
                    isActive = canDrawOverlays,
                    badgeText = "REQUIRED",
                    isMandatory = true,
                    actionText = if (canDrawOverlays) "Active" else "Grant Overlay",
                    onAction = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                )
            }

            // 3. Device Administrator
            item {
                SetupPermissionCard(
                    icon = Icons.Default.AdminPanelSettings,
                    title = "Device Administrator Policy",
                    subtitle = "Prevents uninstallation, force stopping, or tampering without authentication",
                    isActive = isAdminActive,
                    badgeText = "REQUIRED",
                    isMandatory = true,
                    actionText = if (isAdminActive) "Active" else "Activate Admin",
                    onAction = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        DeviceAdminHelper.launchActivation(context, deviceAdminLauncher)
                    }
                )
            }

            // Section 2: Optional Defenses
            item {
                Text(
                    text = "OPTIONAL DEFENSE FEATURES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp)
                )
            }

            // 4. Intruder Camera
            item {
                SetupPermissionCard(
                    icon = Icons.Default.CameraAlt,
                    title = "Intruder Selfie Detection",
                    subtitle = "Silently photographs unauthorized breach attempts after failed biometric attempts",
                    isActive = hasCamera,
                    badgeText = "OPTIONAL",
                    isMandatory = false,
                    actionText = if (hasCamera) "Active" else "Grant Camera",
                    onAction = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (!hasCamera) {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        } else {
                            Toast.makeText(context, "Camera permission already granted", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // 5. Usage Stats
            item {
                SetupPermissionCard(
                    icon = Icons.Default.DataUsage,
                    title = "Usage Statistics Access",
                    subtitle = "Backup app state tracking for devices with background power restrictions",
                    isActive = hasUsage,
                    badgeText = "OPTIONAL",
                    isMandatory = false,
                    actionText = if (hasUsage) "Active" else "Grant Access",
                    onAction = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                )
            }

            // 6. Notification Privacy Shield
            item {
                SetupPermissionCard(
                    icon = Icons.Default.NotificationsOff,
                    title = "Notification Privacy Shield",
                    subtitle = "Redacts confidential previews, messages, and OTPs from locked apps",
                    isActive = hasNotifShield,
                    badgeText = "OPTIONAL",
                    isMandatory = false,
                    actionText = if (hasNotifShield) "Active" else "Grant Access",
                    onAction = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val postNotifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        } else true

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !postNotifGranted) {
                            postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else if (!BioKeyNotificationShieldService.isNotificationAccessGranted(context)) {
                            context.startActivity(BioKeyNotificationShieldService.getNotificationSettingsIntent())
                        } else {
                            Toast.makeText(context, "Notification Privacy Shield active", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }

        // Sticky Bottom Completion Bar
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSetupComplete()
                    },
                    enabled = isRequiredReady,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(
                        imageVector = if (isRequiredReady) Icons.Default.Lock else Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRequiredReady) "Complete Setup & Authenticate" else "Grant Required Permissions ($requiredCount/3)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Biometric authentication will be required once to seal hardware security.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SetupPermissionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isActive: Boolean,
    badgeText: String,
    isMandatory: Boolean,
    actionText: String,
    onAction: () -> Unit,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (isActive) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            else if (isMandatory) MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isActive)
                    MaterialTheme.colorScheme.primaryContainer
                else if (isMandatory)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isActive) Icons.Default.CheckCircle else icon,
                        contentDescription = null,
                        tint = if (isActive)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else if (isMandatory)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = CircleShape,
                        color = if (isActive)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else if (isMandatory)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isActive)
                                MaterialTheme.colorScheme.primary
                            else if (isMandatory)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (secondaryActionText != null && onSecondaryAction != null) {
                    OutlinedButton(
                        onClick = onSecondaryAction,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = secondaryActionText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Button(
                    onClick = onAction,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActive)
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        else if (isMandatory)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary,
                        contentColor = if (isActive)
                            MaterialTheme.colorScheme.primary
                        else if (isMandatory)
                            MaterialTheme.colorScheme.onError
                        else
                            MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = actionText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
