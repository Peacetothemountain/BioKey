package com.android.BioKey.feature.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.android.BioKey.ui.theme.springPress
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.BioKey.BioKeyApplication
import com.android.BioKey.R
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.feature.lock.AppInfoCache
import com.android.BioKey.ui.theme.ExpressiveTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ICON_RENDER_DP = 40
private const val FILTER_ALL = "filter:all"

/**
 * BioKey's home: one list of apps, each with a lock switch that always works, plus profiles.
 *
 * A profile is a page of its own (pick its chip): its header holds everything about it (name,
 * icon, re-lock delay, add apps, delete), so editing a profile happens inside the profile.
 * Tapping any app opens its options (lock, which profile).
 */
@Composable
fun MainScreenExpressive(a11yActive: Boolean) {
    val context = LocalContext.current
    val iconPx = with(LocalDensity.current) { ICON_RENDER_DP.dp.roundToPx() }
    val vault = remember { SecurityVault.getInstance(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val lockedApps by vault.lockedAppsFlow.collectAsStateWithLifecycle()
    val globalLockEnabled by vault.globalLockEnabledFlow.collectAsStateWithLifecycle()
    val pausedUntil by vault.pausedUntilFlow.collectAsStateWithLifecycle()
    val profiles by vault.profilesFlow.collectAsStateWithLifecycle()
    val defaultTimeout by vault.timeoutModeFlow.collectAsStateWithLifecycle()
    val autoSortProfiles by vault.autoSortProfilesFlow.collectAsStateWithLifecycle()
    val packageChangeVersion by BioKeyApplication.packageChangeVersion.collectAsStateWithLifecycle()

    var installedApps by remember { mutableStateOf(InstalledAppsCache.get(packageChangeVersion, iconPx) ?: emptyList()) }
    var isLoadingApps by remember { mutableStateOf(installedApps.isEmpty()) }

    LaunchedEffect(packageChangeVersion, iconPx) {
        InstalledAppsCache.get(packageChangeVersion, iconPx)?.let {
            installedApps = it
            isLoadingApps = false
            return@LaunchedEffect
        }
        val apps = withContext(Dispatchers.IO) { loadInstalledApps(context, iconPx) }
        InstalledAppsCache.apps = apps
        InstalledAppsCache.version = packageChangeVersion
        InstalledAppsCache.iconPx = iconPx
        installedApps = apps
        isLoadingApps = false
        AppInfoCache.preWarm(context, vault.getLockedPackages())
    }

    LaunchedEffect(autoSortProfiles, installedApps) {
        if (autoSortProfiles && installedApps.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                vault.autoSortAppsIntoProfiles(installedApps.map { it.packageName to it.label }, context.packageManager)
            }
        }
    }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var optionsForPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddApps by rememberSaveable { mutableStateOf(false) }
    var showNewProfile by rememberSaveable { mutableStateOf(false) }
    /** After creating a profile from an app's options, put that app in it. */
    var newProfileForPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var showEditProfile by rememberSaveable { mutableStateOf(false) }
    var showRelock by rememberSaveable { mutableStateOf(false) }

    val visibleProfiles = profiles.filter { it.id != SecurityVault.PROFILE_ALL }
    val currentProfile = visibleProfiles.firstOrNull { it.id == filter }
    LaunchedEffect(filter, currentProfile == null) {
        if (filter != FILTER_ALL && currentProfile == null) filter = FILTER_ALL
    }

    val profileByPackage = remember(visibleProfiles) {
        HashMap<String, SecurityProfile>().apply {
            for (p in visibleProfiles) for (pkg in p.packages) put(pkg, p)
        }
    }
    fun profileOf(pkg: String) = packageAliases(pkg).firstNotNullOfOrNull { profileByPackage[it] }
    fun isLocked(pkg: String) = packageAliases(pkg).any { it in lockedApps } || profileOf(pkg) != null

    if (showSettings) {
        SettingsScreenExpressive(onNavigateBack = { showSettings = false })
        return
    }

    BackHandler(enabled = filter != FILTER_ALL) { filter = FILTER_ALL }

    val shownApps = remember(installedApps, searchQuery, filter, profiles, lockedApps) {
        installedApps
            .filter { app ->
                when {
                    currentProfile != null -> profileOf(app.packageName)?.id == currentProfile.id
                    else -> true
                }
            }
            .filter { searchQuery.isBlank() || it.label.contains(searchQuery.trim(), ignoreCase = true) }
    }
    val appCountByProfile = remember(installedApps, profiles) {
        installedApps.mapNotNull { profileOf(it.packageName)?.id }.groupingBy { it }.eachCount()
    }

    fun toggleLock(app: AppItem, locked: Boolean) {
        ExpressiveTokens.triggerHaptic(
            context,
            if (locked) ExpressiveTokens.HapticFeedbackStyle.TOGGLE_ON else ExpressiveTokens.HapticFeedbackStyle.TOGGLE_OFF
        )
        val formerProfile = profileOf(app.packageName)
        vault.setAppLock(app.packageName, locked)
        if (!locked) {
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val text = if (formerProfile != null) "${app.label} unlocked and removed from ${formerProfile.name}" else "${app.label} unlocked"
                val result = snackbar.showSnackbar(text, actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) {
                    if (formerProfile != null) vault.moveAppToProfile(app.packageName, formerProfile.id)
                    else vault.setAppLock(app.packageName, true)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.rejectObscuredTouches()
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
        // Living constellation behind the header (drawn under the status bar, like before).
        HeaderMatrixDotsExpressive(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .align(Alignment.TopCenter)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            MainHeader(onOpenSettings = { showSettings = true })
            StatusBanner(
                a11yActive = a11yActive,
                globalLockEnabled = globalLockEnabled,
                pausedUntil = pausedUntil,
                onEnableService = { IntentNavigationHelper.openAccessibilitySettings(context) },
                onResume = { vault.resumeProtection() }
            )
            SearchField(searchQuery) { searchQuery = it }

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = FILTER_ALL) {
                    FilterChoice("All apps", Icons.Default.Apps, filter == FILTER_ALL) { filter = FILTER_ALL }
                }
                items(visibleProfiles, key = { it.id }) { profile ->
                    FilterChoice(
                        label = "${profile.name} · ${appCountByProfile[profile.id] ?: 0}",
                        icon = profileIcon(profile.iconKey),
                        selected = filter == profile.id
                    ) { filter = profile.id }
                }
                item(key = "new_profile") {
                    FilterChip(
                        selected = false,
                        onClick = { newProfileForPackage = null; showNewProfile = true },
                        label = { Text("New profile") },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        shape = CircleShape
                    )
                }
            }

            val listState = key(filter) { rememberLazyListState() }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                if (currentProfile != null) {
                    item(key = "profile_header_${currentProfile.id}") {
                        ProfileHeader(
                            profile = currentProfile,
                            appCount = appCountByProfile[currentProfile.id] ?: 0,
                            onEdit = { showEditProfile = true },
                            onRelock = { showRelock = true },
                            onAddApps = { showAddApps = true }
                        )
                    }
                }
                when {
                    isLoadingApps -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    shownApps.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            text = when {
                                searchQuery.isNotBlank() -> "No apps match “${searchQuery.trim()}”"
                                currentProfile != null -> "No apps in ${currentProfile.name} yet"
                                else -> "No apps found"
                            },
                            actionLabel = if (currentProfile != null && searchQuery.isBlank()) "Add apps" else null,
                            onAction = { showAddApps = true }
                        )
                    }
                    else -> {
                        if (currentProfile == null) {
                            item(key = "list_label") {
                                SectionLabel("Choose apps to lock")
                            }
                        }
                        items(shownApps, key = { it.packageName }) { app ->
                            AppRow(
                                app = app,
                                locked = isLocked(app.packageName),
                                profile = if (currentProfile == null) profileOf(app.packageName) else null,
                                onLockedChange = { toggleLock(app, it) },
                                onOpenOptions = { optionsForPackage = app.packageName }
                            )
                        }
                    }
                }
            }
        }
        }
    }

    // ---- Sheets & dialogs ------------------------------------------------------------------

    optionsForPackage?.let { pkg ->
        val app = installedApps.firstOrNull { it.packageName == pkg }
        if (app == null) {
            optionsForPackage = null
        } else {
            AppOptionsSheet(
                app = app,
                isLocked = isLocked(pkg),
                currentProfile = profileOf(pkg),
                profiles = visibleProfiles,
                defaultTimeout = defaultTimeout,
                onLockedChange = { toggleLock(app, it) },
                onMoveToProfile = { profileId -> vault.moveAppToProfile(pkg, profileId) },
                onNewProfile = {
                    newProfileForPackage = pkg
                    optionsForPackage = null
                    showNewProfile = true
                },
                onDismiss = { optionsForPackage = null }
            )
        }
    }

    if (showNewProfile) {
        ProfileDetailsDialog(
            existing = null,
            onDismiss = { showNewProfile = false; newProfileForPackage = null },
            onSave = { name, iconKey ->
                val created = vault.createProfile(name, iconKey, defaultTimeout)
                showNewProfile = false
                filter = created.id
                val forPkg = newProfileForPackage
                newProfileForPackage = null
                if (forPkg != null) vault.moveAppToProfile(forPkg, created.id) else showAddApps = true
            },
            onDelete = null
        )
    }

    if (showEditProfile && currentProfile != null) {
        ProfileDetailsDialog(
            existing = currentProfile,
            onDismiss = { showEditProfile = false },
            onSave = { name, iconKey ->
                vault.updateProfileDetails(currentProfile.id, name = name, iconKey = iconKey)
                showEditProfile = false
            },
            onDelete = {
                val name = currentProfile.name
                vault.deleteProfile(currentProfile.id)
                showEditProfile = false
                filter = FILTER_ALL
                scope.launch { snackbar.showSnackbar("Deleted $name. Its apps are still locked.") }
            }
        )
    }

    if (showRelock && currentProfile != null) {
        RelockSheet(
            title = "Re-lock ${currentProfile.name}",
            current = currentProfile.timeoutMode,
            onSelect = { mode ->
                vault.updateProfileDetails(currentProfile.id, timeoutMode = mode)
                showRelock = false
            },
            onDismiss = { showRelock = false }
        )
    }

    if (showAddApps && currentProfile != null) {
        AddAppsSheet(
            profile = currentProfile,
            installedApps = installedApps,
            profileOfApp = ::profileOf,
            lockedApps = lockedApps,
            onAdd = { packages ->
                vault.addAppsToProfile(currentProfile.id, packages)
                showAddApps = false
            },
            onDismiss = { showAddApps = false }
        )
    }
}

@Composable
private fun MainHeader(onOpenSettings: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    // Breathing aura behind the logo. Read only inside graphicsLayer, so each frame is a cheap
    // layer property update: no recomposition, no re-layout, no re-record of the logo.
    val breath = rememberInfiniteTransition(label = "logo_aura_main")
    val auraScale by breath.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(2800, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "aura_scale"
    )
    val auraAlpha by breath.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "aura_alpha"
    )
    val auraBrush = remember(primary, tertiary) {
        Brush.radialGradient(listOf(primary.copy(alpha = 0.22f), tertiary.copy(alpha = 0.09f), Color.Transparent))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp)
    ) {
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
                        scaleX = auraScale
                        scaleY = auraScale
                        alpha = auraAlpha
                    }
                    .background(auraBrush, CircleShape)
            )
            Image(
                painter = painterResource(R.drawable.ic_title_logo_letters),
                contentDescription = "BioKey",
                modifier = Modifier.fillMaxHeight(),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(primary, BlendMode.Modulate)
            )
            Image(
                painter = painterResource(R.drawable.ic_title_logo_tentacles),
                contentDescription = null,
                modifier = Modifier.fillMaxHeight(),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(tertiary, BlendMode.Modulate)
            )
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .springPress()
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun StatusBanner(
    a11yActive: Boolean,
    globalLockEnabled: Boolean,
    pausedUntil: Long,
    onEnableService: () -> Unit,
    onResume: () -> Unit
) {
    val context = LocalContext.current
    val (title, body, action, onAction) = when {
        !a11yActive -> Quad("App lock is off", "Turn on BioKey in Accessibility settings to lock apps.", "Turn on", onEnableService)
        !globalLockEnabled -> {
            val title = if (pausedUntil > System.currentTimeMillis()) {
                "Paused until " + android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(pausedUntil))
            } else "Protection paused"
            Quad(title, "Locked apps open without authentication.", "Resume", onResume)
        }
        else -> return
    }
    val isError = !a11yActive
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isError) Icons.Default.Warning else Icons.Default.Shield,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onAction) { Text(action, fontWeight = FontWeight.SemiBold) }
        }
    }
}

private data class Quad(val title: String, val body: String, val action: String, val onAction: () -> Unit)

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search apps") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
            }
        },
        shape = CircleShape,
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun FilterChoice(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    )
}

/** Everything about a profile, at the top of its own page. */
@Composable
private fun ProfileHeader(
    profile: SecurityProfile,
    appCount: Int,
    onEdit: () -> Unit,
    onRelock: () -> Unit,
    onAddApps: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clickable(onClick = onEdit)) { ProfileBadge(profile.iconKey, 56.dp) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = "${if (appCount == 1) "1 app" else "$appCount apps"} · all locked",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename, change icon or delete ${profile.name}")
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                onClick = onRelock,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LockClock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Re-lock", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            profile.timeoutMode.shortLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAddApps, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add apps")
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit profile")
                }
            }
        }
    }
    if (appCount > 0) SectionLabel("Apps in ${profile.name}")
}

@Composable
private fun AppRow(
    app: AppItem,
    locked: Boolean,
    profile: SecurityProfile?,
    onLockedChange: (Boolean) -> Unit,
    onOpenOptions: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenOptions)
            .padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = buildString {
                if (profile != null) append(profile.name) else append(app.category.label)
                if (app.isWorkProfile) append(" · Work")
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (profile != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = locked, onCheckedChange = onLockedChange)
    }
}

@Composable
private fun EmptyState(text: String, actionLabel: String?, onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(actionLabel)
            }
        }
    }
}

