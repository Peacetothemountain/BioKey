package com.android.BioKey.feature.main

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.BioKey.BioKeyApplication
import com.android.BioKey.R
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.data.openFor
import com.android.BioKey.core.security.LockAllNow
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectDialogWindow
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.feature.lock.AppInfoCache
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.ui.brand.BioKeyMarkSideways
import com.android.BioKey.ui.brand.HeaderMatrixDotsExpressive
import com.android.BioKey.ui.brand.KeyMark
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

private const val FILTER_ALL = "filter:all"

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
    onSearchRequestTaken: () -> Unit = {}
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val view = LocalView.current
    val activity = LocalActivity.current
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
    LaunchedEffect(searchRequest) {
        if (searchRequest != null) {
            filter = FILTER_ALL
            searchQuery = searchRequest
            onSearchRequestTaken()
        }
    }
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
    val look = protectionLook(mode, globalLockEnabled, pausedUntil, lockedCount, unwatchedCount, defaultTimeout)

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
    var showUnlocked by rememberSaveable { mutableStateOf(false) }

    // What happened while the owner was away: failed unlocks, and protection switched off.
    // Seeing them here is what the notification was for, so it goes.
    val attempts by UnlockAttempts.attempts.collectAsStateWithLifecycle()
    val attemptsChosen by UnlockAttempts.chosen.collectAsStateWithLifecycle()
    val gaps by ProtectionGaps.gaps.collectAsStateWithLifecycle()
    var showActivity by rememberSaveable { mutableStateOf(false) }
    var hasStrongBiometric by remember { mutableStateOf(false) }
    var alertsMuted by remember { mutableStateOf(false) }
    val attemptsEnabled by UnlockAttempts.enabled.collectAsStateWithLifecycle()
    val biometricOnlyOn by vault.biometricOnlyFlow.collectAsStateWithLifecycle()
    // Read off the main thread; null until then, so the card doesn't flash in and out.
    var lockSettingsDismissed by remember { mutableStateOf<Boolean?>(null) }
    // Read again on every visit: an engine coming back clears it.
    var remindersStopped by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            withContext(Dispatchers.IO) {
                lockSettingsDismissed = homePrefs(context).getBoolean(KEY_LOCK_SETTINGS_DISMISSED, false)
                remindersStopped = ProtectionAlerts.remindersStopped(context)
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        val job = scope.launch(Dispatchers.IO) {
            hasStrongBiometric = UnlockAttempts.hasStrongBiometric(context)
            alertsMuted = UnlockAttempts.channelMuted(context)
            // Past their 7 days, gone from the disk too.
            UnlockAttempts.prune(context)
            ProtectionGaps.prune(context)
        }
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
        if (!locked) ProtectionGaps.recordIfScreenLock(context, "${app.label}'s lock switched off")
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
            GroupRow(
                index = i,
                count = apps.size,
                title = app.label,
                subtitle = buildString {
                    val locked = isLocked(app.packageName)
                    // A locked app says how it re-locks; "App" (no category known) says nothing, so it goes.
                    // "App" (no category known) says nothing, so it isn't shown.
                    val name = profile?.name ?: app.category.label.takeIf { app.category != AppCategory.GENERAL }
                    if (name != null) append(name)
                    if (profile != null && !profile.enabled) append(" · " + stringResource(R.string.home_off))
                    // The default is said once, in the section header; a row says only a delay of its own.
                    val delay = vault.getTimeoutMode(app.packageName)
                    if (locked && !homeScreen && delay != defaultTimeout) {
                        val relock = stringResource(delay.relockPhraseRes)
                        append(if (name != null) " · " + relock.replaceFirstChar { it.lowercase() } else relock)
                    }
                    if (isUnlockedNow(app.packageName) && locked) append(if (isEmpty()) stringResource(R.string.home_unlocked_now) else " · " + stringResource(R.string.home_unlocked_now_lower))
                    if (app.isWorkProfile) append(" · " + stringResource(R.string.home_row_work))
                    if (homeScreen) {
                        append(" · " + stringResource(R.string.home_screen_note))
                    } else if (unwatched) {
                        append(
                            " · " + stringResource(
                                if (app.isWorkProfile) R.string.home_row_not_protected_compat else R.string.home_row_work_copy_not_protected_compat
                            )
                        )
                    }
                },
                subtitleColor = when {
                    homeScreen && isOn(app.packageName) -> MaterialTheme.colorScheme.error
                    homeScreen -> MaterialTheme.colorScheme.onSurfaceVariant
                    unwatched -> MaterialTheme.colorScheme.error
                    // A state, not a category: its own colour.
                    isUnlockedNow(app.packageName) && isLocked(app.packageName) -> MaterialTheme.colorScheme.tertiary
                    profile != null && profile.enabled -> MaterialTheme.colorScheme.primary
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

    Box(
        Modifier
            .fillMaxSize()
            .rejectObscuredTouches()
            // Out of screen readers' reach while Settings is over it, as it is out of the eye's.
            .then(if (showSettings) Modifier.clearAndSetSemantics { } else Modifier)
    ) {
        // Phones use the full width; on tablets and in landscape the list keeps a readable width
        // in the middle, while the header's constellation still runs edge to edge.
        val density = LocalDensity.current
        val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
        val sidePad = ((windowWidth - MaxContentWidth) / 2).coerceAtLeast(0.dp)
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
                // Where the header and its logo are on screen: the constellation thins out round
                // the logo, so no link cuts through the name.
                var headerOrigin by remember { mutableStateOf(Offset.Zero) }
                var logoBounds by remember { mutableStateOf<Rect?>(null) }
                Box(Modifier.onGloballyPositioned { headerOrigin = it.positionInRoot() }) {
                    HeaderMatrixDotsExpressive(
                        Modifier
                            .matchParentSize()
                            .horizontalBleed(sidePadPx),
                        animated = headerActive,
                        quiet = logoBounds?.translate(-(headerOrigin - Offset(sidePadPx.toFloat(), 0f)))
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
                            title = stringResource(R.string.home_alerts_off_title),
                            subtitle = stringResource(R.string.home_alerts_off_text),
                            icon = Icons.Default.NotificationsOff,
                            iconTint = MaterialTheme.colorScheme.onErrorContainer,
                            iconContainer = MaterialTheme.colorScheme.errorContainer,
                            onClick = notifications::requestOrOpenSettings
                        )
                    }
                }
            }

            // Nothing locking right now: the most serious state gets the clearest card, with its one action.
            if (look.action != null && vault.isSetupCompleted()) {
                item(key = "not_protecting") {
                    // A pause the owner chose isn't an emergency; off, not running or set up is.
                    val paused = ProtectionEngine.isProtecting(mode) && !globalLockEnabled
                    val quiet = mode == ProtectionEngine.Mode.OFF && remindersStopped
                    ActionCard(
                        icon = look.icon,
                        title = look.title,
                        subtitle = if (quiet) stringResource(R.string.home_reminders_off_body, look.body) else look.body,
                        urgent = !paused,
                        modifier = Modifier.animateItem()
                    ) {
                        if (paused) {
                            FilledTonalButton(onClick = ::onProtectionAction) { Text(look.action) }
                        } else {
                            // Switched off in Accessibility (maybe on purpose): the owner can stop the
                            // reminders until it's back on. Not offered for a crash or a missing permission.
                            val cardText = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                            if (quiet) {
                                // Only strengthens: no authentication.
                                TextButton(
                                    onClick = {
                                        ProtectionAlerts.resumeReminders(context)
                                        remindersStopped = false
                                    },
                                    colors = cardText
                                ) { Text(stringResource(R.string.home_remind_me)) }
                            } else if (mode == ProtectionEngine.Mode.OFF) {
                                TextButton(
                                    onClick = {
                                        confirmOwner(activity, res.getString(R.string.home_confirm_stop_reminding)) {
                                            ProtectionAlerts.stopReminders(context)
                                            remindersStopped = true
                                            BioKeyMessages.show(res.getString(R.string.home_reminders_stopped), Icons.Default.NotificationsOff)
                                        }
                                    },
                                    colors = cardText
                                ) { Text(stringResource(R.string.home_stop_reminding)) }
                            }
                            UrgentButton(look.action, ::onProtectionAction)
                        }
                    }
                }
            }

            // One of BioKey's alert channels silenced in Android's settings (all of its notifications
            // off is the row above): say so. Only one card on the page is urgent at a time.
            if (alertsMuted && notifications.allowed) {
                item(key = "alerts_muted") {
                    ActionCard(
                        icon = Icons.Default.NotificationsOff,
                        title = stringResource(R.string.home_alerts_muted_title),
                        subtitle = stringResource(R.string.home_alerts_muted_text),
                        urgent = look.action == null && !securityActivityUrgent(attempts, gaps),
                        modifier = Modifier.animateItem()
                    ) {
                        val open: () -> Unit = { IntentNavigationHelper.openChannelSettings(context, UnlockAttempts.mutedChannel(context)) }
                        if (look.action == null && !securityActivityUrgent(attempts, gaps)) UrgentButton(stringResource(R.string.common_turn_on), onClick = open)
                        else FilledTonalButton(onClick = open) { Text(stringResource(R.string.common_turn_on)) }
                    }
                }
            }

            // Asked once, and only where it can help: a strong face or fingerprint is set up.
            if (!attemptsChosen && vault.isSetupCompleted() && hasStrongBiometric) {
                item(key = "attempts_intro") {
                    AttemptsIntroCard(
                        onNotNow = { UnlockAttempts.setEnabled(context, false) },
                        onTurnOn = {
                            UnlockAttempts.setEnabled(context, true)
                            if (!notifications.allowed) notifications.request()
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            if (attempts.isNotEmpty() || gaps.isNotEmpty()) {
                item(key = "activity") {
                    // One urgent card on the page: not protecting outranks this.
                    SecurityActivityCard(
                        attempts, gaps, ::attemptLabel, onReview = { showActivity = true }, modifier = Modifier.animateItem(),
                        urgent = securityActivityUrgent(attempts, gaps) && !(look.action != null && vault.isSetupCompleted() && !(ProtectionEngine.isProtecting(mode) && !globalLockEnabled))
                    )
                }
            }

            if (unlockedNowApps.isNotEmpty()) {
                item(key = "unlocked_now") {
                    UnlockedNowCard(
                        apps = unlockedNowApps,
                        timeoutOf = vault::getTimeoutMode,
                        onLock = ::lockOne,
                        onChoose = { showUnlocked = true },
                        onLockAll = { lockAllNow(includeOwnApp = false) },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            // Calm cards after the urgent ones. With your PIN, someone could add their own fingerprint
            // in Settings: lock it.
            if (biometricOnlyOn && hasStrongBiometric && look.action == null && SETTINGS_PACKAGE !in lockedApps && lockSettingsDismissed == false) {
                item(key = "lock_settings") {
                    ActionCard(
                        icon = Icons.Default.Lock,
                        title = stringResource(R.string.settings_lock_settings_title),
                        subtitle = stringResource(R.string.home_lock_settings_text),
                        modifier = Modifier.animateItem()
                    ) {
                        TextButton(onClick = {
                            lockSettingsDismissed = true
                            scope.launch(Dispatchers.IO) { homePrefs(context).edit { putBoolean(KEY_LOCK_SETTINGS_DISMISSED, true) } }
                        }) { Text(stringResource(R.string.common_not_now)) }
                        FilledTonalButton(onClick = {
                            val undo = vault.lockSettingsApp()
                            BioKeyMessages.show(res.getString(R.string.settings_lock_settings_done), Icons.Default.Lock, actionLabel = res.getString(R.string.common_undo), onAction = undo)
                        }) { Text(stringResource(R.string.new_app_lock)) }
                    }
                }
            }

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
                                    !profile.enabled -> " · " + stringResource(R.string.home_off)
                                    apps == 0 -> ""
                                    else -> " · $apps"
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
                                onClick = { newProfileForPackage = null; showNewProfile = true },
                                label = { Text(stringResource(R.string.profile_new)) },
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
                        defaultTimeout = defaultTimeout,
                        onEnabledChange = { on ->
                            Haptics.toggle(view, on)
                            vault.setProfileEnabled(currentProfile.id, on)
                            if (!on) ProtectionGaps.recordIfScreenLock(context, "${currentProfile.name} profile switched off")
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
                            appsFailed -> stringResource(R.string.home_apps_failed)
                            searchQuery.isNotBlank() -> stringResource(R.string.home_no_match, searchQuery.trim())
                            currentProfile != null -> stringResource(R.string.home_profile_empty, currentProfile.name)
                            else -> stringResource(R.string.home_no_apps)
                        },
                        actionLabel = if (currentProfile != null && searchQuery.isBlank()) stringResource(R.string.home_add_apps) else null,
                        onAction = { showAddApps = true }
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
                                !ProtectionEngine.isProtecting(mode) -> " · " + stringResource(R.string.home_not_protected_now)
                                !globalLockEnabled -> " · " + stringResource(R.string.home_paused_lower)
                                else -> " · " + stringResource(defaultTimeout.relockPhraseRes).replaceFirstChar { it.lowercase() }
                            }
                            SectionHeader(
                                stringResource(R.string.home_section_locked) + " · ${lockedShown.size}$relock",
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
                onProtectionAction()
            },
            onPause = {
                showProtectionSheet = false
                onPauseRequested()
            },
            onLockAll = {
                showProtectionSheet = false
                lockAllNow(includeOwnApp = true)
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
                onMoveToProfile = { profileId ->
                    val wasLocked = lockedInVault(pkg)
                    vault.moveAppToProfile(pkg, profileId)
                    // Into a profile that's off: no longer locked.
                    if (wasLocked && !lockedInVault(pkg)) ProtectionGaps.recordIfScreenLock(context, "${app.label} moved where it isn't locked")
                },
                onRemoveFromProfile = {
                    val former = profileOf(pkg)
                    val wasLocked = lockedInVault(pkg)
                    vault.removeAppFromProfile(pkg)
                    if (wasLocked && !lockedInVault(pkg)) ProtectionGaps.recordIfScreenLock(context, "${app.label} taken out of ${former?.name ?: "its profile"}, unlocking it")
                    optionsForPackage = null
                    if (former != null) {
                        BioKeyMessages.show(
                            res.getString(R.string.home_removed_from_profile, app.label, former.name),
                            Icons.Default.RemoveCircleOutline,
                            actionLabel = res.getString(R.string.common_undo)
                        ) {
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

    if (showActivity && (attempts.isNotEmpty() || gaps.isNotEmpty())) {
        // Reviewed: the notification has done its job (merely opening BioKey doesn't count). Not when
        // BioKey was opened with the screen lock on a phone with a face or fingerprint: someone who
        // only knows the PIN mustn't be able to quiet it.
        var ownerProven by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(Unit) {
            val proven = withContext(Dispatchers.Default) { SecurityEngine.ownerProven(context) }
            ownerProven = proven
            if (proven) UnlockAttempts.markSeen(context)
        }
        SecurityActivityDialog(
            attempts = attempts,
            gaps = gaps,
            labelOf = ::attemptLabel,
            onMarkReviewed = if (ownerProven == false) {
                {
                    confirmOwner(activity, res.getString(R.string.home_confirm_mark_reviewed)) {
                        UnlockAttempts.markSeen(context)
                        showActivity = false
                    }
                }
            } else null,
            onClear = {
                // The record of who tried: a face or fingerprint to clear it, not just the screen lock.
                confirmOwner(activity, res.getString(R.string.home_confirm_clear_activity)) {
                    // Proven just now: what stays (a face or fingerprint change) counts as reviewed.
                    UnlockAttempts.markSeen(context)
                    val oldAttempts = UnlockAttempts.clear(context)
                    val oldGaps = ProtectionGaps.clear(context)
                    showActivity = false
                    BioKeyMessages.show(res.getString(R.string.home_cleared), Icons.Default.Delete, actionLabel = res.getString(R.string.common_undo)) {
                        UnlockAttempts.restore(context, oldAttempts)
                        ProtectionGaps.restore(context, oldGaps)
                    }
                }
            },
            onDismiss = { showActivity = false }
        )
    }

    if (showUnlocked && unlockedNowApps.isNotEmpty()) {
        UnlockedAppsDialog(
            apps = unlockedNowApps,
            timeoutOf = vault::getTimeoutMode,
            onLock = ::lockOne,
            onLockAll = {
                showUnlocked = false
                lockAllNow(includeOwnApp = false)
            },
            onDismiss = { showUnlocked = false }
        )
    }

    if (showNewProfile) {
        ProfileDetailsDialog(
            existing = null,
            onDismiss = { showNewProfile = false; newProfileForPackage = null },
            onSave = { name, iconKey ->
                val created = vault.createProfile(name, iconKey)
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
                val deleted = vault.deleteProfile(currentProfile.id)
                if (deleted != null) ProtectionGaps.recordIfScreenLock(context, "$name profile deleted")
                showEditProfile = false
                filter = FILTER_ALL
                if (deleted != null) {
                    BioKeyMessages.show(res.getString(R.string.home_profile_deleted, name), Icons.Default.Delete, actionLabel = res.getString(R.string.common_undo)) {
                        vault.restoreDeletedProfile(deleted)
                    }
                }
            }
        )
    }

    if (showRelock && currentProfile != null) {
        RelockSheet(
            title = stringResource(R.string.home_relock_title, currentProfile.name),
            current = currentProfile.timeoutMode,
            onSelect = { mode ->
                val before = currentProfile.timeoutMode ?: defaultTimeout
                if ((mode ?: defaultTimeout).openFor > before.openFor) {
                    ProtectionGaps.recordIfScreenLock(context, "${currentProfile.name} re-lock delay made longer")
                }
                vault.setProfileTimeout(currentProfile.id, mode)
                showRelock = false
            },
            onDismiss = { showRelock = false },
            settingsDefault = defaultTimeout
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
                val wereLocked = packages.filter(::lockedInVault)
                vault.addAppsToProfile(currentProfile.id, packages)
                // Into a profile that's off: those that were locked aren't any more.
                val unlocked = wereLocked.filterNot(::lockedInVault)
                if (unlocked.isNotEmpty()) {
                    ProtectionGaps.recordIfScreenLock(
                        context,
                        if (unlocked.size == 1) "${labelByPackage[unlocked.single()] ?: unlocked.single()} added to ${currentProfile.name}, which is off"
                        else "${unlocked.size} locked apps added to ${currentProfile.name}, which is off"
                    )
                }
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
    val settings = stringResource(R.string.settings_title)
    val labelStyle = headerLabelStyle(status.shortLabel, settings)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderTile(
            icon = status.icon,
            label = status.shortLabel,
            labelStyle = labelStyle,
            container = status.container,
            content = status.content,
            contentDescription = stringResource(R.string.home_status_description, status.title),
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
            label = settings,
            labelStyle = labelStyle,
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
            contentDescription = settings,
            onClick = onOpenSettings
        )
    }
}

/**
 * The header tiles' label style: labelLarge, made smaller only as far as both [labels] need to
 * fit a tile on one line (large text would otherwise break "Protected" mid-word), and the same
 * for both, so they never look mismatched.
 */
@Composable
private fun headerLabelStyle(vararg labels: String): TextStyle {
    val base = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val width = with(density) { (HeaderTileWidth - 8.dp).roundToPx() }
    return remember(labels.toList(), base, density) {
        var size = base.fontSize
        while (size.value > 9f && labels.any { measurer.measure(it, base.copy(fontSize = size), maxLines = 1, softWrap = false).size.width > width }) {
            size = (size.value - 0.5f).sp
        }
        base.copy(fontSize = size)
    }
}

private val HeaderTileBaseWidth = 84.dp

/** Wider with large text (up to 1.4×), so labels shrink less to fit. */
private val HeaderTileWidth: Dp
    @Composable get() = HeaderTileBaseWidth * LocalDensity.current.fontScale.coerceIn(1f, 1.4f)

/** A circle around an icon, with its label underneath; the whole column is the touch target. */
@Composable
private fun HeaderTile(
    icon: ImageVector,
    label: String,
    /** Shared by both tiles ([headerLabelStyle]), so their labels match. */
    labelStyle: TextStyle,
    container: Color,
    content: Color,
    contentDescription: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(HeaderTileWidth)
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
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
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
    val apps = pluralStringResource(R.plurals.home_apps_locked, lockedCount, lockedCount)
    return when {
        // Locked apps open without a check here too: the same colours as off.
        mode == ProtectionEngine.Mode.NEEDS_COMPATIBILITY_SETUP -> ProtectionLook(
            colors.errorContainer, colors.onErrorContainer, Icons.Default.GppMaybe, stringResource(R.string.home_setup_short),
            stringResource(R.string.home_needs_compat_title),
            stringResource(R.string.home_needs_compat_body),
            stringResource(R.string.home_needs_compat_action)
        )
        mode == ProtectionEngine.Mode.OFF -> ProtectionLook(
            colors.errorContainer, colors.onErrorContainer, Icons.Default.Warning, stringResource(R.string.home_off_short),
            stringResource(R.string.alert_off_title), stringResource(R.string.home_off_body), stringResource(R.string.common_turn_on)
        )
        // Switched on, but not running (it crashed): saying "Protected" here would be the one lie that matters.
        mode == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING -> ProtectionLook(
            colors.errorContainer, colors.onErrorContainer, Icons.Default.Warning, stringResource(R.string.home_not_running_short),
            stringResource(R.string.home_not_running_title),
            stringResource(R.string.home_not_running_body),
            stringResource(R.string.home_not_running_action)
        )
        !enabled -> ProtectionLook(
            colors.tertiaryContainer, colors.onTertiaryContainer, Icons.Default.PauseCircle, stringResource(R.string.common_paused),
            if (pausedUntil > System.currentTimeMillis()) {
                stringResource(R.string.home_paused_until, android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(pausedUntil)))
            } else stringResource(R.string.home_protection_paused),
            stringResource(R.string.home_paused_body),
            stringResource(R.string.home_resume_protection)
        )
        else -> ProtectionLook(
            colors.primaryContainer, colors.onPrimaryContainer, Icons.Default.VerifiedUser, stringResource(R.string.home_protected),
            stringResource(R.string.home_protected),
            apps + " · " + stringResource(defaultTimeout.relockPhraseRes).lowercase() +
                (if (mode == ProtectionEngine.Mode.COMPATIBILITY) "\n" + stringResource(R.string.home_protected_compat) else "") +
                (if (unwatchedCount == 0) "" else "\n" + pluralStringResource(R.plurals.home_unwatched, unwatchedCount, unwatchedCount)),
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
    onLockAll: () -> Unit,
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
            Text(
                look.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                look.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            if (look.action != null) {
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(look.action) }
            } else {
                // Before handing the phone over, say: every locked app asks again, without waiting
                // for the screen to turn off.
                Button(onClick = onLockAll, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.shortcut_lock_all_long))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Default.PauseCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_pause_protection))
                }
            }
        }
    }
}

/** Draws [px] wider on each side than it is laid out: over the list's side margins on wide screens. */
private fun Modifier.horizontalBleed(px: Int): Modifier = if (px <= 0) this else layout { measurable, constraints ->
    val width = constraints.maxWidth + 2 * px
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-px, 0) }
}

/** [description]: what screen readers say in place of a short [label] ("Finance profile, 2 apps" for "Finance · 2"). */
@Composable
private fun FilterChoice(label: String, icon: ImageVector, selected: Boolean, description: String? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    )
}

/** Everything about a profile, at the top of its own page. */
@Composable
private fun ProfileCard(
    profile: SecurityProfile,
    appCount: Int,
    defaultTimeout: LockTimeoutMode,
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
                        text = pluralStringResource(R.plurals.home_app_count, appCount, appCount) + " · " +
                            if (profile.enabled) stringResource((profile.timeoutMode ?: defaultTimeout).relockPhraseRes).lowercase() else stringResource(R.string.home_off),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.home_profile_edit_description, profile.name))
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
                    Text(stringResource(R.string.home_profile_lock_apps, profile.name), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(if (profile.enabled) R.string.home_profile_on_text else R.string.home_profile_off_text),
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
                    Text(stringResource(R.string.home_add_apps))
                }
                FilledTonalButton(
                    onClick = onRelock,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    Icon(Icons.Default.LockClock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_relock))
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


/** The home screen's own small memory: cards the owner put away. */
internal fun homePrefs(context: Context) = context.getSharedPreferences("biokey_home", Context.MODE_PRIVATE)
internal const val KEY_LOCK_SETTINGS_DISMISSED = "lock_settings_dismissed"
