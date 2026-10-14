package com.android.BioKey.feature.main

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import com.android.BioKey.BioKeyApplication
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.SecurityProfile
import java.util.UUID
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.BioKey.R
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.ui.theme.ExpressiveTokens
import com.android.BioKey.ui.theme.springPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ICON_RENDER_DP = 44

private object InstalledAppsCache {
    @Volatile var cachedApps: List<AppItem> = emptyList()
    @Volatile var cachedVersion: Int = -1
    @Volatile var cachedIconPx: Int = -1
}

@Composable
fun MainScreenExpressive(
    a11yActive: Boolean = BioKeyAccessibilityService.isServiceEnabled(LocalContext.current)
) {
    val context = LocalContext.current
    val iconPx = with(LocalDensity.current) { ICON_RENDER_DP.dp.roundToPx() }
    val vault = remember { SecurityVault.getInstance(context) }

    var showSettings by rememberSaveable { mutableStateOf(false) }

    val lockedApps by vault.lockedAppsFlow.collectAsStateWithLifecycle()
    val globalLockEnabled by vault.globalLockEnabledFlow.collectAsStateWithLifecycle()
    val pausedUntil by vault.pausedUntilFlow.collectAsStateWithLifecycle()
    val activeProfileId by vault.activeProfileIdFlow.collectAsStateWithLifecycle()
    val profiles by vault.profilesFlow.collectAsStateWithLifecycle()
    val autoSortProfiles by vault.autoSortProfilesFlow.collectAsStateWithLifecycle()
    val packageChangeVersion by BioKeyApplication.packageChangeVersion.collectAsStateWithLifecycle()
    val currentProfile = if (activeProfileId == SecurityVault.PROFILE_ALL) null else profiles.firstOrNull { it.id == activeProfileId }

    var showProfileEditor by rememberSaveable { mutableStateOf(false) }
    var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingProfile = remember(editingProfileId, profiles) {
        editingProfileId?.let { id -> profiles.firstOrNull { it.id == id } }
    }

    val initialCached = remember(packageChangeVersion, iconPx) {
        if (InstalledAppsCache.cachedVersion == packageChangeVersion &&
            InstalledAppsCache.cachedIconPx == iconPx &&
            InstalledAppsCache.cachedApps.isNotEmpty()
        ) {
            InstalledAppsCache.cachedApps
        } else null
    }
    var installedApps by remember { mutableStateOf(initialCached ?: InstalledAppsCache.cachedApps) }
    var isLoadingApps by remember { mutableStateOf(installedApps.isEmpty()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val listState = key(activeProfileId) { rememberLazyListState() }

    // Load installed launcher apps once per packageChangeVersion and keep in memory across re-auth
    LaunchedEffect(packageChangeVersion, iconPx) {
        if (InstalledAppsCache.cachedVersion == packageChangeVersion &&
            InstalledAppsCache.cachedIconPx == iconPx &&
            InstalledAppsCache.cachedApps.isNotEmpty()
        ) {
            installedApps = InstalledAppsCache.cachedApps
            isLoadingApps = false
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val items = mutableListOf<AppItem>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                launcherApps?.let { la ->
                    val myUser = Process.myUserHandle()
                    val userProfiles = la.profiles.sortedBy { it != myUser }
                    for (user in userProfiles) {
                        val isWork = user != myUser
                        val activities = la.getActivityList(null, user)
                        for (activity in activities) {
                            val pkg = activity.applicationInfo.packageName
                            if (pkg == context.packageName) continue
                            if (pkg == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER) continue

                            val label = activity.label.toString()
                            val drawable = if (isWork) activity.getBadgedIcon(0) else activity.getIcon(0)
                            val bitmap = drawableToBitmap(drawable, iconPx)
                            val category = categorizeApp(label, pkg, activity.applicationInfo.category)
                            val isRec = category in setOf(AppCategory.FINANCE, AppCategory.AUTH, AppCategory.PRIVACY, AppCategory.SOCIAL)
                            items.add(AppItem(pkg, label, bitmap.asImageBitmap(), category, isRec, isWorkProfile = isWork, uniqueKey = pkg))
                        }
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
                    val category = categorizeApp(label, pkg, resolveInfo.activityInfo.applicationInfo?.category ?: -1)
                    val isRec = category in setOf(AppCategory.FINANCE, AppCategory.AUTH, AppCategory.PRIVACY, AppCategory.SOCIAL)
                    AppItem(pkg, label, bitmap.asImageBitmap(), category, isRec, isWorkProfile = false)
                })
            }

            // Synthesize Google Password Manager if present
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

            val finalItems = items.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            InstalledAppsCache.cachedApps = finalItems
            InstalledAppsCache.cachedVersion = packageChangeVersion
            InstalledAppsCache.cachedIconPx = iconPx
            val appPairs = finalItems.map { it.packageName to it.label }
            vault.autoSortAppsIntoProfiles(appPairs, pm)
            withContext(Dispatchers.Main) {
                installedApps = finalItems
                isLoadingApps = false
                com.android.BioKey.feature.lock.AppInfoCache.preWarm(context, vault.getLockedPackages())
            }
        }
    }

    LaunchedEffect(autoSortProfiles, installedApps) {
        if (autoSortProfiles && installedApps.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val appPairs = installedApps.map { it.packageName to it.label }
                vault.autoSortAppsIntoProfiles(appPairs, context.packageManager)
            }
        }
    }

    if (showSettings) {
        SettingsScreenExpressive(onNavigateBack = { showSettings = false })
        return
    }

    if (showProfileEditor) {
        ProfileEditorScreenExpressive(
            profileToEdit = editingProfile,
            allProfiles = profiles,
            lockedApps = lockedApps,
            installedApps = installedApps,
            onDismiss = { showProfileEditor = false },
            onSaveProfile = { profile ->
                vault.saveProfile(profile)
                vault.activateProfile(profile.id)
                showProfileEditor = false
            },
            onDeleteProfile = { profileId ->
                vault.deleteProfile(profileId)
                vault.activateProfile(SecurityVault.PROFILE_ALL)
                showProfileEditor = false
            }
        )
        return
    }

    val isViewingAll = currentProfile == null

    BackHandler(enabled = !isViewingAll) {
        vault.activateProfile(SecurityVault.PROFILE_ALL)
    }

    val filteredApps = remember(installedApps, searchQuery, activeProfileId, profiles) {
        val baseList = if (currentProfile == null) {
            installedApps
        } else {
            installedApps.filter { app ->
                val targets = if (app.packageName in setOf(BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS, BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER)) {
                    setOf(BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS, BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER)
                } else setOf(app.packageName)
                currentProfile.packages.any { it in targets }
            }
        }
        if (searchQuery.isBlank()) {
            baseList
        } else {
            baseList.filter {
                it.label.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .rejectObscuredTouches()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Living dynamic constellation ambient particle field across top header
        HeaderMatrixDotsExpressive(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .align(Alignment.TopCenter)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "logo_aura_main")
            val pulseAura by infiniteTransition.animateFloat(
                initialValue = 0.94f,
                targetValue = 1.08f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2800, easing = EaseInOutCubic),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse_aura"
            )

            // Header with dual-layer branding centered and settings button on the right with generous breathing room
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp)
            ) {
                // Dual-layer BioKey logo centered with generous room and ambient aura
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .height(80.dp)
                        .width(240.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(240.dp, 80.dp)
                            .graphicsLayer {
                                scaleX = pulseAura
                                scaleY = pulseAura
                            }
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )

                    Image(
                        painter = painterResource(R.drawable.ic_title_logo_letters),
                        contentDescription = "BioKey",
                        modifier = Modifier.fillMaxHeight(),
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary, BlendMode.Modulate)
                    )
                    Image(
                        painter = painterResource(R.drawable.ic_title_logo_tentacles),
                        contentDescription = null,
                        modifier = Modifier.fillMaxHeight(),
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.tertiary, BlendMode.Modulate)
                    )
                }

                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .springPress()
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Banners (seamless in-stream alerts, no squircle box)
            if (!a11yActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "App lock is off",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Turn on accessibility in settings to lock apps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    TextButton(
                        onClick = { IntentNavigationHelper.openAccessibilitySettings(context) }
                    ) {
                        Text(
                            text = "Turn on",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            } else if (!globalLockEnabled) {
                val pausedTitle = if (pausedUntil > System.currentTimeMillis()) {
                    val timeText = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(pausedUntil))
                    "Protection paused until $timeText"
                } else {
                    "Protection paused"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pausedTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = "Locked apps open without authentication.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    TextButton(
                        onClick = { vault.resumeProtection() }
                    ) {
                        Text(
                            text = "Resume",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            val keyboardController = LocalSoftwareKeyboardController.current

            // Search Bar (Pill)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search apps") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                ),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Uniform-height Profile selector pills row (never swaps height or layout structure)
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // "All" pill
                item(key = "profile_all_pill") {
                    val isAllActive = activeProfileId == SecurityVault.PROFILE_ALL
                    Surface(
                        onClick = {
                            ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE)
                            vault.activateProfile(SecurityVault.PROFILE_ALL)
                        },
                        shape = CircleShape,
                        color = if (isAllActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.springPress()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = if (isAllActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "All",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isAllActive) FontWeight.Bold else FontWeight.Medium,
                                color = if (isAllActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Profile pills
                items(
                    items = profiles.filter { it.id != SecurityVault.PROFILE_ALL },
                    key = { it.id }
                ) { profile ->
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
                            ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE)
                            if (isActive) {
                                editingProfileId = profile.id
                                showProfileEditor = true
                            } else {
                                vault.activateProfile(profile.id)
                            }
                        },
                        shape = CircleShape,
                        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
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
                            if (isActive) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit profile",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                // "+ New Profile" button
                item(key = "new_profile_pill") {
                    Surface(
                        onClick = {
                            editingProfileId = null
                            showProfileEditor = true
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.springPress()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "New",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Seamless App List — No container boxes, no squircle shapes!
            if (isLoadingApps) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                }
            } else if (currentProfile != null && searchQuery.isBlank() && filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No apps in '${currentProfile.name}'",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    editingProfileId = currentProfile.id
                                    showProfileEditor = true
                                },
                                shape = CircleShape
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add apps")
                            }

                            Button(
                                onClick = {
                                    vault.activateProfile(SecurityVault.PROFILE_ALL)
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.filledTonalButtonColors()
                            ) {
                                Text("All apps")
                            }
                        }
                    }
                }
            } else if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No matching apps",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val switchColors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                    disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                )
                val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    itemsIndexed(
                        items = filteredApps,
                        key = { _, app -> app.uniqueKey }
                    ) { index, app ->
                        val memberProfiles = remember(profiles, app.packageName) {
                            val targets = if (app.packageName in setOf(BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS, BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER)) {
                                setOf(BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS, BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER, "com.google.android.gms.passwordmanager")
                            } else setOf(app.packageName)
                            profiles.filter { it.id != SecurityVault.PROFILE_ALL && it.packages.any { pkg -> pkg in targets } }
                        }
                        val isLocked = if (currentProfile == null) {
                            app.packageName in lockedApps || memberProfiles.isNotEmpty()
                        } else {
                            true
                        }
                        // Apps in profiles are non-selectable on "All", and apps already inside a profile page are non-selectable on that profile page
                        val isRowSelectable = isViewingAll && memberProfiles.isEmpty()

                        AppRowItem(
                            app = app,
                            isLocked = isLocked,
                            enabled = isRowSelectable,
                            memberProfiles = if (isViewingAll) memberProfiles else listOfNotNull(currentProfile),
                            onToggle = { checked ->
                                if (!isRowSelectable) return@AppRowItem
                                ExpressiveTokens.triggerHaptic(
                                    context,
                                    if (checked) ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON
                                    else ExpressiveTokens.HapticFeedbackStyle.TOGGLE_OFF
                                )
                                vault.setAppLock(app.packageName, checked)
                            },
                            switchColors = switchColors
                        )

                        // Subtle inset divider between rows (omit on last item)
                        if (index < filteredApps.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 80.dp, end = 20.dp),
                                color = dividerColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRowItem(
    app: AppItem,
    isLocked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    switchColors: androidx.compose.material3.SwitchColors,
    modifier: Modifier = Modifier,
    memberProfiles: List<SecurityProfile> = emptyList()
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (enabled) {
                    Modifier.toggleable(
                        value = isLocked,
                        role = Role.Switch,
                        onValueChange = onToggle
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (app.iconBitmap != null) {
            Image(
                bitmap = app.iconBitmap,
                contentDescription = null,
                modifier = Modifier.size(44.dp)
            )
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = app.label.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            if (memberProfiles.isNotEmpty()) {
                val profileNames = memberProfiles.joinToString(", ") { it.name }
                Text(
                    text = "$profileNames • ${if (app.isWorkProfile) "Work profile • " else ""}${app.category.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = if (app.isWorkProfile) "Work profile • ${app.category.label}" else app.category.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Switch(
            checked = isLocked,
            onCheckedChange = null,
            enabled = enabled,
            colors = switchColors
        )
    }
}

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

private fun categorizeApp(label: String, pkg: String, appCategory: Int = -1): AppCategory {
    val haystack = "${pkg.lowercase()} ${label.lowercase()}"

    val financeWords = listOf(
        "bank", "crypto", "wallet", "pay", "cash", "venmo", "paypal", "zelle", "chime", "wise",
        "revolut", "robinhood", "coinbase", "binance", "kraken", "metamask", "trustwallet",
        "phantom", "ledger", "fidelity", "vanguard", "schwab", "etrade", "webull", "acorns",
        "stash", "mint", "ynab", "credit", "karma", "experian", "equifax", "transunion",
        "amex", "chase", "wellsfargo", "citi", "capitalone", "discover", "barclays", "hsbc",
        "santander", "ally", "sofi", "affirm", "klarna", "afterpay", "stripe", "square",
        "turbotax", "irs", "finance", "invest", "trading", "stock", "money"
    )
    val authWords = listOf(
        "authenticator", "authy", "2fas", "totp", "bitwarden", "1password", "dashlane",
        "lastpass", "keepass", "enpass", "nordpass", "protonpass", "roboform", "keeper",
        "yubico", "okta", "duo", "passwordmanager", "credentialmanager", "password"
    )
    val socialWords = listOf(
        "whatsapp", "telegram", "signal", "messenger", "instagram", "facebook", "threads",
        "twitter", "x.android", "snapchat", "tiktok", "musically", "discord", "reddit",
        "pinterest", "tumblr", "linkedin", "mastodon", "bluesky", "wechat", "line", "viber",
        "kakaotalk", "hinge", "tinder", "bumble", "grindr", "badoo", "okcupid", "match",
        "messages", "messaging", "chat"
    )
    val privacyWords = listOf(
        "photo", "gallery", "camera", "album", "files", "filemanager", "documents",
        "downloads", "explorer", "solidexplorer", "mixplorer", "vault", "private", "secret",
        "locker", "notes", "keep", "diary", "journal", "dayone", "health", "garmin", "strava",
        "flo", "clue", "myfitnesspal", "medical", "patient", "mychart", "pharmacy",
        "walgreens", "cvs", "chrome", "firefox", "brave", "duckduckgo", "opera", "vivaldi",
        "edge", "tor", "vpn", "protonvpn", "nordvpn", "expressvpn", "mullvad", "tailscale", "wireguard"
    )

    return when {
        financeWords.any(haystack::contains) -> AppCategory.FINANCE
        authWords.any(haystack::contains) -> AppCategory.AUTH
        appCategory == android.content.pm.ApplicationInfo.CATEGORY_SOCIAL || socialWords.any(haystack::contains) -> AppCategory.SOCIAL
        appCategory == android.content.pm.ApplicationInfo.CATEGORY_IMAGE || privacyWords.any(haystack::contains) -> AppCategory.PRIVACY
        pkg.lowercase().startsWith("com.android.") || pkg.lowercase().startsWith("com.google.android.") -> AppCategory.SYSTEM
        else -> AppCategory.GENERAL
    }
}

@Composable
private fun ProfileEditorScreenExpressive(
    profileToEdit: SecurityProfile?,
    allProfiles: List<SecurityProfile>,
    lockedApps: Set<String>,
    installedApps: List<AppItem>,
    onDismiss: () -> Unit,
    onSaveProfile: (SecurityProfile) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    BackHandler(onBack = onDismiss)

    var profileName by rememberSaveable(profileToEdit?.id) { mutableStateOf(profileToEdit?.name ?: "") }
    var selectedIconKey by rememberSaveable(profileToEdit?.id) { mutableStateOf(profileToEdit?.iconKey ?: "shield") }
    var selectedPackages by remember(profileToEdit?.id) { mutableStateOf(profileToEdit?.packages ?: emptySet()) }
    var selectedTimeoutMode by remember(profileToEdit?.id) { mutableStateOf(profileToEdit?.timeoutMode ?: LockTimeoutMode.IMMEDIATE) }
    var appSearchQuery by rememberSaveable(profileToEdit?.id) { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm && profileToEdit != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete '${profileToEdit.name}'?") },
            text = { Text("This custom profile will be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteProfile(profileToEdit.id)
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val iconOptions = listOf(
        "shield" to Icons.Default.Shield,
        "finance" to Icons.Default.AccountBalance,
        "social" to Icons.AutoMirrored.Filled.Chat,
        "work" to Icons.Default.Work,
        "privacy" to Icons.Default.VisibilityOff,
        "lock" to Icons.Default.Lock
    )

    val timeoutOptions = listOf(
        LockTimeoutMode.IMMEDIATE to "Immediate",
        LockTimeoutMode.ON_SCREEN_OFF to "Screen off",
        LockTimeoutMode.GRACE_30_SECONDS to "30s",
        LockTimeoutMode.GRACE_1_MINUTE to "1m",
        LockTimeoutMode.GRACE_5_MINUTES to "5m",
        LockTimeoutMode.GRACE_15_MINUTES to "15m"
    )

    val uniqueApps = remember(installedApps) {
        installedApps.distinctBy { it.packageName }
    }

    val otherProfilesByPkg = remember(allProfiles, profileToEdit?.id) {
        val map = HashMap<String, SecurityProfile>()
        for (p in allProfiles) {
            if (p.id == SecurityVault.PROFILE_ALL || p.id == profileToEdit?.id) continue
            for (pkg in p.packages) {
                map[pkg] = p
            }
        }
        map
    }

    // Apps already inside this profile
    val currentProfileApps = remember(uniqueApps, selectedPackages, appSearchQuery) {
        val inProfile = uniqueApps.filter { it.packageName in selectedPackages }
        if (appSearchQuery.isBlank()) {
            inProfile
        } else {
            val q = appSearchQuery.trim().lowercase()
            inProfile.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
    }

    // Apps available to add (excludes apps already in this profile)
    val candidateAppsToAdd = remember(uniqueApps, selectedPackages, appSearchQuery) {
        val notInThisProfile = uniqueApps.filterNot { it.packageName in selectedPackages }
        if (appSearchQuery.isBlank()) {
            notInThisProfile
        } else {
            val q = appSearchQuery.trim().lowercase()
            notInThisProfile.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .rejectObscuredTouches()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (profileToEdit == null) "New profile" else "Edit ${profileToEdit.name}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (profileToEdit != null && !profileToEdit.isPreset) {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Profile",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Button(
                onClick = {
                    val id = profileToEdit?.id ?: "profile_${UUID.randomUUID()}"
                    val profile = SecurityProfile(
                        id = id,
                        name = profileName.trim(),
                        iconKey = selectedIconKey,
                        packages = selectedPackages,
                        isPreset = profileToEdit?.isPreset ?: false,
                        timeoutMode = selectedTimeoutMode
                    )
                    onSaveProfile(profile)
                },
                enabled = profileName.isNotBlank(),
                shape = CircleShape
            ) {
                Text("Save")
            }
        }

        // Single unified LazyColumn so headers + app lists scroll smoothly without nested scroll jitter
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "editor_controls") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("Profile name") },
                        singleLine = true,
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Icon",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp)
                    ) {
                        items(iconOptions, key = { it.first }) { (key, iconVector) ->
                            val isSelected = selectedIconKey == key
                            Surface(
                                onClick = { selectedIconKey = key },
                                shape = CircleShape,
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = iconVector,
                                        contentDescription = key,
                                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Re-lock delay",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp)
                    ) {
                        items(timeoutOptions, key = { it.first.name }) { (mode, label) ->
                            val isSelected = selectedTimeoutMode == mode
                            Surface(
                                onClick = { selectedTimeoutMode = mode },
                                shape = CircleShape,
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = appSearchQuery,
                        onValueChange = { appSearchQuery = it },
                        placeholder = { Text("Search apps") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            if (appSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { appSearchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            if (currentProfileApps.isNotEmpty()) {
                item(key = "header_in_profile") {
                    Text(
                        text = "IN THIS PROFILE (${currentProfileApps.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }

                itemsIndexed(
                    items = currentProfileApps,
                    key = { _, app -> "in_profile_${app.packageName}" }
                ) { index, app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (app.iconBitmap != null) {
                            Image(
                                bitmap = app.iconBitmap,
                                contentDescription = null,
                                modifier = Modifier.size(38.dp)
                            )
                        } else {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = app.label.take(1).uppercase(),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Already in this profile • ${app.category.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Checkbox(
                            checked = true,
                            onCheckedChange = null,
                            enabled = false,
                            colors = CheckboxDefaults.colors(
                                disabledCheckedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                checkmarkColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(
                            onClick = { selectedPackages = selectedPackages - app.packageName },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Remove from profile",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (index < currentProfileApps.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp, end = 20.dp),
                            color = dividerColor
                        )
                    }
                }

                item(key = "spacer_after_in_profile") {
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }

            item(key = "header_add_apps") {
                Text(
                    text = "ADD APPS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            itemsIndexed(
                items = candidateAppsToAdd,
                key = { _, app -> "add_${app.packageName}" }
            ) { index, app ->
                val otherProfile = otherProfilesByPkg[app.packageName]
                val isInOtherProfile = otherProfile != null
                val isIndividuallyLocked = app.packageName in lockedApps
                val isChecked = isInOtherProfile || isIndividuallyLocked
                val isSelectable = !isInOtherProfile

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (isSelectable) 1f else 0.6f)
                        .then(
                            if (isSelectable) {
                                Modifier.toggleable(
                                    value = false,
                                    role = Role.Checkbox,
                                    onValueChange = {
                                        selectedPackages = selectedPackages + app.packageName
                                    }
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (app.iconBitmap != null) {
                        Image(
                            bitmap = app.iconBitmap,
                            contentDescription = null,
                            modifier = Modifier.size(38.dp)
                        )
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = app.label.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitleText = when {
                            otherProfile != null -> "In ${otherProfile.name} profile • ${app.category.label}"
                            isIndividuallyLocked -> "Protected in All • ${app.category.label}"
                            else -> app.category.label
                        }
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (otherProfile != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = null,
                        enabled = isSelectable,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            disabledCheckedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                    )
                }

                if (index < candidateAppsToAdd.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp, end = 20.dp),
                        color = dividerColor
                    )
                }
            }
        }
    }
}

