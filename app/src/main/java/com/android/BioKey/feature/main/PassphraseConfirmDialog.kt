package com.android.BioKey.feature.main

import android.content.res.Resources
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.BioKey.R
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.ui.security.ProtectDialogWindow
import com.android.BioKey.ui.security.rejectObscuredTouches
import kotlinx.coroutines.delay
import kotlin.math.ceil

/*
 * The passphrase asked for in place of a face or fingerprint before a change that removes
 * protection, when "Face or fingerprint only" is on and none can be used: the screen lock must not
 * be enough then. A sibling of Settings' passphrase dialogs to look at; the lock-screen entry's
 * ([com.android.BioKey.feature.lock.PassphraseEntry]) twin in how it treats what's typed, and in
 * its words for what went wrong and how long to wait.
 */

/** What the passphrase confirmation shows; the wiring owns one and changes it. */
@Stable
internal class PassphraseConfirmState {
    /** What is being confirmed, in the words the face or fingerprint prompt would have used as its title ("Remove your passphrase", "Allow your screen lock again"). Null: nothing showing. */
    var title by mutableStateOf<String?>(null)

    /** A check is running: the field and its button wait. */
    var busy by mutableStateOf(false)

    private var errorText by mutableStateOf<String?>(null)

    /** Counts each message set, so the same words twice still empty the field again. */
    internal var errorSerial by mutableIntStateOf(0)
        private set

    /** Said under the field (announced to screen readers). Setting a non-null value also empties the field, even when it is the same words twice running. */
    var error: String?
        get() = errorText
        set(value) {
            errorText = value
            if (value != null) errorSerial++
        }

    /** Tries are paused until this SystemClock.elapsedRealtime() value: field and button off, a countdown shows, they come back by themselves. Zero or a past time: no wait. */
    var waitUntilElapsed by mutableLongStateOf(0L)
}

/** How long a screen reader's line keeps the words it was given to announce (as on the lock screen). */
private const val ANNOUNCE_HOLD_MS = 700L

/** Draws nothing while [state].title is null. [onSubmit] gets what was typed (the caller zeroes it); [onDismiss] when the owner backs out (Cancel, Back, a tap outside). */
@Composable
internal fun PassphraseConfirmDialog(state: PassphraseConfirmState, onSubmit: (CharArray) -> Unit, onDismiss: () -> Unit) {
    val title = state.title ?: return
    PassphraseConfirmContent(state, title, onSubmit, onDismiss)
}

/**
 * The dialog itself, composed only while it shows: what's typed lives in [remember] here, so it
 * goes (overwritten first) when the dialog closes. Never autofilled, saved, suggested, learned,
 * copied or restored, as on the lock screen.
 */
@Composable
private fun PassphraseConfirmContent(state: PassphraseConfirmState, title: String, onSubmit: (CharArray) -> Unit, onDismiss: () -> Unit) {
    val res = LocalResources.current
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { TextFieldState() }
    val focus = remember { FocusRequester() }
    var revealed by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    /** A wait just ended by itself: the line says so until the owner types. */
    var ended by remember { mutableStateOf(false) }
    /** The message last put aside: one from before the dialog opened, or about a wait now over. */
    var staleSerial by remember { mutableIntStateOf(state.errorSerial) }
    /** What screen readers were last given to announce, and while it's held. */
    var announced by remember { mutableStateOf("") }
    var announcing by remember { mutableIntStateOf(0) }

    val busy = state.busy
    val message = state.error?.takeIf { it.isNotBlank() && state.errorSerial != staleSerial }
    val waitUntil = state.waitUntilElapsed
    val waiting = waitUntil > now
    val enabled = !busy && !waiting
    val canSubmit = enabled && field.text.isNotEmpty()

    fun lineText(at: Long = now): String {
        val remaining = waitUntil - at
        val countdown = if (remaining > 0) confirmWaitText(res, remaining) else null
        val over = if (ended && message == null && countdown == null) res.getString(R.string.lock_passphrase_wait_over) else null
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
        wipeTyped(field)
        onSubmit(typed)
    }

    // A message: the field empties and it's announced, even when it's the same words again.
    LaunchedEffect(state.errorSerial) {
        if (state.errorSerial == staleSerial) return@LaunchedEffect
        ended = false
        if (state.error != null) {
            wipeTyped(field)
            announce(lineText(SystemClock.elapsedRealtime()))
        }
    }

    // A wait: on starting, the field empties, the keyboard goes and it's announced; the countdown
    // then follows its number unannounced; when it ends by itself, that's said.
    LaunchedEffect(waitUntil) {
        now = SystemClock.elapsedRealtime()
        if (waitUntil <= now) return@LaunchedEffect
        wipeTyped(field)
        keyboard?.hide()
        ended = false
        announce(lineText(now))
        while (true) {
            val remaining = waitUntil - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            val unit = if (remaining <= 60_000L) 1_000L else if (remaining <= 120 * 60_000L) 60_000L else 3_600_000L
            delay((remaining - 1) % unit + 1)
            now = SystemClock.elapsedRealtime()
        }
        now = SystemClock.elapsedRealtime()
        staleSerial = state.errorSerial
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

    // On opening, and whenever the field comes back (a check done, a wait over): focused.
    LaunchedEffect(enabled) {
        if (enabled) runCatching { focus.requestFocus() }
    }

    // However the dialog goes (cancelled, a match, the screen leaving), what was typed goes with it.
    DisposableEffect(field) { onDispose { wipeTyped(field) } }

    AlertDialog(
        // Not while a check runs: its answer must find the dialog it was asked from.
        onDismissRequest = { if (!state.busy) onDismiss() },
        icon = { Icon(Icons.Default.Key, contentDescription = null) },
        title = { Text(title) },
        text = {
            ProtectDialogWindow()
            Column(Modifier.rejectObscuredTouches(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.passphrase_confirm_text))
                Column {
                    InterceptPlatformTextInput(ConfirmHardenedTextInput) {
                        OutlinedSecureTextField(
                            state = field,
                            enabled = enabled,
                            label = { Text(stringResource(R.string.passphrase_field)) },
                            trailingIcon = {
                                IconButton(onClick = { revealed = !revealed }) {
                                    Icon(
                                        if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = stringResource(if (revealed) R.string.passphrase_hide else R.string.passphrase_show)
                                    )
                                }
                            },
                            isError = message != null,
                            inputTransformation = InputTransformation.maxLength(OwnerPassphrase.MAX_LENGTH).then(CountsAsUse),
                            textObfuscationMode = if (revealed) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Done
                            ),
                            // Done with nothing typed (or while a check runs) does nothing, and keeps the keyboard.
                            onKeyboardAction = KeyboardActionHandler { submit() },
                            shape = CircleShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .semantics {
                                    // Not a place for a password manager: it neither fills nor offers to keep it.
                                    contentDataType = ContentDataType.None
                                    message?.let { error(it) }
                                }
                        )
                    }
                    // Where a field's help goes; announced only when there's something new to say
                    // (a message, a wait starting or over), not as the countdown ticks.
                    if (line.isNotEmpty() || announcing != 0) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                                .fillMaxWidth()
                                .clearAndSetSemantics {
                                    text = AnnotatedString(if (announcing != 0) announced else line)
                                    if (announcing != 0) liveRegion = LiveRegionMode.Polite
                                }
                        )
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = canSubmit) { Text(stringResource(R.string.passphrase_confirm_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.prompt_cancel)) }
        }
    )
}

/** Each keystroke counts as using BioKey: the keyboard's input never passes its touch handling, so a long passphrase could hit the idle lock. */
private val CountsAsUse = InputTransformation { LockStateTracker.onOwnAppInteraction() }

/*
 * Copied from feature/lock/PassphraseGate.kt, where they're private or test-only (waitText, wipe,
 * hardenEditorInfo, HardenedTextInput): to be merged with those.
 */

/** "Try again in 28 s", "in 4 min", "in 2 h": always rounded up, so it never says 0. */
private fun confirmWaitText(res: Resources, remainingMs: Long): String = when {
    remainingMs <= 60_000L -> res.getString(R.string.lock_passphrase_wait_seconds, ceil(remainingMs / 1_000.0).toInt())
    remainingMs <= 120 * 60_000L -> res.getString(R.string.lock_passphrase_wait_minutes, ceil(remainingMs / 60_000.0).toInt())
    else -> res.getString(R.string.lock_passphrase_wait_hours, ceil(remainingMs / 3_600_000.0).toInt())
}

/** Overwrites what was typed, then empties the field and forgets its undo history (which could bring it back). */
@OptIn(ExperimentalFoundationApi::class) // The undo history's clearing.
private fun wipeTyped(field: TextFieldState) {
    val length = field.text.length
    if (length > 0) field.edit { replace(0, length, "\u0000".repeat(length)) }
    field.clearText()
    field.undoState.clearHistory()
}

/** What the keyboard is told, beyond what Compose's own options can say: never suggest, learn or go full-screen. */
private fun hardenConfirmEditorInfo(info: EditorInfo) {
    if (info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
        info.inputType = info.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
        EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
}

@OptIn(ExperimentalComposeUiApi::class)
private object ConfirmHardenedTextInput : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession
    ): Nothing = nextHandler.startInputMethod(object : PlatformTextInputMethodRequest by request {
        override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
            request.createInputConnection(outAttributes).also { hardenConfirmEditorInfo(outAttributes) }
    })
}
