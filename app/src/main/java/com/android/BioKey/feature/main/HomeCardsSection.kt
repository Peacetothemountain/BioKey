package com.android.BioKey.feature.main

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.BioKey.R
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.ui.theme.BioKeyMessages
import com.android.BioKey.ui.theme.GroupInset
import com.android.BioKey.ui.theme.GroupRow

/**
 * The cards between home's header and its search bar, most urgent first: alerts off, nothing
 * protecting, hiding without access, an alert channel muted, the one-time attempts question, what
 * happened while the owner was away, apps unlocked now, and the calm Lock Settings suggestion.
 * Only one is urgent at a time.
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
    attemptsChosen: Boolean,
    biometricOnlyOn: Boolean,
    lockedApps: Set<String>,
    unlockedNowApps: List<AppItem>,
    attemptLabel: (UnlockAttempts.Attempt) -> String,
    onProtectionAction: () -> Unit,
    onReview: () -> Unit,
    onLockOne: (AppItem) -> Unit,
    onChooseUnlocked: () -> Unit,
    onLockAll: () -> Unit
) {
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

    // Which card, if any, is the page's one urgent card ([urgentCard]).
    val notProtecting = look.action != null && vault.isSetupCompleted()
    val urgent = urgentCard(
        notProtecting = notProtecting,
        paused = ProtectionEngine.isProtecting(mode) && !globalLockEnabled,
        activityUrgent = securityActivityUrgent(attempts, gaps, seenUpTo),
        notificationsShowing = home.shieldWithoutAccess && vault.isSetupCompleted(),
        alertsMuted = home.alertsMuted && notifications.allowed
    )

    // Nothing locking right now: the most serious state gets the clearest card, with its one action.
    if (notProtecting) {
        item(key = "not_protecting") {
            val activity = LocalActivity.current
            val res = LocalResources.current
            // A pause the owner chose isn't an emergency; off, not running or set up is.
            val paused = urgent != UrgentCard.NOT_PROTECTING
            val quiet = mode == ProtectionEngine.Mode.OFF && home.remindersStopped
            ActionCard(
                icon = look.icon,
                title = look.title,
                subtitle = if (quiet) stringResource(R.string.home_reminders_off_body, look.body) else look.body,
                urgent = urgent == UrgentCard.NOT_PROTECTING,
                modifier = Modifier.animateItem()
            ) {
                if (paused) {
                    FilledTonalButton(onClick = onProtectionAction) { Text(look.action) }
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
                    UrgentButton(look.action, onProtectionAction)
                }
            }
        }
    }

    // The shield is on but its access was taken away: notifications show in full right now.
    // Switching the shield off stays offered beside it: the owner may have meant it.
    val shieldUrgent = urgent == UrgentCard.NOTIFICATIONS_SHOWING
    if (home.shieldWithoutAccess && vault.isSetupCompleted()) {
        item(key = "shield_no_access") {
            val context = LocalContext.current
            ActionCard(
                icon = Icons.Default.NotificationsOff,
                title = stringResource(R.string.home_shield_no_access_title),
                subtitle = stringResource(R.string.home_shield_no_access_text),
                urgent = shieldUrgent,
                modifier = Modifier.animateItem()
            ) {
                // Revoked on purpose: switching the shield off ends it (and is recorded like any switch-off).
                TextButton(onClick = {
                    // As the switch in Settings does: recorded from a screen-lock session, with Undo.
                    homeViewModel.switchShieldOff(vault)
                }) { Text(stringResource(R.string.home_shield_turn_off_hiding)) }
                // The explanation first, as everywhere notification access is asked for.
                val allow: () -> Unit = {
                    disclosures.request(Disclosure.NOTIFICATIONS) { IntentNavigationHelper.openNotificationListenerSettings(context) }
                }
                if (shieldUrgent) UrgentButton(stringResource(R.string.home_shield_no_access_action), onClick = allow)
                else FilledTonalButton(onClick = allow) { Text(stringResource(R.string.home_shield_no_access_action)) }
            }
        }
    }

    // One of BioKey's alert channels silenced in Android's settings (all of its notifications
    // off is the row above): say so. Only one card on the page is urgent at a time.
    val alertsUrgent = urgent == UrgentCard.ALERTS_MUTED
    if (home.alertsMuted && notifications.allowed) {
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

    // Asked once, and only where it can help: a strong face or fingerprint is set up.
    if (!attemptsChosen && vault.isSetupCompleted() && home.hasStrongBiometric) {
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

    if (attempts.isNotEmpty() || gaps.isNotEmpty()) {
        item(key = "activity") {
            // One urgent card on the page: not protecting outranks this.
            SecurityActivityCard(
                attempts, gaps, attemptLabel, onReview = onReview, modifier = Modifier.animateItem(),
                urgent = urgent == UrgentCard.ACTIVITY
            )
        }
    }

    if (unlockedNowApps.isNotEmpty()) {
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

    // Calm cards after the urgent ones. With your PIN, someone could add their own fingerprint
    // in Settings: lock it.
    if (biometricOnlyOn && home.hasStrongBiometric && look.action == null && SETTINGS_PACKAGE !in lockedApps && home.lockSettingsDismissed == false) {
        item(key = "lock_settings") {
            val res = LocalResources.current
            ActionCard(
                icon = Icons.Default.Lock,
                title = stringResource(R.string.settings_lock_settings_title),
                subtitle = stringResource(R.string.home_lock_settings_text),
                modifier = Modifier.animateItem()
            ) {
                TextButton(onClick = { homeViewModel.dismissLockSettings() }) { Text(stringResource(R.string.common_not_now)) }
                FilledTonalButton(onClick = {
                    val undo = vault.lockSettingsApp()
                    BioKeyMessages.show(res.getString(R.string.settings_lock_settings_done), Icons.Default.Lock, actionLabel = res.getString(R.string.common_undo), onAction = undo)
                }) { Text(stringResource(R.string.settings_lock_action)) }
            }
        }
    }
}

/** Home's cards that can be urgent, most serious first. */
internal enum class UrgentCard { NOT_PROTECTING, ACTIVITY, NOTIFICATIONS_SHOWING, ALERTS_MUTED, NONE }

/**
 * The one card on home shown as urgent: the most serious of those showing. Not protecting comes
 * first, unless it's a pause the owner chose (never an emergency, and then the next one is);
 * then security activity not yet reviewed, notifications showing in full under the shield, and
 * BioKey's alerts silenced.
 */
internal fun urgentCard(
    notProtecting: Boolean,
    paused: Boolean,
    activityUrgent: Boolean,
    notificationsShowing: Boolean,
    alertsMuted: Boolean
): UrgentCard = when {
    notProtecting && !paused -> UrgentCard.NOT_PROTECTING
    activityUrgent -> UrgentCard.ACTIVITY
    notificationsShowing -> UrgentCard.NOTIFICATIONS_SHOWING
    alertsMuted -> UrgentCard.ALERTS_MUTED
    else -> UrgentCard.NONE
}
