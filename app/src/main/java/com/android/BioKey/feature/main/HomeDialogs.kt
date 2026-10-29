package com.android.BioKey.feature.main

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.BioKey.R
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.data.openFor
import com.android.BioKey.core.security.ProtectDialogWindow
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.BioKeyMessages

/**
 * Which of home's sheets and dialogs is open. Each is kept as it was on its own: across a rotation
 * and the process being restarted, gone when home leaves the screen.
 */
@Stable
internal class HomeOverlays(
    showProtectionSheet: MutableState<Boolean>,
    optionsForPackage: MutableState<String?>,
    showAddApps: MutableState<Boolean>,
    showNewProfile: MutableState<Boolean>,
    newProfileForPackage: MutableState<String?>,
    showEditProfile: MutableState<Boolean>,
    showRelock: MutableState<Boolean>,
    showActivity: MutableState<Boolean>,
    showUnlocked: MutableState<Boolean>
) {
    var showProtectionSheet by showProtectionSheet
    /** The app whose options are open. */
    var optionsForPackage by optionsForPackage
    var showAddApps by showAddApps
    var showNewProfile by showNewProfile
    /** After creating a profile from an app's options, put that app in it. */
    var newProfileForPackage by newProfileForPackage
    var showEditProfile by showEditProfile
    var showRelock by showRelock
    /** Review. */
    var showActivity by showActivity
    var showUnlocked by showUnlocked
}

@Composable
internal fun rememberHomeOverlays(): HomeOverlays {
    val showProtectionSheet = rememberSaveable { mutableStateOf(false) }
    val optionsForPackage = rememberSaveable { mutableStateOf<String?>(null) }
    val showAddApps = rememberSaveable { mutableStateOf(false) }
    val showNewProfile = rememberSaveable { mutableStateOf(false) }
    val newProfileForPackage = rememberSaveable { mutableStateOf<String?>(null) }
    val showEditProfile = rememberSaveable { mutableStateOf(false) }
    val showRelock = rememberSaveable { mutableStateOf(false) }
    val showActivity = rememberSaveable { mutableStateOf(false) }
    val showUnlocked = rememberSaveable { mutableStateOf(false) }
    return remember {
        HomeOverlays(
            showProtectionSheet, optionsForPackage, showAddApps, showNewProfile, newProfileForPackage,
            showEditProfile, showRelock, showActivity, showUnlocked
        )
    }
}

/** Home's sheets and dialogs, over the list and Settings. */
@Composable
internal fun HomeDialogs(
    overlays: HomeOverlays,
    homeViewModel: HomeViewModel,
    vault: SecurityVault,
    look: ProtectionLook,
    installedApps: List<AppItem>,
    profiles: List<SecurityProfile>,
    currentProfile: SecurityProfile?,
    defaultTimeout: LockTimeoutMode,
    lockedApps: Set<String>,
    homeScreens: Set<String>,
    unlockedNowApps: List<AppItem>,
    attempts: List<UnlockAttempts.Attempt>,
    gaps: List<ProtectionGaps.Gap>,
    labelByPackage: Map<String, String>,
    profileOf: (String) -> SecurityProfile?,
    /** The app's own lock switch. */
    isOn: (String) -> Boolean,
    isHomeScreen: (String) -> Boolean,
    /** Locked as the vault has it this moment, paused or not: for telling what a change just unlocked. */
    lockedInVault: (String) -> Boolean,
    attemptLabel: (UnlockAttempts.Attempt) -> String,
    onToggleLock: (AppItem, Boolean) -> Unit,
    onLockOne: (AppItem) -> Unit,
    onLockAll: (includeOwnApp: Boolean) -> Unit,
    onProtectionAction: () -> Unit,
    onPauseRequested: () -> Unit,
    onFilterChange: (String) -> Unit
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val activity = LocalActivity.current
    val reviewState by homeViewModel.review.collectAsStateWithLifecycle()

    if (overlays.showProtectionSheet) {
        ProtectionSheet(
            look = look,
            onAction = {
                overlays.showProtectionSheet = false
                onProtectionAction()
            },
            onPause = {
                overlays.showProtectionSheet = false
                onPauseRequested()
            },
            onLockAll = {
                overlays.showProtectionSheet = false
                onLockAll(true)
            },
            onDismiss = { overlays.showProtectionSheet = false }
        )
    }

    overlays.optionsForPackage?.let { pkg ->
        val app = installedApps.firstOrNull { it.packageName == pkg }
        if (app != null) {
            AppOptionsSheet(
                app = app,
                isLocked = isOn(pkg),
                isHomeScreen = isHomeScreen(pkg),
                currentProfile = profileOf(pkg),
                profiles = profiles,
                defaultTimeout = defaultTimeout,
                onLockedChange = { onToggleLock(app, it) },
                onMoveToProfile = { profileId ->
                    val wasLocked = lockedInVault(pkg)
                    val delayBefore = vault.getTimeoutMode(pkg).openFor
                    vault.moveAppToProfile(pkg, profileId)
                    when {
                        // Into a profile that's off: no longer locked.
                        wasLocked && !lockedInVault(pkg) ->
                            ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.MOVED_UNLOCKED, app.label)
                        // Into one that keeps it open longer.
                        wasLocked && vault.getTimeoutMode(pkg).openFor > delayBefore ->
                            ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.MOVED_LONGER_DELAY, app.label, vault.profileOf(pkg)?.name.orEmpty())
                    }
                },
                onRemoveFromProfile = {
                    val former = profileOf(pkg)
                    val wasLocked = lockedInVault(pkg)
                    val delayBefore = vault.getTimeoutMode(pkg).openFor
                    vault.removeAppFromProfile(pkg)
                    // Out of a strict profile, it follows the default delay: maybe a longer one.
                    if (wasLocked && (!lockedInVault(pkg) || vault.getTimeoutMode(pkg).openFor > delayBefore)) {
                        ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.TAKEN_OUT, app.label, former?.name.orEmpty())
                    }
                    overlays.optionsForPackage = null
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
                    overlays.newProfileForPackage = pkg
                    overlays.optionsForPackage = null
                    overlays.showNewProfile = true
                },
                onDismiss = { overlays.optionsForPackage = null }
            )
        }
    }

    val review = reviewState
    if (overlays.showActivity && review != null && (attempts.isNotEmpty() || gaps.isNotEmpty())) {
        // Only what the dialog shows counts as seen, not something that arrives while it's open.
        val shownUpTo = maxOf(attempts.maxOfOrNull { it.seq } ?: 0L, gaps.maxOfOrNull { it.seq } ?: 0L)
        // Reviewed: the notification has done its job (merely opening BioKey doesn't count). Not when
        // the owner isn't proven ([SecurityEngine.ownerProven]): someone who only knows the PIN, or
        // whose face or fingerprint was only just added, mustn't be able to quiet it.
        val openedAt = remember { shownUpTo }
        LaunchedEffect(shownUpTo) { homeViewModel.reviewShowing(shownUpTo, openedAt) }
        // What it shows by then, not when the wait began; and only while BioKey is on screen.
        val latestShown by rememberUpdatedState(shownUpTo)
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(review.heldUntil, lifecycle) {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                homeViewModel.recheckWhenHoldEnds(review.heldUntil) { latestShown }
            }
        }
        SecurityActivityDialog(
            attempts = attempts,
            gaps = gaps,
            labelOf = attemptLabel,
            heldUntil = review.heldUntil,
            openedWithScreenLock = !review.ownerProven && review.heldUntil == null,
            onMarkReviewed = if (!review.ownerProven && review.heldUntil == null) {
                {
                    confirmOwner(activity, res.getString(R.string.home_confirm_mark_reviewed), silencesRecords = true) {
                        overlays.showActivity = false
                        homeViewModel.markReviewed(shownUpTo)
                    }
                }
            } else null,
            onClear = {
                // The record of who tried: a face or fingerprint to clear it, not just the screen lock.
                confirmOwner(activity, res.getString(R.string.home_confirm_clear_activity), silencesRecords = true) {
                    overlays.showActivity = false
                    homeViewModel.clearReviewed(shownUpTo)
                }
            },
            onDismiss = { overlays.showActivity = false; homeViewModel.closeReview() }
        )
    }

    if (overlays.showUnlocked && unlockedNowApps.isNotEmpty()) {
        UnlockedAppsDialog(
            apps = unlockedNowApps,
            timeoutOf = vault::getTimeoutMode,
            onLock = onLockOne,
            onLockAll = {
                overlays.showUnlocked = false
                onLockAll(false)
            },
            onDismiss = { overlays.showUnlocked = false }
        )
    }

    if (overlays.showNewProfile) {
        ProfileDetailsDialog(
            existing = null,
            onDismiss = { overlays.showNewProfile = false; overlays.newProfileForPackage = null },
            onSave = { name, iconKey ->
                val created = vault.createProfile(name, iconKey)
                overlays.showNewProfile = false
                onFilterChange(created.id)
                val forPkg = overlays.newProfileForPackage
                overlays.newProfileForPackage = null
                if (forPkg != null) {
                    val wasLocked = lockedInVault(forPkg)
                    val delayBefore = vault.getTimeoutMode(forPkg).openFor
                    vault.moveAppToProfile(forPkg, created.id)
                    // A new profile follows the default delay: maybe longer than the app had.
                    if (wasLocked && vault.getTimeoutMode(forPkg).openFor > delayBefore) {
                        ProtectionGaps.recordIfScreenLock(
                            context, ProtectionGaps.Change.MOVED_LONGER_DELAY, labelByPackage[forPkg] ?: forPkg, created.name
                        )
                    }
                } else {
                    overlays.showAddApps = true
                }
            },
            onDelete = null
        )
    }

    if (overlays.showEditProfile && currentProfile != null) {
        ProfileDetailsDialog(
            existing = currentProfile,
            onDismiss = { overlays.showEditProfile = false },
            onSave = { name, iconKey ->
                vault.updateProfileDetails(currentProfile.id, name = name, iconKey = iconKey)
                overlays.showEditProfile = false
            },
            onDelete = {
                val name = currentProfile.name
                val deleted = vault.deleteProfile(currentProfile.id)
                if (deleted != null) ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.PROFILE_DELETED, name)
                overlays.showEditProfile = false
                onFilterChange(FILTER_ALL)
                if (deleted != null) {
                    BioKeyMessages.show(res.getString(R.string.home_profile_deleted, name), Icons.Default.Delete, actionLabel = res.getString(R.string.common_undo)) {
                        vault.restoreDeletedProfile(deleted)
                    }
                }
            }
        )
    }

    if (overlays.showRelock && currentProfile != null) {
        RelockSheet(
            title = stringResource(R.string.home_relock_title, currentProfile.name),
            current = currentProfile.timeoutMode,
            onSelect = { mode ->
                val before = currentProfile.timeoutMode ?: defaultTimeout
                if ((mode ?: defaultTimeout).openFor > before.openFor) {
                    ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.PROFILE_DELAY_LONGER, currentProfile.name)
                }
                vault.setProfileTimeout(currentProfile.id, mode)
                overlays.showRelock = false
            },
            onDismiss = { overlays.showRelock = false },
            settingsDefault = defaultTimeout
        )
    }

    if (overlays.showAddApps && currentProfile != null) {
        AddAppsScreen(
            profile = currentProfile,
            installedApps = installedApps,
            profiles = profiles,
            profileOfApp = profileOf,
            lockedApps = lockedApps,
            homeScreens = homeScreens,
            onAdd = { packages ->
                val wereLocked = packages.filter(lockedInVault)
                val delaysBefore = wereLocked.associateWith { vault.getTimeoutMode(it).openFor }
                vault.addAppsToProfile(currentProfile.id, packages)
                // Into a profile that keeps them open longer.
                val looser = wereLocked.filter { lockedInVault(it) && vault.getTimeoutMode(it).openFor > (delaysBefore[it] ?: 0L) }
                looser.forEach { pkg ->
                    ProtectionGaps.recordIfScreenLock(
                        context, ProtectionGaps.Change.MOVED_LONGER_DELAY, labelByPackage[pkg] ?: pkg, currentProfile.name
                    )
                }
                // Into a profile that's off: those that were locked aren't any more.
                val unlocked = wereLocked.filterNot(lockedInVault)
                if (unlocked.isNotEmpty()) {
                    if (unlocked.size == 1) {
                        val label = labelByPackage[unlocked.single()] ?: unlocked.single()
                        ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.ADDED_TO_OFF_PROFILE, label, currentProfile.name)
                    } else {
                        ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.ADDED_MANY_TO_OFF_PROFILE, unlocked.size.toString(), currentProfile.name)
                    }
                }
                overlays.showAddApps = false
            },
            onDismiss = { overlays.showAddApps = false }
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
