package com.android.BioKey.feature.main

import android.content.Context
import android.content.res.Resources
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.RemoveModerator
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.BioKey.R
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectDialogWindow
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.ui.theme.GroupInset
import com.android.BioKey.ui.theme.scaledWithText
import kotlinx.coroutines.delay

/*
 * The cards at the top of BioKey's home screen (what needs the owner) and their dialogs: the
 * one-time question about failed unlock alerts, security activity (failed unlocks, times app
 * locking was off), and the locked apps unlocked right now.
 */

/**
 * A card at the top of home. Its actions sit beside the title when there's room (normal text,
 * [inline]), else on their own row under the text, so a long title or large text never squeezes
 * them. [urgent] cards take the error colours, so they never look like routine ones.
 */
@Composable
internal fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    urgent: Boolean = false,
    inline: Boolean = false,
    actions: @Composable RowScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    // Tinted towards the error colour rather than a full slab of it: urgent, without drowning the
    // page (less in the dark theme, where the error container is a deep, heavy red).
    val dark = colors.surface.luminance() < 0.5f
    val container = if (urgent) lerp(colors.surfaceContainer, colors.errorContainer, if (dark) 0.45f else 0.7f) else colors.surfaceContainer
    val content = if (urgent) colors.onErrorContainer else colors.onSurface
    val secondary = if (urgent) colors.onErrorContainer else colors.onSurfaceVariant
    val iconContainer = if (urgent) colors.error else colors.secondaryContainer
    val iconTint = if (urgent) colors.onError else colors.onSecondaryContainer
    // Beside the text only while the line under the title is short: a longer one would be squeezed
    // into a narrow column by the button and run to three lines. Decided from the text itself, not
    // from a layout pass, so the card never flips back and forth between the two arrangements.
    val sideBySide = inline && LocalDensity.current.fontScale < 1.3f && subtitle.length <= INLINE_SUBTITLE_MAX
    // The icon grows with the text beside it (up to 1.5×).
    val iconSize = CARD_ICON_SIZE.scaledWithText()
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = container,
        contentColor = content,
        modifier = modifier
            .padding(horizontal = GroupInset)
            .padding(bottom = 8.dp)
            .fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = if (sideBySide) 16.dp else 8.dp)) {
            Row(verticalAlignment = if (sideBySide) Alignment.CenterVertically else Alignment.Top) {
                Box(Modifier.size(iconSize).background(iconContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp.scaledWithText()))
                }
                Spacer(Modifier.width(CARD_ICON_GAP))
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    // Urgent ones are announced as they appear, not only when reached.
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics {
                            heading()
                            if (urgent) liveRegion = LiveRegionMode.Polite
                        }
                    )
                    if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = secondary)
                }
                if (sideBySide) {
                    CompositionLocalProvider(LocalContentColor provides content) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
                    }
                }
            }
            if (!sideBySide) {
                CompositionLocalProvider(LocalContentColor provides content) {
                    // Wrapping, so two buttons at large text go on two lines instead of squeezing.
                    FlowRow(
                        Modifier.fillMaxWidth().padding(start = iconSize + CARD_ICON_GAP, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        itemVerticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
            }
        }
    }
}

/** Asked once: failed unlock alerts keep a record, so they start only when the owner says so. */
@Composable
internal fun AttemptsIntroCard(onNotNow: () -> Unit, onTurnOn: () -> Unit, modifier: Modifier = Modifier) {
    ActionCard(
        icon = Icons.Default.GppMaybe,
        title = stringResource(R.string.settings_failed_alerts_title),
        subtitle = stringResource(R.string.card_attempts_intro_text),
        modifier = modifier
    ) {
        val notNowDescription = stringResource(R.string.card_attempts_intro_not_now_description)
        TextButton(onClick = onNotNow, modifier = Modifier.semantics { contentDescription = notNowDescription }) {
            Text(stringResource(R.string.common_not_now))
        }
        val turnOnDescription = stringResource(R.string.card_attempts_intro_turn_on_description)
        FilledTonalButton(onClick = onTurnOn, modifier = Modifier.semantics { contentDescription = turnOnDescription }) {
            Text(stringResource(R.string.common_turn_on))
        }
    }
}

/** Failed unlocks and times app locking was off, newest first in one line; Review opens the rest. */
@Composable
internal fun SecurityActivityCard(
    attempts: List<UnlockAttempts.Attempt>,
    gaps: List<ProtectionGaps.Gap>,
    labelOf: (UnlockAttempts.Attempt) -> String,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether this is the page's one urgent card ([securityActivityUrgent], unless another one is). */
    urgent: Boolean = securityActivityUrgent(attempts, gaps, UnlockAttempts.seen.collectAsStateWithLifecycle().value)
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val newestAttempt = attempts.firstOrNull()
    val newestGap = gaps.firstOrNull()
    // Newest by the order things happened in, not by clock times.
    val gapIsNewest = newestGap != null && (newestAttempt == null || newestGap.seq > newestAttempt.seq)
    ActionCard(
        // What the newest thing was, at a glance: protection off, a fingerprint changed, a change
        // from BioKey opened with the PIN, or failed unlocks.
        icon = when {
            gapIsNewest -> when (newestGap!!.kind) {
                ProtectionGaps.Kind.BIOMETRICS_CHANGED, ProtectionGaps.Kind.BIOMETRICS_REMOVED -> Icons.Default.Fingerprint
                ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK -> Icons.Default.Password
                ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED -> Icons.Default.NotificationsOff
                else -> Icons.Default.RemoveModerator
            }
            urgent -> Icons.Default.GppBad
            else -> Icons.Default.GppMaybe
        },
        title = when {
            gaps.isEmpty() -> resources.getQuantityString(R.plurals.attempts_title, attempts.size, attempts.size)
            attempts.isEmpty() && gaps.all { it.isOff } -> pluralStringResource(R.plurals.card_gaps_off_title, gaps.size, gaps.size)
            else -> stringResource(R.string.activity_title)
        },
        subtitle = if (gapIsNewest) gapLine(context, newestGap!!) else newestAttempt?.let {
            stringResource(R.string.card_attempt_line, labelOf(it), attemptTime(context, it.at), UnlockAttempts.describe(context, it))
        }.orEmpty(),
        urgent = urgent,
        inline = true,
        modifier = modifier
    ) {
        val review = stringResource(R.string.card_review)
        val reviewDescription = stringResource(R.string.card_review_description)
        // On an urgent card, the one thing to do is the card's strongest button, as on the others.
        if (urgent) {
            UrgentButton(review, onReview, Modifier.semantics { contentDescription = reviewDescription })
        } else {
            TextButton(onClick = onReview, modifier = Modifier.semantics { contentDescription = reviewDescription }) { Text(review) }
        }
    }
}

/** Security activity worth the error colours: protection off or weakened, or misses nobody followed. */
internal fun securityActivityUrgent(attempts: List<UnlockAttempts.Attempt>, gaps: List<ProtectionGaps.Gap>, seen: Long = 0L) =
    gaps.any { it.seq > seen } || attempts.any { it.unanswered && it.seq > seen }

/** The locked apps unlocked right now: one with its countdown and Lock, several with Choose and Lock all. */
@Composable
internal fun UnlockedNowCard(
    apps: List<AppItem>,
    timeoutOf: (String) -> LockTimeoutMode,
    onLock: (AppItem) -> Unit,
    onChoose: () -> Unit,
    onLockAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val first = apps.first()
    val single = apps.size == 1
    ActionCard(
        icon = Icons.Default.LockOpen,
        title = if (single) stringResource(R.string.card_app_unlocked, first.label)
            else pluralStringResource(R.plurals.card_apps_unlocked, apps.size, apps.size),
        subtitle = if (single) rememberSessionPhrase(first.packageName, timeoutOf(first.packageName)) else apps.joinToString(", ") { it.label },
        inline = single,
        modifier = modifier
    ) {
        if (single) {
            val lockDescription = stringResource(R.string.home_lock_app_description, first.label)
            FilledTonalButton(
                onClick = { onLock(first) },
                modifier = Modifier.semantics { contentDescription = lockDescription }
            ) { Text(stringResource(R.string.new_app_lock)) }
        } else {
            TextButton(onClick = onChoose) { Text(stringResource(R.string.card_see_apps)) }
            FilledTonalButton(onClick = onLockAll) { Text(stringResource(R.string.lock_all_tile_label)) }
        }
    }
}

/** "2:14 PM" today, "Yesterday, 2:14 PM", else "Oct 27, 2:14 PM". */
internal fun attemptTime(context: Context, at: Long): String {
    // "Oct 30" and "3:02 PM" each stay on one line.
    fun unbroken(text: String) = text.replace(' ', ' ')
    val time = unbroken(DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME))
    return when {
        DateUtils.isToday(at) -> time
        DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.card_time_yesterday, time)
        else -> context.getString(
            R.string.card_time_date,
            unbroken(DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)),
            time
        )
    }
}

/** Times app locking was off, as against changes that happened at one moment. */
internal val ProtectionGaps.Gap.isOff get() = kind == ProtectionGaps.Kind.SWITCHED_OFF || kind == ProtectionGaps.Kind.FORCE_STOPPED

internal val ProtectionGaps.Gap.isBiometricChange get() = kind == ProtectionGaps.Kind.BIOMETRICS_CHANGED || kind == ProtectionGaps.Kind.BIOMETRICS_REMOVED

/** "3:02 – 3:10 PM · switched off", or "3:04 PM · a face or fingerprint was added or removed". */
internal fun gapLine(context: Context, gap: ProtectionGaps.Gap): String {
    if (!gap.isOff) {
        val time = attemptTime(context, gap.to)
        return when (gap.kind) {
            ProtectionGaps.Kind.BIOMETRICS_CHANGED -> context.getString(R.string.card_gap_biometrics_changed, time)
            ProtectionGaps.Kind.BIOMETRICS_REMOVED -> context.getString(R.string.card_gap_biometrics_removed, time)
            ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED ->
                if (gap.to - gap.from < 60_000L) context.getString(R.string.card_gap_shield_access_revoked, time)
                else context.getString(R.string.card_gap_shield_access_revoked_range, timeRange(context, gap))
            else -> {
                val change = ProtectionGaps.Change.decode(gap.detail)
                // Older records hold their words as written; newer ones a code, worded here.
                val what = when {
                    change != null -> changeText(context, change.first, change.second)
                    gap.detail.isNotEmpty() -> gap.detail
                    else -> context.getString(R.string.card_gap_protection_changed)
                }
                context.getString(
                    when {
                        change?.first?.fromScreenLock == false -> R.string.card_gap_event
                        ProtectionGaps.Change.duringHold(gap.detail) -> R.string.card_gap_changed_during_hold
                        else -> R.string.card_gap_changed_with_screen_lock
                    },
                    time, what
                )
            }
        }
    }
    if (gap.unknownStart) return context.getString(R.string.card_gap_force_stopped_unknown, attemptTime(context, gap.to))
    return context.getString(if (gap.kind == ProtectionGaps.Kind.SWITCHED_OFF) R.string.card_gap_switched_off else R.string.card_gap_force_stopped, timeRange(context, gap))
}

/** "3:02 – 3:10 PM", with the date when it isn't today. */
private fun timeRange(context: Context, gap: ProtectionGaps.Gap): String {
    val day = if (DateUtils.isToday(gap.to)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
    // The dash binds both ways: a range never breaks around it.
    return DateUtils.formatDateRange(context, gap.from, gap.to, DateUtils.FORMAT_SHOW_TIME or day)
        .replace(RANGE_DASH, "\u00A0$1\u00A0")
}

/** A stored change ([ProtectionGaps.Change]) in words: its arguments are names, a count or a delay. */
private fun changeText(context: Context, change: ProtectionGaps.Change, args: List<String>): String {
    val shown: Array<Any> = when (change) {
        ProtectionGaps.Change.DEFAULT_DELAY_LONGER ->
            arrayOf(context.getString(LockTimeoutMode.fromName(args.firstOrNull()).shortLabelRes))
        ProtectionGaps.Change.ADDED_MANY_TO_OFF_PROFILE -> {
            val count = args.getOrNull(0)?.toIntOrNull() ?: 0
            return context.resources.getQuantityString(change.plural, count, count, args.getOrNull(1).orEmpty())
        }
        else -> args.toTypedArray()
    }
    return context.getString(change.text, *shown)
}

/** When an unlocked app, off screen, locks again: "Re-locks in 4 min", or how it will. */
internal fun sessionPhrase(res: Resources, mode: LockTimeoutMode, remainingMs: Long?): String = when {
    mode == LockTimeoutMode.ON_SCREEN_OFF -> res.getString(R.string.profile_relock_screen_off_explanation)
    remainingMs == null -> res.getString(
        when (mode) {
            LockTimeoutMode.IMMEDIATE -> R.string.card_session_immediately
            LockTimeoutMode.ON_SCREEN_OFF -> R.string.profile_relock_screen_off_explanation
            LockTimeoutMode.GRACE_30_SECONDS -> R.string.card_session_30_seconds
            LockTimeoutMode.GRACE_1_MINUTE -> R.string.card_session_1_minute
            LockTimeoutMode.GRACE_5_MINUTES -> R.string.card_session_5_minutes
            LockTimeoutMode.GRACE_15_MINUTES -> R.string.card_session_15_minutes
        }
    )
    remainingMs < 60_000L -> res.getString(R.string.card_session_less_than_minute)
    else -> ((remainingMs + 59_999L) / 60_000L).toInt().let { res.getQuantityString(R.plurals.card_session_minutes, it, it) }
}

/**
 * [sessionPhrase], kept current while BioKey is in front: it changes on the minute, so it's read
 * again exactly then, and not at all while BioKey is out of sight.
 */
@Composable
internal fun rememberSessionPhrase(pkg: String, mode: LockTimeoutMode): String {
    val res = LocalResources.current
    var phrase by remember(pkg, mode, res) { mutableStateOf(sessionPhrase(res, mode, null)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(pkg, mode, lifecycle, res) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val remaining = LockStateTracker.awayRemainingMs(pkg, mode)
                phrase = sessionPhrase(res, mode, remaining)
                val toNextMinute = remaining?.let { it % 60_000L }?.takeIf { it > 0L } ?: 60_000L
                delay(toNextMinute + 50L)
            }
        }
    }
    return phrase
}

/** The unlocked apps, each with its countdown and its own Lock, and Lock all. */
@Composable
internal fun UnlockedAppsDialog(
    apps: List<AppItem>,
    timeoutOf: (String) -> LockTimeoutMode,
    onLock: (AppItem) -> Unit,
    onLockAll: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.LockOpen, contentDescription = null) },
        title = { Text(stringResource(R.string.home_unlocked_now)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProtectDialogWindow()
                apps.forEach { app ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(app)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                rememberSessionPhrase(app.packageName, timeoutOf(app.packageName)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val lockDescription = stringResource(R.string.home_lock_app_description, app.label)
                        TextButton(
                            onClick = { onLock(app) },
                            modifier = Modifier.semantics { contentDescription = lockDescription }
                        ) { Text(stringResource(R.string.new_app_lock)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onLockAll) { Text(stringResource(R.string.lock_all_tile_label)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } }
    )
}

/** Every failed unlock and time app locking was off, newest first, and the way to clear them. */
@Composable
internal fun SecurityActivityDialog(
    attempts: List<UnlockAttempts.Attempt>,
    gaps: List<ProtectionGaps.Gap>,
    labelOf: (UnlockAttempts.Attempt) -> String,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    /** BioKey was opened with the screen lock: reviewing takes a face or fingerprint, here. */
    onMarkReviewed: (() -> Unit)? = null,
    /** A face or fingerprint change stays new until then ([UnlockAttempts.heldUntil]), whatever Review does. */
    heldUntil: Long? = null,
    /** Opened with the screen lock (no hold): reviewing takes a face or fingerprint. */
    openedWithScreenLock: Boolean = onMarkReviewed != null && heldUntil == null
) {
    val context = LocalContext.current
    val note = MaterialTheme.colorScheme.onSurfaceVariant
    val anyNew = securityActivityUrgent(attempts, gaps, UnlockAttempts.seen.collectAsStateWithLifecycle().value)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (anyNew) Icons.Default.GppBad else Icons.Default.GppMaybe, contentDescription = null,
                tint = if (anyNew) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
            )
        },
        title = { Text(stringResource(R.string.activity_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProtectDialogWindow()
                // At most one note before the records: why they stay new.
                val heading = when {
                    // The hold explains everything at once: why they stay new, why nothing clears.
                    heldUntil != null -> stringResource(R.string.activity_held_until)
                    openedWithScreenLock -> stringResource(R.string.card_activity_screen_lock_note)
                    else -> null
                }
                if (heading != null) {
                    ActivityCallout(
                        heading,
                        headline = heldUntil?.let { stringResource(R.string.activity_held_headline, attemptTime(context, it)) },
                        held = heldUntil != null,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
                val changes = gaps.filterNot { it.isOff }
                if (changes.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.card_activity_changes))
                    RecordList {
                        changes.forEach { change ->
                            Column(Modifier.semantics(mergeDescendants = true) { }) {
                                Text(gapLine(context, change), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(
                                        when (change.kind) {
                                            ProtectionGaps.Kind.BIOMETRICS_CHANGED -> R.string.card_change_biometrics_changed_hint
                                            ProtectionGaps.Kind.BIOMETRICS_REMOVED -> R.string.card_change_biometrics_removed_hint
                                            ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED -> R.string.card_change_shield_access_hint
                                            else -> if (ProtectionGaps.Change.duringHold(change.detail)) R.string.card_change_during_hold_hint
                                            else R.string.card_change_screen_lock_hint
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = note
                                )
                                if (change.isBiometricChange) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(stringResource(R.string.card_change_biometrics_note), style = MaterialTheme.typography.bodySmall, color = note)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                val offs = gaps.filter { it.isOff }
                if (offs.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.gaps_title))
                    RecordList {
                        offs.forEach { gap ->
                            Column(Modifier.semantics(mergeDescendants = true) { }) {
                                Text(gapLine(context, gap), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.card_gap_hint), style = MaterialTheme.typography.bodyMedium, color = note)
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.card_gaps_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = note
                    )
                    Spacer(Modifier.height(16.dp))
                }
                if (attempts.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.card_failed_unlocks))
                    RecordList {
                        attempts.forEach { attempt ->
                            Column(Modifier.semantics(mergeDescendants = true) { }) {
                                Text(labelOf(attempt), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.card_attempt_detail, attemptTime(context, attempt.at), UnlockAttempts.describe(context, attempt)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = note
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.card_attempts_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = note
                    )
                }
                if (gaps.any { it.isBiometricChange || it.kind == ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK }) {
                    // What to do about them: look over the faces, fingerprints and screen lock.
                    FilledTonalButton(
                        onClick = { IntentNavigationHelper.openSecuritySettings(context) },
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp.scaledWithText()))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.activity_open_security_settings))
                    }
                }
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                // What this visit is for, when it's opened with the screen lock.
                if (onMarkReviewed != null && heldUntil == null) FilledTonalButton(onClick = onMarkReviewed) { Text(stringResource(R.string.card_mark_reviewed)) }
            }
        },
        // Destructive: in the error colour, and behind a face or fingerprint (and Undo). Not offered
        // when only a face or fingerprint change is left (that can't be cleared), nor while one is
        // held: whoever made it could otherwise clear what happened around it.
        dismissButton = if (heldUntil == null && (attempts.isNotEmpty() || gaps.any { !it.isBiometricChange })) {
            {
                TextButton(onClick = onClear, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.card_clear))
                }
            }
        } else null
    )
}

/** The one thing to do on an urgent card: a filled button in the error colours. */
@Composable
internal fun UrgentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
    ) { Text(text, textAlign = TextAlign.Center) }
}


/**
 * Review's note above the records, as a tonal callout: tinted towards the error colours while a
 * face or fingerprint change is [held] (as an urgent card is), else the calm secondary ones.
 */
@Composable
private fun ActivityCallout(text: String, held: Boolean, modifier: Modifier = Modifier, headline: String? = null) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (held) lerp(colors.surfaceContainerHighest, colors.errorContainer, if (dark) 0.45f else 0.7f) else colors.secondaryContainer,
        contentColor = if (held) colors.onErrorContainer else colors.onSecondaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Icon(Icons.Default.GppMaybe, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(20.dp.scaledWithText()))
            Spacer(Modifier.width(12.dp))
            Column {
                if (headline != null) Text(headline, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Records under a section label: 8dp apart, 4dp in from the label above and what follows. */
@Composable
private fun RecordList(content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() }
    )
}

/** An action card's icon, and the gap after it: wrapped actions line up with the text after both. */
private val CARD_ICON_SIZE = 40.dp
private val CARD_ICON_GAP = 16.dp

/**
 * The longest line under a card's title that keeps an action beside it: past this, on a phone, the
 * text column the button leaves wraps it into a tall, narrow block ("Re-locks in 5 min" stays
 * beside; "Venmo · Oct 29, 2:14 PM · 3 tries didn't match" puts the button under the text).
 */
private const val INLINE_SUBTITLE_MAX = 40

/** A range's dash with the spaces round it, thin ones included (ICU uses U+2009). */
private val RANGE_DASH = Regex("[\\s\u2009\u202F]([\u2013-])[\\s\u2009\u202F]")
