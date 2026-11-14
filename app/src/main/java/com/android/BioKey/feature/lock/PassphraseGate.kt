package com.android.BioKey.feature.lock

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.BioKey.R
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.ui.theme.springPress
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
        TextButton(onClick = onUsePassphrase, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.lock_passphrase_use), style = MaterialTheme.typography.titleSmall)
        }
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.prompt_cancel), style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** How long a screen reader's line keeps the words it was given to announce, before following the countdown again unspoken. */
private const val ANNOUNCE_HOLD_MS = 700L

/**
 * The entry, in the buttons' place: a pill-shaped field with an eye, a line under it for what
 * went wrong or how long to wait, "Unlock" (no fingerprint: this isn't one) and "Back".
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
    clock: () -> Long = SystemClock::elapsedRealtime
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
    val waitUntil = gate.waitUntilElapsed
    val waiting = waitUntil > now
    val enabled = !gate.busy && !waiting
    val canSubmit = enabled && field.text.isNotEmpty()

    fun lineText(at: Long = now): String {
        val remaining = waitUntil - at
        val countdown = if (remaining > 0) waitText(res, remaining) else null
        val over = if (ended && message == null && countdown == null) res.getString(R.string.lock_passphrase_wait_over) else null
        // A message and a countdown are two sentences, as on the cover.
        if (message != null && countdown != null) return res.getString(R.string.lock_passphrase_then, message, countdown)
        return listOfNotNull(message, countdown, over).joinToString(" ")
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

    // A wait: on starting, the field empties, the keyboard goes and it's announced; the countdown
    // then changes each second in the last minute, otherwise as its number does, unannounced; when
    // it ends by itself, that's said, in place of the message about it.
    LaunchedEffect(waitUntil) {
        now = clock()
        if (waitUntil <= now) return@LaunchedEffect
        wipe(field)
        keyboard?.hide()
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

    // On opening, and whenever the field comes back (a check done, a wait over): focused, keyboard up.
    LaunchedEffect(enabled) {
        if (enabled) runCatching { focus.requestFocus() }
    }

    // Back (on screen or the gesture) goes back to the buttons, not out of the lock.
    BackHandler { gate.open = false }

    // However the entry goes (closed, a match, the screen leaving), what was typed goes with it.
    DisposableEffect(field) { onDispose { wipe(field) } }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        InterceptPlatformTextInput(HardenedTextInput) {
            OutlinedSecureTextField(
                state = field,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text(stringResource(R.string.lock_passphrase_hint)) },
                trailingIcon = {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(if (revealed) R.string.lock_passphrase_hide else R.string.lock_passphrase_show)
                        )
                    }
                },
                isError = message != null,
                inputTransformation = InputTransformation.maxLength(OwnerPassphrase.MAX_LENGTH),
                textObfuscationMode = if (revealed) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done
                ),
                // Done with nothing typed (or while a check runs) does nothing, and keeps the keyboard.
                onKeyboardAction = KeyboardActionHandler { submit() },
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent
                ),
                contentPadding = OutlinedTextFieldDefaults.contentPadding(start = 24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .focusRequester(focus)
                    // Not a place for a password manager: it neither fills nor offers to keep it.
                    .semantics { contentDataType = ContentDataType.None }
            )
        }
        // Always there (empty, it takes no room), so screen readers hear its first words too.
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(top = if (line.isEmpty()) 0.dp else 8.dp)
                .fillMaxWidth()
                .then(if (line.isEmpty()) Modifier.height(0.dp) else Modifier)
        ) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
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
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { submit() },
            enabled = canSubmit,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .springPress()
        ) {
            Text(
                stringResource(if (gate.busy) R.string.lock_passphrase_checking else R.string.lock_passphrase_unlock),
                style = MaterialTheme.typography.titleMedium
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { gate.open = false }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.lock_passphrase_back), style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** "Try again in 28 s", "in 4 min", "in 2 h": always rounded up, so it never says 0. The cover's own rule. */
private fun waitText(res: android.content.res.Resources, remainingMs: Long): String = when {
    remainingMs <= 60_000L -> res.getString(R.string.lock_passphrase_wait_seconds, ceil(remainingMs / 1_000.0).toInt())
    remainingMs <= 120 * 60_000L -> res.getString(R.string.lock_passphrase_wait_minutes, ceil(remainingMs / 60_000.0).toInt())
    else -> res.getString(R.string.lock_passphrase_wait_hours, ceil(remainingMs / 3_600_000.0).toInt())
}

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

