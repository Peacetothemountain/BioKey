package com.android.BioKey.feature.main

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoEncryption
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.BioKey.R
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.core.security.UnlockMethods
import com.android.BioKey.ui.disclosure.Disclosure
import com.android.BioKey.ui.disclosure.DisclosureGate
import com.android.BioKey.ui.disclosure.NotificationPermission
import com.android.BioKey.ui.navigation.IntentNavigationHelper
import com.android.BioKey.ui.theme.BioKeyMessages
import com.android.BioKey.ui.theme.GroupInset
import com.android.BioKey.ui.theme.GroupRow

/**
 * The cards between home's header and its search bar, most urgent first: those that need the
 * owner (alerts off, nothing protecting, no screen lock, an alert channel muted, what happened
 * while the owner was away, apps unlocked now), all of them, the one urgent card leading
 * ([homeCardOrder]), then at most one calm [offer] ([HomeOffer]). Only one is urgent at a time.
 */
internal fun LazyListScope.homeCards(
    mode: ProtectionEngine.Mode,
    look: ProtectionLook,
    vault: SecurityVault,
    globalLockEnabled: Boolean,
    notifications: NotificationPermission,
    disclosures: DisclosureGate,
    homeViewModel: HomeViewModel,
    home: HomeState,
    attempts: List<UnlockAttempts.Attempt>,
    gaps: List<ProtectionGaps.Gap>,
    seenUpTo: Long,
    unlockedNowApps: List<AppItem>,
    attemptLabel: (UnlockAttempts.Attempt) -> String,
    onProtectionAction: () -> Unit,
    onReview: () -> Unit,
    onLockOne: (AppItem) -> Unit,
    onChooseUnlocked: () -> Unit,
    onLockAll: () -> Unit,
    /** The phone's screen lock is gone, so locked apps open without a check ([ScreenLockWatch]). */
    screenLockMissing: Boolean = false,
    /** The one calm offer to show, if any ([chooseOffer]). */
    offer: HomeOffer? = null
) {
    // Which card, if any, is the page's one urgent card ([urgentCard]).
    val notProtecting = look.action != null && vault.isSetupCompleted()
    val urgent = urgentCard(
        notProtecting = notProtecting,
        paused = ProtectionEngine.isProtecting(mode) && !globalLockEnabled,
        activityUrgent = securityActivityUrgent(attempts, gaps, seenUpTo),
        alertsMuted = home.alertsMuted && notifications.allowed,
        noScreenLock = screenLockMissing && vault.isSetupCompleted()
    )

    val showing = buildSet {
        if (!notifications.allowed) add(HomeCard.ALERTS_OFF)
        if (notProtecting) add(HomeCard.NOT_PROTECTING)
        if (screenLockMissing && vault.isSetupCompleted()) add(HomeCard.NO_SCREEN_LOCK)
        if (home.alertsMuted && notifications.allowed) add(HomeCard.ALERTS_MUTED)
        if (attempts.isNotEmpty() || gaps.isNotEmpty()) add(HomeCard.ACTIVITY)
        if (unlockedNowApps.isNotEmpty()) add(HomeCard.UNLOCKED_NOW)
    }
    // The page's one urgent card first, then the others that need the owner, then the calm offer.
    for (card in homeCardOrder(showing, urgent)) when (card) {
    HomeCard.ALERTS_OFF -> {
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
    HomeCard.NOT_PROTECTING -> {
        // Shown only with an action ([notProtecting]).
        val action = look.action.orEmpty()
        item(key = "not_protecting") {
            val activity = LocalActivity.current
            val res = LocalResources.current
            // A pause the owner chose isn't an emergency; off, not running or set up is.
            val paused = urgent != UrgentCard.NOT_PROTECTING
            val quiet = mode == ProtectionEngine.Mode.OFF && home.remindersStopped
            ActionCard(
                icon = look.icon,
                title = look.title,
                subtitle = if (quiet) stringResource(R.string.home_reminders_off_body) else look.body,
                urgent = urgent == UrgentCard.NOT_PROTECTING,
                modifier = Modifier.animateItem()
            ) {
                if (paused) {
                    FilledTonalButton(onClick = onProtectionAction) { Text(action) }
                } else {
                    // Switched off in Accessibility (maybe on purpose): the owner can stop the
                    // reminders until it's back on. Not offered for a crash or a missing permission.
                    val cardText = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                    if (quiet) {
                        // Only strengthens: no authentication.
                        TextButton(
                            onClick = { homeViewModel.resumeReminders() },
                            colors = cardText
                        ) { Text(stringResource(R.string.home_remind_me)) }
                    } else if (mode == ProtectionEngine.Mode.OFF) {
                        TextButton(
                            onClick = {
                                confirmOwner(activity, res.getString(R.string.home_confirm_stop_reminding)) {
                                    homeViewModel.stopReminders()
                                    BioKeyMessages.show(res.getString(R.string.home_reminders_stopped), Icons.Default.NotificationsOff)
                                }
                            },
                            colors = cardText
                        ) { Text(stringResource(R.string.home_stop_reminding)) }
                    }
                    UrgentButton(action, onProtectionAction)
                }
            }
        }
    }

    // No screen lock: nothing can check who opens a locked app, so they open without one (and
    // that's being recorded). Setting one again is the fix, and BioKey can only point to it.
    HomeCard.NO_SCREEN_LOCK -> {
        item(key = "no_screen_lock") {
            val context = LocalContext.current
            val urgentHere = urgent == UrgentCard.NO_SCREEN_LOCK
            ActionCard(
                icon = Icons.Default.NoEncryption,
                title = stringResource(R.string.home_no_screen_lock_title),
                subtitle = stringResource(R.string.home_no_screen_lock_text),
                urgent = urgentHere,
                modifier = Modifier.animateItem()
            ) {
                val add: () -> Unit = { IntentNavigationHelper.openScreenLockSetup(context) }
                if (urgentHere) UrgentButton(stringResource(R.string.home_no_screen_lock_action), onClick = add)
                else FilledTonalButton(onClick = add) { Text(stringResource(R.string.home_no_screen_lock_action)) }
            }
        }
    }

    // One of BioKey's alert channels silenced in Android's settings (all of its notifications
    // off is the row above): say so. Only one card on the page is urgent at a time.
    HomeCard.ALERTS_MUTED -> {
        val alertsUrgent = urgent == UrgentCard.ALERTS_MUTED
        item(key = "alerts_muted") {
            val context = LocalContext.current
            ActionCard(
                icon = Icons.Default.NotificationsOff,
                title = stringResource(R.string.home_alerts_muted_title),
                subtitle = stringResource(R.string.home_alerts_muted_text),
                urgent = alertsUrgent,
                modifier = Modifier.animateItem()
            ) {
                val open: () -> Unit = { IntentNavigationHelper.openChannelSettings(context, UnlockAttempts.mutedChannel(context)) }
                if (alertsUrgent) UrgentButton(stringResource(R.string.common_turn_on), onClick = open)
                else FilledTonalButton(onClick = open) { Text(stringResource(R.string.common_turn_on)) }
            }
        }
    }

    HomeCard.ACTIVITY -> {
        item(key = "activity") {
            // One urgent card on the page: not protecting outranks this.
            SecurityActivityCard(
                attempts, gaps, attemptLabel, onReview = onReview, modifier = Modifier.animateItem(),
                urgent = urgent == UrgentCard.ACTIVITY
            )
        }
    }

    HomeCard.UNLOCKED_NOW -> {
        item(key = "unlocked_now") {
            UnlockedNowCard(
                apps = unlockedNowApps,
                timeoutOf = vault::getTimeoutMode,
                onLock = onLockOne,
                onChoose = onChooseUnlocked,
                onLockAll = onLockAll,
                modifier = Modifier.animateItem()
            )
        }
    }

    }

    // After the cards that need the owner, at most one calm offer ([chooseOffer]), so the apps
    // are never pushed far down the page. The next comes once this one is answered or put away.
    // The apps auto-sort locked, right after setup, as setup said it would. It stays (home left to
    // try a locked app, say) until "Got it", and then doesn't come back.
    if (offer == HomeOffer.AUTO_SORT) {
        item(key = "auto_sort_note") {
            val count = home.autoSortLocked
            val setting = stringResource(R.string.settings_auto_sort_title)
            // News, put away with "Got it": a text button, as the other calm cards' way of putting
            // them away. Beside the text where the card is wide enough for both on a line each (a
            // wide screen, the 600-dp column), so it isn't left alone on a row of its own.
            BoxWithConstraints(Modifier.animateItem()) {
                ActionCard(
                    // Auto-sort's own icon, as on its row in Settings.
                    icon = Icons.Default.AutoAwesome,
                    title = pluralStringResource(R.plurals.home_auto_sort_title, count, count),
                    subtitle = pluralStringResource(R.plurals.home_auto_sort_text, count, count, setting),
                    roomBeside = maxWidth >= AutoSortBesideWidth
                ) {
                    TextButton(onClick = { homeViewModel.dismissAutoSortNote() }) { Text(stringResource(R.string.home_auto_sort_got_it)) }
                }
            }
        }
    }

    // The offer of failed unlock alerts: asked once, and only where it can help (a strong face or
    // fingerprint is set up).
    if (offer == HomeOffer.FAILED_ALERTS) {
        item(key = "attempts_intro") {
            val context = LocalContext.current
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

    // No face or fingerprint set up where the phone has the
    // sensor: locked apps take the PIN every time, and Face or fingerprint only can't be used.
    // Put away, it comes back if the screen lock is removed (every face and fingerprint goes with it).
    if (offer == HomeOffer.ADD_BIOMETRIC) {
        item(key = "add_biometric") {
            val context = LocalContext.current
            ActionCard(
                icon = Icons.Default.Fingerprint,
                title = stringResource(UnlockMethods.addBiometricTitle(context)),
                subtitle = stringResource(R.string.card_add_biometric_text),
                modifier = Modifier.animateItem()
            ) {
                TextButton(onClick = { homeViewModel.dismissAddBiometric() }) { Text(stringResource(R.string.card_add_biometric_no_thanks)) }
                FilledTonalButton(onClick = { IntentNavigationHelper.openBiometricEnroll(context) }) {
                    Text(stringResource(R.string.card_add_biometric_action))
                }
            }
        }
    }

    // With your PIN, someone could add their own fingerprint in Settings: lock it.
    if (offer == HomeOffer.LOCK_SETTINGS) {
        item(key = "lock_settings") {
            val res = LocalResources.current
            ActionCard(
                icon = Icons.Default.Lock,
                title = stringResource(R.string.settings_lock_settings_title),
                subtitle = stringResource(R.string.settings_lock_settings_summary),
                modifier = Modifier.animateItem()
            ) {
                TextButton(onClick = { homeViewModel.dismissLockSettings() }) { Text(stringResource(R.string.common_not_now)) }
                // "Lock it" says what in full to a screen reader, wherever focus lands first.
                val lockDescription = stringResource(R.string.settings_lock_settings_title)
                FilledTonalButton(
                    onClick = {
                        val undo = vault.lockSettingsApp()
                        BioKeyMessages.show(res.getString(R.string.settings_lock_settings_done), Icons.Default.Lock, actionLabel = res.getString(R.string.common_undo), onAction = undo)
                    },
                    modifier = Modifier.semantics { contentDescription = lockDescription }
                ) { Text(stringResource(R.string.settings_lock_action)) }
            }
        }
    }
}

/** The width from which auto-sort's note has "Got it" beside its text, each line still on one line at normal text. */
private val AutoSortBesideWidth = 520.dp

/**
 * Home's calm offers, in the order they're made: first what auto-sort just did (news, once, right
 * after setup); then a face or fingerprint (what the other two need); then locking Android
 * Settings (closes a way round "Face or fingerprint only"); then failed unlock alerts (a record,
 * nice to have). One shows at a time ([chooseOffer]).
 */
internal enum class HomeOffer { AUTO_SORT, ADD_BIOMETRIC, LOCK_SETTINGS, FAILED_ALERTS }

/**
 * The offers that could be made now. [setupCompleted]: home after setup. [lockSettingsWanted]:
 * "Face or fingerprint only" is on, protection is on, and Android Settings isn't locked.
 */
internal fun eligibleOffers(
    home: HomeState,
    setupCompleted: Boolean,
    attemptsChosen: Boolean,
    screenLockMissing: Boolean,
    lockSettingsWanted: Boolean
): Set<HomeOffer> = buildSet {
    if (home.autoSortLocked > 0) add(HomeOffer.AUTO_SORT)
    // Put away, it comes back if the screen lock is removed (every face and fingerprint goes with it).
    if (home.canAddBiometric && home.addBiometricDismissed == false && !screenLockMissing && setupCompleted) add(HomeOffer.ADD_BIOMETRIC)
    if (lockSettingsWanted && home.hasStrongBiometric && home.lockSettingsDismissed == false) add(HomeOffer.LOCK_SETTINGS)
    // Asked once, and only where it can help (a strong face or fingerprint is set up).
    if (!attemptsChosen && setupCompleted && home.hasStrongBiometric) add(HomeOffer.FAILED_ALERTS)
}

/**
 * The one calm offer shown: the one [showing] already, until it's answered or put away, so an
 * offer never swaps for another under the owner's eyes; else the first [eligible] in
 * [HomeOffer]'s order. Auto-sort's note is the exception: news of what just happened, it goes
 * first while it lasts (it lasts one visit).
 */
internal fun chooseOffer(eligible: Set<HomeOffer>, showing: HomeOffer?): HomeOffer? = when {
    HomeOffer.AUTO_SORT in eligible -> HomeOffer.AUTO_SORT
    showing != null && showing in eligible -> showing
    else -> HomeOffer.entries.firstOrNull { it in eligible }
}

/** Home's cards that need the owner, in the order they show when none of them is urgent. */
internal enum class HomeCard { ALERTS_OFF, NOT_PROTECTING, NO_SCREEN_LOCK, ALERTS_MUTED, ACTIVITY, UNLOCKED_NOW }

/**
 * The order of the cards that need the owner ([showing]), all before the calm offer: the page's
 * one urgent card ([urgent], drawn in red) first, so nothing sits above it, then the rest in
 * [HomeCard]'s order.
 */
internal fun homeCardOrder(showing: Set<HomeCard>, urgent: UrgentCard): List<HomeCard> {
    val lead = when (urgent) {
        UrgentCard.NOT_PROTECTING -> HomeCard.NOT_PROTECTING
        UrgentCard.NO_SCREEN_LOCK -> HomeCard.NO_SCREEN_LOCK
        UrgentCard.ACTIVITY -> HomeCard.ACTIVITY
        UrgentCard.ALERTS_MUTED -> HomeCard.ALERTS_MUTED
        UrgentCard.NONE -> null
    }?.takeIf { it in showing }
    return listOfNotNull(lead) + HomeCard.entries.filter { it in showing && it != lead }
}

/** Home's cards that can be urgent, most serious first. */
internal enum class UrgentCard { NOT_PROTECTING, NO_SCREEN_LOCK, ACTIVITY, ALERTS_MUTED, NONE }

/**
 * The one card on home shown as urgent: the most serious of those showing. Not protecting comes
 * first, unless it's a pause the owner chose (never an emergency, and then the next one is);
 * then no screen lock (locked apps open without a check), security activity not yet reviewed,
 * and BioKey's alerts silenced.
 */
internal fun urgentCard(
    notProtecting: Boolean,
    paused: Boolean,
    activityUrgent: Boolean,
    alertsMuted: Boolean,
    noScreenLock: Boolean = false
): UrgentCard = when {
    notProtecting && !paused -> UrgentCard.NOT_PROTECTING
    noScreenLock -> UrgentCard.NO_SCREEN_LOCK
    activityUrgent -> UrgentCard.ACTIVITY
    alertsMuted -> UrgentCard.ALERTS_MUTED
    else -> UrgentCard.NONE
}
