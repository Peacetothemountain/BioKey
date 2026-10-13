package com.android.BioKey.feature.setup

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.DeviceAdminHelper
import com.android.BioKey.feature.main.IntentNavigationHelper
import com.android.BioKey.feature.main.reportsInteraction
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.ui.theme.ExpressiveTokens
import com.android.BioKey.ui.theme.LocalExpressiveHaptics
import com.android.BioKey.ui.theme.M3XBorderDefaults
import com.android.BioKey.ui.theme.springPress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * State snapshot of Android OS permissions and hardware capabilities required by BioKey.
 */
data class SetupState(
    val screenLock: Boolean,
    val accessibility: Boolean,
    val admin: Boolean,
    val notifications: Boolean,
    val camera: Boolean,
    val notificationAccess: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val restrictedSettingsBlocked: Boolean
) {
    val requiredCount get() = listOf(screenLock, accessibility, admin).count { it }
    val requiredReady get() = screenLock && accessibility && admin

    companion object {
        const val REQUIRED_TOTAL = 3

        fun read(context: Context): SetupState {
            val km = context.getSystemService(KeyguardManager::class.java)
            val pm = context.getSystemService(PowerManager::class.java)

            val notifs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.granted(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }

            return SetupState(
                screenLock = km?.isDeviceSecure == true,
                accessibility = BioKeyAccessibilityService.isServiceEnabled(context),
                admin = DeviceAdminHelper.isAdminActive(context),
                notifications = notifs,
                camera = context.granted(Manifest.permission.CAMERA),
                notificationAccess = BioKeyNotificationShieldService.isNotificationAccessGranted(context),
                batteryOptimizationIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) == true,
                restrictedSettingsBlocked = RestrictedSettingsHelper.isRestricted(context)
            )
        }

        private fun Context.granted(permission: String) =
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Observes system secure settings changes for Accessibility and Notification Listeners reactively,
 * eliminating the need for periodic IPC polling loops.
 */
@Composable
private fun rememberPermissionObserver(
    context: Context,
    onPermissionSettingChanged: () -> Unit
) {
    val currentOnChanged by rememberUpdatedState(onPermissionSettingChanged)

    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                currentOnChanged()
            }
        }

        val observedUris = listOfNotNull(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
            Settings.Secure.getUriFor("enabled_notification_listeners")
        )

        for (uri in observedUris) {
            resolver.registerContentObserver(uri, false, observer)
        }

        onDispose {
            resolver.unregisterContentObserver(observer)
        }
    }
}

/**
 * BioKey v5.2.0 Expressive Setup Screen.
 *
 * Implements Android 17 QPR3 sequential progressive disclosure via a 4-step HorizontalPager:
 * - Page 0: Welcome & Privacy Promise (Hero Shield, 100% offline, zero-cloud guarantee).
 * - Page 1: Core Armor (Screen Lock + Accessibility Service with interactive restricted settings helper).
 * - Page 2: System Hardening (Device Admin Uninstall Protection + Battery Optimization + Alerts).
 * - Page 3: Sentinel Shields & Biometric Seal (Intruder Photos + Notification Shield + Vault Activation).
 */
@Composable
fun SetupScreenExpressive(
    repairMode: Boolean = false,
    isAuthenticating: Boolean = false,
    authError: String? = null,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalExpressiveHaptics.current
    val coroutineScope = rememberCoroutineScope()

    var state by remember { mutableStateOf(SetupState.read(context)) }
    var previousState by remember { mutableStateOf(state) }

    var showAccessibilityDisclosure by rememberSaveable { mutableStateOf(false) }
    var showRestrictedSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var hasAttemptedA11y by rememberSaveable { mutableStateOf(false) }

    // Instant re-check upon returning from system settings
    LifecycleResumeEffect(Unit) {
        val fresh = SetupState.read(context)
        state = fresh
        // If user returns from Accessibility and switch was greyed out due to sideload restriction
        if (hasAttemptedA11y && !fresh.accessibility && fresh.restrictedSettingsBlocked) {
            showRestrictedSettingsSheet = true
        }
        onPauseOrDispose { }
    }

    // Zero-overhead reactive ContentObserver for real-time split-screen toggles
    rememberPermissionObserver(context) {
        val fresh = SetupState.read(context)
        if (fresh != state) {
            state = fresh
        }
    }

    // Dual-Axis LRA tactile feedback on permission acquisition
    LaunchedEffect(state) {
        val newlyGranted = (state.requiredCount > previousState.requiredCount) ||
            (!previousState.notifications && state.notifications) ||
            (!previousState.camera && state.camera) ||
            (!previousState.notificationAccess && state.notificationAccess) ||
            (!previousState.batteryOptimizationIgnored && state.batteryOptimizationIgnored)

        if (newlyGranted) {
            haptics.successDoublePulse()
        }
        previousState = state
    }

    // Activity Result Launchers
    val deviceAdminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        state = SetupState.read(context)
        // Dual-check to defeat OEM RESULT_CANCELED and async DPMS binder commit latency
        if (!state.admin) {
            coroutineScope.launch {
                val confirmed = DeviceAdminHelper.verifyAdminActiveWithRetry(context)
                if (confirmed) state = SetupState.read(context)
            }
        }
    }

    var hasRequestedCamera by rememberSaveable { mutableStateOf(false) }
    var hasRequestedNotifications by rememberSaveable { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasRequestedCamera = true
        state = SetupState.read(context)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasRequestedNotifications = true
        state = SetupState.read(context)
    }

    // Sheets
    if (showAccessibilityDisclosure) {
        AccessibilityDisclosureSheet(
            onAccept = {
                showAccessibilityDisclosure = false
                hasAttemptedA11y = true
                IntentNavigationHelper.openAccessibilitySettings(context)
            },
            onDecline = { showAccessibilityDisclosure = false }
        )
    }

    if (showRestrictedSettingsSheet) {
        RestrictedSettingsSheet(
            onDismiss = { showRestrictedSettingsSheet = false }
        )
    }

    if (repairMode) {
        SetupRepairScreen(
            state = state,
            onShowAccessibilityDisclosure = { showAccessibilityDisclosure = true },
            onLaunchAdmin = { DeviceAdminHelper.launchActivation(context, deviceAdminLauncher) },
            onSetupComplete = onSetupComplete
        )
    } else {
        SetupWizardPager(
            state = state,
            isAuthenticating = isAuthenticating,
            authError = authError,
            hasAttemptedA11y = hasAttemptedA11y,
            hasRequestedCamera = hasRequestedCamera,
            hasRequestedNotifications = hasRequestedNotifications,
            onShowAccessibilityDisclosure = { showAccessibilityDisclosure = true },
            onShowRestrictedHelp = { showRestrictedSettingsSheet = true },
            onLaunchAdmin = { DeviceAdminHelper.launchActivation(context, deviceAdminLauncher) },
            onLaunchCamera = {
                hasRequestedCamera = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onLaunchNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasRequestedNotifications = true
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onSetupComplete = onSetupComplete
        )
    }
}

/**
 * 4-Step Material 3 Expressive Progressive Disclosure Wizard.
 */
@Composable
private fun SetupWizardPager(
    state: SetupState,
    isAuthenticating: Boolean,
    authError: String?,
    hasAttemptedA11y: Boolean,
    hasRequestedCamera: Boolean,
    hasRequestedNotifications: Boolean,
    onShowAccessibilityDisclosure: () -> Unit,
    onShowRestrictedHelp: () -> Unit,
    onLaunchAdmin: () -> Unit,
    onLaunchCamera: () -> Unit,
    onLaunchNotifications: () -> Unit,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalExpressiveHaptics.current
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0) { 4 }

    // Predictive back handler for wizard navigation
    PredictiveBackHandler(enabled = pagerState.currentPage > 0) { progressFlow ->
        try {
            progressFlow.collect { }
            haptics.tick()
            pagerState.animateScrollToPage(
                page = pagerState.currentPage - 1,
                animationSpec = ExpressiveTokens.Springs.smooth()
            )
        } catch (_: CancellationException) { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest) // Tone 4 OLED Pitch Black
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Header with Back Button and Bouncy Segmented Pill Indicator
            SetupWizardHeader(
                currentPage = pagerState.currentPage,
                totalPages = 4,
                onBack = {
                    if (pagerState.currentPage > 0 && !pagerState.isScrollInProgress) {
                        coroutineScope.launch {
                            haptics.tick()
                            pagerState.animateScrollToPage(
                                page = pagerState.currentPage - 1,
                                animationSpec = ExpressiveTokens.Springs.smooth()
                            )
                        }
                    }
                }
            )

            // Pager content with programmatic swipe navigation
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                when (page) {
                    0 -> WelcomePrivacyPage()
                    1 -> CoreArmorPage(
                        state = state,
                        hasAttemptedA11y = hasAttemptedA11y,
                        onShowAccessibilityDisclosure = onShowAccessibilityDisclosure,
                        onShowRestrictedHelp = onShowRestrictedHelp
                    )
                    2 -> SystemHardeningPage(
                        state = state,
                        hasRequestedNotifications = hasRequestedNotifications,
                        onLaunchAdmin = onLaunchAdmin,
                        onLaunchNotifications = onLaunchNotifications
                    )
                    3 -> SentinelBiometricPage(
                        state = state,
                        hasRequestedCamera = hasRequestedCamera,
                        onLaunchCamera = onLaunchCamera
                    )
                }
            }

            // Bottom Navigation Dock
            SetupWizardFooter(
                currentPage = pagerState.currentPage,
                state = state,
                isAuthenticating = isAuthenticating,
                authError = authError,
                onNext = {
                    if (pagerState.isScrollInProgress) return@SetupWizardFooter
                    when (pagerState.currentPage) {
                        0 -> coroutineScope.launch {
                            haptics.click()
                            pagerState.animateScrollToPage(1, animationSpec = ExpressiveTokens.Springs.smooth())
                        }
                        1 -> if (state.screenLock && state.accessibility) {
                            coroutineScope.launch {
                                haptics.click()
                                pagerState.animateScrollToPage(2, animationSpec = ExpressiveTokens.Springs.smooth())
                            }
                        }
                        2 -> if (state.admin) {
                            coroutineScope.launch {
                                haptics.click()
                                pagerState.animateScrollToPage(3, animationSpec = ExpressiveTokens.Springs.smooth())
                            }
                        }
                        3 -> if (state.requiredReady && !isAuthenticating) {
                            haptics.click()
                            onSetupComplete()
                        }
                    }
                }
            )
        }
    }
}

/**
 * Top wizard header featuring the Bouncy Segmented Pill Indicator.
 */
@Composable
private fun SetupWizardHeader(
    currentPage: Int,
    totalPages: Int,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (currentPage > 0) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }

        BouncySegmentedPillIndicator(
            totalSteps = totalPages,
            currentStep = currentPage,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.size(48.dp))
    }
}

/**
 * Segmented Pill Indicator where the active step stretches into an expressive 28dp pill.
 */
@Composable
private fun BouncySegmentedPillIndicator(
    totalSteps: Int,
    currentStep: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            progressBarRangeInfo = ProgressBarRangeInfo(
                current = (currentStep + 1).toFloat(),
                range = 1f..totalSteps.toFloat()
            )
            contentDescription = "Step ${currentStep + 1} of $totalSteps"
        },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (step in 0 until totalSteps) {
            val isActive = step == currentStep
            val isCompleted = step < currentStep

            val pillWidth by animateDpAsState(
                targetValue = if (isActive) 28.dp else 8.dp,
                animationSpec = ExpressiveTokens.Springs.bouncy(),
                label = "pill_width_$step"
            )

            val pillColor by animateColorAsState(
                targetValue = when {
                    isActive -> MaterialTheme.colorScheme.primary
                    isCompleted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                },
                animationSpec = ExpressiveTokens.Springs.fast(),
                label = "pill_color_$step"
            )

            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .height(8.dp)
                    .width(pillWidth)
                    .clip(CircleShape)
                    .background(pillColor)
            )
        }
    }
}

/**
 * Page 0: Welcome & Privacy Promise.
 */
@Composable
private fun WelcomePrivacyPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        Surface(
            shape = ExpressiveTokens.Shapes.BentoCardExtraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
            border = M3XBorderDefaults.subtleBorderStroke(),
            modifier = Modifier.size(96.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(52.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Welcome to BioKey",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Hardware-backed biometric armor for your private apps and sensitive data.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        PrivacyPromiseCard(
            icon = Icons.Default.WifiOff,
            title = "100% Offline Architecture",
            description = "BioKey holds zero network permissions. No data, keystrokes, or biometrics can ever leave this device."
        )

        Spacer(modifier = Modifier.height(12.dp))

        PrivacyPromiseCard(
            icon = Icons.Default.VpnKey,
            title = "Hardware Keystore Guard",
            description = "AES-256 keys are anchored in your device's Titan M / Secure Element hardware."
        )

        Spacer(modifier = Modifier.height(12.dp))

        PrivacyPromiseCard(
            icon = Icons.Default.Security,
            title = "Zero Cloud & Zero Telemetry",
            description = "No tracking, no analytics, no external servers, and no accounts. Complete on-device isolation."
        )

        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun PrivacyPromiseCard(icon: ImageVector, title: String, description: String) {
    Surface(
        shape = ExpressiveTokens.Shapes.BentoCardMedium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = M3XBorderDefaults.subtleBorderStroke(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Page 1: Core Armor (Screen Lock + Accessibility Service).
 */
@Composable
private fun CoreArmorPage(
    state: SetupState,
    hasAttemptedA11y: Boolean,
    onShowAccessibilityDisclosure: () -> Unit,
    onShowRestrictedHelp: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalExpressiveHaptics.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Core Armor",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "These required defenses establish hardware encryption keys and detect protected app launches.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        SetupPermissionCardExpressive(
            icon = Icons.Default.Password,
            title = "Device Screen Lock",
            subtitle = "A PIN, pattern, or password. BioKey's hardware encryption keys only exist while your phone is secured.",
            isActive = state.screenLock,
            badgeText = "REQUIRED",
            isMandatory = true,
            actionText = if (state.screenLock) "Active" else "Set up",
            onAction = {
                haptics.click()
                if (!state.screenLock) IntentNavigationHelper.openScreenLockSetup(context)
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        val isRestrictedBlocked = hasAttemptedA11y && !state.accessibility && state.restrictedSettingsBlocked

        SetupPermissionCardExpressive(
            icon = Icons.Default.Shield,
            title = "App Lock Service",
            subtitle = if (isRestrictedBlocked) {
                "Android restricted setting detected. Tap 'Help' to approve in App Info."
            } else {
                "Accessibility service detects when a locked app opens so BioKey can mask it before content is visible."
            },
            isActive = state.accessibility,
            badgeText = "REQUIRED",
            isMandatory = true,
            actionText = if (state.accessibility) "Active" else "Turn on",
            onAction = {
                haptics.click()
                if (!state.accessibility) onShowAccessibilityDisclosure()
            },
            secondaryActionText = if (isRestrictedBlocked) "Help" else null,
            onSecondaryAction = if (isRestrictedBlocked) {
                {
                    haptics.click()
                    onShowRestrictedHelp()
                }
            } else null
        )

        Spacer(modifier = Modifier.height(28.dp))
    }
}

/**
 * Page 2: System Hardening (Device Admin + Battery Optimization + Protection Alerts).
 */
@Composable
private fun SystemHardeningPage(
    state: SetupState,
    hasRequestedNotifications: Boolean,
    onLaunchAdmin: () -> Unit,
    onLaunchNotifications: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalExpressiveHaptics.current
    val activity = remember(context) { context.findActivity() }

    val isNotifsPermanentlyDenied = remember(state.notifications, hasRequestedNotifications, activity) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !state.notifications && hasRequestedNotifications && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "System Hardening",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Hardens BioKey against unauthorized uninstall and background task termination.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        SetupPermissionCardExpressive(
            icon = Icons.Default.AdminPanelSettings,
            title = "Uninstall Protection",
            subtitle = "Device Admin without management policies. Ensures BioKey cannot be uninstalled without biometric verification.",
            isActive = state.admin,
            badgeText = "REQUIRED",
            isMandatory = true,
            actionText = if (state.admin) "Active" else "Activate",
            onAction = {
                haptics.click()
                onLaunchAdmin()
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        SetupPermissionCardExpressive(
            icon = Icons.Default.BatteryChargingFull,
            title = "Background Reliability",
            subtitle = "Exempts BioKey from aggressive OEM battery restrictions so app locking never sleeps or delays.",
            isActive = state.batteryOptimizationIgnored,
            badgeText = "RECOMMENDED",
            isMandatory = false,
            actionText = if (state.batteryOptimizationIgnored) "Exempt" else "Allow",
            onAction = {
                haptics.click()
                if (!state.batteryOptimizationIgnored) {
                    IntentNavigationHelper.openBatteryOptimization(context)
                }
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(modifier = Modifier.height(14.dp))

            SetupPermissionCardExpressive(
                icon = Icons.Default.Notifications,
                title = "Protection Alerts",
                subtitle = if (isNotifsPermanentlyDenied) {
                    "Notifications blocked in system settings. Tap Settings to enable."
                } else {
                    "Alerts you immediately if app protection is paused or security settings are tampered with."
                },
                isActive = state.notifications,
                badgeText = "RECOMMENDED",
                isMandatory = false,
                actionText = when {
                    state.notifications -> "Active"
                    isNotifsPermanentlyDenied -> "Settings"
                    else -> "Allow"
                },
                onAction = {
                    haptics.click()
                    if (isNotifsPermanentlyDenied) {
                        IntentNavigationHelper.openAppNotificationSettings(context)
                    } else if (!state.notifications) {
                        onLaunchNotifications()
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

/**
 * Page 3: Sentinel Shields & Biometric Seal.
 */
@Composable
private fun SentinelBiometricPage(
    state: SetupState,
    hasRequestedCamera: Boolean,
    onLaunchCamera: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalExpressiveHaptics.current
    val activity = remember(context) { context.findActivity() }

    val isCameraPermanentlyDenied = remember(state.camera, hasRequestedCamera, activity) {
        !state.camera && hasRequestedCamera && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Sentinel Shields & Seal",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Configure optional surveillance sentinels, then seal your vault with your biometric master key.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        SetupPermissionCardExpressive(
            icon = Icons.Default.CameraAlt,
            title = "Intruder Photos",
            subtitle = if (isCameraPermanentlyDenied) {
                "Camera access denied. Tap Settings to enable in App Info."
            } else {
                "Takes an encrypted front-camera photo after repeated failed unlocks. Stored strictly on-device."
            },
            isActive = state.camera,
            badgeText = "OPTIONAL",
            isMandatory = false,
            actionText = when {
                state.camera -> "Active"
                isCameraPermanentlyDenied -> "Settings"
                else -> "Enable"
            },
            onAction = {
                haptics.click()
                if (isCameraPermanentlyDenied) {
                    IntentNavigationHelper.openAppDetailsSettings(context)
                } else if (!state.camera) {
                    onLaunchCamera()
                }
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        SetupPermissionCardExpressive(
            icon = Icons.Default.NotificationsOff,
            title = "Notification Shield",
            subtitle = "Hides notifications from locked apps until you unlock them.",
            isActive = state.notificationAccess,
            badgeText = "OPTIONAL",
            isMandatory = false,
            actionText = if (state.notificationAccess) "Active" else "Enable",
            onAction = {
                haptics.click()
                if (!state.notificationAccess) {
                    IntentNavigationHelper.openNotificationListenerSettings(context)
                }
            }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Affirmative Readiness Card
        Surface(
            shape = ExpressiveTokens.Shapes.BentoCardMedium,
            color = if (state.requiredReady) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = M3XBorderDefaults.subtleBorderStroke(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (state.requiredReady) Icons.Default.CheckCircle else Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (state.requiredReady) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (state.requiredReady) {
                        "All required defenses armed. Ready to activate your Master Key."
                    } else {
                        "Required defenses incomplete (${state.requiredCount}/${SetupState.REQUIRED_TOTAL}). Return to previous steps."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (state.requiredReady) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

/**
 * Bottom navigation bar with spring-press buttons, loading feedback, and retry affordance.
 */
@Composable
private fun SetupWizardFooter(
    currentPage: Int,
    state: SetupState,
    isAuthenticating: Boolean,
    authError: String?,
    onNext: () -> Unit
) {
    val canProceed = when (currentPage) {
        0 -> true
        1 -> state.screenLock && state.accessibility
        2 -> state.admin
        3 -> state.requiredReady && !isAuthenticating
        else -> false
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = ExpressiveTokens.Shapes.BentoCardLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = M3XBorderDefaults.subtleBorderStroke()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Error banner if biometric confirmation failed
            if (authError != null && currentPage == 3) {
                Surface(
                    shape = ExpressiveTokens.Shapes.BentoCardSmall,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                    border = M3XBorderDefaults.subtleBorderStroke(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = authError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Button(
                onClick = onNext,
                enabled = canProceed,
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
                    .springPress(enabled = canProceed, hapticsEnabled = false)
            ) {
                if (isAuthenticating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Confirming identity...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = when {
                            currentPage == 3 && authError != null -> Icons.Default.Refresh
                            currentPage == 3 -> Icons.Default.Fingerprint
                            canProceed -> Icons.Default.Check
                            else -> Icons.Default.Lock
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = when (currentPage) {
                            0 -> "Begin Setup"
                            1 -> if (canProceed) "Continue" else "Enable Core Armor (${listOf(state.screenLock, state.accessibility).count { it }}/2)"
                            2 -> if (canProceed) "Continue" else "Activate Uninstall Protection"
                            3 -> when {
                                authError != null -> "Retry confirmation"
                                canProceed -> "Activate BioKey Armor"
                                else -> "Arm Required Defenses (${state.requiredCount}/${SetupState.REQUIRED_TOTAL})"
                            }
                            else -> "Continue"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (currentPage == 3) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        isAuthenticating -> "Waiting for biometric or screen lock confirmation..."
                        authError != null -> "Tap above to try confirming again."
                        else -> "You'll confirm with your fingerprint, face, or screen lock to seal."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (authError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Expressive Bento Permission Card with M3 subtle borders and calm neutral styling.
 */
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
    val colors = MaterialTheme.colorScheme
    val cardBg = colors.surfaceContainerLow

    Surface(
        shape = ExpressiveTokens.Shapes.BentoCardMedium,
        color = cardBg,
        border = M3XBorderDefaults.subtleBorderStroke(),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isActive) { onAction() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isActive) colors.primaryContainer else colors.surfaceContainerHighest,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isActive) Icons.Default.CheckCircle else icon,
                        contentDescription = null,
                        tint = if (isActive) colors.onPrimaryContainer else colors.primary,
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
                        color = if (isActive) colors.primaryContainer.copy(alpha = 0.6f) else colors.surfaceContainerHighest
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isActive) colors.primary else colors.onSurfaceVariant,
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
                        modifier = Modifier.springPress(enabled = true, hapticsEnabled = false)
                    ) {
                        Text(text = secondaryActionText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = onAction,
                    enabled = !isActive,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                        disabledContainerColor = colors.surfaceContainerHighest,
                        disabledContentColor = colors.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.springPress(enabled = !isActive, hapticsEnabled = false)
                ) {
                    Text(text = actionText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Dedicated Repair Mode screen for users with revoked core permissions.
 */
@Composable
private fun SetupRepairScreen(
    state: SetupState,
    onShowAccessibilityDisclosure: () -> Unit,
    onLaunchAdmin: () -> Unit,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalExpressiveHaptics.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Protection is Off",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "A core permission required by BioKey was turned off or revoked. Locked apps are currently unprotected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            if (!state.screenLock) {
                item {
                    SetupPermissionCardExpressive(
                        icon = Icons.Default.Password,
                        title = "Screen Lock",
                        subtitle = "A PIN, pattern, or password is required to hold BioKey's hardware encryption keys.",
                        isActive = state.screenLock,
                        badgeText = "ACTION REQUIRED",
                        isMandatory = true,
                        actionText = "Set up",
                        onAction = {
                            haptics.click()
                            IntentNavigationHelper.openScreenLockSetup(context)
                        }
                    )
                }
            }

            if (!state.accessibility) {
                item {
                    SetupPermissionCardExpressive(
                        icon = Icons.Default.Shield,
                        title = "App Lock Service",
                        subtitle = "Detects when locked apps open. Turn this back on to resume shielding.",
                        isActive = state.accessibility,
                        badgeText = "ACTION REQUIRED",
                        isMandatory = true,
                        actionText = "Turn on",
                        onAction = {
                            haptics.click()
                            onShowAccessibilityDisclosure()
                        }
                    )
                }
            }

            if (!state.admin) {
                item {
                    SetupPermissionCardExpressive(
                        icon = Icons.Default.AdminPanelSettings,
                        title = "Uninstall Protection",
                        subtitle = "Device Admin was disabled. Reactivate to protect against quiet removal.",
                        isActive = state.admin,
                        badgeText = "ACTION REQUIRED",
                        isMandatory = true,
                        actionText = "Activate",
                        onAction = {
                            haptics.click()
                            onLaunchAdmin()
                        }
                    )
                }
            }
        }

        // Bottom continue dock
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            shape = ExpressiveTokens.Shapes.BentoCardLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = M3XBorderDefaults.subtleBorderStroke()
        ) {
            Button(
                onClick = {
                    haptics.biometricSuccess()
                    onSetupComplete()
                },
                enabled = state.requiredReady,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(52.dp)
                    .springPress(enabled = state.requiredReady)
            ) {
                Text(
                    text = if (state.requiredReady) "Continue to BioKey" else "Re-enable Required Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * M3 Expressive ModalBottomSheet for Google Play compliant Accessibility disclosure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccessibilityDisclosureSheet(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDecline,
        sheetState = sheetState,
        modifier = Modifier.reportsInteraction(),
        shape = ExpressiveTokens.Shapes.BentoCardLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Accessibility & Privacy",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Required for real-time app protection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    onClick = onDecline,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Surface(
                shape = ExpressiveTokens.Shapes.BentoCardMedium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = M3XBorderDefaults.subtleBorderStroke(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "WHAT BIOKEY OBSERVES",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.1.sp, fontWeight = FontWeight.ExtraBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    DisclosureRow(
                        icon = Icons.Default.Visibility,
                        title = "Foreground App Detection",
                        description = "Uses the Android AccessibilityService API to observe window state and foreground app package names, displaying the biometric lock screen before protected content is revealed."
                    )
                    DisclosureRow(
                        icon = Icons.Default.Security,
                        title = "Self-Protection Shield",
                        description = "Recognises attempts to disable BioKey, force-stop, or clear app data in Settings, enforcing biometric authentication first."
                    )
                }
            }

            Surface(
                shape = ExpressiveTokens.Shapes.BentoCardMedium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = M3XBorderDefaults.subtleBorderStroke(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "ZERO DATA COLLECTED",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.1.sp, fontWeight = FontWeight.ExtraBold),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    DisclosureRow(
                        icon = Icons.Default.CheckCircle,
                        title = "No Keystroke or Screen Logging",
                        description = "BioKey never inspects text input, typed passwords, clipboard contents, or screen elements."
                    )
                    DisclosureRow(
                        icon = Icons.Default.WifiOff,
                        title = "100% Offline & On-Device",
                        description = "BioKey does not have Android's Internet permission. No telemetry, identifiers, or credentials ever leave this device."
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = onAccept,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .springPress()
                ) {
                    Text(
                        text = "Agree & Continue to Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                TextButton(
                    onClick = onDecline,
                    modifier = Modifier.springPress()
                ) {
                    Text(
                        text = "Not Now",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DisclosureRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
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
    }
}

/**
 * M3 Expressive ModalBottomSheet for Android 13+ Restricted Settings guidance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestrictedSettingsSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Restricted Setting Approval",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Android security requires manual approval",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Text(
                text = "Because BioKey was sideloaded, Android blocks its accessibility switch until approved in App Info. Follow these 3 steps:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = ExpressiveTokens.Shapes.BentoCardMedium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = M3XBorderDefaults.subtleBorderStroke(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StepRow(
                        stepNumber = "1",
                        title = "Open App Info",
                        description = "Tap 'Open App Settings' below to jump directly to BioKey's system settings. (Ensure you attempted to turn on BioKey in Accessibility settings first so Android enables the 3-dot option)."
                    )
                    StepRow(
                        stepNumber = "2",
                        title = "Tap the 3-Dot Menu",
                        description = "In the top-right corner of App Info, tap the three dots icon.",
                        inlineIcon = Icons.Default.MoreVert
                    )
                    StepRow(
                        stepNumber = "3",
                        title = "Allow Restricted Settings",
                        description = "Select 'Allow restricted settings', enter your device PIN/fingerprint, then return here to turn on BioKey."
                    )
                }
            }

            Button(
                onClick = {
                    onDismiss()
                    IntentNavigationHelper.openAppDetailsSettings(context)
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .springPress()
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Open App Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StepRow(
    stepNumber: String,
    title: String,
    description: String,
    inlineIcon: ImageVector? = null
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = stepNumber,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (inlineIcon != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = inlineIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
