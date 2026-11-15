package com.android.BioKey.feature.main

import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.BioKey.R
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.ui.format.afterSeparator
import com.android.BioKey.ui.format.attemptTime
import com.android.BioKey.ui.format.recordRange
import com.android.BioKey.ui.format.startsWithArgument
import com.android.BioKey.ui.navigation.IntentNavigationHelper
import com.android.BioKey.ui.security.ProtectDialogWindow
import com.android.BioKey.ui.theme.Callout
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
 * them. [urgent] cards take the error colours, so they never look like routine ones. A [compact]
 * card (calm news under an urgent one) is a row's height: tighter, a smaller icon, and its one
 * short action beside the text whatever the text's length, until large text needs the room.
 * [roomBeside]: the caller knows the card is wide enough for its action beside the text (a wide
 * screen), whatever the text's length; still not at large text. The title and the line under it
 * use the full width, their lines made even only where one word would be left alone on the last.
 */
@Composable
internal fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    urgent: Boolean = false,
    inline: Boolean = false,
    compact: Boolean = false,
    roomBeside: Boolean = false,
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
    val fontScale = LocalDensity.current.fontScale
    val sideBySide = (roomBeside && fontScale < LARGE_TEXT) ||
        inline && (if (compact) fontScale < LARGE_TEXT else actionsFitBeside(title, subtitle, fontScale))
    // The icon grows with the text beside it (up to 1.5×).
    val iconSize = (if (compact) CARD_ICON_SIZE_COMPACT else CARD_ICON_SIZE).scaledWithText()
    val vertical = if (compact) 12.dp else 16.dp
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = container,
        contentColor = content,
        modifier = modifier
            .padding(horizontal = GroupInset)
            .padding(bottom = 8.dp)
            .fillMaxWidth()
    ) {
        // Under the text, a button's own touch margin (4dp) already spaces it: the padding allows for it.
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = vertical, bottom = if (sideBySide) vertical else 8.dp)) {
            Row(verticalAlignment = if (sideBySide) Alignment.CenterVertically else Alignment.Top) {
                Box(Modifier.size(iconSize).background(iconContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size((iconSize * 0.55f)))
                }
                Spacer(Modifier.width(CARD_ICON_GAP))
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    // Urgent ones are announced as they appear, not only when reached.
                    NoLoneWordText(
                        title,
                        style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics {
                            heading()
                            if (urgent) liveRegion = LiveRegionMode.Polite
                        }
                    )
                    if (subtitle.isNotEmpty()) {
                        NoLoneWordText(subtitle, style = MaterialTheme.typography.bodyMedium, color = secondary)
                    }
                }
                if (sideBySide) {
                    CompositionLocalProvider(LocalContentColor provides content) {
                        MaterialTheme(colorScheme = if (urgent) colors.copy(primary = colors.onErrorContainer) else colors) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
                        }
                    }
                }
            }
            if (!sideBySide) {
                CompositionLocalProvider(LocalContentColor provides content) {
                    // On an urgent card, a text button is in the card's own colour.
                    MaterialTheme(colorScheme = if (urgent) colors.copy(primary = colors.onErrorContainer) else colors) {
                    // Wrapping, so two buttons at large text go on two lines instead of squeezing.
                    FlowRow(
                        Modifier.fillMaxWidth().padding(start = iconSize + CARD_ICON_GAP, top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        itemVerticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                    }
                }
            }
        }
    }
}

/** Asked once: failed unlock alerts keep a record, so they start only when the owner says so. */
@Composable
internal fun AttemptsIntroCard(onNotNow: () -> Unit, onTurnOn: () -> Unit, modifier: Modifier = Modifier) {
    ActionCard(
        icon = FailedAlertsIcon,
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
        // Security activity's one icon, whatever the newest record is: the line under the title
        // says what it was, and the card's colour whether it's urgent.
        icon = SecurityActivityIcon,
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
        // Not urgent: news to look at when convenient, so it takes a row's room, not a card's,
        // and the urgent card above it (if any) leads.
        compact = !urgent,
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

/** Lines of even length: used only where the full width would leave one word alone on the last. */
private val EvenLines = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default)

/**
 * Text that uses the card's full width, as its neighbours do, unless that leaves a single word
 * alone on its last line: then (and only then) its lines are made even.
 */
@Composable
private fun NoLoneWordText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified
) {
    // Per width: a wider card (a turn, a fold) may not leave the word alone.
    BoxWithConstraints {
        var even by remember(text, constraints.maxWidth) { mutableStateOf(false) }
        Text(
            text,
            style = if (even) style.copy(lineBreak = EvenLines) else style,
            color = color,
            modifier = modifier,
            onTextLayout = { layout -> if (!even && endsWithLoneWord(text, layout)) even = true }
        )
    }
}

/** Whether [layout] of [text] runs to more than one line, the last of them a single word. */
internal fun endsWithLoneWord(text: String, layout: TextLayoutResult): Boolean {
    if (layout.lineCount < 2) return false
    val last = layout.lineCount - 1
    return text.substring(layout.getLineStart(last), layout.getLineEnd(last)).trim().none { it == ' ' }
}

/** Security activity's icon, on its card and its dialog: a record of what happened. */
internal val SecurityActivityIcon get() = Icons.Default.History

/** Failed unlock alerts' icon, on home's offer and in Settings: a check that failed. */
internal val FailedAlertsIcon get() = Icons.Default.GppBad

/** Security activity worth the error colours: protection off or weakened, or misses nobody followed. */
internal fun securityActivityUrgent(attempts: List<UnlockAttempts.Attempt>, gaps: List<ProtectionGaps.Gap>, seen: Long = 0L) =
    gaps.any { it.seq > seen } || attempts.any { it.unanswered && it.seq > seen }

/** The locked apps unlocked right now: one with its countdown and Lock, several with See apps and Re-lock now. */
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

/** Times app locking was off (or opened apps without a check), as against changes that happened at one moment. */
internal val ProtectionGaps.Gap.isOff get() =
    kind == ProtectionGaps.Kind.SWITCHED_OFF || kind == ProtectionGaps.Kind.FORCE_STOPPED || kind == ProtectionGaps.Kind.DATA_CLEARED ||
        kind == ProtectionGaps.Kind.NO_SCREEN_LOCK

internal val ProtectionGaps.Gap.isBiometricChange get() = kind == ProtectionGaps.Kind.BIOMETRICS_CHANGED || kind == ProtectionGaps.Kind.BIOMETRICS_REMOVED

/**
 * "3:02 – 3:10 PM · switched off", or "3:04 PM · a face or fingerprint was added or removed".
 * [time] stands in for a moment's time: a run of the same record says when it began and ended.
 * [grouped]: shown under its group's heading ([ChangeGroup]), which says once what the line
 * would otherwise add in brackets.
 */
internal fun gapLine(context: Context, gap: ProtectionGaps.Gap, time: String? = null, grouped: Boolean = false): String {
    if (!gap.isOff) {
        val time = time ?: attemptTime(context, gap.to)
        return when (gap.kind) {
            ProtectionGaps.Kind.BIOMETRICS_CHANGED -> context.getString(R.string.card_gap_biometrics_changed, time)
            ProtectionGaps.Kind.BIOMETRICS_REMOVED -> context.getString(R.string.card_gap_biometrics_removed, time)
            ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED ->
                if (gap.to - gap.from < 60_000L) context.getString(R.string.card_gap_shield_access_revoked, time)
                else context.getString(R.string.card_gap_shield_access_revoked_range, timeRange(context, gap))
            else -> {
                val change = ProtectionGaps.Change.decode(gap.detail)
                // Older records hold their words as written; newer ones a code, worded here.
                // After " · ": in lower case unless it starts with a name ([afterSeparator]).
                val what = when {
                    change != null -> afterSeparator(changeText(context, change.first, change.second), changeStartsWithName(context, change.first))
                    // Older records hold their words as written: an app's name may come first, so they stay so.
                    gap.detail.isNotEmpty() -> gap.detail
                    else -> afterSeparator(context.getString(R.string.card_gap_protection_changed))
                }
                context.getString(
                    when {
                        grouped || change?.first?.fromScreenLock == false -> R.string.card_gap_event
                        ProtectionGaps.Change.duringReset(gap.detail) -> R.string.card_gap_changed_during_reset
                        ProtectionGaps.Change.duringHold(gap.detail) -> R.string.card_gap_changed_during_hold
                        else -> R.string.card_gap_changed_with_screen_lock
                    },
                    time, what
                )
            }
        }
    }
    if (gap.kind == ProtectionGaps.Kind.NO_SCREEN_LOCK) {
        // Still without one (or only just noticed): since when; once it's back, from and to.
        return if (gap.to - gap.from < 60_000L) context.getString(R.string.card_gap_no_screen_lock_since, attemptTime(context, gap.from))
        else context.getString(R.string.card_gap_no_screen_lock, timeRange(context, gap))
    }
    if (gap.kind == ProtectionGaps.Kind.DATA_CLEARED) {
        return if (gap.unknownStart) context.getString(R.string.card_gap_storage_cleared_unknown, attemptTime(context, gap.to))
        else context.getString(R.string.card_gap_storage_cleared, timeRange(context, gap))
    }
    if (gap.unknownStart) {
        val words = if (gap.detail == ProtectionGaps.RESTARTED) R.string.card_gap_force_stopped_restarted else R.string.card_gap_force_stopped_unknown
        return context.getString(words, attemptTime(context, gap.to))
    }
    return context.getString(if (gap.kind == ProtectionGaps.Kind.SWITCHED_OFF) R.string.card_gap_switched_off else R.string.card_gap_force_stopped, timeRange(context, gap))
}

/** A time off's span, as every record's span is said ([recordRange]). */
private fun timeRange(context: Context, gap: ProtectionGaps.Gap): String = recordRange(context, gap.from, gap.to)

private fun timeRange(context: Context, from: Long, to: Long): String = recordRange(context, from, to)

/** A record, or the same one several times running: [newest] stands for them all, the oldest at [oldestAt]. */
internal data class RecordRun(val newest: ProtectionGaps.Gap, val count: Int = 1, val oldestAt: Long = newest.to)

/**
 * Records (newest first) with the same one several times running put together: the same change
 * to the same thing, each at a moment. Times locking was off each have their own span, and
 * stay apart, as does notification access (worded by how long it was off).
 */
internal fun groupRepeats(records: List<ProtectionGaps.Gap>): List<RecordRun> {
    val runs = ArrayList<RecordRun>()
    for (record in records) {
        val last = runs.lastOrNull()
        val repeat = last != null && !record.isOff && record.kind != ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED &&
            record.kind == last.newest.kind && record.detail == last.newest.detail
        if (repeat) runs[runs.lastIndex] = last!!.copy(count = last.count + 1, oldestAt = record.to)
        else runs += RecordRun(record)
    }
    return runs
}

/** A run's line: one record as [gapLine] has it, several with when the first and the last were. */
internal fun runLine(context: Context, run: RecordRun, grouped: Boolean = false): String =
    if (run.count == 1) gapLine(context, run.newest, grouped = grouped)
    else gapLine(context, run.newest, time = timeRange(context, run.oldestAt, run.newest.to), grouped = grouped)

/**
 * The changes made in a visit of one kind, each group under one heading that says so, in place of
 * the same words in brackets on every line: made within a day of a face or fingerprint change, of
 * BioKey being reset, or after BioKey was opened with the screen lock.
 */
internal enum class ChangeGroup(@androidx.annotation.StringRes val heading: Int) {
    DURING_HOLD(R.string.activity_changes_during_hold),
    DURING_RESET(R.string.activity_changes_during_reset),
    WITH_SCREEN_LOCK(R.string.activity_changes_with_screen_lock)
}

/** Which [ChangeGroup] a record goes under, or null: a face or fingerprint change, notification access, or nobody's change. */
internal fun changeGroup(gap: ProtectionGaps.Gap): ChangeGroup? {
    if (gap.isOff || gap.kind != ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK) return null
    val change = ProtectionGaps.Change.decode(gap.detail)
    return when {
        change?.first?.fromScreenLock == false -> null
        ProtectionGaps.Change.duringReset(gap.detail) -> ChangeGroup.DURING_RESET
        ProtectionGaps.Change.duringHold(gap.detail) -> ChangeGroup.DURING_HOLD
        else -> ChangeGroup.WITH_SCREEN_LOCK
    }
}

/**
 * The changes section's notes, each fact once: a face or fingerprint change's "check them" and, for
 * the changes made within a day of it, "whoever did may have made them", said together as one.
 */
internal fun changeSectionNotes(changes: List<ProtectionGaps.Gap>): List<Int> {
    val notes = changes.flatMap(::changeNotes).distinctBy { it.text }
    val hold = notes.any { it.text == R.string.card_change_during_hold_hint }
    val check = notes.any { it.text == R.string.card_change_biometrics_changed_hint }
    val merged = if (!hold || !check) notes else notes.mapNotNull {
        when (it.text) {
            R.string.card_change_biometrics_changed_hint -> NoteLine(R.string.card_change_biometrics_hold_hint, true)
            R.string.card_change_during_hold_hint -> null
            else -> it
        }
    }
    return merged.map { it.text }
}

/** [changeSectionNotes] as lines, each with its style. */
private fun changeSectionNoteLines(changes: List<ProtectionGaps.Gap>): List<NoteLine> {
    val all = changes.flatMap(::changeNotes) + NoteLine(R.string.card_change_biometrics_hold_hint, true)
    // What to do first, then the background to it.
    return changeSectionNotes(changes).map { id -> all.first { it.text == id } }.sortedByDescending { it.lead }
}

/** What a change means, and what to do: one or two lines ([NoteLine]), said once per section however many need them. */
private fun changeNotes(change: ProtectionGaps.Gap): List<NoteLine> = when (change.kind) {
    ProtectionGaps.Kind.BIOMETRICS_CHANGED ->
        listOf(NoteLine(R.string.card_change_biometrics_changed_hint, true), NoteLine(R.string.card_change_biometrics_note, false))
    ProtectionGaps.Kind.BIOMETRICS_REMOVED ->
        listOf(NoteLine(R.string.card_change_biometrics_removed_hint, true), NoteLine(R.string.card_change_biometrics_note, false))
    ProtectionGaps.Kind.SHIELD_ACCESS_REVOKED -> listOf(NoteLine(R.string.card_change_shield_access_hint, true))
    else -> when {
        // Not made in a visit opened with the screen lock (a log shared, the sensor failing): nobody's change.
        ProtectionGaps.Change.decode(change.detail)?.first?.fromScreenLock == false -> emptyList()
        ProtectionGaps.Change.duringReset(change.detail) -> listOf(NoteLine(R.string.card_change_during_reset_hint, true))
        ProtectionGaps.Change.duringHold(change.detail) -> listOf(NoteLine(R.string.card_change_during_hold_hint, true))
        else -> listOf(NoteLine(R.string.card_change_screen_lock_hint, true))
    }
}

/** What a time locking was off meant. */
private fun offNotes(gap: ProtectionGaps.Gap): List<NoteLine> = when (gap.kind) {
    // A reset: what it may hide, and why it holds for a day.
    ProtectionGaps.Kind.DATA_CLEARED -> listOf(NoteLine(R.string.card_gap_reset_hint, true), NoteLine(R.string.card_gap_reset_note, false))
    ProtectionGaps.Kind.NO_SCREEN_LOCK -> listOf(NoteLine(R.string.card_gap_no_screen_lock_hint, true))
    else -> listOf(NoteLine(R.string.card_gap_hint, true))
}

/** A note under a section's records: advice ([lead], the stronger style) or background. */
private data class NoteLine(@androidx.annotation.StringRes val text: Int, val lead: Boolean)

/** Whether a change's words start with a name filled in (an app's or a profile's), or a count. */
private fun changeStartsWithName(context: Context, change: ProtectionGaps.Change): Boolean =
    change.plural != 0 || startsWithArgument(context.resources.getText(change.text).toString())

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

/** The unlocked apps, each with its countdown and its own Lock, and Re-lock now. */
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
        title = { PassphraseDialogTitle(stringResource(R.string.home_unlocked_now)) },
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
    /** The hold is BioKey's reset, not a face or fingerprint change. */
    heldByReset: Boolean = false,
    /** Opened with the screen lock (no hold): reviewing takes a face or fingerprint. */
    openedWithScreenLock: Boolean = onMarkReviewed != null && heldUntil == null
) {
    val context = LocalContext.current
    val note = MaterialTheme.colorScheme.onSurfaceVariant
    val anyNew = securityActivityUrgent(attempts, gaps, UnlockAttempts.seen.collectAsStateWithLifecycle().value)
    // Read in home's window, not the dialog's (which is only as big as the dialog).
    val short = shortWindow()
    val changes = gaps.filterNot { it.isOff }
    val offs = gaps.filter { it.isOff }
    // What to do about them: look over the faces, fingerprints and screen lock. Under the records it's about.
    val checkAfterChanges = changes.any { it.kind.holds || (it.kind == ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK && changeNotes(it).isNotEmpty()) }
    val checkAfterOffs = !checkAfterChanges && offs.any { it.kind.holds }
    // Offered unless only what can't be cleared is left, or while a hold is on: whoever made the
    // change could otherwise clear what happened around it.
    val clearOffered = heldUntil == null && (attempts.isNotEmpty() || gaps.any { !it.kind.holds })
    AlertDialog(
        onDismissRequest = onDismiss,
        // In a short window (landscape, half a split screen), the records get all the height there is.
        modifier = if (short) Modifier.padding(horizontal = 24.dp, vertical = 8.dp).widthIn(max = 560.dp) else Modifier,
        properties = DialogProperties(usePlatformDefaultWidth = !short),
        icon = {
            // The card's icon; new records show in the colour, as on the card.
            Icon(
                SecurityActivityIcon, contentDescription = null,
                tint = if (anyNew) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
            )
        },
        title = { PassphraseDialogTitle(stringResource(R.string.activity_title)) },
        text = {
            Column {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    ProtectDialogWindow()
                    // At most one note before the records: why they stay new.
                    if (heldUntil != null) {
                        // The hold in one plain sentence: what, until when and why. What waits is
                        // under the records, folded away until asked for.
                        Callout(
                            stringResource(
                                if (heldByReset) R.string.activity_held_reset else R.string.activity_held_cause,
                                attemptTime(context, heldUntil)
                            ),
                            warning = true,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    } else if (openedWithScreenLock) {
                        Callout(stringResource(R.string.card_activity_screen_lock_note), warning = false, modifier = Modifier.padding(bottom = 16.dp))
                    }
                    if (changes.isNotEmpty()) {
                        SectionLabel(stringResource(R.string.card_activity_changes))
                        // Each group's words said once, over its records, not on every line.
                        val byGroup = changes.groupBy(::changeGroup)
                        byGroup[null]?.let { RecordRuns(groupRepeats(it)) }
                        ChangeGroup.entries.forEach { group ->
                            byGroup[group]?.let {
                                SubLabel(stringResource(group.heading))
                                RecordRuns(groupRepeats(it), grouped = true)
                            }
                        }
                        SectionNotes(changeSectionNoteLines(changes))
                        if (checkAfterChanges) CheckSecurityButton()
                        Spacer(Modifier.height(20.dp))
                    }
                    if (offs.isNotEmpty()) {
                        SectionLabel(stringResource(R.string.gaps_title))
                        RecordRuns(groupRepeats(offs))
                        // What switches it off: for those, not for a reset or a missing screen lock (which say so themselves).
                        val switchedOff = offs.any { it.kind == ProtectionGaps.Kind.SWITCHED_OFF || it.kind == ProtectionGaps.Kind.FORCE_STOPPED }
                        SectionNotes(offs.flatMap(::offNotes) + listOfNotNull(NoteLine(R.string.card_gaps_note, false).takeIf { switchedOff }))
                        if (checkAfterOffs) CheckSecurityButton()
                        Spacer(Modifier.height(20.dp))
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
                        SectionNotes(listOf(NoteLine(R.string.card_attempts_note, false)))
                    }
                    if (heldUntil != null) HeldList(Modifier.padding(top = 12.dp))
                    // What stays, at the end of the records: it scrolls with them, leaving them the room.
                    if (gaps.any { it.kind.holds }) {
                        Text(
                            stringResource(R.string.activity_kept_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = note,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                // What this visit is for, when it's opened with the screen lock.
                if (onMarkReviewed != null && heldUntil == null) Button(onClick = onMarkReviewed) { Text(stringResource(R.string.card_mark_reviewed)) }
            }
        },
        // Destructive: in the error colour (dimmed like any other when disabled), and behind a face
        // or fingerprint (and Undo).
        dismissButton = if (clearOffered) {
            {
                TextButton(onClick = onClear, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.card_clear))
                }
            }
        } else null
    )
}

/**
 * What waits while a hold is on, folded under one line ("What waits until then") that opens it:
 * all of it there for the owner who wants it, out of the way of the records for the one who doesn't.
 */
@Composable
private fun HeldList(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.activity_held_more)
    val state = stringResource(if (open) R.string.common_expanded else R.string.common_collapsed)
    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { open = !open }
                .semantics(mergeDescendants = true) { stateDescription = state }
                .padding(vertical = 4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp.scaledWithText())
            )
        }
        if (open) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stringArrayResource(R.array.activity_held_refused).forEach { item ->
                        Row {
                            Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                            Text(item, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(
                        stringResource(R.string.activity_held_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Records under a section label, the same one several times running shown once with how many times. */
@Composable
private fun RecordRuns(runs: List<RecordRun>, grouped: Boolean = false) {
    val context = LocalContext.current
    RecordList {
        runs.forEach { run ->
            Column(Modifier.semantics(mergeDescendants = true) { }) {
                Text(runLine(context, run, grouped), style = MaterialTheme.typography.titleSmall)
                if (run.count > 1) {
                    Text(
                        pluralStringResource(R.plurals.card_record_count, run.count, run.count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * A section's notes, each said once however many records need it, set apart from the records in a
 * panel of their own, a gap between each so no two run together.
 */
@Composable
private fun SectionNotes(notes: List<NoteLine>) {
    val shown = notes.distinctBy { it.text }
    if (shown.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            shown.forEach { line ->
                Text(
                    stringResource(line.text),
                    style = if (line.lead) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    color = if (line.lead) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Android's security page, where faces, fingerprints and the screen lock are: the full width, under the records it's for. */
@Composable
private fun CheckSecurityButton() {
    val context = LocalContext.current
    FilledTonalButton(
        onClick = { IntentNavigationHelper.openSecuritySettings(context) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)
    ) {
        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp.scaledWithText()))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.activity_open_security_settings), textAlign = TextAlign.Center)
    }
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

/** Records under a section label: 8dp apart, 4dp in from the label above and what follows. */
@Composable
private fun RecordList(content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** A group's heading inside a section ([ChangeGroup]): smaller than the section's, over the records it's about. */
@Composable
private fun SubLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() }
    )
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
private val CARD_ICON_SIZE_COMPACT = 32.dp
private val CARD_ICON_GAP = 16.dp

/**
 * The longest line under a card's title that keeps an action beside it: past this, on a phone, the
 * text column the button leaves wraps it into a tall, narrow block ("Re-locks in 5 min" stays
 * beside; "Venmo · Oct 29, 2:14 PM · 3 tries didn't match" puts the button under the text).
 */
internal const val INLINE_SUBTITLE_MAX = 40

/**
 * The longest title that keeps an action beside it: a longer one ("Lock Android Settings too")
 * wraps to two lines in the column the button leaves.
 */
internal const val INLINE_TITLE_MAX = 24

/** From this font scale, text needs the whole width: actions go under it. */
private const val LARGE_TEXT = 1.3f

/**
 * Whether a short action fits beside a title and the line under it, or goes under them both:
 * decided from the text itself, not from a layout pass, so a card or row never flips back and
 * forth between the two arrangements. Home's cards and Settings' action rows share it.
 */
internal fun actionsFitBeside(title: String, subtitle: String, fontScale: Float): Boolean =
    fontScale < LARGE_TEXT && title.length <= INLINE_TITLE_MAX && subtitle.length <= INLINE_SUBTITLE_MAX
