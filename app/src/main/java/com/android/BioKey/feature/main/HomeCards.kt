package com.android.BioKey.feature.main

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RemoveModerator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.android.BioKey.R
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectDialogWindow
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.ui.theme.GroupInset
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
    // Tinted towards the error colour rather than a full slab of it: urgent, without drowning the page.
    val container = if (urgent) lerp(colors.surfaceContainer, colors.errorContainer, 0.7f) else colors.surfaceContainer
    val content = if (urgent) colors.onErrorContainer else colors.onSurface
    val secondary = if (urgent) colors.onErrorContainer else colors.onSurfaceVariant
    val iconContainer = if (urgent) colors.error else colors.secondaryContainer
    val iconTint = if (urgent) colors.onError else colors.onSecondaryContainer
    val sideBySide = inline && LocalDensity.current.fontScale < 1.3f
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
                Box(Modifier.size(40.dp).background(iconContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(16.dp))
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
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
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
        title = "Failed unlock alerts",
        subtitle = "Get told when a face or fingerprint fails on a locked app and only you can clear it. " +
            "Kept on this phone until you've seen it, then 7 days.",
        modifier = modifier
    ) {
        TextButton(onClick = onNotNow, modifier = Modifier.semantics { contentDescription = "Not now: no failed unlock alerts" }) {
            Text("Not now")
        }
        FilledTonalButton(onClick = onTurnOn, modifier = Modifier.semantics { contentDescription = "Turn on failed unlock alerts" }) {
            Text("Turn on")
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val urgent = gaps.isNotEmpty() || attempts.any { it.unanswered }
    val newestAttempt = attempts.firstOrNull()
    val newestGap = gaps.firstOrNull()
    val gapIsNewest = newestGap != null && (newestAttempt == null || newestGap.to > newestAttempt.lastAt)
    ActionCard(
        // Protection off and failed unlocks look different at a glance.
        icon = when {
            attempts.isEmpty() -> Icons.Default.RemoveModerator
            urgent -> Icons.Default.GppBad
            else -> Icons.Default.GppMaybe
        },
        title = when {
            gaps.isEmpty() -> resources.getQuantityString(R.plurals.attempts_title, attempts.size, attempts.size)
            attempts.isEmpty() && gaps.all { it.isOff } -> if (gaps.size == 1) "App locking was off" else "App locking was off ${gaps.size} times"
            else -> "Security activity"
        },
        subtitle = if (gapIsNewest) gapLine(context, newestGap!!) else newestAttempt?.let {
            "${labelOf(it)} · ${attemptTime(context, it.at)} · ${UnlockAttempts.describe(context, it)}"
        }.orEmpty(),
        urgent = urgent,
        inline = true,
        modifier = modifier
    ) {
        TextButton(
            onClick = onReview,
            colors = cardTextButtonColors(urgent),
            modifier = Modifier.semantics { contentDescription = "Review security activity" }
        ) { Text("Review") }
    }
}

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
        title = if (single) "${first.label} is unlocked" else "${apps.size} apps unlocked",
        subtitle = if (single) rememberSessionPhrase(first.packageName, timeoutOf(first.packageName)) else apps.joinToString(", ") { it.label },
        inline = single,
        modifier = modifier
    ) {
        if (single) {
            FilledTonalButton(
                onClick = { onLock(first) },
                modifier = Modifier.semantics { contentDescription = "Lock ${first.label}" }
            ) { Text("Lock") }
        } else {
            TextButton(onClick = onChoose) { Text("See apps") }
            FilledTonalButton(onClick = onLockAll) { Text("Lock all") }
        }
    }
}

/** "2:14 PM" today, "Yesterday, 2:14 PM", else "Oct 27, 2:14 PM". */
internal fun attemptTime(context: Context, at: Long): String {
    val time = DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME)
    return when {
        DateUtils.isToday(at) -> time
        DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> "Yesterday, $time"
        else -> DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR) + ", $time"
    }
}

/** Times app locking was off, as against changes that happened at one moment. */
internal val ProtectionGaps.Gap.isOff get() = kind == ProtectionGaps.Kind.SWITCHED_OFF || kind == ProtectionGaps.Kind.FORCE_STOPPED

/** "3:02 – 3:10 PM · switched off", or "3:04 PM · a face or fingerprint was added or removed". */
internal fun gapLine(context: Context, gap: ProtectionGaps.Gap): String {
    if (!gap.isOff) return attemptTime(context, gap.to) + " · " + when (gap.kind) {
        ProtectionGaps.Kind.BIOMETRICS_CHANGED -> "a face or fingerprint was added or removed"
        ProtectionGaps.Kind.BIOMETRICS_REMOVED -> "no face or fingerprint is set up any more"
        else -> "paused from BioKey opened with the screen lock"
    }
    val day = if (DateUtils.isToday(gap.to)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
    val range = DateUtils.formatDateRange(context, gap.from, gap.to, DateUtils.FORMAT_SHOW_TIME or day)
    return range + if (gap.kind == ProtectionGaps.Kind.SWITCHED_OFF) " · switched off" else " · BioKey was force-stopped"
}

/** When an unlocked app, off screen, locks again: "Re-locks in 4 min", or how it will. */
internal fun sessionPhrase(mode: LockTimeoutMode, remainingMs: Long?): String = when {
    mode == LockTimeoutMode.ON_SCREEN_OFF -> "Stays unlocked until the screen turns off"
    remainingMs == null -> "Re-locks " + mode.shortLabel.replaceFirstChar { it.lowercase() }.removePrefix("after ") + " after you leave it"
    remainingMs < 60_000L -> "Re-locks in less than a minute"
    else -> "Re-locks in ${(remainingMs + 59_999L) / 60_000L} min"
}

/**
 * [sessionPhrase], kept current while BioKey is in front: it changes on the minute, so it's read
 * again exactly then, and not at all while BioKey is out of sight.
 */
@Composable
internal fun rememberSessionPhrase(pkg: String, mode: LockTimeoutMode): String {
    var phrase by remember(pkg, mode) { mutableStateOf(sessionPhrase(mode, null)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(pkg, mode, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val remaining = LockStateTracker.awayRemainingMs(pkg, mode)
                phrase = sessionPhrase(mode, remaining)
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
        title = { Text("Unlocked now") },
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
                        TextButton(
                            onClick = { onLock(app) },
                            modifier = Modifier.semantics { contentDescription = "Lock ${app.label}" }
                        ) { Text("Lock") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onLockAll) { Text("Lock all") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Every failed unlock and time app locking was off, newest first, and the way to clear them. */
@Composable
internal fun SecurityActivityDialog(
    attempts: List<UnlockAttempts.Attempt>,
    gaps: List<ProtectionGaps.Gap>,
    labelOf: (UnlockAttempts.Attempt) -> String,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val note = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.GppBad, contentDescription = null) },
        title = { Text("Security activity") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProtectDialogWindow()
                val changes = gaps.filterNot { it.isOff }
                if (changes.isNotEmpty()) {
                    SectionLabel("Changes")
                    changes.forEach { change ->
                        Text(gapLine(context, change), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    Text(
                        "Anyone who knows your screen lock can add their own face or fingerprint in Android's settings. " +
                            "If you didn't, check them there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = note
                    )
                    Spacer(Modifier.height(16.dp))
                }
                val offs = gaps.filter { it.isOff }
                if (offs.isNotEmpty()) {
                    SectionLabel("App locking was off")
                    offs.forEach { gap ->
                        Column(Modifier.padding(vertical = 4.dp).semantics(mergeDescendants = true) { }) {
                            Text(gapLine(context, gap), style = MaterialTheme.typography.titleSmall)
                            Text("Locked apps could be opened without a check", style = MaterialTheme.typography.bodyMedium, color = note)
                        }
                    }
                    Text(
                        "Switched off in Android's settings, or BioKey force-stopped. Android stopping BioKey on its own " +
                            "isn't listed, but some phone makers' battery savers force-stop apps just as a person would.",
                        style = MaterialTheme.typography.bodySmall,
                        color = note
                    )
                    Spacer(Modifier.height(16.dp))
                }
                if (attempts.isNotEmpty()) {
                    SectionLabel("Failed unlocks")
                    attempts.forEach { attempt ->
                        Column(Modifier.padding(vertical = 4.dp).semantics(mergeDescendants = true) { }) {
                            Text(labelOf(attempt), style = MaterialTheme.typography.titleSmall)
                            Text(
                                attemptTime(context, attempt.at) + " · " + UnlockAttempts.describe(context, attempt),
                                style = MaterialTheme.typography.bodyMedium,
                                color = note
                            )
                        }
                    }
                    Text(
                        "Faces or fingerprints that didn't match. Misses your own face or fingerprint then fixed, on the same " +
                            "prompt, were you and aren't listed. Android doesn't tell apps about a wrong PIN.",
                        style = MaterialTheme.typography.bodySmall,
                        color = note
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        // Destructive: in the error colour, and behind a face or fingerprint (and Undo).
        dismissButton = {
            TextButton(onClick = onClear, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Clear")
            }
        }
    )
}

/** On an urgent (error-coloured) card, a text button takes the card's own content colour. */
/** The one thing to do on an urgent card: a filled button in the error colours. */
@Composable
internal fun UrgentButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
    ) { Text(text) }
}

@Composable
private fun cardTextButtonColors(urgent: Boolean) =
    if (urgent) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer) else ButtonDefaults.textButtonColors()

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() }
    )
}

