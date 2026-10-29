package com.android.BioKey.feature.main

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.BioKey.BioKeyApplication
import com.android.BioKey.R
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.security.LockAllNow
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.feature.lock.AppInfoCache
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.ui.theme.BioKeyMessages
import com.android.BioKey.ui.theme.GroupGap
import com.android.BioKey.ui.theme.GroupInset
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.Haptics
import com.android.BioKey.ui.theme.MaxContentWidth
import com.android.BioKey.ui.theme.SEPARATOR
import com.android.BioKey.ui.theme.SectionHeader
import com.android.BioKey.ui.theme.scaledWithText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val FILTER_ALL = "filter:all"

/** From here (Material's expanded width), Settings opens beside home rather than over it. */
private val TwoPaneMinWidth = 840.dp

/** Home's share of the window with Settings open beside it. */
private const val HomePaneFraction = 0.48f

/** The list's kinds of row, so a row scrolled away is reused for another of its kind. */
private const val CONTENT_APP = "app"
private const val CONTENT_HEADER = "header"

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
fun MainScreenExpressive(
    mode: ProtectionEngine.Mode,
    onPauseRequested: () -> Unit,
    /** An app to show as home opens (the new-app offer); [onSearchRequestTaken] once it's in the search. */
    searchRequest: String? = null,
    onSearchRequestTaken: () -> Unit = {},
    /** Home's state from outside the vault, and Review's. */
    homeViewModel: HomeViewModel = viewModel(),
    /** Settings open as home opens (screenshots of the tablet's two panes). */
    settingsOpenAtStart: Boolean = false
) {
    val context = LocalContext.current
    val res = LocalResources.current
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

    var showSettings by rememberSaveable { mutableStateOf(settingsOpenAtStart) }
    // Settings' slide in and out, followed so the header holds still only once Settings covers it.
    val settingsVisibility = remember { MutableTransitionState(showSettings) }
    settingsVisibility.targetState = showSettings
    // On a wide window (expanded width) Settings opens as a pane beside home instead of over it.
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val twoPane = windowWidth >= TwoPaneMinWidth
    val homeFraction by animateFloatAsState(if (twoPane && showSettings) HomePaneFraction else 1f, label = "home_pane")
    var filter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(searchRequest) {
        if (searchRequest != null) {
            filter = FILTER_ALL
            searchQuery = searchRequest
            onSearchRequestTaken()
        }
    }
    val overlays = rememberHomeOverlays()

    val currentProfile = profiles.firstOrNull { it.id == filter }
    LaunchedEffect(filter, currentProfile == null) {
        if (filter != FILTER_ALL && currentProfile == null) filter = FILTER_ALL
    }
    // An app's options close if it's uninstalled; not while the list is still loading (after
    // the process was restarted, say), or the sheet the user had open would be lost.
    LaunchedEffect(isLoadingApps, installedApps) {
        val pkg = overlays.optionsForPackage
        if (!isLoadingApps && pkg != null && installedApps.none { it.packageName == pkg }) overlays.optionsForPackage = null
    }
    // Sheets, dialogs and the notification shade take the window's focus, while BioKey stays
    // resumed behind their scrim. Only once it has had focus: before, the header moves from its
    // first frame, as it always has.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(windowFocused) { if (windowFocused) hadFocus = true }
    /**
     * Home's decorations animate only while nothing covers them: the app picker, Settings once
     * it has slid in over home (stopping as it starts would show them jump to a still frame; as
     * a pane beside home it covers nothing), or a window over BioKey's.
     */
    val headerActive = !(settingsVisibility.currentState && settingsVisibility.isIdle && !twoPane) && !(overlays.showAddApps && currentProfile != null) &&
        (windowFocused || !hadFocus)

    val profileByPackage = remember(profiles) {
        HashMap<String, SecurityProfile>().apply { for (p in profiles) for (pkg in p.packages) put(pkg, p) }
    }
    fun profileOf(pkg: String) = PasswordManager.aliasesOf(pkg).firstNotNullOfOrNull { profileByPackage[it] }
    /** The app's own lock switch. */
    fun isOn(pkg: String) = PasswordManager.aliasesOf(pkg).any { it in selectedApps }
    /** Actually locked: switched on, and its profile (if any) is on too. */
    fun isLocked(pkg: String) = PasswordManager.aliasesOf(pkg).any { it in lockedApps }
    /** Locked as the vault has it this moment, paused or not: for telling what a change just unlocked. */
    fun lockedInVault(pkg: String) = PasswordManager.aliasesOf(pkg).any { it in vault.getLockedPackages() }
    /** Never covered, so its switch can only be turned off. */
    fun isHomeScreen(pkg: String) = pkg in homeScreens

    BackHandler(enabled = filter != FILTER_ALL && !showSettings) { filter = FILTER_ALL }

    val matching = remember(installedApps, searchQuery, filter, profiles) {
        installedApps.filter { app ->
            (currentProfile == null || profileOf(app.packageName)?.id == currentProfile.id) &&
                (searchQuery.isBlank() || app.label.contains(searchQuery.trim(), ignoreCase = true))
        }
    }
    // Grouped by what actually happens: an app in a profile that's off is under "Not locked".
    val (lockedShown, unlockedShown) = remember(matching, lockedApps, profiles) { matching.partition { isLocked(it.packageName) } }
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
    val home by homeViewModel.state.collectAsStateWithLifecycle()
    val look = protectionLook(
        mode, globalLockEnabled, pausedUntil, lockedCount, unwatchedCount, defaultTimeout,
        notificationsShowing = home.shieldWithoutAccess && vault.isSetupCompleted()
    )

    /**
     * Locked apps open right now. Every unlock and lock (an app's re-lock delay running out
     * included) says so, so the card and the rows follow sessions starting and ending while
     * BioKey is open; read again on every resume too. Read in an effect, not while composing:
     * looking can end a session whose time ran out, which is logged and tells listeners.
     */
    var sessionsChanged by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val onAll: () -> Unit = { main.post { sessionsChanged++ } }
        val onApp: (String) -> Unit = { main.post { sessionsChanged++ } }
        LockStateTracker.addLockAllListener(onAll)
        LockStateTracker.addAppUnlockListener(onApp)
        LockStateTracker.addAppLockListener(onApp)
        onDispose {
            LockStateTracker.removeLockAllListener(onAll)
            LockStateTracker.removeAppUnlockListener(onApp)
            LockStateTracker.removeAppLockListener(onApp)
            main.removeCallbacksAndMessages(null)
        }
    }
    LifecycleResumeEffect(Unit) {
        sessionsChanged++
        onPauseOrDispose { }
    }
    var unlockedNow by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(sessionsChanged, lockedApps, profiles, defaultTimeout, globalLockEnabled) {
        // Paused, nothing is locked, so nothing is "unlocked" either.
        unlockedNow = if (globalLockEnabled) LockStateTracker.unlockedApps(vault::getTimeoutMode) else emptySet()
    }
    fun isUnlockedNow(pkg: String) = PasswordManager.aliasesOf(pkg).any { it in unlockedNow }
    val unlockedNowApps = remember(unlockedNow, installedApps, lockedApps, profiles) {
        installedApps.filter { isLocked(it.packageName) && isUnlockedNow(it.packageName) }
    }

    // What happened while the owner was away: failed unlocks, and protection switched off.
    // Seeing them here is what the notification was for, so it goes.
    val attempts by UnlockAttempts.attempts.collectAsStateWithLifecycle()
    val attemptsChosen by UnlockAttempts.chosen.collectAsStateWithLifecycle()
    val gaps by ProtectionGaps.gaps.collectAsStateWithLifecycle()
    fun openReview() = homeViewModel.openReview { overlays.showActivity = true }
    // Back after a rotation with Review open: work it out again.
    LaunchedEffect(overlays.showActivity) { if (overlays.showActivity) homeViewModel.restoreReview { overlays.showActivity = true } }
    // Review's state goes with the screen, so it's worked out again when it comes back.
    DisposableEffect(homeViewModel) { onDispose { homeViewModel.forgetReview() } }
    val attemptsEnabled by UnlockAttempts.enabled.collectAsStateWithLifecycle()
    val biometricOnlyOn by vault.biometricOnlyFlow.collectAsStateWithLifecycle()
    val seenUpTo by UnlockAttempts.seen.collectAsStateWithLifecycle()
    // Read again on every visit (an engine coming back clears stopped reminders, say), off the main thread.
    LifecycleResumeEffect(homeViewModel) {
        val job = homeViewModel.refresh()
        onPauseOrDispose { job.cancel() }
    }
    val labelByPackage = remember(installedApps) { installedApps.associate { it.packageName to it.label } }
    fun attemptLabel(attempt: UnlockAttempts.Attempt) = labelByPackage[attempt.packageName] ?: attempt.label

    /**
     * Ends every locked app's session at once. [includeOwnApp]: BioKey too, for the status sheet's
     * "Lock all apps now" (the phone is about to be handed over); the unlocked card's Lock all
     * leaves BioKey open, where the owner is.
     */
    fun lockAllNow(includeOwnApp: Boolean) {
        Haptics.confirm(view)
        val outcome = LockAllNow.run(context, includeOwnApp = includeOwnApp, reason = "locked from BioKey")
        BioKeyMessages.show(LockAllNow.message(context, outcome), Icons.Default.Lock)
    }

    /** The one thing to do when nothing is protecting: turn an engine on, or resume. */
    fun onProtectionAction() {
        when (mode) {
            ProtectionEngine.Mode.NEEDS_COMPATIBILITY_SETUP ->
                disclosures.request(Disclosure.COMPATIBILITY) { IntentNavigationHelper.openNextCompatibilityPermission(context) }
            // Not running too: turning it off and on grants it again, so the disclosure comes first.
            ProtectionEngine.Mode.OFF, ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING ->
                disclosures.request(Disclosure.ACCESSIBILITY) { IntentNavigationHelper.openAccessibilitySettings(context) }
            else -> vault.resumeProtection()
        }
    }

    /** Ends one app's session: the others stay open. */
    fun lockOne(app: AppItem) {
        Haptics.confirm(view)
        LockStateTracker.lockPackage(app.packageName, "locked from BioKey")
        BioKeyMessages.show(res.getString(R.string.home_app_locked, app.label), Icons.Default.Lock)
    }

    /** The app's own switch; it stays in its profile either way. */
    fun toggleLock(app: AppItem, locked: Boolean) {
        Haptics.toggle(view, locked)
        vault.setAppLock(app.packageName, locked)
        if (!locked) ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.LOCK_OFF, app.label)
        if (!locked) {
            // "Unlocked" is kept for an app open right now: this is its lock taken off.
            BioKeyMessages.show(res.getString(R.string.home_lock_removed, app.label), Icons.Default.LockOpen, actionLabel = res.getString(R.string.common_undo)) {
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
            // An app whose profile is off always says so: it's why its switch is off.
            val profile = profileOf(app.packageName)?.takeIf { showProfile || !it.enabled }
            // The lock stays on: it still hides the app's notifications, and protects it again as
            // soon as the accessibility engine is back.
            val unwatched = isUnwatched(app)
            val homeScreen = isHomeScreen(app.packageName)
            // A state, not a category: "unlocked now" takes its own colour, the rest of the line
            // keeps the row's. Not where the whole line is a warning.
            val unlockedColor = MaterialTheme.colorScheme.tertiary.takeIf { !homeScreen && !unwatched }
            GroupRow(
                index = i,
                count = apps.size,
                title = app.label,
                subtitle = buildAnnotatedString {
                    val locked = isLocked(app.packageName)
                    // Its profile, or else its kind; "App" (no category known) says nothing, so it isn't shown.
                    val name = profile?.name ?: if (app.category != AppCategory.GENERAL) stringResource(app.category.label) else null
                    if (name != null) append(name)
                    if (profile != null && !profile.enabled) append(SEPARATOR + stringResource(R.string.home_off))
                    // The default is said once, in the section header; a row says only a delay of its own.
                    val delay = vault.getTimeoutMode(app.packageName)
                    if (locked && !homeScreen && delay != defaultTimeout) {
                        val relock = stringResource(delay.relockPhraseRes)
                        append(if (name != null) SEPARATOR + relock.replaceFirstChar { it.lowercase() } else relock)
                    }
                    if (isUnlockedNow(app.packageName) && locked) {
                        val first = length == 0
                        if (!first) append(SEPARATOR)
                        val unlocked = stringResource(if (first) R.string.home_unlocked_now else R.string.home_unlocked_now_lower)
                        if (unlockedColor != null) withStyle(SpanStyle(color = unlockedColor)) { append(unlocked) } else append(unlocked)
                    }
                    if (app.isWorkProfile) append(SEPARATOR + stringResource(R.string.home_row_work))
                    if (homeScreen) {
                        append(SEPARATOR + stringResource(R.string.home_screen_note))
                    } else if (unwatched) {
                        append(
                            SEPARATOR + stringResource(
                                if (app.isWorkProfile) R.string.home_row_not_protected_compat else R.string.home_row_work_copy_not_protected_compat
                            )
                        )
                    }
                },
                subtitleColor = when {
                    homeScreen && isOn(app.packageName) -> MaterialTheme.colorScheme.error
                    homeScreen -> MaterialTheme.colorScheme.onSurfaceVariant
                    unwatched -> MaterialTheme.colorScheme.error
                    profile != null && profile.enabled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                leading = { AppIcon(app) },
                onClick = { overlays.optionsForPackage = app.packageName },
                modifier = Modifier
                    .animateItem()
                    .padding(horizontal = GroupInset)
                    .padding(top = if (i == 0) 0.dp else GroupGap)
            ) {
                // Its own TalkBack stop, so it says which app it locks. A home screen's can only
                // be turned off. While its profile is off it shows off and can't be changed (the
                // subtitle says why); its own choice comes back with the profile.
                val profileOff = profile?.enabled == false
                val lockDescription = stringResource(R.string.home_lock_app_description, app.label)
                Switch(
                    checked = isLocked(app.packageName),
                    onCheckedChange = { toggleLock(app, it) },
                    enabled = !profileOff && (!homeScreen || isOn(app.packageName)),
                    modifier = Modifier.semantics { contentDescription = lockDescription }
                )
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(homeFraction)
                .rejectObscuredTouches()
                // Out of screen readers' reach while Settings is over it, as it is out of the eye's.
                .then(if (showSettings && !twoPane) Modifier.clearAndSetSemantics { } else Modifier)
        ) {
            // Phones use the full width; on tablets and in landscape the list keeps a readable width
            // in the middle (of its pane, with Settings open beside it), while the header's
            // constellation still runs edge to edge.
            val homeWidth = windowWidth * homeFraction
            val sidePad = ((homeWidth - MaxContentWidth) / 2).coerceAtLeast(0.dp)
            val sidePadPx = with(density) { sidePad.roundToPx() }
            val listState = key(filter) { rememberLazyListState() }
            // The list runs under the status bar, and so would the search bar pinned at its top: by as
            // much as it would be under the status bar, it moves down, over a band of the page's colour.
            val statusBarTop = WindowInsets.statusBars.getTop(LocalDensity.current)
            val controlsInset by remember(listState, statusBarTop) {
                derivedStateOf {
                    val items = listState.layoutInfo.visibleItemsInfo
                    val controls = items.firstOrNull { it.key == "controls" } ?: return@derivedStateOf 0
                    // Where it would be if it didn't stick: just after the item before it.
                    val before = items.lastOrNull { it.index == controls.index - 1 }
                    val natural = before?.let { it.offset + it.size } ?: Int.MIN_VALUE
                    (statusBarTop.toLong() - natural).coerceIn(0L, statusBarTop.toLong()).toInt()
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = sidePad, end = sidePad, bottom = 96.dp)
            ) {
                item(key = "header") {
                    HomeHeader(
                        look = look,
                        animated = headerActive,
                        sidePadPx = sidePadPx,
                        onOpenStatus = { overlays.showProtectionSheet = true },
                        onOpenSettings = { showSettings = if (twoPane) !showSettings else true },
                        settingsSelected = if (twoPane) showSettings else null
                    )
                }

                homeCards(
                    mode = mode,
                    look = look,
                    vault = vault,
                    globalLockEnabled = globalLockEnabled,
                    notifications = notifications,
                    disclosures = disclosures,
                    homeViewModel = homeViewModel,
                    home = home,
                    attempts = attempts,
                    gaps = gaps,
                    seenUpTo = seenUpTo,
                    attemptsChosen = attemptsChosen,
                    biometricOnlyOn = biometricOnlyOn,
                    lockedApps = lockedApps,
                    unlockedNowApps = unlockedNowApps,
                    attemptLabel = ::attemptLabel,
                    onProtectionAction = ::onProtectionAction,
                    onReview = ::openReview,
                    onLockOne = ::lockOne,
                    onChooseUnlocked = { overlays.showUnlocked = true },
                    onLockAll = { lockAllNow(includeOwnApp = false) }
                )

                stickyHeader(key = "controls") {
                    val surface = MaterialTheme.colorScheme.surface
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer { translationY = controlsInset.toFloat() }
                            .drawBehind {
                                drawRect(surface, topLeft = Offset(0f, -controlsInset.toFloat()), size = Size(size.width, size.height + controlsInset))
                            }
                            .padding(top = 12.dp, bottom = 4.dp)
                    ) {
                        SearchPill(searchQuery, onChange = { searchQuery = it }, modifier = Modifier.padding(horizontal = GroupInset))
                        // On wide screens the chips scroll edge to edge, like the header, not cut off at the column.
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().horizontalBleed(sidePadPx),
                            contentPadding = PaddingValues(horizontal = GroupInset + sidePad, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item(key = FILTER_ALL) {
                                FilterChoice(stringResource(R.string.home_filter_all), Icons.Default.Apps, filter == FILTER_ALL) { filter = FILTER_ALL }
                            }
                            items(profiles, key = { it.id }) { profile ->
                                val apps = appCountByProfile[profile.id] ?: 0
                                FilterChoice(
                                    // An empty profile says just its name: "· 0" is noise.
                                    label = profile.name + when {
                                        !profile.enabled -> SEPARATOR + stringResource(R.string.home_off)
                                        apps == 0 -> ""
                                        else -> "$SEPARATOR$apps"
                                    },
                                    description = if (!profile.enabled) {
                                        stringResource(R.string.home_profile_chip_off_description, profile.name)
                                    } else {
                                        pluralStringResource(R.plurals.home_profile_chip_description, apps, profile.name, apps)
                                    },
                                    icon = profileIcon(profile.iconKey),
                                    selected = filter == profile.id
                                ) { filter = profile.id }
                            }
                            item(key = "new_profile") {
                                FilterChip(
                                    selected = false,
                                    onClick = { overlays.newProfileForPackage = null; overlays.showNewProfile = true },
                                    label = { Text(stringResource(R.string.profile_new)) },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp.scaledWithText())) },
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
                            defaultTimeout = defaultTimeout,
                            onEnabledChange = { on ->
                                Haptics.toggle(view, on)
                                vault.setProfileEnabled(currentProfile.id, on)
                                if (!on) ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.PROFILE_OFF, currentProfile.name)
                                if (!on) {
                                    val id = currentProfile.id
                                    BioKeyMessages.show(
                                        res.getString(R.string.home_profile_switched_off, currentProfile.name),
                                        Icons.Default.LockOpen,
                                        actionLabel = res.getString(R.string.common_undo)
                                    ) {
                                        vault.setProfileEnabled(id, true)
                                    }
                                }
                            },
                            onEdit = { overlays.showEditProfile = true },
                            onRelock = { overlays.showRelock = true },
                            onAddApps = { overlays.showAddApps = true }
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
                                appsFailed -> stringResource(R.string.home_apps_failed)
                                searchQuery.isNotBlank() -> stringResource(R.string.home_no_match, searchQuery.trim())
                                currentProfile != null -> stringResource(R.string.home_profile_empty, currentProfile.name)
                                else -> stringResource(R.string.home_no_apps)
                            },
                            actionLabel = if (currentProfile != null && searchQuery.isBlank()) stringResource(R.string.home_add_apps) else null,
                            onAction = { overlays.showAddApps = true }
                        )
                    }
                    currentProfile != null -> {
                        item(key = "h_profile", contentType = CONTENT_HEADER) { SectionHeader(stringResource(R.string.home_apps_in_profile, currentProfile.name)) }
                        appGroup(matching, showProfile = false)
                    }
                    else -> {
                        if (lockedShown.isNotEmpty()) {
                            item(key = "h_locked", contentType = CONTENT_HEADER) {
                                // How they re-lock, said once, only while something actually locks them.
                                val relock = when {
                                    !ProtectionEngine.isProtecting(mode) -> SEPARATOR + stringResource(R.string.home_not_protected_now)
                                    !globalLockEnabled -> SEPARATOR + stringResource(R.string.home_paused_lower)
                                    else -> SEPARATOR + stringResource(defaultTimeout.relockPhraseRes).replaceFirstChar { it.lowercase() }
                                }
                                SectionHeader(
                                    stringResource(R.string.home_section_locked) + "$SEPARATOR${lockedShown.size}$relock",
                                    color = when {
                                        !ProtectionEngine.isProtecting(mode) -> MaterialTheme.colorScheme.error
                                        // A pause the owner chose: calm, like its card.
                                        !globalLockEnabled -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )
                            }
                            appGroup(lockedShown, showProfile = true)
                        }
                        if (unlockedShown.isNotEmpty()) {
                            item(key = "h_unlocked", contentType = CONTENT_HEADER) { SectionHeader(stringResource(if (lockedShown.isEmpty()) R.string.home_choose_apps else R.string.common_not_locked)) }
                            // With their profile: one that's off is why they aren't locked.
                            appGroup(unlockedShown, showProfile = true)
                        }
                    }
                }
            }

        }

        // Settings slides in over the home screen, which stays composed underneath so the
        // predictive back gesture can reveal it. On a wide window it's a pane on the right instead,
        // home still in use beside it; its back arrow (and Back) closes the pane.
        AnimatedVisibility(
            visibleState = settingsVisibility,
            modifier = if (twoPane) Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(1f - HomePaneFraction) else Modifier.fillMaxSize(),
            enter = slideInHorizontally { it / 3 } + fadeIn(),
            exit = slideOutHorizontally { it / 3 } + fadeOut()
        ) {
            Row(Modifier.fillMaxSize()) {
                if (twoPane) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f)) {
                    SettingsScreenExpressive(
                        mode = mode, onNavigateBack = { showSettings = false }, onPauseRequested = onPauseRequested,
                        onBiometricOnlyOn = homeViewModel::undismissLockSettings, asPane = twoPane
                    )
                }
            }
        }
    }

    // ---- Sheets & dialogs ------------------------------------------------------------------

    HomeDialogs(
        overlays = overlays,
        homeViewModel = homeViewModel,
        vault = vault,
        look = look,
        installedApps = installedApps,
        profiles = profiles,
        currentProfile = currentProfile,
        defaultTimeout = defaultTimeout,
        lockedApps = lockedApps,
        homeScreens = homeScreens,
        unlockedNowApps = unlockedNowApps,
        attempts = attempts,
        gaps = gaps,
        labelByPackage = labelByPackage,
        profileOf = ::profileOf,
        isOn = ::isOn,
        isHomeScreen = ::isHomeScreen,
        lockedInVault = ::lockedInVault,
        attemptLabel = ::attemptLabel,
        onToggleLock = ::toggleLock,
        onLockOne = ::lockOne,
        onLockAll = ::lockAllNow,
        onProtectionAction = ::onProtectionAction,
        onAllowNotifications = {
            disclosures.request(Disclosure.NOTIFICATIONS) { IntentNavigationHelper.openNotificationListenerSettings(context) }
        },
        onPauseRequested = onPauseRequested,
        onFilterChange = { filter = it }
    )
}
