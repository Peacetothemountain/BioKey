package com.android.BioKey.feature.lock

import androidx.compose.ui.semantics.heading
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.compose.BackHandler
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.then
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.android.BioKey.R
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.ui.theme.springPress
import com.android.BioKey.ui.format.passphraseWaitText
import kotlinx.coroutines.delay
import kotlin.math.ceil

/*
 * The owner passphrase on BioKey's own lock ([BioKeyLockScreen]): the Compose twin of the lock
 * cover's ([LockCoverView] with [PassphraseEntryView]), built to the same design and in the same
 * words, so the two locks can't drift apart.
 */

/** What BioKey's own lock shows of the passphrase; MainActivity owns one and changes it. */
@Stable
class PassphraseGateState {
    /** "Use passphrase" is among the buttons. */
    var offered by mutableStateOf(false)
    /** The entry is open, in the buttons' place. */
    var open by mutableStateOf(false)
    /** A check is running: the field and its button wait. */
    var busy by mutableStateOf(false)

    private var errorText by mutableStateOf<String?>(null)
    /** Counts each message set, so the same words twice still empty the field again. */
    internal var errorSerial by mutableIntStateOf(0)
        private set

    /** Said under the field (announced to screen readers). Setting a non-null value also empties the field. */
    var error: String?
        get() = errorText
        set(value) {
            errorText = value
            if (value != null) errorSerial++
        }

    /** Tries are paused until this SystemClock.elapsedRealtime() value: field and button off, a countdown shows, they come back by themselves. Zero or a past time: no wait. */
    var waitUntilElapsed by mutableLongStateOf(0L)
}

/** "Use passphrase" beside Cancel, styled as it is; one above the other when the two don't fit. */
@Composable
internal fun PassphraseAndCancel(onUsePassphrase: () -> Unit, onCancel: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        CoverTextButton(stringResource(R.string.lock_passphrase_use), onUsePassphrase)
        CoverTextButton(stringResource(R.string.prompt_cancel), onCancel)
    }
}

/**
 * Cancel, "Use passphrase" and Back, drawn as the cover draws them: at least 88 by 48dp, 16dp
 * either side of the word, in Material's titleSmall.
 */
@Composable
internal fun CoverTextButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 88.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    }
}

/** How long a screen reader's line keeps the words it was given to announce, before following the countdown again unspoken. */
private const val ANNOUNCE_HOLD_MS = 700L

/**
 * The entry, in the buttons' place: a pill-shaped field with an eye, a line under it for what
 * went wrong or how long to wait, "Unlock" (no fingerprint: this isn't one) and "Back".
 *
 * With [compactTitle] (a wide window with little height: a phone in landscape with the keyboard
 * up), it's one row, as on the cover: that name (what's locked), the field with its line under it,
 * Back and Unlock, each centred on the field, all in view together above the keyboard.
 *
 * What's typed is BioKey's own secret on a lock screen: held in [remember] only, never
 * autofilled, saved, suggested, learned, copied or restored; overwritten and dropped as soon as
 * it's handed over, a message or a wait comes, or the entry goes.
 */
@Composable
internal fun PassphraseEntry(
    gate: PassphraseGateState,
    onSubmit: (CharArray) -> Unit,
    modifier: Modifier = Modifier,
    clock: () -> Long = SystemClock::elapsedRealtime,
    compactTitle: String? = null,
    roomHeight: Dp = Dp.Infinity
) {
    val res = LocalResources.current
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { TextFieldState() }
    val focus = remember { FocusRequester() }
    var revealed by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(clock()) }
    /** A wait just ended by itself: the line says so until the owner types. */
    var ended by remember { mutableStateOf(false) }
    /** The message last put aside: one from before the entry opened, or about a wait now over. */
    var staleSerial by remember { mutableIntStateOf(gate.errorSerial) }
    /** What screen readers were last given to announce, and while it's held. */
    var announced by remember { mutableStateOf("") }
    var announcing by remember { mutableIntStateOf(0) }

    val message = gate.error?.takeIf { it.isNotBlank() && gate.errorSerial != staleSerial }
    val context = LocalContext.current
    /** Read as a wrong passphrase is said: how many more wrong tries there are before one starts a wait. */
    val triesBeforeWait = remember(gate.errorSerial) {
        if (gate.error == res.getString(R.string.lock_passphrase_wrong)) OwnerPassphrase.triesBeforeWait(context) else null
    }
    val waitUntil = gate.waitUntilElapsed
    val waiting = waitUntil > now
    val enabled = !gate.busy && !waiting
    // Something went wrong, or tries are paused: the line and the field's outline take the error
    // colour together, as on the cover. A check running and "You can try again now" don't.
    val errorTone = message != null || waiting
    val canSubmit = enabled && field.text.isNotEmpty()
    /** Nothing typed or pasted goes in while a check runs or tries are paused; wipe's own edits aren't the owner's and pass. */
    val holding by rememberUpdatedState(!enabled)
    /**
     * What went wrong stays until the owner types again: the first character of a new try clears
     * the line and the field's error look, as on the cover. Told by the owner's own typing only
     * (the field's own wipes aren't), so an error is never cleared by what was typed before it.
     * A countdown isn't an error and stays (nothing can be typed while it runs). Screen readers
     * aren't left with the old words: what was held to be announced goes too.
     */
    val typedAgain by rememberUpdatedState {
        if (gate.error != null && gate.errorSerial != staleSerial) {
            staleSerial = gate.errorSerial
            announcing = 0
        }
    }
    val holdInput = remember {
        InputTransformation.maxLength(OwnerPassphrase.MAX_LENGTH).then(InputTransformation {
            if (holding) revertAllChanges() else if (length > 0) typedAgain()
        })
    }

    fun lineText(at: Long = now): String {
        val remaining = waitUntil - at
        val countdown = if (remaining > 0) passphraseWaitText(res, remaining) else null
        val over = if (ended && message == null && countdown == null) res.getString(R.string.lock_passphrase_wait_over) else null
        val said = message?.let { passphraseMessage(res, it, triesBeforeWait, waiting = countdown != null) }
        // A message and a countdown are two sentences, as on the cover.
        if (said != null && countdown != null) return res.getString(R.string.lock_passphrase_then, said, countdown)
        return listOfNotNull(said, countdown, over).joinToString(" ")
    }
    val line = lineText()

    fun announce(text: String) {
        announced = text
        announcing++
    }

    fun submit() {
        if (!canSubmit) return
        val text = field.text
        // Straight into the array: no String of it is made here.
        val typed = CharArray(text.length) { text[it] }
        wipe(field)
        onSubmit(typed)
    }

    // A message: the field empties and it's announced, even when it's the same words again.
    LaunchedEffect(gate.errorSerial) {
        if (gate.errorSerial == staleSerial) return@LaunchedEffect
        ended = false
        if (gate.error != null) {
            wipe(field)
            announce(lineText(clock()))
        }
    }

    // A wait: on starting, the field empties and it's announced (the keyboard stays, and the
    // field keeps focus, refusing what's typed: nothing moves, nothing else is left focused); the countdown
    // then changes each second in the last minute, otherwise as its number does, unannounced; when
    // it ends by itself, that's said, in place of the message about it.
    LaunchedEffect(waitUntil) {
        now = clock()
        if (waitUntil <= now) return@LaunchedEffect
        wipe(field)
        ended = false
        announce(lineText(now))
        while (true) {
            val remaining = waitUntil - clock()
            if (remaining <= 0) break
            val unit = if (remaining <= 60_000L) 1_000L else if (remaining <= 120 * 60_000L) 60_000L else 3_600_000L
            delay((remaining - 1) % unit + 1)
            now = clock()
        }
        now = clock()
        staleSerial = gate.errorSerial
        ended = true
        announce(res.getString(R.string.lock_passphrase_wait_over))
    }

    // The "you can try again" note stays until the owner types.
    LaunchedEffect(field) {
        snapshotFlow { field.text.isNotEmpty() }.collect { typed -> if (typed) ended = false }
    }

    // Held only long enough to be read out; then the line is read as it stands, unannounced.
    LaunchedEffect(announcing) {
        if (announcing == 0) return@LaunchedEffect
        delay(ANNOUNCE_HOLD_MS)
        announcing = 0
    }

    // On opening, and whenever the field comes back (a check done, a wait over): focused, keyboard
    // up, as on the cover, however the owner left it meanwhile (the keyboard put away, say). Held
    // (a check, a wait), it keeps focus and refuses what's typed ([holdInput]), so the keyboard
    // stays, nothing moves, and focus never lands on Back or the eye.
    LaunchedEffect(enabled) {
        runCatching { focus.requestFocus() }
        if (enabled) keyboard?.show()
    }

    // Back (on screen or the gesture) goes back to the buttons, not out of the lock.
    BackHandler { gate.open = false }

    // However the entry goes (closed, a match, the screen leaving), what was typed goes with it.
    DisposableEffect(field) { onDispose { wipe(field) } }

    val scheme = MaterialTheme.colorScheme
    // Held (a check, a wait), the field is drawn as Material draws one turned off: the words, the
    // hint and the eye faint, no cursor, the outline faint, or the error colour while the line
    // under it is. In use, as usual, except that a wrong passphrase leaves the eye and the cursor
    // as they were (as on the cover): the outline and the line say it, the eye isn't a second error.
    val faint = scheme.onSurface.copy(alpha = 0.38f)
    val heldOutline = if (errorTone) scheme.error else scheme.onSurface.copy(alpha = 0.12f)
    val fieldColors = if (enabled) {
        OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
            errorCursorColor = scheme.primary,
            errorTrailingIconColor = scheme.onSurfaceVariant
        )
    } else {
        OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
            focusedTextColor = faint,
            unfocusedTextColor = faint,
            errorTextColor = faint,
            focusedPlaceholderColor = faint,
            unfocusedPlaceholderColor = faint,
            errorPlaceholderColor = faint,
            focusedTrailingIconColor = faint,
            unfocusedTrailingIconColor = faint,
            errorTrailingIconColor = faint,
            cursorColor = Color.Transparent,
            errorCursorColor = Color.Transparent,
            focusedBorderColor = heldOutline,
            unfocusedBorderColor = heldOutline,
            errorBorderColor = heldOutline
        )
    }

    val fieldPart = @Composable {
        InterceptPlatformTextInput(HardenedTextInput) {
            OutlinedSecureTextField(
                state = field,
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text(stringResource(R.string.lock_passphrase_hint)) },
                trailingIcon = {
                    // Off with the field (a check, a wait): nothing to show, and never left
                    // focused as a grey circle. Its faint colour is the field's own held one.
                    IconButton(
                        onClick = { revealed = !revealed },
                        enabled = enabled,
                        colors = IconButtonDefaults.iconButtonColors(disabledContentColor = LocalContentColor.current)
                    ) {
                        Icon(
                            if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(if (revealed) R.string.passphrase_hide else R.string.passphrase_show)
                        )
                    }
                },
                isError = message != null,
                inputTransformation = holdInput,
                textObfuscationMode = if (revealed) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done
                ),
                // Done with nothing typed (or while a check runs) does nothing, and keeps the keyboard.
                onKeyboardAction = KeyboardActionHandler { submit() },
                shape = CircleShape,
                colors = fieldColors,
                // In one row, 8dp above and below the words, not 16: at large text the room above
                // the keyboard is short (never under 56dp all the same, so at usual sizes it's as upright).
                contentPadding = if (compactTitle != null) {
                    OutlinedTextFieldDefaults.contentPadding(start = 24.dp, top = 8.dp, bottom = 8.dp)
                } else {
                    OutlinedTextFieldDefaults.contentPadding(start = 24.dp)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .focusRequester(focus)
                    // Not a place for a password manager: it neither fills nor offers to keep it.
                    // Held, screen readers hear it as off, as it's drawn.
                    .semantics {
                        contentDataType = ContentDataType.None
                        if (!enabled) disabled()
                    }
            )
        }
    }
    /** In one row with too little height for the line wrapped under the field, it runs on wider, from its start. */
    var lineWide by remember { mutableStateOf(false) }
    // Always there, so screen readers hear its first words too; and its room is kept from the
    // moment the entry opens (the longest it usually says, unseen), so nothing moves as a
    // message, a countdown or its end comes and goes. Anything longer still shows in full.
    val linePart = @Composable {
        val align = if (lineWide) TextAlign.Start else TextAlign.Center
        Box(Modifier.fillMaxWidth(), contentAlignment = if (lineWide) Alignment.TopStart else Alignment.TopCenter) {
            Text(
                text = longestPassphraseNote(res),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = align,
                modifier = Modifier.fillMaxWidth().alpha(0f).clearAndSetSemantics { }
            )
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = if (errorTone) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = align,
                modifier = Modifier
                    .fillMaxWidth()
                    // Read as it stands; announced only when there's something new to say (a
                    // message, a wait starting or over), not as the countdown ticks.
                    .clearAndSetSemantics {
                        text = AnnotatedString(if (announcing != 0) announced else line)
                        if (announcing != 0) liveRegion = LiveRegionMode.Polite
                    }
            )
        }
    }
    val unlockButton = @Composable { unlockModifier: Modifier ->
        Button(
            onClick = { submit() },
            enabled = canSubmit,
            shape = CircleShape,
            modifier = unlockModifier
                .heightIn(min = 56.dp)
                .springPress()
        ) {
            // Both words laid out, one unseen: the button is as wide as the wider, so nothing
            // moves as a check starts or ends. Screen readers hear only the one shown.
            Box(contentAlignment = Alignment.Center) {
                for ((label, shown) in listOf(R.string.lock_screen_unlock to !gate.busy, R.string.lock_passphrase_checking to gate.busy)) {
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = if (shown) Modifier else Modifier.alpha(0f).clearAndSetSemantics { }
                    )
                }
            }
        }
    }
    val backButton = @Composable { CoverTextButton(stringResource(R.string.lock_passphrase_back)) { gate.open = false } }

    // Upright (the field, Unlock, Back, one under the other) or, with [compactTitle], one row
    // (that name, the field with its line under it, Back and Unlock, each centred on the field:
    // not on the field and its line). One layout places the same parts either way, so the field
    // is never made anew as the keyboard's coming or going switches between the two: it keeps its
    // focus, what's typed and the keyboard (made anew, it lost them, the keyboard went, the room
    // grew back, and round again).
    //
    // In one row the line must show whole above the keyboard ([roomHeight]), with nothing else
    // pushed out of view. The room round the row (up to 12dp above and below it) goes first; if
    // the line still doesn't fit under the field (large text, where it wraps), it runs on from
    // the field's start under Back and Unlock too, or, if need be, across the whole row, on as
    // few lines as that takes. Its room is the longest it usually says, so nothing moves as it
    // changes.
    Layout(
        contents = listOf(
            {
                if (compactTitle != null) {
                    Text(
                        compactTitle,
                        // As the cover's name beside its field: 20sp, medium.
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() }
                    )
                }
            },
            { fieldPart() },
            { linePart() },
            { backButton() },
            { unlockButton(Modifier) }
        ),
        modifier = modifier
    ) { (titleParts, fieldParts, lineParts, backParts, unlockParts), constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 360.dp.roundToPx()
        val loose = Constraints(maxWidth = width)
        val lineGap = 8.dp.roundToPx()
        val lineMargin = 16.dp.roundToPx()
        val fieldMeasurable = fieldParts.first()
        val lineMeasurable = lineParts.first()
        if (compactTitle != null) {
            val titleGap = 16.dp.roundToPx()
            val gap = 8.dp.roundToPx()
            val title = titleParts.firstOrNull()?.measure(Constraints(maxWidth = minOf(COMPACT_TITLE_MAX.roundToPx(), width)))
            val back = backParts.first().measure(loose)
            val unlock = unlockParts.first().measure(Constraints(minWidth = minOf(120.dp.roundToPx(), width), maxWidth = width))
            val titleWidth = title?.width ?: 0
            val titleHigh = title?.height ?: 0
            val buttons = gap + back.width + gap + unlock.width
            val room = if (roomHeight == Dp.Infinity) Int.MAX_VALUE else roomHeight.roundToPx()
            val underWidth = (width - titleWidth - titleGap - buttons).coerceAtLeast(0)
            // The field's height doesn't depend on its width.
            val fieldHigh = fieldMeasurable.minIntrinsicHeight(underWidth)
            val sides = maxOf(titleHigh, back.height, unlock.height)
            /** How far down the field sits, for anything beside it taller than it to stay centred on it. */
            val fieldTop = ((sides - fieldHigh) / 2).coerceAtLeast(0)
            val fieldX = titleWidth + titleGap
            /** Where the line can go, narrowest first: its x and width. */
            val spans = listOf(
                fieldX + lineMargin to (underWidth - 2 * lineMargin).coerceAtLeast(0),
                fieldX + lineMargin to (width - fieldX - lineMargin).coerceAtLeast(0),
                0 to width
            )
            val leastGap = 2.dp.roundToPx()
            val fits = spans.indices.indexOfFirst { i ->
                val under = if (i == 0) lineGap else leastGap
                maxOf(fieldTop + fieldHigh + under + lineMeasurable.minIntrinsicHeight(spans[i].second), fieldTop + (fieldHigh + sides) / 2) <= room
            }
            val chosen = if (fits >= 0) fits else spans.lastIndex
            val wide = chosen > 0
            if (wide != lineWide) lineWide = wide
            val (lineX, lineWidth) = spans[chosen]
            val fieldPlaced = fieldMeasurable.measure(Constraints.fixedWidth(underWidth))
            val linePlaced = lineMeasurable.measure(Constraints.fixedWidth(lineWidth))
            // Widened, the gap under the field goes before anything else does.
            val gapUnder = if (!wide) lineGap else (room - fieldTop - fieldPlaced.height - linePlaced.height).coerceIn(leastGap, lineGap)
            val high = maxOf(fieldTop + fieldPlaced.height + gapUnder + linePlaced.height, fieldTop + (fieldPlaced.height + sides) / 2)
            val pad = ((room.toLong() - high) / 2).coerceIn(0L, COMPACT_ROOM.roundToPx().toLong()).toInt()
            val middle = pad + fieldTop + fieldPlaced.height / 2
            layout(width, high + 2 * pad) {
                title?.placeRelative(0, middle - title.height / 2)
                fieldPlaced.placeRelative(fieldX, pad + fieldTop)
                linePlaced.placeRelative(lineX, pad + fieldTop + fieldPlaced.height + gapUnder)
                var x = fieldX + underWidth + gap
                back.placeRelative(x, middle - back.height / 2)
                x += back.width + gap
                unlock.placeRelative(x, middle - unlock.height / 2)
            }
        } else {
            if (lineWide) lineWide = false
            val fieldPlaced = fieldMeasurable.measure(Constraints.fixedWidth(width))
            val linePlaced = lineMeasurable.measure(Constraints.fixedWidth((width - 2 * lineMargin).coerceAtLeast(0)))
            val unlock = unlockParts.first().measure(Constraints.fixedWidth(width))
            val back = backParts.first().measure(loose)
            val afterField = 12.dp.roundToPx()
            val afterUnlock = 8.dp.roundToPx()
            val lineBottom = fieldPlaced.height + lineGap + linePlaced.height
            val height = lineBottom + afterField + unlock.height + afterUnlock + back.height
            layout(width, height) {
                // Placed in reading order: the field, its line, Unlock, Back.
                fieldPlaced.placeRelative(0, 0)
                linePlaced.placeRelative(lineMargin, fieldPlaced.height + lineGap)
                unlock.placeRelative(0, lineBottom + afterField)
                back.placeRelative((width - back.width) / 2, lineBottom + afterField + unlock.height + afterUnlock)
            }
        }
    }
}

/** The widest the locked app's name gets beside the field, in one row (as on the cover). */
private val COMPACT_TITLE_MAX = 200.dp
/** In one row, the most room kept above and below it, given up first when the room is short. */
private val COMPACT_ROOM = 12.dp


/** Overwrites what was typed, then empties the field and forgets its undo history (which could bring it back). */
@OptIn(ExperimentalFoundationApi::class) // The undo history's clearing.
private fun wipe(field: TextFieldState) {
    val length = field.text.length
    if (length > 0) field.edit { replace(0, length, "\u0000".repeat(length)) }
    field.clearText()
    field.undoState.clearHistory()
}

/**
 * What the keyboard is told, beyond what Compose's own options can say: as on the cover, never
 * suggested, learned or put in the keyboard's full-screen copy (landscape), which would cover the lock.
 */
@VisibleForTesting
internal fun hardenEditorInfo(info: EditorInfo) {
    if (info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
        info.inputType = info.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
        EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
}

@OptIn(ExperimentalComposeUiApi::class)
private object HardenedTextInput : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession
    ): Nothing = nextHandler.startInputMethod(object : PlatformTextInputMethodRequest by request {
        override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
            request.createInputConnection(outAttributes).also { hardenEditorInfo(outAttributes) }
    })
}

