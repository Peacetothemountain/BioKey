package com.android.BioKey.feature.setup

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.DeviceAdminHelper
import com.android.BioKey.feature.main.openSystemSettings
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.ui.theme.ExpressiveTokens
import com.android.BioKey.ui.theme.springPress

private data class SetupState(
    val screenLock: Boolean,
    val accessibility: Boolean,
    val admin: Boolean,
    val notifications: Boolean,
    val camera: Boolean,
    val notificationAccess: Boolean,
    val batteryOptimizationIgnored: Boolean
) {
    val requiredCount get() = listOf(screenLock, accessibility).count { it }
    val requiredReady get() = screenLock && accessibility

    companion object {
        const val REQUIRED_TOTAL = 2

        fun read(context: Context): SetupState {
            val pm = context.getSystemService(PowerManager::class.java)
            return SetupState(
                screenLock = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true,
                accessibility = BioKeyAccessibilityService.isServiceEnabled(context),
                admin = DeviceAdminHelper.isAdminActive(context),
                notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.granted(Manifest.permission.POST_NOTIFICATIONS),
                camera = context.granted(Manifest.permission.CAMERA),
                notificationAccess = BioKeyNotificationShieldService.isNotificationAccessGranted(context),
                batteryOptimizationIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            )
        }

        private fun Context.granted(permission: String) =
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * First-run setup, and the repair screen shown when a required permission was revoked later
 * ([repairMode]). Required: a device screen lock (Keystore keys depend on it), the accessibility
 * service (detects locked apps) and the device admin (blocks one-step uninstall).
 */
@Composable
fun SetupScreenExpressive(
    repairMode: Boolean,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(SetupState.read(context)) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }

    // Re-check whenever the user comes back from a system settings page.
    LifecycleResumeEffect(Unit) {
        state = SetupState.read(context)
        onPauseOrDispose { }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(1000L)
                val fresh = SetupState.read(context)
                if (fresh != state) {
                    state = fresh
                }
            }
        }
    }

    val deviceAdminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        state = SetupState.read(context)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        state = SetupState.read(context)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        state = SetupState.read(context)
    }

    val progressFraction by animateFloatAsState(
        targetValue = state.requiredCount / SetupState.REQUIRED_TOTAL.toFloat(),
        animationSpec = ExpressiveTokens.Springs.BouncySpatial,
        label = "setup_progress"
    )

    if (showAccessibilityDisclosure) {
        AccessibilityDisclosureDialog(
            onAccept = {
                showAccessibilityDisclosure = false
                context.openSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onDecline = { showAccessibilityDisclosure = false }
        )
    }

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
            item {
                SetupHeader(
                    repairMode = repairMode,
                    requiredCount = state.requiredCount,
                    ready = state.requiredReady,
                    progressFraction = progressFraction
                )
            }

            item { SectionLabel("REQUIRED (${state.requiredCount}/${SetupState.REQUIRED_TOTAL})", MaterialTheme.colorScheme.primary) }

            item {
                SetupPermissionCardExpressive(
                    icon = Icons.Default.Password,
                    title = "Screen lock",
                    subtitle = "A PIN, pattern, or password. BioKey's hardware keys only exist while the device has one.",
                    isActive = state.screenLock,
                    badgeText = "REQUIRED",
                    isMandatory = true,
                    actionText = if (state.screenLock) "Set" else "Set up",
                    onAction = {
                        if (!state.screenLock) context.openSystemSettings(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    }
                )
            }

            item {
                val canUnrestrict = !state.accessibility && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                SetupPermissionCardExpressive(
                    icon = Icons.Default.Shield,
                    title = "App lock service",
                    subtitle = "Detects when a locked app opens so BioKey can cover it before its content is visible.",
                    isActive = state.accessibility,
                    badgeText = "REQUIRED",
                    isMandatory = true,
                    actionText = if (state.accessibility) "On" else "Turn on",
                    onAction = {
                        ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CONFIRM_SUCCESS)
                        if (!state.accessibility) showAccessibilityDisclosure = true
                    },
                    secondaryActionText = if (canUnrestrict) "Allow" else null,
                    onSecondaryAction = if (canUnrestrict) {
                        {
                            Toast.makeText(
                                context,
                                "If the switch is greyed out: ⋮ menu → Allow restricted settings",
                                Toast.LENGTH_LONG
                            ).show()
                            context.openSystemSettings(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            )
                        }
                    } else null
                )
            }

            item { SectionLabel("RECOMMENDED", MaterialTheme.colorScheme.onSurfaceVariant) }

            item {
                SetupPermissionCardExpressive(
                    icon = Icons.Default.AdminPanelSettings,
                    title = "Uninstall protection",
                    subtitle = "Device admin with no policies. BioKey must be deactivated (behind a biometric check) before it can be uninstalled.",
                    isActive = state.admin,
                    badgeText = "RECOMMENDED",
                    isMandatory = false,
                    actionText = if (state.admin) "On" else "Turn on",
                    onAction = {
                        ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CONFIRM_SUCCESS)
                        if (!state.admin) DeviceAdminHelper.launchActivation(context, deviceAdminLauncher)
                    }
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item {
                    SetupPermissionCardExpressive(
                        icon = Icons.Default.Notifications,
                        title = "Protection alerts",
                        subtitle = "Warns you if app locking is switched off or BioKey's settings are tampered with.",
                        isActive = state.notifications,
                        badgeText = "RECOMMENDED",
                        isMandatory = false,
                        actionText = if (state.notifications) "On" else "Allow",
                        onAction = {
                            if (!state.notifications) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )
                }
            }

            item {
                SetupPermissionCardExpressive(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "Background reliability",
                    subtitle = "Exempts BioKey from aggressive OEM battery restrictions so app locking never sleeps or delays.",
                    isActive = state.batteryOptimizationIgnored,
                    badgeText = "RECOMMENDED",
                    isMandatory = false,
                    actionText = if (state.batteryOptimizationIgnored) "Exempt" else "Allow",
                    onAction = {
                        if (!state.batteryOptimizationIgnored) {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.openSystemSettings(intent)
                        }
                    }
                )
            }

            item {
                SetupPermissionCardExpressive(
                    icon = Icons.Default.CameraAlt,
                    title = "Intruder photos",
                    subtitle = "Takes an encrypted front-camera photo after repeated failed unlocks. Photos never leave the device.",
                    isActive = state.camera,
                    badgeText = "OPTIONAL",
                    isMandatory = false,
                    actionText = if (state.camera) "On" else "Allow",
                    onAction = {
                        if (!state.camera) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                )
            }

            item {
                SetupPermissionCardExpressive(
                    icon = Icons.Default.NotificationsOff,
                    title = "Notification shield",
                    subtitle = "Hides notifications from locked apps until you unlock them.",
                    isActive = state.notificationAccess,
                    badgeText = "OPTIONAL",
                    isMandatory = false,
                    actionText = if (state.notificationAccess) "On" else "Allow",
                    onAction = {
                        if (!state.notificationAccess) {
                            context.openSystemSettings(BioKeyNotificationShieldService.getNotificationSettingsIntent())
                        }
                    }
                )
            }
        }

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
                    enabled = state.requiredReady,
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
                        imageVector = if (state.requiredReady) Icons.Default.Lock else Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = when {
                            !state.requiredReady -> "Finish required steps (${state.requiredCount}/${SetupState.REQUIRED_TOTAL})"
                            repairMode -> "Continue"
                            else -> "Finish setup"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (!repairMode) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "You'll confirm with your fingerprint, face, or screen lock to finish.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupHeader(repairMode: Boolean, requiredCount: Int, ready: Boolean, progressFraction: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = if (repairMode) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (repairMode) Icons.Default.Warning else Icons.Default.Security,
                    contentDescription = null,
                    tint = if (repairMode) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(38.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (repairMode) "Protection is off" else "Set up BioKey",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = if (repairMode) {
                "A permission BioKey needs was turned off, so locked apps currently open without a check. Turn it back on below."
            } else {
                "Three permissions let BioKey lock your apps and stop itself from being quietly switched off."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(18.dp))

        Surface(
            shape = ExpressiveTokens.Shapes.BentoCardMedium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (ready) "All required steps done" else "$requiredCount of ${SetupState.REQUIRED_TOTAL} required steps done",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.ExtraBold),
        color = color,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

/**
 * Prominent disclosure required before asking for the accessibility service: what is read, why,
 * and that nothing leaves the device.
 */
@Composable
private fun AccessibilityDisclosureDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        icon = { Icon(Icons.Default.Shield, contentDescription = null) },
        title = { Text("How BioKey uses accessibility") },
        text = {
            Text(
                "BioKey uses the Accessibility API to:\n\n" +
                    "• See which app is in the foreground, so it can lock apps you've chosen to protect.\n" +
                    "• Recognise BioKey's own App info, accessibility, and uninstall screens, so switching " +
                    "BioKey off requires your fingerprint, face, or screen lock.\n\n" +
                    "BioKey doesn't read what you type, doesn't record your screen, and sends no data " +
                    "anywhere. It has no internet permission.\n\n" +
                    "On the next screen, choose BioKey and turn it on.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = { Button(onClick = onAccept) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Not now") } }
    )
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
    val colors = MaterialTheme.colorScheme

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
                color = when {
                    isActive -> colors.primaryContainer
                    isMandatory -> colors.errorContainer
                    else -> colors.surfaceContainerHighest
                },
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isActive) Icons.Default.CheckCircle else icon,
                        contentDescription = null,
                        tint = when {
                            isActive -> colors.onPrimaryContainer
                            isMandatory -> colors.onErrorContainer
                            else -> colors.onSurfaceVariant
                        },
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
                        color = colors.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = CircleShape,
                        color = when {
                            isActive -> colors.primaryContainer.copy(alpha = 0.6f)
                            isMandatory -> colors.errorContainer
                            else -> colors.surfaceContainerHighest
                        }
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            fontWeight = FontWeight.ExtraBold,
                            color = when {
                                isActive -> colors.primary
                                isMandatory -> colors.error
                                else -> colors.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
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
                        Text(text = secondaryActionText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = onAction,
                    enabled = !isActive,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMandatory) colors.error else colors.primary,
                        contentColor = if (isMandatory) colors.onError else colors.onPrimary,
                        disabledContainerColor = colors.surfaceContainerHighest,
                        disabledContentColor = colors.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.springPress(enabled = !isActive)
                ) {
                    Text(text = actionText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
