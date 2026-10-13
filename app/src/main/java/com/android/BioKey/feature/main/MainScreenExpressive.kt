package com.android.BioKey.feature.main

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.BioKey.R
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.intruder.IntruderManager
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.HardwareKeyManager
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.DeviceAdminHelper
import com.android.BioKey.ui.theme.ExpressiveTokens
import com.android.BioKey.ui.theme.expressiveClickable
import com.android.BioKey.ui.theme.springPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Modern Material 3 Expressive (October 2026 Android Canal / 16 / 17 Standards) Main Screen.
 * Demonstrates:
 * - Fluid spatial spring physics across all cards, chips, and modals
 * - Distinct tonal container roles (surfaceContainerLowest through surfaceContainerHighest)
 * - Living interactive ambient particle constellation
 * - Tactile multi-tier haptic feedback
 * - Bento-style application security rows with instant visual feedback
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenExpressive() {
    val context = LocalContext.current
    // Icons are drawn at up to 54dp; rendering them at their intrinsic size (adaptive icons are
    // 108dp) costs ~4x the memory, which adds up to an OOM with a few hundred apps.
    val iconPx = with(LocalDensity.current) { ICON_RENDER_DP.dp.roundToPx() }
    val vault = remember { SecurityVault.getInstance(context) }

    val lockedApps by vault.lockedAppsFlow.collectAsStateWithLifecycle()
    val globalLockEnabled by vault.globalLockEnabledFlow.collectAsStateWithLifecycle()
    val profiles by vault.profilesFlow.collectAsStateWithLifecycle()
    val activeProfileId by vault.activeProfileIdFlow.collectAsStateWithLifecycle()

    var installedApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    var selectedAppForDetails by remember { mutableStateOf<AppItem?>(null) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }
    var showIntruderVaultSheet by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<SecurityProfile?>(null) }
    var showProfileEditor by remember { mutableStateOf(false) }
    var showPermissionsSheet by remember { mutableStateOf(false) }
    var showAllAppsActionsSheet by remember { mutableStateOf(false) }
    var showPauseDialog by remember { mutableStateOf(false) }
    val pausedUntil by vault.pausedUntilFlow.collectAsStateWithLifecycle()
    val stepUp = LocalStepUpAuth.current

    var intruderCount by remember { mutableIntStateOf(0) }

    /** Locking is immediate; unlocking (protection-reducing) needs a fresh authentication. */
    fun setLocked(app: AppItem, lock: Boolean, profile: SecurityProfile?) {
        ExpressiveTokens.triggerHaptic(
            context,
            if (lock) ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON else ExpressiveTokens.HapticFeedbackStyle.TOGGLE_OFF
        )
        when {
            lock && profile != null -> vault.addAppToProfile(profile.id, app.packageName)
            lock -> vault.setAppLock(app.packageName, true)
            profile != null -> stepUp.require("Unlock ${app.label}") { vault.removeAppFromProfile(profile.id, app.packageName) }
            else -> stepUp.require("Unlock ${app.label}") { vault.setAppLock(app.packageName, false) }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcherApps = context.getSystemService(android.content.pm.LauncherApps::class.java)
            val userManager = context.getSystemService(android.os.UserManager::class.java)
            val userProfiles = userManager?.userProfiles ?: emptyList()
            val myUser = android.os.Process.myUserHandle()

            val items = mutableListOf<AppItem>()
            val seenKeys = mutableSetOf<String>()

            if (launcherApps != null && userProfiles.isNotEmpty()) {
                for (user in userProfiles) {
                    val isWork = (user != myUser)
                    val activities = runCatching { launcherApps.getActivityList(null, user) }.getOrNull().orEmpty()
                    for (activity in activities) {
                        val pkg = activity.applicationInfo.packageName
                        if (pkg == context.packageName) continue
                        if (pkg == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER) continue
                        val key = "$pkg:${user.hashCode()}"
                        if (!seenKeys.add(key)) continue
                        val label = activity.label.toString()
                        val drawable = activity.getBadgedIcon(iconPx)
                        val bitmap = drawableToBitmap(drawable, iconPx)
                        val category = categorizeApp(label, pkg)
                        val isRec = category in setOf(
                            AppCategory.FINANCE,
                            AppCategory.AUTH,
                            AppCategory.PRIVACY,
                            AppCategory.SOCIAL
                        )
                        items.add(AppItem(pkg, label, bitmap.asImageBitmap(), category, isRec, isWorkProfile = isWork, uniqueKey = key))
                    }
                }
            }

            if (items.isEmpty()) {
                val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val activities = pm.queryIntentActivities(mainIntent, 0)
                items.addAll(activities.mapNotNull { resolveInfo ->
                    val pkg = resolveInfo.activityInfo.packageName
                    if (pkg == context.packageName) return@mapNotNull null
                    if (pkg == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER) return@mapNotNull null
                    val label = resolveInfo.loadLabel(pm).toString()
                    val drawable = resolveInfo.loadIcon(pm)
                    val bitmap = drawableToBitmap(drawable, iconPx)
                    val category = categorizeApp(label, pkg)
                    val isRec = category in setOf(
                        AppCategory.FINANCE,
                        AppCategory.AUTH,
                        AppCategory.PRIVACY,
                        AppCategory.SOCIAL
                    )
                    AppItem(pkg, label, bitmap.asImageBitmap(), category, isRec, isWorkProfile = false)
                })
            }

            // Synthesize Google Password Manager if present on device
            val hasGooglePasswords = try {
                val hasCredMgr = try {
                    pm.getPackageInfo(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER, 0) != null
                } catch (_: Exception) { false }

                val gmsInfo = try {
                    pm.getPackageInfo("com.google.android.gms", android.content.pm.PackageManager.GET_ACTIVITIES)
                } catch (_: Exception) { null }
                val hasGmsPasswords = gmsInfo?.activities?.any {
                    it.name.contains("PasswordManager", ignoreCase = true) ||
                            it.name.contains("ManagePasswords", ignoreCase = true)
                } == true

                hasCredMgr || hasGmsPasswords
            } catch (_: Exception) {
                false
            }

            if (hasGooglePasswords) {
                val iconBitmap = try {
                    val iconDrawable = try {
                        val credMgrInfo = pm.getApplicationInfo(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER, 0)
                        pm.getApplicationIcon(credMgrInfo)
                    } catch (_: Exception) {
                        val gmsAppInfo = pm.getApplicationInfo("com.google.android.gms", 0)
                        pm.getApplicationIcon(gmsAppInfo)
                    }
                    drawableToBitmap(iconDrawable, iconPx)
                } catch (_: Exception) {
                    null
                }
                items.add(
                    AppItem(
                        packageName = BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS,
                        label = "Google Password Manager",
                        iconBitmap = iconBitmap?.asImageBitmap(),
                        category = AppCategory.AUTH,
                        isRecommended = true
                    )
                )
            }

            val finalItems = items.distinctBy { it.uniqueKey }.sortedBy { it.label.lowercase() }

            if (vault.isAutoSortProfilesEnabled()) {
                val appPairs = finalItems.map { it.packageName to it.label }
                vault.autoSortAppsIntoProfiles(appPairs, pm)
            }

            val attempts = IntruderManager.getIntruderRecords(context)

            withContext(Dispatchers.Main) {
                installedApps = finalItems
                intruderCount = attempts.size
                isLoadingApps = false
            }
        }
    }

    val currentProfile = profiles.firstOrNull { it.id == activeProfileId }
    val isViewingAll = activeProfileId == null || activeProfileId == "all"

    val filteredApps = remember(installedApps, searchQuery, activeProfileId, profiles) {
        val baseList = if (isViewingAll || currentProfile == null) {
            installedApps
        } else {
            installedApps.filter { currentProfile.packages.contains(it.packageName) }
        }

        baseList.filter { app ->
            searchQuery.isBlank() ||
                    app.label.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Living dynamic constellation ambient particle field
        HeaderMatrixDotsExpressive(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .align(Alignment.TopCenter)
        )

        // Main content column with edge-to-edge system insets
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Material 3 Expressive Floating Header Action Bar
            ExpressiveTopHeader(
                globalLockEnabled = globalLockEnabled,
                intruderCount = intruderCount,
                onToggleGlobalLock = { enabled ->
                    ExpressiveTokens.triggerHaptic(
                        context,
                        if (enabled) ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON else ExpressiveTokens.HapticFeedbackStyle.TOGGLE_OFF
                    )
                    if (enabled) vault.resumeProtection() else showPauseDialog = true
                },
                onOpenDiagnostics = {
                    ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE)
                    showDiagnosticsSheet = true
                },
                onOpenIntruderVault = {
                    ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE)
                    showIntruderVaultSheet = true
                },
                onOpenSettings = {
                    ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE)
                    showSettingsSheet = true
                }
            )

            // Centered BioKey Dual-Layer Logo with Breathing Neural Aura
            CenteredBioKeyLogoExpressive()

            // Paused-protection banner: the state an attacker would most like to leave unnoticed.
            if (!globalLockEnabled) {
                val resumeText = if (pausedUntil > 0L) {
                    "Resumes automatically at " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                        .format(java.util.Date(pausedUntil))
                } else {
                    "Locked apps open without authentication until you resume"
                }
                Surface(
                    shape = ExpressiveTokens.Shapes.BentoCardMedium,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Protection paused",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = resumeText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON)
                                vault.resumeProtection()
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Resume", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Expressive Security Profiles Pill Carousel
            SecurityProfilesBarExpressive(
                profiles = profiles,
                activeProfileId = activeProfileId,
                onSelectProfile = { profileId ->
                    ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CONFIRM_SUCCESS)
                    // A profile with a more lenient timeout needs a fresh authentication.
                    if (!vault.activateProfile(profileId)) {
                        stepUp.require("Switch profile") { vault.activateProfile(profileId) }
                    }
                },
                onEditProfile = { profile ->
                    ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE)
                    editingProfile = profile
                    showProfileEditor = true
                },
                onCreateProfile = {
                    ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE)
                    editingProfile = null
                    showProfileEditor = true
                },
                onLongPressAllApps = {
                    ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.HEAVY_SNAP)
                    showAllAppsActionsSheet = true
                }
            )

            // Profile Info Header Banner when viewing a custom profile
            if (!isViewingAll && currentProfile != null) {
                Surface(
                    shape = ExpressiveTokens.Shapes.BentoCardMedium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = currentProfile.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = "${currentProfile.packages.size} apps inside this profile",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = {
                                editingProfile = currentProfile
                                showProfileEditor = true
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.springPress()
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit Profile", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Floating Expressive Search Bar Capsule
            ExpressiveSearchBar(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                totalCount = filteredApps.size
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Pure Material 3 Expressive Application Guard List
            if (isLoadingApps) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Cataloging hardware applications...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Shield,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (!isViewingAll && currentProfile != null)
                                "No apps in '${currentProfile.name}' yet"
                            else
                                "No applications match your query",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!isViewingAll && currentProfile != null) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    editingProfile = currentProfile
                                    showProfileEditor = true
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.springPress()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add Apps to ${currentProfile.name}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)
                ) {
                    items(filteredApps, key = { it.uniqueKey }) { app ->
                        val isLocked = if (isViewingAll || currentProfile == null) {
                            lockedApps.contains(app.packageName)
                        } else {
                            currentProfile.packages.contains(app.packageName)
                        }
                        ExpressiveAppRow(
                            app = app,
                            isLocked = isLocked,
                            onToggleLock = { lock ->
                                setLocked(app, lock, if (isViewingAll) null else currentProfile)
                            },
                            onOpenOptions = {
                                ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE)
                                selectedAppForDetails = app
                            }
                        )
                    }
                }
            }
        }
    }

    // Per-App Modal Sheet
    selectedAppForDetails?.let { app ->
        val isLocked = lockedApps.contains(app.packageName)

        AppSecurityBottomSheetExpressive(
            app = app,
            isLocked = isLocked,
            onDismiss = { selectedAppForDetails = null },
            onToggleLock = { lock -> setLocked(app, lock, profile = null) },
            onTestLaunch = {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                }
            }
        )
    }

    // Material 3 Expressive Full-Screen Settings Destination (Smooth, non-bouncy spatial physics)
    AnimatedVisibility(
        visible = showSettingsSheet,
        enter = fadeIn(animationSpec = ExpressiveTokens.Springs.smooth()) +
                slideInHorizontally(
                    animationSpec = ExpressiveTokens.Springs.smooth(),
                    initialOffsetX = { it }
                ),
        exit = fadeOut(animationSpec = ExpressiveTokens.Springs.smooth()) +
                slideOutHorizontally(
                    animationSpec = ExpressiveTokens.Springs.smooth(),
                    targetOffsetX = { it }
                )
    ) {
        SettingsScreenExpressive(
            onBack = { showSettingsSheet = false },
            onOpenIntruderVault = {
                showSettingsSheet = false
                showIntruderVaultSheet = true
            },
            onOpenPermissions = {
                showSettingsSheet = false
                showPermissionsSheet = true
            }
        )
    }

    BackHandler(enabled = showSettingsSheet) {
        showSettingsSheet = false
    }

    if (showDiagnosticsSheet) {
        HardwareDiagnosticsSheetExpressive(
            onDismiss = { showDiagnosticsSheet = false }
        )
    }

    if (showIntruderVaultSheet) {
        IntruderVaultSheet(
            onDismiss = { showIntruderVaultSheet = false },
            onChanged = { intruderCount = it }
        )
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

    if (showProfileEditor) {
        ProfileEditorModalSheetExpressive(
            profileToEdit = editingProfile,
            installedApps = installedApps,
            onDismiss = { showProfileEditor = false },
            onSaveProfile = { profile ->
                // Both calls refuse protection-reducing changes (removing apps from the active
                // profile, a more lenient timeout) without a fresh authentication, so retry after one.
                val save = { vault.saveProfile(profile) && vault.activateProfile(profile.id) }
                if (save()) {
                    showProfileEditor = false
                } else {
                    stepUp.require("Save ${profile.name}") { if (save()) showProfileEditor = false }
                }
            },
            onDeleteProfile = { profileId ->
                vault.deleteProfile(profileId)
                vault.activateProfile("all")
                showProfileEditor = false
            }
        )
    }

    if (showPermissionsSheet) {
        SecurityPermissionsModalSheetExpressive(
            onDismiss = { showPermissionsSheet = false }
        )
    }

    if (showAllAppsActionsSheet) {
        AllAppsActionsModalSheetExpressive(
            totalCount = installedApps.size,
            lockedCount = lockedApps.size,
            onDismiss = { showAllAppsActionsSheet = false },
            onSelectAll = {
                val targetPkgs = if (searchQuery.isNotBlank()) {
                    filteredApps.map { it.packageName }
                } else {
                    installedApps.map { it.packageName }
                }
                vault.setMultipleAppLocks(targetPkgs, true)
                Toast.makeText(context, "Locked all applications (${targetPkgs.size})", Toast.LENGTH_SHORT).show()
            },
            onDeselectAll = {
                val targetPkgs = if (searchQuery.isNotBlank()) {
                    filteredApps.map { it.packageName }
                } else {
                    installedApps.map { it.packageName }
                }
                stepUp.require("Unlock ${targetPkgs.size} apps") {
                    if (vault.setMultipleAppLocks(targetPkgs, false)) {
                        Toast.makeText(context, "Unlocked ${targetPkgs.size} apps", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

/**
 * Top Header: Minimal, lightweight, fluid action bar with Titan M3 chip badge and controls.
 */
@Composable
private fun ExpressiveTopHeader(
    globalLockEnabled: Boolean,
    intruderCount: Int,
    onToggleGlobalLock: (Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenIntruderVault: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val chipBadge by produceState("KEYSTORE") {
        value = withContext(Dispatchers.IO) { HardwareKeyManager.getDiagnostics(context).getChipBadge() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Hardware Status Pill (Left)
        Surface(
            onClick = onOpenDiagnostics,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.springPress()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Living EAL6+ indicator dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Hardware Security",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = chipBadge,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Action Controls (Right)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (intruderCount > 0) {
                Surface(
                    onClick = onOpenIntruderVault,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.springPress()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Breaches",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$intruderCount",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Master Protection Switch
            Switch(
                checked = globalLockEnabled,
                onCheckedChange = onToggleGlobalLock,
                thumbContent = {
                    Text(
                        text = if (globalLockEnabled) "ON" else "OFF",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold
                        ),
                        color = if (globalLockEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            )

            // Settings Button
            Surface(
                onClick = onOpenSettings,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .size(40.dp)
                    .springPress()
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Centered BioKey Dual-Layer Logo with Breathing Neural Aura.
 */
@Composable
private fun CenteredBioKeyLogoExpressive() {
    val infiniteTransition = rememberInfiniteTransition(label = "logo_aura_expressive")
    val pulseAura = infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_aura"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(78.dp)
            .padding(top = 0.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(160.dp, 72.dp)
                // Read the animated value in the draw phase only: no recomposition per frame.
                .graphicsLayer {
                    scaleX = pulseAura.value
                    scaleY = pulseAura.value
                }
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.06f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        Image(
            painter = painterResource(R.drawable.ic_title_logo_letters),
            contentDescription = "Bio Key Logo Letters",
            modifier = Modifier.fillMaxHeight(),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(
                MaterialTheme.colorScheme.primary,
                BlendMode.Modulate
            )
        )

        Image(
            painter = painterResource(R.drawable.ic_title_logo_tentacles),
            contentDescription = "Bio Key Logo Tentacles",
            modifier = Modifier.fillMaxHeight(),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(
                MaterialTheme.colorScheme.tertiary,
                BlendMode.Modulate
            )
        )
    }
}

/**
 * Material 3 Expressive Security Profiles Selector Bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SecurityProfilesBarExpressive(
    profiles: List<SecurityProfile>,
    activeProfileId: String?,
    onSelectProfile: (String) -> Unit,
    onEditProfile: (SecurityProfile) -> Unit,
    onCreateProfile: () -> Unit,
    onLongPressAllApps: () -> Unit
) {
    val isAllActive = activeProfileId == null || activeProfileId == "all"

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item(key = "profile_all_pill") {
            val pillContainerColor by animateColorAsState(
                targetValue = if (isAllActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                animationSpec = ExpressiveTokens.Springs.fast(),
                label = "pill_all_color"
            )

            Surface(
                shape = CircleShape,
                color = pillContainerColor,
                modifier = Modifier
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { onSelectProfile("all") },
                        onLongClick = onLongPressAllApps
                    )
                    .springPress()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = if (isAllActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "All Apps",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isAllActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isAllActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    if (isAllActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    }
                }
            }
        }

        items(profiles.filter { it.id != "all" }, key = { it.id }) { profile ->
            val isActive = profile.id == activeProfileId
            val icon = when (profile.iconKey) {
                "finance" -> Icons.Default.AccountBalance
                "social" -> Icons.AutoMirrored.Filled.Chat
                "lock" -> Icons.Default.Lock
                "work" -> Icons.Default.Work
                "privacy" -> Icons.Default.VisibilityOff
                else -> Icons.Default.Shield
            }

            val pillContainerColor by animateColorAsState(
                targetValue = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                animationSpec = ExpressiveTokens.Springs.fast(),
                label = "pill_custom_color"
            )

            Surface(
                onClick = {
                    if (isActive) onEditProfile(profile) else onSelectProfile(profile.id)
                },
                shape = CircleShape,
                color = pillContainerColor,
                modifier = Modifier.springPress()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        item(key = "new_profile_pill") {
            Surface(
                onClick = onCreateProfile,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                modifier = Modifier.springPress()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Profile",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "New Profile",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * Expressive Search Capsule.
 */
@Composable
private fun ExpressiveSearchBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    totalCount: Int
) {
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
        placeholder = {
            Text(
                "Search $totalCount applications...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            if (searchQuery.isNotBlank()) {
                IconButton(onClick = { onSearchQueryChange("") }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        shape = CircleShape,
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color.Transparent,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/**
 * Material 3 Expressive Application Item Row with Rounded Container Silhouette.
 */
@Composable
private fun ExpressiveAppRow(
    app: AppItem,
    isLocked: Boolean,
    onToggleLock: (Boolean) -> Unit,
    onOpenOptions: () -> Unit
) {
    val cardContainerColor by animateColorAsState(
        targetValue = if (isLocked) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = ExpressiveTokens.Springs.fast(),
        label = "app_row_bg"
    )

    Surface(
        onClick = { onToggleLock(!isLocked) },
        modifier = Modifier
            .fillMaxWidth()
            .clip(ExpressiveTokens.Shapes.BentoCardMedium)
            .springPress(pressScale = 0.98f),
        shape = ExpressiveTokens.Shapes.BentoCardMedium,
        color = cardContainerColor,
        border = if (isLocked) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Icon
            if (app.iconBitmap != null) {
                Image(
                    bitmap = app.iconBitmap,
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Label & Subtitle
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (app.isWorkProfile) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = "Work",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLocked) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Biometrics Enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Text(
                            text = if (app.isRecommended) "High Risk • ${app.category.label}" else "Not protected",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (app.isRecommended) MaterialTheme.colorScheme.error.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        )
                    }
                }
            }

            // Options Button
            IconButton(
                onClick = onOpenOptions,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Switch
            Switch(
                checked = isLocked,
                onCheckedChange = onToggleLock,
                thumbContent = if (isLocked) {
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
    }
}

/**
 * App Security Modal Bottom Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSecurityBottomSheetExpressive(
    app: AppItem,
    isLocked: Boolean,
    onDismiss: () -> Unit,
    onToggleLock: (Boolean) -> Unit,
    onTestLaunch: () -> Unit
) {
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (app.iconBitmap != null) {
                    Image(
                        bitmap = app.iconBitmap,
                        contentDescription = app.label,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = ExpressiveTokens.Shapes.BentoCardMedium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Require Biometric Lock",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Fingerprint or face authentication required to open",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isLocked,
                        onCheckedChange = onToggleLock,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            Surface(
                onClick = {
                    onDismiss()
                    onTestLaunch()
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .springPress()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Test Launch",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Test Launch Application",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

/**
 * All Applications Actions Modal Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllAppsActionsModalSheetExpressive(
    totalCount: Int,
    lockedCount: Int,
    onDismiss: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit
) {
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "All Applications",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$lockedCount of $totalCount applications secured",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Surface(
                onClick = {
                    onDismiss()
                    onSelectAll()
                },
                shape = ExpressiveTokens.Shapes.BentoCardMedium,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                modifier = Modifier
                    .fillMaxWidth()
                    .springPress()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Select All (Lock All)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Enforce biometric lock across all applications", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Surface(
                onClick = {
                    onDismiss()
                    onDeselectAll()
                },
                shape = ExpressiveTokens.Shapes.BentoCardMedium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .springPress()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Deselect All (Unlock All)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Remove biometric lock requirement across all applications", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Keystore and protection status, read from the platform (KeyInfo, BiometricManager) rather
 * than assumed. Loaded off the main thread because Keystore queries can block.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HardwareDiagnosticsSheetExpressive(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val diagnostics by produceState<HardwareKeyManager.KeystoreDiagnostics?>(null) {
        value = withContext(Dispatchers.IO) { HardwareKeyManager.getDiagnostics(context) }
    }
    val a11yActive = remember { BioKeyAccessibilityService.isServiceEnabled(context) }
    val adminActive = remember { DeviceAdminHelper.isAdminActive(context) }

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Security status",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Surface(
                shape = ExpressiveTokens.Shapes.BentoCardMedium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val d = diagnostics
                    if (d == null) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else {
                        DiagnosticItemExpressive("Key storage", d.securityLevel)
                        DiagnosticItemExpressive("StrongBox available", if (d.isStrongBoxSupported) "Yes" else "No")
                        DiagnosticItemExpressive("Biometrics", d.biometricStatus)
                    }
                    DiagnosticItemExpressive("App lock service", if (a11yActive) "Running" else "Off")
                    DiagnosticItemExpressive("Uninstall protection", if (adminActive) "On" else "Off")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticItemExpressive(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Profile Editor Modal Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorModalSheetExpressive(
    profileToEdit: SecurityProfile?,
    installedApps: List<AppItem>,
    onDismiss: () -> Unit,
    onSaveProfile: (SecurityProfile) -> Unit,
    onDeleteProfile: ((String) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var profileName by remember(profileToEdit) { mutableStateOf(profileToEdit?.name ?: "") }
    var selectedIcon by remember(profileToEdit) { mutableStateOf(profileToEdit?.iconKey ?: "shield") }
    var selectedPackages by remember(profileToEdit) { mutableStateOf(profileToEdit?.packages ?: emptySet()) }
    var searchQuery by remember { mutableStateOf("") }

    val iconOptions = listOf(
        "shield" to Icons.Default.Shield,
        "lock" to Icons.Default.Lock,
        "finance" to Icons.Default.AccountBalance,
        "social" to Icons.AutoMirrored.Filled.Chat,
        "work" to Icons.Default.Work,
        "privacy" to Icons.Default.VisibilityOff
    )

    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
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
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (profileToEdit != null) "Edit '${profileToEdit.name}'" else "Create Security Profile",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("Profile Name") },
                        placeholder = { Text("e.g. Finance Vault, Night Shield") },
                        singleLine = true,
                        shape = ExpressiveTokens.Shapes.BentoCardSmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Column {
                        Text(
                            text = "Profile Icon",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            iconOptions.forEach { (key, icon) ->
                                val isSelected = selectedIcon == key
                                Surface(
                                    onClick = { selectedIcon = key },
                                    shape = CircleShape,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .springPress()
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = key,
                                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }


                item {
                    Column {
                        Text(
                            text = "Apps Protected in this Profile (${selectedPackages.size} selected)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                Surface(
                                    onClick = { selectedPackages = installedApps.map { it.packageName }.toSet() },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.springPress()
                                ) {
                                    Text(
                                        text = "Select All",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                            item {
                                Surface(
                                    onClick = { selectedPackages = emptySet() },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.springPress()
                                ) {
                                    Text(
                                        text = "Clear All",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Filter apps to add/remove...") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        singleLine = true,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                items(filteredApps, key = { it.uniqueKey }) { app ->
                    val isChecked = selectedPackages.contains(app.packageName)
                    Surface(
                        onClick = {
                            selectedPackages = if (isChecked) selectedPackages - app.packageName else selectedPackages + app.packageName
                        },
                        shape = ExpressiveTokens.Shapes.BentoCardSmall,
                        color = if (isChecked) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .springPress(pressScale = 0.98f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedPackages = if (checked) selectedPackages + app.packageName else selectedPackages - app.packageName
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            if (app.iconBitmap != null) {
                                Image(
                                    bitmap = app.iconBitmap,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (app.isWorkProfile) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text(
                                                text = "Work",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    val profile = SecurityProfile(
                        id = profileToEdit?.id ?: "profile_${UUID.randomUUID().toString().take(8)}",
                        name = profileName.trim().ifEmpty { profileToEdit?.name ?: "Custom Profile" },
                        iconKey = selectedIcon,
                        packages = selectedPackages,
                        isPreset = false,
                        timeoutMode = profileToEdit?.timeoutMode ?: LockTimeoutMode.IMMEDIATE
                    )
                    onSaveProfile(profile)
                },
                enabled = profileName.isNotBlank() || selectedPackages.isNotEmpty(),
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
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (profileToEdit != null) "Update Profile (${selectedPackages.size} Apps)" else "Save & Activate Profile (${selectedPackages.size} Apps)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            if (profileToEdit != null && profileToEdit.id != "all" && onDeleteProfile != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onDeleteProfile(profileToEdit.id) },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(bottom = 6.dp)
                        .springPress()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete This Profile", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

/**
 * Optional permissions. The required ones (app lock service, uninstall protection) are enforced
 * by MainActivity, which shows the repair screen whenever one is missing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecurityPermissionsModalSheetExpressive(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    var hasCamera by remember { mutableStateOf(granted(android.Manifest.permission.CAMERA)) }
    // POST_NOTIFICATIONS only exists from Android 13; earlier versions allow notifications by default.
    fun notificationsAllowed() = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
        granted(android.Manifest.permission.POST_NOTIFICATIONS)

    var hasNotifications by remember { mutableStateOf(notificationsAllowed()) }
    var hasNotificationAccess by remember {
        mutableStateOf(BioKeyNotificationShieldService.isNotificationAccessGranted(context))
    }
    var isIgnoringBattery by remember {
        mutableStateOf(
            (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
        )
    }

    LifecycleResumeEffect(Unit) {
        hasCamera = granted(android.Manifest.permission.CAMERA)
        hasNotifications = notificationsAllowed()
        hasNotificationAccess = BioKeyNotificationShieldService.isNotificationAccessGranted(context)
        isIgnoringBattery = (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
        onPauseOrDispose { }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasNotifications = it
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.reportsInteraction(),
        shape = ExpressiveTokens.Shapes.BentoCardLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Permissions",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Optional features and the permissions they need",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }

            item {
                PermissionCardExpressive(
                    title = "Protection alerts",
                    subtitle = "Notifies you if app locking stops or settings are tampered with",
                    isActive = hasNotifications,
                    isRequired = false,
                    actionText = if (hasNotifications) "On" else "Allow",
                    onAction = {
                        if (!hasNotifications) notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                )
            }

            item {
                PermissionCardExpressive(
                    title = "Intruder photos",
                    subtitle = "Front camera, used only after repeated failed unlocks",
                    isActive = hasCamera,
                    isRequired = false,
                    actionText = if (hasCamera) "On" else "Allow",
                    onAction = {
                        if (!hasCamera) cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    }
                )
            }

            item {
                PermissionCardExpressive(
                    title = "Notification shield",
                    subtitle = "Notification access, to hide notifications from locked apps",
                    isActive = hasNotificationAccess,
                    isRequired = false,
                    actionText = if (hasNotificationAccess) "On" else "Allow",
                    onAction = {
                        context.openSystemSettings(BioKeyNotificationShieldService.getNotificationSettingsIntent(context))
                    }
                )
            }

            item {
                PermissionCardExpressive(
                    title = "Background reliability",
                    subtitle = "Exempts BioKey from battery restrictions so background protection never sleeps",
                    isActive = isIgnoringBattery,
                    isRequired = false,
                    actionText = if (isIgnoringBattery) "On" else "Allow",
                    onAction = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                        context.openSystemSettings(intent)
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionCardExpressive(
    title: String,
    subtitle: String,
    isActive: Boolean,
    isRequired: Boolean,
    actionText: String,
    onAction: () -> Unit
) {
    Surface(
        shape = ExpressiveTokens.Shapes.BentoCardMedium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else if (isRequired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isRequired) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "REQUIRED",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onAction,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.surfaceContainerHighest else if (isRequired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (isActive) MaterialTheme.colorScheme.primary else if (isRequired) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.springPress()
            ) {
                Text(actionText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private const val ICON_RENDER_DP = 56

/** Renders [drawable] into a [sizePx]-square bitmap (reusing an existing bitmap no larger than that). */
private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null && drawable.bitmap.width <= sizePx) {
        return drawable.bitmap
    }
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

private fun categorizeApp(label: String, pkg: String): AppCategory {
    val l = label.lowercase()
    val p = pkg.lowercase()

    return when {
        p.contains("bank") || p.contains("crypto") || p.contains("pay") ||
                p.contains("wallet") || p.contains("binance") || p.contains("coinbase") ||
                l.contains("bank") || l.contains("crypto") || l.contains("wallet") -> AppCategory.FINANCE

        p.contains("auth") || p.contains("authenticator") || p.contains("duo") ||
                p.contains("security") || p.contains("yubico") || p.contains("bitwarden") ||
                p.contains("1password") || p.contains("dashlane") || p.contains("keepass") ||
                p.contains("password") || l.contains("password") -> AppCategory.AUTH

        p.contains("photo") || p.contains("gallery") || p.contains("vault") ||
                p.contains("secret") || p.contains("hidden") || p.contains("note") -> AppCategory.PRIVACY

        p.contains("whatsapp") || p.contains("telegram") || p.contains("signal") ||
                p.contains("discord") || p.contains("instagram") || p.contains("facebook") ||
                p.contains("snapchat") || p.contains("twitter") || p.contains("messenger") -> AppCategory.SOCIAL

        p.startsWith("com.android.") || p.startsWith("com.google.android.") -> AppCategory.SYSTEM

        else -> AppCategory.GENERAL
    }
}
