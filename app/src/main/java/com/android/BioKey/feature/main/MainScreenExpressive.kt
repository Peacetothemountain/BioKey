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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.BioKey.BioKeyApplication
import com.android.BioKey.R
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.data.openFor
import com.android.BioKey.core.security.LockAllNow
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.ScreenLockWatch
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.feature.lock.AppInfoCache
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.ui.disclosure.Disclosure
import com.android.BioKey.ui.disclosure.rememberDisclosureGate
import com.android.BioKey.ui.disclosure.rememberNotificationPermission
import com.android.BioKey.ui.navigation.IntentNavigationHelper
import com.android.BioKey.ui.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.BioKeyMessages
import com.android.BioKey.ui.theme.GroupGap
import com.android.BioKey.ui.theme.GroupInset
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.Haptics
import com.android.BioKey.ui.theme.MaxContentWidth
import com.android.BioKey.ui.theme.SEPARATOR
import com.android.BioKey.ui.theme.SectionHeader
import com.android.BioKey.ui.theme.SectionHeaderTop
import com.android.BioKey.ui.theme.SectionHeaderTopAfterChips
import com.android.BioKey.ui.theme.scaledWithText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val FILTER_ALL = "filter:all"

/** [pickingIn] for picking on All apps, which has no profile id; can’t be one. */
private const val PICK_ALL = "pick:all"

/** From here (Material's expanded width), Settings opens beside home rather than over it. */
private val TwoPaneMinWidth = 840.dp

/**
 * Widest home's cards and rows get: a readable column, so a switch stays near its app's name. A
 * phone (up to about 480dp) uses its whole width; an unfolded foldable or a tablet gets this, centred.
 */
internal val HomeColumnWidth = MaxContentWidth

/** Room each side of home's column in a pane [homeWidth] wide: none on a phone. */
internal fun homeSidePad(homeWidth: Dp): Dp = ((homeWidth - HomeColumnWidth) / 2).coerceAtLeast(0.dp)

/** The profile chips' row (or rows, wrapped in a narrowed column). */
internal const val CHIPS_TAG = "home_chips"

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
 * "All apps" shows locked apps first, then the rest, each recently used first then A–Z
 * ([AppOrder]), in an order fixed until BioKey is left (a row stays put as its switch is flipped);
 * every switch works. A profile is a page of its own: its card holds everything about it, so a
 * profile is edited inside the profile. Tapping an app opens its options (lock, which profile).
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
    settingsOpenAtStart: Boolean = false,
    /**
     * HOOK for the lock engine: apps Android's own App lock (Android 17) was seen locking, which
     * BioKey can't ask Android about. Their rows say "Also locked by Android". Empty until the
     * engine supplies it.
     */
    lockedByAndroid: Set<String> = emptySet()
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
    /** Where the search bar and chips are in the list. */
    val controlsIndex = remember { intArrayOf(-1) }
    /** The page shown chosen again: back to resting on the controls. */
    var repinRequests by remember { mutableIntStateOf(0) }
    /**
     * The page the list last showed. While it isn't [filter] yet (a page's first frames), the list
     * is changing pages: it keeps its scroll, and rows don't animate from one page to the next.
     */
    var shownPage by remember { mutableStateOf(filter) }
    val pageChanging = shownPage != filter
    LaunchedEffect(filter) {
        withFrameNanos { }
        withFrameNanos { }
        shownPage = filter
    }
    /** A page chosen by the owner (a chip, a profile just made). It keeps the list's scroll ([pageChanging]). */
    fun choosePage(id: String) {
        if (id == filter) {
            if (controlsIndex[0] >= 0) repinRequests++
            return
        }
        filter = id
    }
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
    /**
     * Apps picked to change together: on a profile's page to take out of it (a long press on an
     * app, or "Select"), on All apps to switch their locks off (a long press on a locked app).
     * Picking belongs to the page it began on ([pickingIn] is its profile, or [PICK_ALL]), so
     * leaving it, by a chip or Back, ends it. Kept through a rotation.
     */
    var pickingIn by rememberSaveable { mutableStateOf<String?>(null) }
    val picked = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        mutableStateListOf<String>()
    }
    val pageId = currentProfile?.id ?: PICK_ALL
    val picking = pickingIn == pageId
    // The bar shown while picking, as tall as it is laid out (its buttons go under the count when a
    // row hasn't room for both): the list leaves room for all of it.
    var pickBarHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(pageId) { if (pickingIn != null && pickingIn != pageId) pickingIn = null }
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
    // Composed after the page's own, so while apps are being picked Back ends that first.
    BackHandler(enabled = picking && !showSettings) { pickingIn = null }

    /**
     * Which apps were locked, and when each was last used, as the list was read or BioKey came
     * back (or a page was chosen, or Settings closed): the list's order and groups go by these,
     * not by switches flipped since, so a row never moves under the finger ([AppOrder]). Null
     * before the first look: the live state then.
     */
    var lockedAtLoad by remember { mutableStateOf<Set<String>?>(null) }
    var lastUsed by remember { mutableStateOf(AppOrder.lastRead) }
    LifecycleResumeEffect(installedApps, filter, showSettings) {
        lockedAtLoad = installedApps.mapNotNullTo(HashSet()) { app -> app.packageName.takeIf { isLocked(it) } }
        val job = scope.launch { lastUsed = withContext(Dispatchers.IO) { AppOrder.recentUse(context) } }
        onPauseOrDispose { job.cancel() }
    }
    fun wasLocked(pkg: String) = lockedAtLoad?.let { pkg in it } ?: isLocked(pkg)

    // Once per package change (and once per process), not on every unlock. It runs after the
    // list's groups were taken ([lockedAtLoad]), so the apps it locks join "Locked" as it finishes:
    // they were never the owner's to see move. The owner's own switches stay where they were.
    LaunchedEffect(autoSortProfiles, installedApps) {
        if (!autoSortProfiles) {
            InstalledAppsCache.autoSortedVersion = -1
            // Nothing to sort: the pass setup spoke of locked nothing.
            if (installedApps.isNotEmpty()) homeViewModel.autoSortPassed(0)
            return@LaunchedEffect
        }
        val version = InstalledAppsCache.version
        if (installedApps.isEmpty() || InstalledAppsCache.autoSortedVersion == version) return@LaunchedEffect
        val apps = installedApps
        // Each app's category was worked out (Play category included) when the list was read.
        val newlyLocked = withContext(Dispatchers.IO) {
            val before = vault.getLockedPackages()
            vault.autoSortAppsIntoProfiles(apps.map { it.packageName to it.category })
            AutoSortPass.newlyLocked(apps.map { it.packageName }, before, vault.getLockedPackages())
        }
        InstalledAppsCache.autoSortedVersion = version
        lockedAtLoad = AutoSortPass.regroup(lockedAtLoad, newlyLocked)
        homeViewModel.autoSortPassed(newlyLocked.size)
    }
    val ordered = remember(installedApps, lockedAtLoad, lastUsed) { AppOrder.order(installedApps, ::wasLocked, lastUsed) }

    val matching = remember(ordered, searchQuery, filter, profiles) {
        ordered.filter { app ->
            (currentProfile == null || profileOf(app.packageName)?.id == currentProfile.id) &&
                (searchQuery.isBlank() || app.label.contains(searchQuery.trim(), ignoreCase = true))
        }
    }
    // Grouped by what actually happened as the list was read: an app in a profile that's off is
    // under "Not locked". A switch flipped since moves nothing until BioKey is next opened.
    val (lockedShown, unlockedShown) = remember(matching, lockedAtLoad, lockedApps, profiles) { matching.partition { wasLocked(it.packageName) } }
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
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val screenLockMissing by ScreenLockWatch.missing.collectAsStateWithLifecycle()
    val home = homeState
    val look = protectionLook(mode, globalLockEnabled, pausedUntil, lockedCount, unwatchedCount, defaultTimeout)
    val attemptsChosenNow by UnlockAttempts.chosen.collectAsStateWithLifecycle()
    val biometricOnlyNow by vault.biometricOnlyFlow.collectAsStateWithLifecycle()
    // At most one calm offer at a time: the one showing keeps its place until it's answered.
    val offer = chooseOffer(
        eligibleOffers(
            home = home,
            setupCompleted = vault.isSetupCompleted(),
            attemptsChosen = attemptsChosenNow,
            screenLockMissing = screenLockMissing,
            lockSettingsWanted = biometricOnlyNow && look.action == null && SETTINGS_PACKAGE !in lockedApps
        ),
        homeViewModel.offerShowing
    )
    SideEffect { homeViewModel.offerShowing = offer }
    // The auto-sort card stays until "Got it": leaving home (to try a locked app, say) doesn't put
    // it away, as it's the only word on the apps locked for the owner.

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
    /** [unlockedNow] has been worked out: until then it is empty for want of a look, not for want of apps. */
    var unlockedNowRead by remember { mutableStateOf(false) }
    LaunchedEffect(sessionsChanged, lockedApps, profiles, defaultTimeout, globalLockEnabled) {
        // Paused, nothing is locked, so nothing is "unlocked" either.
        unlockedNow = if (globalLockEnabled) LockStateTracker.unlockedApps(vault::getTimeoutMode) else emptySet()
        unlockedNowRead = true
    }
    fun isUnlockedNow(pkg: String) = PasswordManager.aliasesOf(pkg).any { it in unlockedNow }
    val unlockedNowApps = remember(unlockedNow, installedApps, lockedApps, profiles) {
        installedApps.filter { isLocked(it.packageName) && isUnlockedNow(it.packageName) }
    }

    // What happened while the owner was away: failed unlocks, and protection switched off.
    // Seeing them here is what the notification was for, so it goes.
    val attempts by UnlockAttempts.attempts.collectAsStateWithLifecycle()
    val gaps by ProtectionGaps.gaps.collectAsStateWithLifecycle()
    fun openReview() = homeViewModel.openReview { overlays.showActivity = true }
    // Back after a rotation with Review open: work it out again.
    LaunchedEffect(overlays.showActivity) { if (overlays.showActivity) homeViewModel.restoreReview { overlays.showActivity = true } }
    // Review's state goes with the screen, so it's worked out again when it comes back.
    DisposableEffect(homeViewModel) { onDispose { homeViewModel.forgetReview() } }
    val attemptsEnabled by UnlockAttempts.enabled.collectAsStateWithLifecycle()
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
     * "Re-lock now" (the phone is about to be handed over); the unlocked card's Re-lock now
     * leaves BioKey open, where the owner is.
     */
    fun lockAllNow(includeOwnApp: Boolean) {
        Haptics.confirm(view)
        val outcome = LockAllNow.run(context, includeOwnApp = includeOwnApp, reason = "locked from BioKey")
        val message = LockAllNow.message(context, outcome)
        // BioKey locked too (the status sheet's): its own lock comes straight up, and a message on
        // BioKey's main page would wait unseen behind it. Said over the lock instead, as the
        // launcher shortcut says it, so being re-locked never looks like being thrown out.
        if (includeOwnApp) {
            android.widget.Toast.makeText(context.applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            BioKeyMessages.show(message, Icons.Default.Lock)
        }
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

    /**
     * [pkg] was just locked by the owner here (its switch, or a profile chosen in its options):
     * the first time, and only if it's actually locked now (not in a profile that's off), what
     * locking does is explained ([LockExplainer]).
     */
    fun explainIfFirst(pkg: String) {
        if (!lockedInVault(pkg) || !LockExplainer.shouldExplain(context, pkg)) return
        overlays.explainJustLocked = true
        overlays.explainPackage = pkg
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
        // Locked by hand for the first time: what that does and doesn't do, once.
        if (locked) explainIfFirst(app.packageName)
        if (!locked) ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.LOCK_OFF, app.label)
        if (!locked) {
            // "Unlocked" is kept for an app open right now: this is its lock taken off.
            // Its row stays under "Locked" while Undo is offered (it doesn't move under the finger),
            // then moves to "Not locked" as the message goes, unless Undo put the lock back.
            BioKeyMessages.show(
                res.getString(R.string.home_lock_removed, app.label), Icons.Default.LockOpen,
                actionLabel = res.getString(R.string.common_undo),
                onAction = { vault.setAppLock(app.packageName, true) },
                onGone = {
                    val pkg = app.packageName
                    if (!lockedInVault(pkg)) lockedAtLoad = lockedAtLoad?.minus(pkg)
                }
            )
        }
    }

    /** Picking starts on this page ([pageId]), with [first] picked if it began on an app. */
    fun startPicking(first: String? = null) {
        Haptics.confirm(view)
        picked.clear()
        if (first != null) picked += first
        pickingIn = pageId
    }

    /** On All apps, what can be picked: an app whose own lock is on and can be switched off here. */
    fun canSwitchOff(pkg: String) = isOn(pkg) && profileOf(pkg)?.enabled != false

    /**
     * The picked apps' locks switched off at once on All apps, each as its own switch does it:
     * recorded where BioKey was opened with the screen lock. They leave "Locked" for "Not locked"
     * at once (nothing is under the finger: it was on the bar), and Undo puts their locks back and
     * brings them back to "Locked".
     */
    fun switchOffPicked() {
        val apps = installedApps.filter { it.packageName in picked && canSwitchOff(it.packageName) }
        pickingIn = null
        picked.clear()
        if (apps.isEmpty()) return
        Haptics.toggle(view, false)
        val packages = apps.map { it.packageName }.toSet()
        for (app in apps) {
            vault.setAppLock(app.packageName, false)
            ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.LOCK_OFF, app.label)
        }
        lockedAtLoad = lockedAtLoad?.minus(packages)
        val message = apps.singleOrNull()?.let { res.getString(R.string.home_lock_removed, it.label) }
            ?: res.getQuantityString(R.plurals.home_locks_removed, apps.size, apps.size)
        BioKeyMessages.show(
            message, Icons.Default.LockOpen,
            actionLabel = res.getString(R.string.common_undo),
            onAction = {
                for (app in apps) vault.setAppLock(app.packageName, true)
                lockedAtLoad = lockedAtLoad?.plus(packages)
            }
        )
    }

    fun togglePicked(pkg: String) {
        Haptics.toggle(view, pkg !in picked)
        if (pkg in picked) picked.remove(pkg) else picked += pkg
    }

    /**
     * The picked apps still in [profile] taken out of it at once, each as "Remove from …" does it
     * alone: its own switch kept, recorded where that leaves it locked less. One Undo puts them
     * all back.
     */
    fun removePicked(profile: SecurityProfile) {
        val apps = installedApps.filter { it.packageName in picked && profileOf(it.packageName)?.id == profile.id }
        pickingIn = null
        picked.clear()
        if (apps.isEmpty()) return
        Haptics.confirm(view)
        for (app in apps) {
            val pkg = app.packageName
            val wasLocked = lockedInVault(pkg)
            val delayBefore = vault.getTimeoutMode(pkg).openFor
            vault.removeAppFromProfile(pkg)
            // Out of a strict profile, it follows the default delay: maybe a longer one.
            if (wasLocked && (!lockedInVault(pkg) || vault.getTimeoutMode(pkg).openFor > delayBefore)) {
                ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.TAKEN_OUT, app.label, profile.name)
            }
        }
        val message = apps.singleOrNull()?.let { res.getString(R.string.home_removed_from_profile, it.label, profile.name) }
            ?: res.getQuantityString(R.plurals.home_removed_apps_from_profile, apps.size, apps.size, profile.name)
        BioKeyMessages.show(message, Icons.Default.RemoveCircleOutline, actionLabel = res.getString(R.string.common_undo)) {
            for (app in apps) vault.moveAppToProfile(app.packageName, profile.id, switchOn = false)
        }
    }

    /**
     * A group of app rows in the list. Keyed by package alone (the groups split one list without
     * repeats), so a row that changes group when the list is next looked at moves rather than
     * being rebuilt. On a profile's page ([pickableIn]) a long press starts picking apps there,
     * and while they're being picked a row is a checkbox: a tap picks it, its switch stands aside.
     */
    // A two-line row's height: its padding and its title's and second line's line heights.
    val appRowHeight = with(density) {
        24.dp + (MaterialTheme.typography.bodyLarge.lineHeight.toDp() + MaterialTheme.typography.bodyMedium.lineHeight.toDp())
    }
    fun LazyListScope.appGroup(apps: List<AppItem>, showProfile: Boolean, pickableIn: SecurityProfile? = null, pickToSwitchOff: Boolean = false) {
        itemsIndexed(apps, key = { _, app -> app.packageName }, contentType = { _, _ -> CONTENT_APP }) { i, app ->
            // On a profile's page the page (its card) says the profile, and whether it's off.
            val profile = profileOf(app.packageName)?.takeIf { showProfile }
            // The lock stays on: it protects the app again as soon as the accessibility engine is back.
            val unwatched = isUnwatched(app)
            val homeScreen = isHomeScreen(app.packageName)
            // A state, not a category: "unlocked now" takes its own colour, the rest of the line
            // keeps the row's. Not where the whole line is a warning.
            val unlockedColor = MaterialTheme.colorScheme.tertiary.takeIf { !homeScreen && !unwatched }
            val pickable = pickableIn != null || (pickToSwitchOff && canSwitchOff(app.packageName))
            GroupRow(
                index = i,
                count = apps.size,
                title = app.label,
                // The second line says only what the section or page doesn't already: the app's
                // profile, then states. With nothing to say, the row has one line, centred, at the
                // same height as the rest, so "No profile" isn't printed twenty times over.
                subtitle = buildAnnotatedString {
                    val locked = isLocked(app.packageName)
                    /** A part of the line: the first starts with a capital, the rest follow a separator. */
                    fun part(text: String, lowerAfterFirst: Boolean = true, color: androidx.compose.ui.graphics.Color? = null) {
                        val shown = if (length == 0) text.replaceFirstChar { it.uppercase() }
                            else text.let { if (lowerAfterFirst) it.replaceFirstChar { c -> c.lowercase() } else it }
                        if (length > 0) append(SEPARATOR)
                        if (color != null) withStyle(SpanStyle(color = color)) { append(shown) } else append(shown)
                    }
                    if (profile != null) {
                        part(profile.name, lowerAfterFirst = false)
                        // An app whose profile is off says so: it's why its switch is off.
                        if (!profile.enabled) part(stringResource(R.string.home_off))
                    }
                    // On a profile's page, an app switched off one by one is the exception: it says so.
                    if (!showProfile && !locked && profileOf(app.packageName)?.enabled != false) part(stringResource(R.string.common_not_locked))
                    // The default is said once, in the section header; a row says only a delay of its own.
                    val delay = vault.getTimeoutMode(app.packageName)
                    if (locked && !homeScreen && delay != defaultTimeout) part(stringResource(delay.relockPhraseRes))
                    if (isUnlockedNow(app.packageName) && locked) part(stringResource(R.string.home_unlocked_now), color = unlockedColor)
                    if (app.isWorkProfile) part(stringResource(R.string.home_row_work))
                    if (app.packageName in lockedByAndroid) part(stringResource(R.string.home_row_also_locked_by_android))
                    if (homeScreen) {
                        part(stringResource(R.string.home_screen_note), lowerAfterFirst = false)
                    } else if (unwatched) {
                        part(
                            stringResource(
                                if (app.isWorkProfile) R.string.home_row_not_protected_compat else R.string.home_row_work_copy_not_protected_compat
                            ),
                            lowerAfterFirst = false
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
                // Outside a profile's page the row opens the app's options, as it always has. On
                // All apps a locked app's row can start picking too; while picking, a row that
                // can't be picked (its lock already off) does nothing.
                onClick = if (pickableIn == null && !pickToSwitchOff) ({ overlays.optionsForPackage = app.packageName }) else null,
                interaction = when {
                    pickableIn == null && !pickToSwitchOff -> Modifier
                    picking && pickable -> Modifier.toggleable(value = app.packageName in picked, role = Role.Checkbox) { togglePicked(app.packageName) }
                    picking -> Modifier
                    pickable -> Modifier.combinedClickable(
                        onLongClickLabel = stringResource(R.string.home_pick_start_label),
                        onLongClick = { startPicking(app.packageName) }
                    ) { overlays.optionsForPackage = app.packageName }
                    else -> Modifier.clickable { overlays.optionsForPackage = app.packageName }
                },
                modifier = Modifier
                    .animateItem()
                    .padding(horizontal = GroupInset)
                    .padding(top = if (i == 0) 0.dp else GroupGap)
                    // A row with one line is as tall as one with two, its line centred.
                    .heightIn(min = appRowHeight)
            ) {
                if (picking && pickable) {
                    // The row says picked or not, and a tap anywhere on it picks: the box only shows it.
                    Checkbox(checked = app.packageName in picked, onCheckedChange = null)
                    return@GroupRow
                }
                // Not to be picked: no switch to reach past the picking, nothing in its place.
                if (picking) return@GroupRow
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
            // Phones use the full width; on an unfolded foldable, a tablet or in landscape the list
            // keeps a readable width in the middle (of its pane, with Settings open beside it), so a
            // switch stays near its app's name, while the header's constellation still runs edge to
            // edge. Not the 720dp it once was: an unfolded foldable (about
            // 790dp) is only a little wider than that, which left its rows nearly full width.
            val homeWidth = windowWidth * homeFraction
            val sidePad = homeSidePad(homeWidth)
            // In a narrowed column the chips wrap within its edges (nothing cut off or sticking out
            // past them), and so they do in home's pane beside Settings, whose edge is the divider:
            // a row scrolling under it would be cut flat there. On a phone they scroll in one
            // line, edge to edge.
            val wrapChips = sidePad > 0.dp || (twoPane && showSettings)
            val sidePadPx = with(density) { sidePad.roundToPx() }
            // A page chosen keeps the scroll the owner left. The header and cards above the controls
            // are the same on every page, so while they show, the new page is at exactly the same
            // place. Scrolled past them (the controls pinned at the top), the new page starts right
            // under the controls. Each page has its own list state, so nothing of the last page's
            // rows animates over the new one (they faded out across it with one shared state).
            /** The list's scroll as last laid out: first item, its offset, and the window's height. */
            val lastScroll = remember { intArrayOf(0, 0, 0) }
            val listState = key(filter) {
                val at = controlsIndex[0]
                val pinnedAt = at >= 0 && lastScroll[0] >= at
                rememberLazyListState(
                    initialFirstVisibleItemIndex = if (pinnedAt) at else lastScroll[0],
                    initialFirstVisibleItemScrollOffset = if (pinnedAt) 0 else lastScroll[1]
                )
            }
            LaunchedEffect(listState) {
                snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.layoutInfo.viewportSize.height) }
                    .collect { (index, offset, height) ->
                        lastScroll[0] = index
                        lastScroll[1] = offset
                        if (height > 0) lastScroll[2] = height
                    }
            }
            // The chips' own scroll, kept here (not in the pinned header, which the list may let go
            // of): the chip chosen is always scrolled into sight, a profile just made among them.
            val chipState = rememberLazyListState()
            val chosenChip = if (filter == FILTER_ALL) 0 else profiles.indexOfFirst { it.id == filter }.let { if (it < 0) -1 else it + 1 }
            LaunchedEffect(chosenChip, wrapChips) {
                if (chosenChip < 0 || wrapChips) return@LaunchedEffect
                // Once the row has laid out the chip (a profile just made is new to it).
                withFrameNanos { }
                val shown = chipState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == chosenChip }
                val viewport = chipState.layoutInfo.let { it.viewportStartOffset..it.viewportEndOffset }
                val whole = shown != null && shown.offset >= viewport.first && shown.offset + shown.size <= viewport.last
                if (!whole) chipState.animateScrollToItem(chosenChip)
            }
            // In a short window (landscape) the controls scroll away with the list, which gets the whole window.
            val pinned = !shortWindow()
            // The list runs under the status bar, and so would the controls pinned at its top: by as
            // much as they'd be under it, they grow a band of the page's colour above themselves.
            // Real height, not a shift: what scrolls under them is covered by all of them, band included.
            val statusBarTop = WindowInsets.statusBars.getTop(LocalDensity.current)
            val controlsInset by remember(listState, statusBarTop, pinned) {
                derivedStateOf {
                    if (!pinned) return@derivedStateOf 0
                    val items = listState.layoutInfo.visibleItemsInfo
                    // Not laid out yet (a page's first frame): pinned if the page starts at or past
                    // them, else nothing to clear. Always taking the status bar's height here pushed
                    // a new page's controls down by it for one frame, a jump at every switch.
                    val controls = items.firstOrNull { it.key == CONTROLS_KEY }
                        ?: return@derivedStateOf if (controlsIndex[0] >= 0 && listState.firstVisibleItemIndex >= controlsIndex[0]) statusBarTop else 0
                    // Where it would be if it didn't stick: just after the item before it.
                    val before = items.lastOrNull { it.index == controls.index - 1 }
                    val natural = before?.let { it.offset + it.size } ?: Int.MIN_VALUE
                    (statusBarTop.toLong() - natural).coerceIn(0L, statusBarTop.toLong()).toInt()
                }
            }
            LaunchedEffect(listState) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == CONTROLS_KEY }?.index }
                    .collect { if (it != null) controlsIndex[0] = it }
            }
            LaunchedEffect(repinRequests) {
                if (repinRequests > 0 && controlsIndex[0] >= 0) listState.animateScrollToItem(controlsIndex[0])
            }
            // Back from "Add apps": the profile's page as it opened, resting on the controls with its
            // card whole under them, not wherever the list was left as the apps came in.
            var addAppsWasOpen by remember { mutableStateOf(false) }
            LaunchedEffect(overlays.showAddApps) {
                if (addAppsWasOpen && !overlays.showAddApps && currentProfile != null && pinned && controlsIndex[0] >= 0) {
                    withFrameNanos { }
                    listState.animateScrollToItem(controlsIndex[0])
                }
                addAppsWasOpen = overlays.showAddApps
            }
            // A profile's card never comes to rest cut by the pinned controls (its title sliced
            // through): a drag let go part of the way under them settles with the card wholly in
            // sight or wholly under them, whichever is nearer. The page's end keeps a little room
            // past its resting place (the controls' band behind the status bar needs it to get
            // there), so a short page would otherwise always have the card's top to slide under.
            if (pinned && currentProfile != null) {
                val cardKey = "profile_${currentProfile.id}"
                LaunchedEffect(listState, cardKey) {
                    snapshotFlow { listState.isScrollInProgress }.collect { moving ->
                        if (moving) return@collect
                        val items = listState.layoutInfo.visibleItemsInfo
                        val controls = items.firstOrNull { it.key == CONTROLS_KEY } ?: return@collect
                        val card = items.firstOrNull { it.key == cardKey } ?: return@collect
                        val by = cardSettle(card.offset, card.size, controls.offset + controls.size, listState.canScrollForward)
                        if (by != 0) listState.animateScrollBy(by.toFloat())
                    }
                }
            }
            // Room at the end of a short page, so it can still rest with the controls at the top
            // (a profile with a few apps, say): as much as the rows after the controls leave unfilled.
            var endRoom by remember { mutableIntStateOf(0) }
            LaunchedEffect(listState, pinned, statusBarTop) {
                snapshotFlow { if (pinned) roomToPin(listState.layoutInfo, controlsInset) else 0 }
                    .collect { if (it != null) endRoom = it }
            }
            // A new page's own room is only known once it's laid out. Until then, a window's worth,
            // so a short page can keep the scroll on its first frame (never pulled up, then pushed
            // back). The room is empty page below the last row: trimming it after moves nothing.
            val roomShown = if (pageChanging && pinned) maxOf(endRoom, lastScroll[2]) else endRoom
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = sidePad,
                    end = sidePad,
                    // Room for the bar shown while apps are picked (its height, its margins and the
                    // navigation bar under it), so the last row can always be scrolled clear of it.
                    bottom = if (picking) {
                        maxOf(112.dp, with(density) { pickBarHeight.toDp() } + 48.dp) + with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
                    } else 96.dp
                )
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
                    unlockedNowApps = unlockedNowApps,
                    attemptLabel = ::attemptLabel,
                    onProtectionAction = ::onProtectionAction,
                    onReview = ::openReview,
                    onLockOne = ::lockOne,
                    onChooseUnlocked = { overlays.showUnlocked = true },
                    onLockAll = { lockAllNow(includeOwnApp = false) },
                    screenLockMissing = screenLockMissing,
                    offer = offer
                )

                val controls: @Composable () -> Unit = {
                    Column(
                        Modifier
                            // Drawn over the rows scrolling under it (later items draw over earlier ones otherwise).
                            .zIndex(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .testTag(CONTROLS_KEY)
                            .layout { measurable, constraints ->
                                val inset = controlsInset
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height + inset) { placeable.place(0, inset) }
                            }
                            // Nothing under the chips: their own touch margin is the air there.
                            .padding(top = 12.dp)
                    ) {
                        SearchPill(searchQuery, onChange = { searchQuery = it }, modifier = Modifier.padding(horizontal = GroupInset))
                        val allChip: @Composable () -> Unit = {
                            FilterChoice(stringResource(R.string.home_filter_all), Icons.Default.Apps, filter == FILTER_ALL) { choosePage(FILTER_ALL) }
                        }
                        val profileChip: @Composable (SecurityProfile) -> Unit = { profile ->
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
                            ) { choosePage(profile.id) }
                        }
                        val newProfileChip: @Composable () -> Unit = {
                            FilterChip(
                                selected = false,
                                onClick = { overlays.newProfileForPackage = null; overlays.showNewProfile = true },
                                label = { Text(stringResource(R.string.profile_new)) },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp.scaledWithText())) },
                                shape = CircleShape
                            )
                        }
                        if (wrapChips) {
                            // Wrapped within the column, its first chip lined up with the search bar.
                            FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = GroupInset).padding(top = 8.dp).testTag(CHIPS_TAG),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                allChip()
                                profiles.forEach { key(it.id) { profileChip(it) } }
                                newProfileChip()
                            }
                        } else {
                            LazyRow(
                                state = chipState,
                                modifier = Modifier.fillMaxWidth().testTag(CHIPS_TAG),
                                contentPadding = PaddingValues(start = GroupInset, end = GroupInset, top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item(key = FILTER_ALL) { allChip() }
                                items(profiles, key = { it.id }) { profileChip(it) }
                                item(key = "new_profile") { newProfileChip() }
                            }
                        }
                    }
                }
                if (pinned) stickyHeader(key = CONTROLS_KEY) { controls() } else item(key = CONTROLS_KEY) { controls() }

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
                            // A profile's card, just above, already has "Add apps".
                            actionLabel = null,
                            onAction = {}
                        )
                    }
                    currentProfile != null -> {
                        item(key = "h_profile", contentType = CONTENT_HEADER) {
                            Row {
                                SectionHeader(stringResource(R.string.home_apps_in_profile, currentProfile.name), Modifier.weight(1f).alignByBaseline())
                                // A long press on an app does the same: this says it can be done.
                                if (!picking) {
                                    TextButton(
                                        onClick = { startPicking() },
                                        modifier = Modifier.alignByBaseline().padding(end = GroupInset + 4.dp)
                                    ) { Text(stringResource(R.string.home_pick_start)) }
                                }
                            }
                        }
                        appGroup(matching, showProfile = false, pickableIn = currentProfile)
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
                                    // Counted as they are now: a lock just taken off is off the count at once,
                                    // though its row waits for its message to go before it moves.
                                    stringResource(R.string.home_section_locked) + "$SEPARATOR${lockedShown.count { isLocked(it.packageName) }}$relock",
                                    // Right under the chips, as the other sections are under what's above them.
                                    top = SectionHeaderTopAfterChips,
                                    color = when {
                                        !ProtectionEngine.isProtecting(mode) -> MaterialTheme.colorScheme.error
                                        // A pause the owner chose: calm, like its card.
                                        !globalLockEnabled -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )
                            }
                            appGroup(lockedShown, showProfile = true, pickToSwitchOff = true)
                        }
                        if (unlockedShown.isNotEmpty()) {
                            item(key = "h_unlocked", contentType = CONTENT_HEADER) {
                                SectionHeader(
                                    stringResource(if (lockedShown.isEmpty()) R.string.home_choose_apps else R.string.common_not_locked),
                                    top = if (lockedShown.isEmpty()) SectionHeaderTopAfterChips else SectionHeaderTop
                                )
                            }
                            // With their profile: one that's off is why they aren't locked.
                            appGroup(unlockedShown, showProfile = true, pickToSwitchOff = true)
                        }
                    }
                }
                if (pinned) item(key = END_ROOM_KEY) { Spacer(Modifier.height(with(density) { roomShown.toDp() })) }
            }

            // Once the header has scrolled away, cards and rows would pass under the status bar's
            // clock and icons: a band of the page's colour there, as behind the pinned search bar.
            val headerGone by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
            // A new page starts with its own band; it doesn't fade out the last page's band over its header.
            val statusBandAnim = remember(listState) { androidx.compose.animation.core.Animatable(if (headerGone) 1f else 0f) }
            LaunchedEffect(statusBandAnim, headerGone) { statusBandAnim.animateTo(if (headerGone) 1f else 0f) }
            val statusBand = statusBandAnim.value
            if (statusBand > 0f) {
                val surface = MaterialTheme.colorScheme.surface
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(with(density) { statusBarTop.toDp() })
                        .drawBehind { drawRect(surface, alpha = statusBand) }
                )
            }

            // While apps are picked: how many, all or none, and taking them out of the profile or,
            // on All apps, switching their locks off.
            val pageApps = if (currentProfile != null) matching.map { it.packageName }
                else (lockedShown + unlockedShown).map { it.packageName }.filter(::canSwitchOff)
            val pickedHere = picked.count { it in pageApps }
            AnimatedVisibility(
                visible = picking,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                val profile = currentProfile
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = GroupInset, vertical = 12.dp)
                        .widthIn(max = HomeColumnWidth)
                        .fillMaxWidth()
                        .onSizeChanged { pickBarHeight = it.height }
                ) {
                    // On one row where there's room; otherwise the buttons go under the count, at the
                    // end, rather than the count breaking a letter to a line (narrow phones, large text).
                    PickBarLayout(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { pickingIn = null }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.home_pick_stop))
                        }
                        Text(
                            pluralStringResource(R.plurals.home_picked_count, pickedHere, pickedHere),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    // The two buttons wrap too, at the end, where even they don't fit on one line.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), itemVerticalAlignment = Alignment.CenterVertically) {
                        val all = pageApps.isNotEmpty() && pickedHere == pageApps.size
                        TextButton(onClick = {
                            picked.removeAll(pageApps)
                            if (!all) picked += pageApps
                        }) { Text(stringResource(if (all) R.string.home_pick_none else R.string.home_pick_all)) }
                        if (profile != null) {
                            // Out of this profile, not unlocked: "Take out", as the record of it says.
                            val takeOut = stringResource(R.string.home_pick_remove_description, profile.name)
                            Button(
                                onClick = { removePicked(profile) },
                                enabled = pickedHere > 0,
                                modifier = Modifier.semantics { contentDescription = takeOut }
                            ) {
                                Text(stringResource(R.string.home_pick_remove))
                            }
                        } else {
                            val switchOff = stringResource(R.string.home_pick_switch_off_description)
                            Button(
                                onClick = { switchOffPicked() },
                                enabled = pickedHere > 0,
                                modifier = Modifier.semantics { contentDescription = switchOff }
                            ) {
                                Text(stringResource(R.string.home_pick_switch_off))
                            }
                        }
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
        unlockedNowKnown = unlockedNowRead && !isLoadingApps,
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
        onPauseRequested = onPauseRequested,
        // A profile just made opens resting on the controls, as its chip would; one deleted goes back to the top.
        onFilterChange = { if (it == FILTER_ALL) filter = it else choosePage(it) },
        onLockedByHand = ::explainIfFirst
    )
}

/** The list's item holding the search bar and profile chips, pinned at the top as it scrolls. */
internal const val CONTROLS_KEY = "controls"

/** The list's last item: room for a short page to rest with the controls at the top ([roomToPin]). */
internal const val END_ROOM_KEY = "end_room"

/**
 * How far to scroll (px; up is negative) so a card at [offset] of [size] isn't left cut by the
 * pinned controls ending at [under]: nothing if it's clear of them or wholly under them; else back
 * until it's wholly in sight, or on until it's wholly under them where that's nearer and the list
 * can go on ([canGoOn]).
 */
internal fun cardSettle(offset: Int, size: Int, under: Int, canGoOn: Boolean): Int {
    val hidden = under - offset
    if (hidden <= 0 || hidden >= size) return 0
    return if (canGoOn && hidden * 2 > size) size - hidden else -hidden
}

/**
 * How much room the list's end needs (px) so its page can rest with the controls pinned at the
 * top: what the controls and the rows after them leave unfilled of the window. Null where that
 * can't be told from what's laid out (rows after the controls scrolled out above): keep the last.
 */
internal fun roomToPin(info: LazyListLayoutInfo, inset: Int): Int? {
    val items = info.visibleItemsInfo
    val controls = items.firstOrNull { it.key == CONTROLS_KEY } ?: return null
    // The end out of sight below the controls at the top: the page is longer than the window
    // already. Below them lower down: it can't be told yet.
    val end = items.firstOrNull { it.key == END_ROOM_KEY } ?: return if (controls.offset <= 0) 0 else null
    val between = items.filter { it.index > controls.index && it.index < end.index }
    if (between.size != end.index - controls.index - 1) return null
    // Their band behind the status bar left out: it only grows as the list scrolls into it, so
    // room counted with it couldn't be scrolled into (the band's height is spare at the end instead).
    val controlsPinned = controls.size - inset
    val window = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
    return (window - controlsPinned - between.sumOf { it.size }).coerceAtLeast(0)
}

/**
 * Auto-sort's pass over home's list, as the list's groups see it ([lockedAtLoad] in
 * [MainScreenExpressive]): the groups are taken as BioKey comes on screen and then held still
 * while the owner flips switches, but the apps auto-sort locks after that are its, not the
 * owner's, and go under "Locked" at once.
 */
internal object AutoSortPass {
    /** The [installed] apps (by package) that weren't locked [before] the pass and are [after] it. */
    fun newlyLocked(installed: List<String>, before: Set<String>, after: Set<String>): Set<String> =
        installed.filterTo(HashSet()) { pkg ->
            val aliases = PasswordManager.aliasesOf(pkg)
            aliases.none { it in before } && aliases.any { it in after }
        }

    /**
     * The groups after the pass: what was locked when they were taken, plus what auto-sort just
     * locked. Not taken yet (null): the list follows the live state, which already has them.
     */
    fun regroup(lockedAtLoad: Set<String>?, newlyLocked: Set<String>): Set<String>? =
        if (newlyLocked.isEmpty()) lockedAtLoad else lockedAtLoad?.plus(newlyLocked)
}

/**
 * The picking bar's two parts: the count (with Close) at the start and the buttons at the end, on
 * one row when both fit at their natural widths; otherwise the buttons go on a line of their own
 * under it, still at the end, so the count never breaks a letter to a line.
 */
@Composable
private fun PickBarLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, constraints ->
        val (count, actions) = measurables
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val oneRow = count.maxIntrinsicWidth(constraints.maxHeight) + actions.maxIntrinsicWidth(constraints.maxHeight) <= width
        val actionsPlaced = actions.measure(loose)
        if (oneRow) {
            val countPlaced = count.measure(loose.copy(maxWidth = (width - actionsPlaced.width).coerceAtLeast(0)))
            val height = maxOf(countPlaced.height, actionsPlaced.height)
            layout(width, height) {
                countPlaced.placeRelative(0, (height - countPlaced.height) / 2)
                actionsPlaced.placeRelative(width - actionsPlaced.width, (height - actionsPlaced.height) / 2)
            }
        } else {
            val countPlaced = count.measure(loose)
            layout(width, countPlaced.height + actionsPlaced.height) {
                countPlaced.placeRelative(0, 0)
                actionsPlaced.placeRelative(width - actionsPlaced.width, countPlaced.height)
            }
        }
    }
}
