package com.android.BioKey.feature.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.BioKey.BioKeyApplication
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.security.ProtectDialogWindow
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.feature.lock.AppInfoCache
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.ui.brand.BioKeyMarkSideways
import com.android.BioKey.ui.brand.KeyMark
import com.android.BioKey.ui.brand.HeaderMatrixDotsExpressive
import com.android.BioKey.ui.theme.BioKeyMessages
import com.android.BioKey.ui.theme.GroupGap
import com.android.BioKey.ui.theme.GroupInset
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.Haptics
import com.android.BioKey.ui.theme.MaxContentWidth
import com.android.BioKey.ui.theme.SectionHeader
import com.android.BioKey.ui.theme.springPress
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

private const val FILTER_ALL = "filter:all"

/** The list's kinds of row, so a row scrolled away is reused for another of its kind. */
private const val CONTENT_APP = "app"
private const val CONTENT_HEADER = "header"

/** Shown wherever a home-screen app's lock switch can't be turned on. */
internal const val HOME_SCREEN_NOTE = "Home screens can't be locked"

/**
 * BioKey's home. From the top: the logo over its constellation, a card saying whether
 * protection is on (with the one action that matters), then the apps. Search and profile chips
 * stay pinned while the list scrolls.
 *
 * "All apps" shows locked apps first, then the rest; every switch works. A profile is a page of
 * its own: its card holds everything about it, so a profile is edited inside the profile.
 * Tapping an app opens its options (lock, which profile).
 */
@Composable
fun MainScreenExpressive(mode: ProtectionEngine.Mode, onPauseRequested: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val vault = remember { SecurityVault.getInstance(context) }

    val selectedApps by vault.selectedAppsFlow.collectAsStateWithLifecycle()
    val lockedApps by vault.lockedAppsFlow.collectAsStateWithLifecycle()
    val globalLockEnabled by vault.globalLockEnabledFlow.collectAsStateWithLifecycle()
    val pausedUntil by vault.pausedUntilFlow.collectAsStateWithLifecycle()
    val profiles by vault.profilesFlow.collectAsStateWithLifecycle()
    val defaultTimeout by vault.timeoutModeFlow.collectAsStateWithLifecycle()
    val autoSortProfiles by vault.autoSortProfilesFlow.collectAsStateWithLifecycle()
    val packageChangeVersion by BioKeyApplication.packageChangeVersion.collectAsStateWithLifecycle()
    val disclosures = rememberDisclosureGate()
    val notifications = rememberNotificationPermission()

    // The last list stays on screen while a newer one loads: an app update shouldn't empty it.
    var installedApps by remember { mutableStateOf(InstalledAppsCache.apps) }
    var isLoadingApps by remember { mutableStateOf(installedApps.isEmpty()) }
    var appsFailed by remember { mutableStateOf(false) }

    LaunchedEffect(packageChangeVersion) {
        if (InstalledAppsCache.isCurrent(packageChangeVersion)) {
            installedApps = InstalledAppsCache.apps
            isLoadingApps = false
            return@LaunchedEffect
        }
        // An update sends a burst of package changes: load once, after the last.
        if (installedApps.isNotEmpty()) delay(300)
        val apps = try {
            withContext(Dispatchers.IO) { loadInstalledApps(context) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not worth losing the accessibility service (same process) over: say so, retry next time.
            appsFailed = installedApps.isEmpty()
            isLoadingApps = false
            return@LaunchedEffect
        }
        InstalledAppsCache.apps = apps
        InstalledAppsCache.version = packageChangeVersion
        installedApps = apps
        isLoadingApps = false
        appsFailed = false
        AppInfoCache.preWarm(context, vault.getLockedPackages())
    }

    // Icons are kept while BioKey is on screen, and for a few minutes after.
    LifecycleStartEffect(Unit) {
        AppIconCache.keep()
        onStopOrDispose { AppIconCache.releaseLater() }
    }

    // Once per package change (and once per process), not on every unlock.
    LaunchedEffect(autoSortProfiles, installedApps) {
        if (!autoSortProfiles) {
            InstalledAppsCache.autoSortedVersion = -1
            return@LaunchedEffect
        }
        val version = InstalledAppsCache.version
        if (installedApps.isEmpty() || InstalledAppsCache.autoSortedVersion == version) return@LaunchedEffect
        // Each app's category was worked out (Play category included) when the list was read.
        withContext(Dispatchers.IO) {
            vault.autoSortAppsIntoProfiles(installedApps.map { it.packageName to it.category })
        }
        InstalledAppsCache.autoSortedVersion = version
    }

    /**
     * Home-screen apps BioKey never covers, so the user can always get Home: the one in use and
     * the system's own ([ForegroundRules.isLauncher]). Locking one would do nothing, so their
     * switches can't be turned on. Read again on every resume: choosing another home screen sends
     * no broadcast.
     */
    var homeScreens by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(installedApps) {
        val apps = installedApps
        val job = scope.launch {
            homeScreens = withContext(Dispatchers.IO) {
                runCatching {
                    apps.mapNotNullTo(HashSet()) { app ->
                        app.packageName.takeIf { ForegroundRules.isLauncher(context, it, isLocked = { true }) }
                    }
                }.getOrDefault(emptySet())
            }
        }
        onPauseOrDispose { job.cancel() }
    }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Settings' slide in and out, followed so the header holds still only once Settings covers it.
    val settingsVisibility = remember { MutableTransitionState(showSettings) }
    settingsVisibility.targetState = showSettings
    var filter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var optionsForPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddApps by rememberSaveable { mutableStateOf(false) }
    var showNewProfile by rememberSaveable { mutableStateOf(false) }
    /** After creating a profile from an app's options, put that app in it. */
    var newProfileForPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var showEditProfile by rememberSaveable { mutableStateOf(false) }
    var showRelock by rememberSaveable { mutableStateOf(false) }
    var showProtectionSheet by rememberSaveable { mutableStateOf(false) }

    val currentProfile = profiles.firstOrNull { it.id == filter }
    LaunchedEffect(filter, currentProfile == null) {
        if (filter != FILTER_ALL && currentProfile == null) filter = FILTER_ALL
    }
    // An app's options close if it's uninstalled; not while the list is still loading (after
    // the process was restarted, say), or the sheet the user had open would be lost.
    LaunchedEffect(isLoadingApps, installedApps) {
        val pkg = optionsForPackage
        if (!isLoadingApps && pkg != null && installedApps.none { it.packageName == pkg }) optionsForPackage = null
    }
    // Sheets, dialogs and the notification shade take the window's focus, while BioKey stays
    // resumed behind their scrim. Only once it has had focus: before, the header moves from its
    // first frame, as it always has.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(windowFocused) { if (windowFocused) hadFocus = true }
    /**
     * Home's decorations animate only while nothing covers them: the app picker, Settings once
     * it has slid in (stopping as it starts would show them jump to a still frame), or a window
     * over BioKey's.
     */
    val headerActive = !(settingsVisibility.currentState && settingsVisibility.isIdle) && !(showAddApps && currentProfile != null) &&
        (windowFocused || !hadFocus)

    val profileByPackage = remember(profiles) {
        HashMap<String, SecurityProfile>().apply { for (p in profiles) for (pkg in p.packages) put(pkg, p) }
    }
    fun profileOf(pkg: String) = PasswordManager.aliasesOf(pkg).firstNotNullOfOrNull { profileByPackage[it] }
    /** The app's own lock switch. */
    fun isOn(pkg: String) = PasswordManager.aliasesOf(pkg).any { it in selectedApps }
    /** Actually locked: switched on, and its profile (if any) is on too. */
    fun isLocked(pkg: String) = PasswordManager.aliasesOf(pkg).any { it in lockedApps }
    /** Never covered, so its switch can only be turned off. */
    fun isHomeScreen(pkg: String) = pkg in homeScreens

    BackHandler(enabled = filter != FILTER_ALL && !showSettings) { filter = FILTER_ALL }

    val matching = remember(installedApps, searchQuery, filter, profiles) {
        installedApps.filter { app ->
            (currentProfile == null || profileOf(app.packageName)?.id == currentProfile.id) &&
                (searchQuery.isBlank() || app.label.contains(searchQuery.trim(), ignoreCase = true))
        }
    }
    val (lockedShown, unlockedShown) = remember(matching, selectedApps, profiles) { matching.partition { isOn(it.packageName) } }
    val appCountByProfile = remember(installedApps, profiles) {
        installedApps.mapNotNull { profileOf(it.packageName)?.id }.groupingBy { it }.eachCount()
    }
    // A home screen left switched on (from before, or a backup) isn't counted: it's never covered.
    val lockedCount = remember(installedApps, lockedApps, profiles, homeScreens) {
        installedApps.count { isLocked(it.packageName) && !isHomeScreen(it.packageName) }
    }
    /** Locked apps with a copy compatibility mode can't see: Android shares app usage only within one profile. */
    fun isUnwatched(app: AppItem) = mode == ProtectionEngine.Mode.COMPATIBILITY && app.inOtherProfile && isLocked(app.packageName) &&
        !isHomeScreen(app.packageName)
    val unwatchedCount = remember(installedApps, lockedApps, profiles, mode, homeScreens) { installedApps.count(::isUnwatched) }
    val look = protectionLook(mode, globalLockEnabled, pausedUntil, lockedCount, unwatchedCount, defaultTimeout)

    /** The app's own switch; it stays in its profile either way. */
    fun toggleLock(app: AppItem, locked: Boolean) {
        Haptics.toggle(view, locked)
        vault.setAppLock(app.packageName, locked)
        if (!locked) {
            BioKeyMessages.show("${app.label} unlocked", Icons.Default.LockOpen, actionLabel = "Undo") {
                vault.setAppLock(app.packageName, true)
            }
        }
    }

    /**
     * A group of app rows in the list. Keyed by package alone (the groups split one list without
     * repeats), so a row whose lock is switched moves to its new group rather than being rebuilt.
     */
    fun LazyListScope.appGroup(apps: List<AppItem>, showProfile: Boolean) {
        itemsIndexed(apps, key = { _, app -> app.packageName }, contentType = { _, _ -> CONTENT_APP }) { i, app ->
            val profile = if (showProfile) profileOf(app.packageName) else null
            // The lock stays on: it still hides the app's notifications, and protects it again as
            // soon as the accessibility engine is back.
            val unwatched = isUnwatched(app)
            val homeScreen = isHomeScreen(app.packageName)
            GroupRow(
                index = i,
                count = apps.size,
                title = app.label,
                subtitle = buildString {
                    append(profile?.name ?: app.category.label)
                    if (profile != null && !profile.enabled) append(" · off")
                    if (app.isWorkProfile) append(" · Work")
                    if (homeScreen) {
                        append(" · $HOME_SCREEN_NOTE")
                    } else if (unwatched) {
                        append(if (app.isWorkProfile) " · Not protected in compatibility mode" else " · Work copy not protected in compatibility mode")
                    }
                },
                subtitleColor = when {
                    homeScreen && isOn(app.packageName) -> MaterialTheme.colorScheme.error
                    homeScreen -> MaterialTheme.colorScheme.onSurfaceVariant
                    unwatched -> MaterialTheme.colorScheme.error
                    profile != null -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                leading = { AppIcon(app) },
                onClick = { optionsForPackage = app.packageName },
                modifier = Modifier
                    .animateItem()
                    .padding(horizontal = GroupInset)
                    .padding(top = if (i == 0) 0.dp else GroupGap)
            ) {
                // Its own TalkBack stop, so it says which app it locks. A home screen's can only
                // be turned off.
                Switch(
                    checked = isOn(app.packageName),
                    onCheckedChange = { toggleLock(app, it) },
                    enabled = !homeScreen || isOn(app.packageName),
                    modifier = Modifier.semantics { contentDescription = "Lock ${app.label}" }
                )
            }
        }
    }

    Box(Modifier.fillMaxSize().rejectObscuredTouches()) {
        val listState = key(filter) { rememberLazyListState() }
        LazyColumn(
            state = listState,
            // Phones use the full width; on tablets and in landscape the list keeps a readable width.
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item(key = "header") {
                // Where the header and its logo are on screen: the constellation thins out round
                // the logo, so no link cuts through the name.
                var headerOrigin by remember { mutableStateOf(Offset.Zero) }
                var logoBounds by remember { mutableStateOf<Rect?>(null) }
                Box(Modifier.onGloballyPositioned { headerOrigin = it.positionInRoot() }) {
                    HeaderMatrixDotsExpressive(
                        Modifier.matchParentSize(),
                        animated = headerActive,
                        quiet = logoBounds?.translate(-headerOrigin)
                    )
                    Column(Modifier.statusBarsPadding()) {
                        MainHeader(
                            status = look,
                            animated = headerActive,
                            onOpenStatus = { showProtectionSheet = true },
                            onOpenSettings = { showSettings = true },
                            onLogoPlaced = { logoBounds = it }
                        )
                    }
                }
            }

            if (!notifications.allowed) {
                item(key = "alerts_off") {
                    Box(Modifier.padding(horizontal = GroupInset).padding(bottom = 8.dp)) {
                        GroupRow(
                            index = 0,
                            count = 1,
                            title = "Alerts are off",
                            subtitle = "BioKey can't tell you if app locking stops, or stand in for hidden notifications · tap to allow",
                            icon = Icons.Default.NotificationsOff,
                            iconTint = MaterialTheme.colorScheme.onErrorContainer,
                            iconContainer = MaterialTheme.colorScheme.errorContainer,
                            onClick = notifications::requestOrOpenSettings
                        )
                    }
                }
            }

            stickyHeader(key = "controls") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(top = 12.dp, bottom = 4.dp)
                ) {
                    SearchPill(searchQuery, onChange = { searchQuery = it }, modifier = Modifier.padding(horizontal = GroupInset))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = GroupInset, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(key = FILTER_ALL) {
                            FilterChoice("All apps", Icons.Default.Apps, filter == FILTER_ALL) { filter = FILTER_ALL }
                        }
                        items(profiles, key = { it.id }) { profile ->
                            FilterChoice(
                                label = "${profile.name} · " + if (profile.enabled) "${appCountByProfile[profile.id] ?: 0}" else "off",
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
                }
            }

            if (currentProfile != null) {
                item(key = "profile_${currentProfile.id}") {
                    ProfileCard(
                        profile = currentProfile,
                        appCount = appCountByProfile[currentProfile.id] ?: 0,
                        onEnabledChange = { on ->
                            Haptics.toggle(view, on)
                            vault.setProfileEnabled(currentProfile.id, on)
                            if (!on) {
                                val id = currentProfile.id
                                BioKeyMessages.show("${currentProfile.name} off · its apps aren't locked", Icons.Default.LockOpen, actionLabel = "Undo") {
                                    vault.setProfileEnabled(id, true)
                                }
                            }
                        },
                        onEdit = { showEditProfile = true },
                        onRelock = { showRelock = true },
                        onAddApps = { showAddApps = true }
                    )
                }
            }

            when {
                isLoadingApps -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                matching.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        text = when {
                            appsFailed -> "Couldn't list your apps. Close BioKey and open it again to retry."
                            searchQuery.isNotBlank() -> "No apps match “${searchQuery.trim()}”"
                            currentProfile != null -> "No apps in ${currentProfile.name} yet"
                            else -> "No apps found"
                        },
                        actionLabel = if (currentProfile != null && searchQuery.isBlank()) "Add apps" else null,
                        onAction = { showAddApps = true }
                    )
                }
                currentProfile != null -> {
                    item(key = "h_profile", contentType = CONTENT_HEADER) { SectionHeader("Apps in ${currentProfile.name}") }
                    appGroup(matching, showProfile = false)
                }
                else -> {
                    if (lockedShown.isNotEmpty()) {
                        item(key = "h_locked", contentType = CONTENT_HEADER) { SectionHeader("Locked · ${lockedShown.size}") }
                        appGroup(lockedShown, showProfile = true)
                    }
                    if (unlockedShown.isNotEmpty()) {
                        item(key = "h_unlocked", contentType = CONTENT_HEADER) { SectionHeader(if (lockedShown.isEmpty()) "Choose apps to lock" else "Not locked") }
                        appGroup(unlockedShown, showProfile = false)
                    }
                }
            }
        }

    }

    // Settings slides in over the home screen, which stays composed underneath so the
    // predictive back gesture can reveal it.
    AnimatedVisibility(
        visibleState = settingsVisibility,
        enter = slideInHorizontally { it / 3 } + fadeIn(),
        exit = slideOutHorizontally { it / 3 } + fadeOut()
    ) {
        SettingsScreenExpressive(mode = mode, onNavigateBack = { showSettings = false }, onPauseRequested = onPauseRequested)
    }

    // ---- Sheets & dialogs ------------------------------------------------------------------

    if (showProtectionSheet) {
        ProtectionSheet(
            look = look,
            onAction = {
                showProtectionSheet = false
                when (mode) {
                    ProtectionEngine.Mode.NEEDS_COMPATIBILITY_SETUP ->
                        disclosures.request(Disclosure.COMPATIBILITY) { IntentNavigationHelper.openNextCompatibilityPermission(context) }
                    // Not running too: turning it off and on grants it again, so the disclosure comes first.
                    ProtectionEngine.Mode.OFF, ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING ->
                        disclosures.request(Disclosure.ACCESSIBILITY) { IntentNavigationHelper.openAccessibilitySettings(context) }
                    else -> vault.resumeProtection()
                }
            },
            onPause = {
                showProtectionSheet = false
                onPauseRequested()
            },
            onDismiss = { showProtectionSheet = false }
        )
    }

    optionsForPackage?.let { pkg ->
        val app = installedApps.firstOrNull { it.packageName == pkg }
        if (app != null) {
            AppOptionsSheet(
                app = app,
                isLocked = isOn(pkg),
                isHomeScreen = isHomeScreen(pkg),
                currentProfile = profileOf(pkg),
                profiles = profiles,
                defaultTimeout = defaultTimeout,
                onLockedChange = { toggleLock(app, it) },
                onMoveToProfile = { profileId -> vault.moveAppToProfile(pkg, profileId) },
                onRemoveFromProfile = {
                    val former = profileOf(pkg)
                    vault.removeAppFromProfile(pkg)
                    optionsForPackage = null
                    if (former != null) {
                        BioKeyMessages.show("Removed ${app.label} from ${former.name}", Icons.Default.RemoveCircleOutline, actionLabel = "Undo") {
                            vault.moveAppToProfile(pkg, former.id, switchOn = false)
                        }
                    }
                },
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
                BioKeyMessages.show("Deleted $name. Its apps keep their own lock switches.", Icons.Default.Delete)
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
        AddAppsScreen(
            profile = currentProfile,
            installedApps = installedApps,
            profiles = profiles,
            profileOfApp = ::profileOf,
            lockedApps = lockedApps,
            homeScreens = homeScreens,
            onAdd = { packages ->
                vault.addAppsToProfile(currentProfile.id, packages)
                showAddApps = false
            },
            onDismiss = { showAddApps = false }
        )
    }
}

/**
 * Status and Settings as equal-width tiles at the edges, BioKey's mark centred between them,
 * moving unless [animated] is false (the header covered).
 */
@Composable
private fun MainHeader(
    status: ProtectionLook,
    animated: Boolean,
    onOpenStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogoPlaced: (Rect) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderTile(
            icon = status.icon,
            label = status.shortLabel,
            container = status.container,
            content = status.content,
            contentDescription = "${status.title}. Details",
            onClick = onOpenStatus
        )
        // Centred between the two equal-width tiles, with breathing room on both sides.
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // The mark alone, on its side (the fingerprint on the right, the key on the left), as
            // large as the header holds; its print swirls while the header is uncovered.
            BioKeyMarkSideways(
                Modifier
                    .height(104.dp)
                    .aspectRatio(1f / KeyMark.ASPECT)
                    .onGloballyPositioned { onLogoPlaced(it.boundsInRoot()) },
                animated = animated
            )
        }
        HeaderTile(
            icon = Icons.Default.Settings,
            label = "Settings",
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
            contentDescription = "Settings",
            onClick = onOpenSettings
        )
    }
}

/** A circle around an icon, with its label underneath; the whole column is the touch target. */
@Composable
private fun HeaderTile(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    contentDescription: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(84.dp)
            .springPress()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick, role = Role.Button)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .padding(vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(container, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** How each protection state looks, in the header tile and its sheet. */
private data class ProtectionLook(
    val container: Color,
    val content: Color,
    val icon: ImageVector,
    val shortLabel: String,
    val title: String,
    val body: String,
    /** Primary action, or null when protecting normally (then the sheet offers Pause). */
    val action: String?
)

@Composable
private fun protectionLook(
    mode: ProtectionEngine.Mode,
    enabled: Boolean,
    pausedUntil: Long,
    lockedCount: Int,
    /** Locked apps with a copy in another profile, which compatibility mode can't see. */
    unwatchedCount: Int,
    defaultTimeout: LockTimeoutMode
): ProtectionLook {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val apps = if (lockedCount == 1) "1 app locked" else "$lockedCount apps locked"
    return when {
        mode == ProtectionEngine.Mode.NEEDS_COMPATIBILITY_SETUP -> ProtectionLook(
            colors.tertiaryContainer, colors.onTertiaryContainer, Icons.Default.GppMaybe, "Set up",
            "Advanced Protection is on",
            "It reserves accessibility for accessibility tools. Allow usage access and display over other apps, and BioKey keeps locking your apps in compatibility mode.",
            "Set up compatibility mode"
        )
        mode == ProtectionEngine.Mode.OFF -> ProtectionLook(
            colors.errorContainer, colors.onErrorContainer, Icons.Default.Warning, "Off",
            "App lock is off", "Turn on BioKey in Accessibility settings to start locking apps.", "Turn on"
        )
        // Switched on, but not running (it crashed): saying "Protected" here would be the one lie that matters.
        mode == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING -> ProtectionLook(
            colors.errorContainer, colors.onErrorContainer, Icons.Default.Warning, "Not running",
            "App lock isn't running",
            "BioKey's accessibility service is switched on but isn't running, so locked apps open without a check. Turn it off and on again in Accessibility settings.",
            "Open Accessibility settings"
        )
        !enabled -> ProtectionLook(
            colors.tertiaryContainer, colors.onTertiaryContainer, Icons.Default.PauseCircle, "Paused",
            if (pausedUntil > System.currentTimeMillis()) {
                "Paused until " + android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(pausedUntil))
            } else "Protection paused",
            "Locked apps open without a check until you resume.",
            "Resume protection"
        )
        else -> ProtectionLook(
            colors.primaryContainer, colors.onPrimaryContainer, Icons.Default.VerifiedUser, "Protected",
            "Protected",
            apps + " · " + defaultTimeout.relockPhrase.lowercase() +
                (if (mode == ProtectionEngine.Mode.COMPATIBILITY) "\nCompatibility mode (works with Advanced Protection)" else "") +
                when (unwatchedCount) {
                    0 -> ""
                    1 -> "\n1 locked app isn't protected in your work profile: compatibility mode can't see apps there"
                    else -> "\n$unwatchedCount locked apps aren't protected in your work profile: compatibility mode can't see apps there"
                },
            null
        )
    }
}

/** Protection details and the one action that matters, opened from the header tile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtectionSheet(
    look: ProtectionLook,
    onAction: () -> Unit,
    onPause: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ProtectDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .rejectObscuredTouches()
                .padding(horizontal = 28.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(look.container, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(look.icon, contentDescription = null, tint = look.content, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(look.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                look.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            if (look.action != null) {
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(look.action) }
            } else {
                OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.PauseCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pause protection")
                }
            }
        }
    }
}

@Composable
private fun FilterChoice(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer)
    )
}

/** Everything about a profile, at the top of its own page. */
@Composable
private fun ProfileCard(
    profile: SecurityProfile,
    appCount: Int,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRelock: () -> Unit,
    onAddApps: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GroupInset, vertical = 8.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileBadge(profile.iconKey, 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = (if (appCount == 1) "1 app" else "$appCount apps") + " · " +
                            if (profile.enabled) profile.timeoutMode.relockPhrase.lowercase() else "off",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename, change icon or delete ${profile.name}")
                }
            }
            Spacer(Modifier.height(12.dp))
            // The profile's own switch: off pauses every app in it; each keeps its own setting.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .toggleable(value = profile.enabled, role = Role.Switch, onValueChange = onEnabledChange)
                    .padding(horizontal = 4.dp, vertical = 8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Lock apps in ${profile.name}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (profile.enabled) "Each app follows its own switch" else "Off: none of its apps are locked",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = profile.enabled, onCheckedChange = null)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAddApps, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add apps")
                }
                FilledTonalButton(
                    onClick = onRelock,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    Icon(Icons.Default.LockClock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Re-lock")
                }
            }
        }
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
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
