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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.ui.theme.ExpressiveTokens
import com.android.BioKey.ui.theme.springPress

/**
 * Modern Material 3 Expressive Initial Setup Screen (Android Canal / 16 / 17 Standards).
 * Features:
 * - Segmented 3-part animated hardware defense progress indicator
 * - Bento permission cards with dynamic state transitions
 * - Expressive floating sticky bottom action capsule
 * - Multi-tier tactile haptic feedback
 */
@Composable
fun SetupScreenExpressive(
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current

    var a11yActive by remember { mutableStateOf(BioKeyAccessibilityService.isServiceEnabled(context)) }
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

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                a11yActive = BioKeyAccessibilityService.isServiceEnabled(context)
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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            a11yActive = BioKeyAccessibilityService.isServiceEnabled(context)
            canDrawOverlays = Settings.canDrawOverlays(context)
            isAdminActive = DeviceAdminHelper.isAdminActive(context)
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

    val isRequiredReady = a11yActive && isAdminActive
    val requiredCount = (if (a11yActive) 1 else 0) + (if (isAdminActive) 1 else 0)

    val progressFraction by animateFloatAsState(
        targetValue = requiredCount / 2f,
        animationSpec = ExpressiveTokens.Springs.BouncySpatial,
        label = "setup_progress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 28.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Hero Section
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "BioKey",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(38.dp)
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
                        text = "Configure hardware defense protocols to seal your application vault.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Progress Status Bar & Pill
                    Surface(
                        shape = ExpressiveTokens.Shapes.BentoCardMedium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isRequiredReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (isRequiredReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isRequiredReady) "All Required Defenses Active" else "Defense Progress: $requiredCount of 2",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isRequiredReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Surface(
                                    shape = CircleShape,
                                    color = if (isRequiredReady) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                                ) {
                                    Text(
                                        text = "${(progressFraction * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (isRequiredReady) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(CircleShape),
                                color = if (isRequiredReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        }
                    }
                }
            }

            // Section 1: Required Protocols
            item {
                Text(
                    text = "REQUIRED DEFENSE PROTOCOLS ($requiredCount/2)",
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
                SetupPermissionCardExpressive(
                    icon = Icons.Default.Shield,
                    title = "Accessibility Shield Service",
                    subtitle = "Foreground application interception to block locked apps instantly",
                    isActive = a11yActive,
                    badgeText = "REQUIRED",
                    isMandatory = true,
                    actionText = if (a11yActive) "Active" else "Enable Service",
                    onAction = {
                        ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CONFIRM_SUCCESS)
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

            // 2. Device Administrator Policy
            item {
                SetupPermissionCardExpressive(
                    icon = Icons.Default.AdminPanelSettings,
                    title = "Device Administrator Policy",
                    subtitle = "Prevents tampering, malicious force stopping, or unauthorized uninstall",
                    isActive = isAdminActive,
                    badgeText = "REQUIRED",
                    isMandatory = true,
                    actionText = if (isAdminActive) "Active" else "Activate Admin",
                    onAction = {
                        ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CONFIRM_SUCCESS)
                        DeviceAdminHelper.launchActivation(context, deviceAdminLauncher)
                    }
                )
            }

            // Section 2: Recommended & Optional Defenses
            item {
                Text(
                    text = "RECOMMENDED & OPTIONAL DEFENSES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }

            // 3. 0ms Overlay Shield (Recommended)
            item {
                SetupPermissionCardExpressive(
                    icon = Icons.Default.Layers,
                    title = "0ms Instant Overlay Shield",
                    subtitle = "Draws hardware biometric lock screen immediately over protected apps",
                    isActive = canDrawOverlays,
                    badgeText = "RECOMMENDED",
                    isMandatory = false,
                    actionText = if (canDrawOverlays) "Active" else "Grant Overlay",
                    onAction = {
                        ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CONFIRM_SUCCESS)
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                        context.startActivity(intent)
                    }
                )
            }


            // 4. Intruder Selfie
            item {
                SetupPermissionCardExpressive(
                    icon = Icons.Default.CameraAlt,
                    title = "Intruder Selfie Detection",
                    subtitle = "Silently photographs unauthorized breach attempts after failed biometrics",
                    isActive = hasCamera,
                    badgeText = "OPTIONAL",
                    isMandatory = false,
                    actionText = if (hasCamera) "Active" else "Grant Camera",
                    onAction = {
                        ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE)
                        if (!hasCamera) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        else Toast.makeText(context, "Camera permission already granted", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // 5. Usage Statistics Access
            item {
                SetupPermissionCardExpressive(
                    icon = Icons.Default.DataUsage,
                    title = "Usage Statistics Access",
                    subtitle = "Backup app state tracking for devices with background power restrictions",
                    isActive = hasUsage,
                    badgeText = "OPTIONAL",
                    isMandatory = false,
                    actionText = if (hasUsage) "Active" else "Grant Access",
                    onAction = {
                        ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE)
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                )
            }

            // 6. Notification Privacy Shield
            item {
                SetupPermissionCardExpressive(
                    icon = Icons.Default.NotificationsOff,
                    title = "Notification Privacy Shield",
                    subtitle = "Redacts confidential previews, messages, and OTPs from locked apps",
                    isActive = hasNotifShield,
                    badgeText = "OPTIONAL",
                    isMandatory = false,
                    actionText = if (hasNotifShield) "Active" else "Grant Access",
                    onAction = {
                        ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE)
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

        // Floating Sticky Bottom Completion Bar
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            shape = ExpressiveTokens.Shapes.BentoCardLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CONFIRM_SUCCESS)
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
                        .height(54.dp)
                        .springPress()
                ) {
                    Icon(
                        imageVector = if (isRequiredReady) Icons.Default.Lock else Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isRequiredReady) "Complete Setup & Authenticate" else "Grant Required Permissions ($requiredCount/2)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Hardware biometrics will seal the Titan M3 cryptographic module.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SetupPermissionCardExpressive(
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
    val cardBg by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = ExpressiveTokens.Springs.fast(),
        label = "setup_card_bg"
    )

    Surface(
        shape = ExpressiveTokens.Shapes.BentoCardMedium,
        color = cardBg,
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
                color = if (isActive) MaterialTheme.colorScheme.primaryContainer else if (isMandatory) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isActive) Icons.Default.CheckCircle else icon,
                        contentDescription = null,
                        tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else if (isMandatory) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
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
                        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else if (isMandatory) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isActive) MaterialTheme.colorScheme.primary else if (isMandatory) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.springPress()
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
                        containerColor = if (isActive) MaterialTheme.colorScheme.surfaceContainerHighest else if (isMandatory) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor = if (isActive) MaterialTheme.colorScheme.primary else if (isMandatory) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.springPress()
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
