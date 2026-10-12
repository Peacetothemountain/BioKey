package com.android.BioKey.feature.main

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
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
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.AdminPanelSettings
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
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.security.TitanM3SecurityManager.ReKeyProof
import java.util.UUID
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.android.BioKey.R
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.intruder.IntruderManager
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.TitanM3SecurityManager
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.BioKeyDeviceAdminReceiver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.BioKey.feature.lock.DeviceAdminHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppCategory(val label: String) {
    FINANCE("Banking & Crypto"),
    AUTH("2FA & Security"),
    PRIVACY("Photos & Vault"),
    SOCIAL("Social & Chat"),
    SYSTEM("System Core"),
    GENERAL("Application")
}

data class AppItem(
    val packageName: String,
    val label: String,
    val iconBitmap: ImageBitmap?,
    val category: AppCategory,
    val isRecommended: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val vault = remember { SecurityVault.getInstance(context) }
    val haptic = LocalHapticFeedback.current

    val lockedApps by vault.lockedAppsFlow.collectAsState()
    val stealthApps by vault.stealthAppsFlow.collectAsState()
    val globalLockEnabled by vault.globalLockEnabledFlow.collectAsState()
    val timeoutMode by vault.timeoutModeFlow.collectAsState()
    val profiles by vault.profilesFlow.collectAsState()
    val activeProfileId by vault.activeProfileIdFlow.collectAsState()

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
    var reKeyProofResult by remember { mutableStateOf<ReKeyProof?>(null) }

    var intruderCount by remember { mutableStateOf(0) }

    var a11yActive by remember { mutableStateOf(BioKeyAccessibilityService.isServiceEnabled(context)) }
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    LaunchedEffect(showSettingsSheet, showPermissionsSheet) {
        a11yActive = BioKeyAccessibilityService.isServiceEnabled(context)
        canDrawOverlays = Settings.canDrawOverlays(context)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = pm.queryIntentActivities(mainIntent, 0)
            val items = activities.mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg == context.packageName) return@mapNotNull null
                if (pkg == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER) return@mapNotNull null
                val label = resolveInfo.loadLabel(pm).toString()
                val drawable = resolveInfo.loadIcon(pm)
                val bitmap = drawableToBitmap(drawable)
                val category = categorizeApp(label, pkg)
                val isRec = category in setOf(
                    AppCategory.FINANCE,
                    AppCategory.AUTH,
                    AppCategory.PRIVACY,
                    AppCategory.SOCIAL
                )
                AppItem(pkg, label, bitmap.asImageBitmap(), category, isRec)
            }.toMutableList()

            // Synthesize Google Password Manager if present on the device
            val hasGooglePasswords = try {
                val hasCredMgr = try {
                    pm.getPackageInfo(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER, 0) != null
                } catch (e: Exception) { false }

                val gmsInfo = try {
                    pm.getPackageInfo("com.google.android.gms", android.content.pm.PackageManager.GET_ACTIVITIES)
                } catch (e: Exception) { null }
                val hasGmsPasswords = gmsInfo?.activities?.any { 
                    it.name.contains("PasswordManager", ignoreCase = true) ||
                    it.name.contains("ManagePasswords", ignoreCase = true)
                } == true

                hasCredMgr || hasGmsPasswords
            } catch (e: Exception) {
                false
            }

            if (hasGooglePasswords) {
                val iconBitmap = try {
                    val iconDrawable = try {
                        val credMgrInfo = pm.getApplicationInfo(BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER, 0)
                        pm.getApplicationIcon(credMgrInfo)
                    } catch (e: Exception) {
                        val gmsAppInfo = pm.getApplicationInfo("com.google.android.gms", 0)
                        pm.getApplicationIcon(gmsAppInfo)
                    }
                    drawableToBitmap(iconDrawable)
                } catch (e: Exception) {
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

            val finalItems = items.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }

            // Purge any excluded apps (like 2048 or mobile games) from profiles and locks
            vault.purgeExcludedApps(pm)

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

    val filteredApps = remember(installedApps, lockedApps, searchQuery, activeProfileId, profiles) {
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
        // Living dynamic constellation dots particle field at the top
        HeaderMatrixDotsAccent(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .align(Alignment.TopCenter)
        )

        // Main unified screen column (NO 3-bar bottom dock!)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Unified M3 Expressive Header Bar
            ExpressiveTopHeader(
                globalLockEnabled = globalLockEnabled,
                intruderCount = intruderCount,
                onToggleGlobalLock = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vault.setGlobalLockEnabled(it)
                },
                onOpenDiagnostics = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showDiagnosticsSheet = true
                },
                onOpenIntruderVault = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showIntruderVaultSheet = true
                },
                onOpenSettings = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showSettingsSheet = true
                }
            )

            // Centered BioKey Dual-Layer Logo with Breathing Neural Aura
            CenteredBioKeyLogo()

            // Expressive Permission Setup Banner (if Core Permissions Missing)
            if (!a11yActive || !canDrawOverlays) {
                Surface(
                    onClick = { showPermissionsSheet = true },
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Setup Permissions Required",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = if (!a11yActive && !canDrawOverlays) "Accessibility & Overlay required"
                                else if (!a11yActive) "Accessibility service required"
                                else "Overlay permission required",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.error
                        ) {
                            Text(
                                text = "Setup",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            // Expressive Security Profiles Selector Bar
            SecurityProfilesBar(
                profiles = profiles,
                activeProfileId = activeProfileId,
                onSelectProfile = { profileId ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vault.activateProfile(profileId)
                },
                onEditProfile = { profile ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    editingProfile = profile
                    showProfileEditor = true
                },
                onCreateProfile = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    editingProfile = null
                    showProfileEditor = true
                },
                onLongPressAllApps = {
                    showAllAppsActionsSheet = true
                }
            )

            // Profile Info Header Banner when viewing a specific profile
            if (!isViewingAll && currentProfile != null) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
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
                                if (currentProfile.isStealth) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Text(
                                            text = "STEALTH",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
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
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit Profile", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Seamless Expressive Search Bar (NO pointless tabs!)
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
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Loading installed applications...",
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
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (!isViewingAll && currentProfile != null)
                                "No apps inside '${currentProfile.name}' yet"
                            else
                                "No applications match your search",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!isViewingAll && currentProfile != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    editingProfile = currentProfile
                                    showProfileEditor = true
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
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
                    contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val isLocked = if (isViewingAll || currentProfile == null) {
                            lockedApps.contains(app.packageName)
                        } else {
                            currentProfile.packages.contains(app.packageName)
                        }
                        val isStealth = stealthApps.contains(app.packageName) || (currentProfile?.isStealth == true && isLocked)

                        ExpressiveAppRow(
                            app = app,
                            isLocked = isLocked,
                            isStealth = isStealth,
                            onToggleLock = { lock ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (isViewingAll || currentProfile == null) {
                                    vault.setAppLock(app.packageName, lock)
                                } else {
                                    if (lock) {
                                        vault.addAppToProfile(currentProfile.id, app.packageName)
                                    } else {
                                        vault.removeAppFromProfile(currentProfile.id, app.packageName)
                                    }
                                }
                            },
                            onOpenOptions = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedAppForDetails = app
                            }
                        )
                    }
                }
            }
        }
    }

    // Per-App Neural Security Sheet
    selectedAppForDetails?.let { app ->
        val isLocked = lockedApps.contains(app.packageName)
        val isStealth = stealthApps.contains(app.packageName)

        AppSecurityBottomSheet(
            app = app,
            isLocked = isLocked,
            isStealth = isStealth,
            onDismiss = { selectedAppForDetails = null },
            onToggleLock = { lock ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                vault.setAppLock(app.packageName, lock)
            },
            onToggleStealth = { stealth ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                vault.setAppStealthCrash(app.packageName, stealth)
            },
            onTestLaunch = {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                }
            }
        )
    }

    // Material 3 Expressive Full-Screen Settings Destination
    AnimatedVisibility(
        visible = showSettingsSheet,
        enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                slideInHorizontally(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                    initialOffsetX = { it }
                ),
        exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
               slideOutHorizontally(
                   animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                   targetOffsetX = { it }
               )
    ) {
        SettingsScreen(
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

    // Hardware Security Diagnostics Sheet
    if (showDiagnosticsSheet) {
        HardwareDiagnosticsSheet(
            onDismiss = { showDiagnosticsSheet = false }
        )
    }

    // Intruder Vault Breaches Sheet
    if (showIntruderVaultSheet) {
        IntruderVaultModalSheet(
            onDismiss = {
                val attempts = IntruderManager.getIntruderRecords(context)
                intruderCount = attempts.size
                showIntruderVaultSheet = false
            }
        )
    }

    // Profile Editor Modal Sheet (Create, Edit, Rename, Add Apps, Delete)
    if (showProfileEditor) {
        ProfileEditorModalSheet(
            profileToEdit = editingProfile,
            installedApps = installedApps,
            onDismiss = { showProfileEditor = false },
            onSaveProfile = { profile ->
                vault.saveProfile(profile)
                vault.activateProfile(profile.id)
                showProfileEditor = false
            },
            onDeleteProfile = { profileId ->
                vault.deleteProfile(profileId)
                vault.activateProfile("all")
                showProfileEditor = false
            }
        )
    }

    // Dedicated Security & Permissions Center Modal Sheet
    if (showPermissionsSheet) {
        SecurityPermissionsModalSheet(
            onDismiss = { showPermissionsSheet = false }
        )
    }

    // All Applications Batch Actions Sheet (Round over container page)
    if (showAllAppsActionsSheet) {
        AllAppsActionsModalSheet(
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
                vault.setMultipleAppLocks(targetPkgs, false)
                Toast.makeText(context, "Unlocked all applications (${targetPkgs.size})", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Cryptographic ReKey Verification Dialog
    reKeyProofResult?.let { proof ->
        ReKeyVerificationDialog(
            proof = proof,
            onDismiss = { reKeyProofResult = null }
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
    val diagnostics = remember { TitanM3SecurityManager.INSTANCE.getDiagnostics(context) }

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
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Hardware Security",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = diagnostics.getChipBadge(),
                    style = MaterialTheme.typography.labelSmall,
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
            // Intruder Breaches Alert (if any)
            if (intruderCount > 0) {
                Surface(
                    onClick = onOpenIntruderVault,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
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
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Master Protection Switch (Pure Material 3 Design with ON/OFF thumb)
            Switch(
                checked = globalLockEnabled,
                onCheckedChange = onToggleGlobalLock,
                thumbContent = {
                    Text(
                        text = if (globalLockEnabled) "ON" else "OFF",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 10.sp
                        ),
                        color = if (globalLockEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
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
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier.size(38.dp)
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
 * Authentic Centered BioKey Dual-Layer Logo.
 * Pure dynamic Monet wallpaper color harmony.
 */
@Composable
private fun CenteredBioKeyLogo() {
    val infiniteTransition = rememberInfiniteTransition(label = "logo_aura")
    val pulseAura by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = EaseInOutCubic),
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
        // Soft radial glow matching user wallpaper
        Box(
            modifier = Modifier
                .size(150.dp, 70.dp)
                .scale(pulseAura)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Letters Layer
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

        // Tentacles Layer
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
private fun SecurityProfilesBar(
    profiles: List<SecurityProfile>,
    activeProfileId: String?,
    onSelectProfile: (String) -> Unit,
    onEditProfile: (SecurityProfile) -> Unit,
    onCreateProfile: () -> Unit,
    onLongPressAllApps: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val isAllActive = activeProfileId == null || activeProfileId == "all"

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // "All Apps" Pill (Tap to view, Long-press for Select All / Deselect All container page)
        item(key = "profile_all_pill") {
            Surface(
                shape = CircleShape,
                color = if (isAllActive)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    if (isAllActive)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                ),
                modifier = Modifier
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { onSelectProfile("all") },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPressAllApps()
                        }
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = if (isAllActive)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "All Apps",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isAllActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isAllActive)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurface
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

        // Custom / Default Profiles (excluding "all")
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

            Surface(
                onClick = {
                    if (isActive) {
                        onEditProfile(profile)
                    } else {
                        onSelectProfile(profile.id)
                    }
                },
                shape = CircleShape,
                color = if (isActive)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    if (isActive)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isActive)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isActive)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // "+ New Profile" button
        item(key = "new_profile_pill") {
            Surface(
                onClick = onCreateProfile,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
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
 * Clean, lightweight, fluid typography with live result count.
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
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/**
 * Material 3 Expressive Application Item Row.
 * Lightweight, non-boxy, fluid typography, instant tactile feedback.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExpressiveAppRow(
    app: AppItem,
    isLocked: Boolean,
    isStealth: Boolean,
    onToggleLock: (Boolean) -> Unit,
    onOpenOptions: () -> Unit
) {
    Surface(
        onClick = { onToggleLock(!isLocked) },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        color = if (isLocked)
            MaterialTheme.colorScheme.surfaceContainerLow
        else
            MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(
            1.dp,
            if (isLocked)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            else
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)
        )
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
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(44.dp)
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

            // App Label and Status Subtitle
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                            text = if (isStealth) "Protected • Stealth Crash Disguise" else "Biometrics Enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Text(
                            text = if (app.isRecommended) "High Risk • ${app.category.label}" else "Not protected",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (app.isRecommended)
                                MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        )
                    }
                }
            }

            // Options Button (3-dots)
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

            // Tactile Material 3 Switch
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
 * App Security Modal Bottom Sheet (Stealth Crash, Test Launch, Biometric Lock).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSecurityBottomSheet(
    app: AppItem,
    isLocked: Boolean,
    isStealth: Boolean,
    onDismiss: () -> Unit,
    onToggleLock: (Boolean) -> Unit,
    onToggleStealth: (Boolean) -> Unit,
    onTestLaunch: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (app.iconBitmap != null) {
                    Image(
                        bitmap = app.iconBitmap,
                        contentDescription = app.label,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
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

            // Biometric Requirement Toggle
            Surface(
                shape = RoundedCornerShape(24.dp),
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
                        Spacer(modifier = Modifier.height(2.dp))
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
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    )
                }
            }

            // Stealth Crash Disguise Toggle
            Surface(
                shape = RoundedCornerShape(24.dp),
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
                            text = "Stealth Crash Disguise",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Disguises as '${app.label} keeps stopping'. Double-tap dialog error to trigger secret unlock.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isStealth,
                        onCheckedChange = onToggleStealth,
                        enabled = isLocked,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    )
                }
            }

            // Test Launch Button
            Surface(
                onClick = {
                    onDismiss()
                    onTestLaunch()
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
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
 * Modern Material 3 Expressive Container Page for Bulk App Defense (Select All / Deselect All).
 * Fully rounded 32dp container with fluid touch feedback, bold typography, and expressive cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllAppsActionsModalSheet(
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
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(vertical = 14.dp)
                    .size(width = 36.dp, height = 4.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            ) {}
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
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
                    Spacer(modifier = Modifier.height(2.dp))
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
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Action 1: Select All (Lock All Apps)
            Surface(
                onClick = {
                    onDismiss()
                    onSelectAll()
                },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
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
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Select All (Lock All)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Enforce biometric lock across all installed applications",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Action 2: Deselect All (Unlock All Apps)
            Surface(
                onClick = {
                    onDismiss()
                    onDeselectAll()
                },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
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
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Deselect All (Unlock All)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Remove biometric lock requirement across all applications",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}



/**
 * Hardware Security Diagnostics Modal Bottom Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HardwareDiagnosticsSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val diagnostics = remember { TitanM3SecurityManager.INSTANCE.getDiagnostics(context) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
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
                    text = "Hardware Security Telemetry",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DiagnosticItem("Hardware Security", diagnostics.getChipBadge())
                    DiagnosticItem("Biometric Class", if (diagnostics.isBiometricHardwarePresent) "Class 3 (Strong Biometric)" else "None")
                    DiagnosticItem("Hardware Keystore", if (diagnostics.isHardwareKeystoreBacked) "StrongBox / TEE Hardware-Backed" else "Software Fallback")
                    DiagnosticItem("Device Admin Active", if (diagnostics.isDeviceAdminActive) "Enforced" else "Inactive")
                    DiagnosticItem("Overlay Permission", if (diagnostics.canDrawOverlays) "Granted" else "Restricted")
                    DiagnosticItem("Accessibility Service", if (diagnostics.isAccessibilityActive) "Bound & Running" else "Inactive")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticItem(title: String, value: String) {
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
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Intruder Vault Modal Bottom Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntruderVaultModalSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var records by remember { mutableStateOf(IntruderManager.getIntruderRecords(context)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
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
                    text = "Intruder Breach Vault",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (records.isNotEmpty()) {
                    TextButton(onClick = {
                        IntruderManager.clearAllIntruders(context)
                        records = emptyList()
                    }) {
                        Text("Clear All", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (records.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No unauthorized breaches recorded",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(records, key = { it.file.absolutePath }) { record ->
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = record.packageName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = record.formattedDate,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                IconButton(onClick = {
                                    IntruderManager.deleteIntruderRecord(record)
                                    records = IntruderManager.getIntruderRecords(context)
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Create / Edit Profile Modal Bottom Sheet.
 * Supports creating new profiles or editing/renaming/deleting existing ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorModalSheet(
    profileToEdit: SecurityProfile?,
    installedApps: List<AppItem>,
    onDismiss: () -> Unit,
    onSaveProfile: (SecurityProfile) -> Unit,
    onDeleteProfile: ((String) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var profileName by remember(profileToEdit) {
        mutableStateOf(profileToEdit?.name ?: "")
    }
    var selectedIcon by remember(profileToEdit) {
        mutableStateOf(profileToEdit?.iconKey ?: "shield")
    }
    var isStealth by remember(profileToEdit) {
        mutableStateOf(profileToEdit?.isStealth ?: false)
    }
    var selectedPackages by remember(profileToEdit) {
        mutableStateOf(profileToEdit?.packages ?: emptySet())
    }
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
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp)
        ) {
            // Header
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
                // Profile Name Input
                item {
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("Profile Name") },
                        placeholder = { Text("e.g. Finance Vault, Night Shield") },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Icon Selection
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
                                    modifier = Modifier.size(44.dp)
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

                // Stealth Disguise Toggle
                item {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Stealth Crash Disguise",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Fake crash screen when opening locked apps in this profile",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = isStealth,
                                onCheckedChange = { isStealth = it }
                            )
                        }
                    }
                }

                // Quick Select Shortcuts
                item {
                    Column {
                        Text(
                            text = "Apps Protected in this Profile (${selectedPackages.size} selected)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Surface(
                                    onClick = {
                                        selectedPackages = installedApps.map { it.packageName }.toSet()
                                    },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
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
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Text(
                                        text = "Clear All",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                            item {
                                Surface(
                                    onClick = {
                                        val financePkgs = installedApps.filter {
                                            categorizeApp(it.label, it.packageName) == AppCategory.FINANCE
                                        }.map { it.packageName }.toSet()
                                        selectedPackages = selectedPackages + financePkgs
                                    },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Text(
                                        text = "+ Finance",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                            item {
                                Surface(
                                    onClick = {
                                        val socialPkgs = installedApps.filter {
                                            categorizeApp(it.label, it.packageName) == AppCategory.SOCIAL
                                        }.map { it.packageName }.toSet()
                                        selectedPackages = selectedPackages + socialPkgs
                                    },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Text(
                                        text = "+ Social",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Search Filter for Apps
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

                // List of Apps with Checkboxes
                items(filteredApps, key = { it.packageName }) { app ->
                    val isChecked = selectedPackages.contains(app.packageName)
                    Surface(
                        onClick = {
                            selectedPackages = if (isChecked) {
                                selectedPackages - app.packageName
                            } else {
                                selectedPackages + app.packageName
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isChecked) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
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
                                    selectedPackages = if (checked) {
                                        selectedPackages + app.packageName
                                    } else {
                                        selectedPackages - app.packageName
                                    }
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
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = app.label.take(1),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
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

            // Save / Update Profile Button
            Button(
                onClick = {
                    val profile = SecurityProfile(
                        id = profileToEdit?.id ?: "profile_${UUID.randomUUID().toString().take(8)}",
                        name = profileName.trim().ifEmpty { profileToEdit?.name ?: "Custom Profile" },
                        iconKey = selectedIcon,
                        packages = selectedPackages,
                        isPreset = false,
                        isStealth = isStealth,
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
                    .height(50.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (profileToEdit != null) "Update Profile (${selectedPackages.size} Apps)" else "Save & Activate Profile (${selectedPackages.size} Apps)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // Delete Profile Button (only if editing an existing profile and not "all")
            if (profileToEdit != null && profileToEdit.id != "all" && onDeleteProfile != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        onDeleteProfile(profileToEdit.id)
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(bottom = 6.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Delete This Profile",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

/**
 * Dedicated Security & Permissions Center Modal Bottom Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecurityPermissionsModalSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var a11yActive by remember { mutableStateOf(BioKeyAccessibilityService.isServiceEnabled(context)) }
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isAdminActive by remember { mutableStateOf(DeviceAdminHelper.isAdminActive(context)) }
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    var hasUsage by remember {
        mutableStateOf(
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
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
                    android.Manifest.permission.CAMERA
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                hasUsage = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                ) == AppOpsManager.MODE_ALLOWED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val deviceAdminLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
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
                            text = "Security & Permissions Center",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Manage system defenses & hardware security",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }

            // 1. Accessibility Service
            item {
                PermissionCard(
                    title = "Accessibility Shield Service",
                    subtitle = "Foreground application detection to intercept locked apps instantly",
                    isActive = a11yActive,
                    isRequired = true,
                    actionText = if (a11yActive) "Active" else "Enable Service",
                    onAction = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                )
            }

            // 2. Draw Over Other Apps (Overlay)
            item {
                PermissionCard(
                    title = "0ms Instant Overlay Shield",
                    subtitle = "Draws secure hardware authentication barrier over locked apps",
                    isActive = canDrawOverlays,
                    isRequired = true,
                    actionText = if (canDrawOverlays) "Active" else "Grant Overlay",
                    onAction = {
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
                PermissionCard(
                    title = "Device Administrator Policy",
                    subtitle = "Prevents malicious app uninstallation and tampering without authentication",
                    isActive = isAdminActive,
                    isRequired = true,
                    actionText = if (isAdminActive) "Active" else "Activate Admin",
                    onAction = {
                        DeviceAdminHelper.launchActivation(context, deviceAdminLauncher)
                    }
                )
            }

            // 4. Camera Permission
            item {
                PermissionCard(
                    title = "Front Camera Sensor",
                    subtitle = "Silently captures intruder selfie on failed biometric unlock attempts",
                    isActive = hasCamera,
                    isRequired = false,
                    actionText = if (hasCamera) "Active" else "Grant Camera",
                    onAction = {
                        if (!hasCamera) {
                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        } else {
                            Toast.makeText(context, "Camera permission already granted", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // 5. Usage Stats Access
            item {
                PermissionCard(
                    title = "Usage Statistics Access",
                    subtitle = "Backup foreground app state monitor for devices with background restrictions",
                    isActive = hasUsage,
                    isRequired = false,
                    actionText = if (hasUsage) "Active" else "Grant Access",
                    onAction = {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    subtitle: String,
    isActive: Boolean,
    isRequired: Boolean,
    actionText: String,
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (isActive)
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            else if (isRequired)
                MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        ),
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
                tint = if (isActive)
                    MaterialTheme.colorScheme.primary
                else if (isRequired)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.outline,
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
                    containerColor = if (isActive)
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    else if (isRequired)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary,
                    contentColor = if (isActive)
                        MaterialTheme.colorScheme.primary
                    else if (isRequired)
                        MaterialTheme.colorScheme.onError
                    else
                        MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
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

/**
 * Cryptographic ReKey Hardware Verification Dialog.
 * Shows proof of hardware key rotation with SHA-256 token.
 */
@Composable
private fun ReKeyVerificationDialog(
    proof: ReKeyProof,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                "Hardware Security Re-Key Verified",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Master biometric encryption key was regenerated directly inside the hardware security module.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "SECURITY PROVIDER",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = proof.provider,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "KEY ALIAS",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = proof.keyAlias,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "HARDWARE AUTH TOKEN (SHA-256)",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = proof.verificationHash,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "ROTATION TIMESTAMP",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = proof.creationTimestamp,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = CircleShape
            ) {
                Text("Done")
            }
        }
    )
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 128
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 128
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
