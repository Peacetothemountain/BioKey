package com.android.BioKey.feature.main

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import android.app.Activity
import android.content.Context
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.then
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.BioKey.R
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.ui.theme.BioKeyMessages
import com.android.BioKey.ui.theme.DialogGap
import com.android.BioKey.ui.theme.DialogSpacing
import com.android.BioKey.ui.theme.KeyboardSafeDialog
import com.android.BioKey.ui.theme.keepInViewWhileFocused
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Settings' "Passphrase" row: BioKey's own way in for when "Face or fingerprint only" is on and the
 * face or fingerprint can't be used. Only the row and its dialogs live here; keeping and checking
 * the passphrase is [OwnerPassphrase]'s.
 */

/** What the passphrase row shows, from what's true now (never only what was chosen). */
internal enum class PassphraseRowState(
    @param:StringRes val subtitle: Int?,
    /** Whether a new one may be typed. Removing is always allowed once a passphrase is set. */
    val canSet: Boolean
) {
    /** No passphrase, and it couldn't do anything now: the row isn't shown. */
    HIDDEN(null, canSet = false),

    /** No passphrase; "Face or fingerprint only" is on with a face or fingerprint to use. */
    NOT_SET(R.string.passphrase_row_not_set, canSet = true),

    /** Set, and asked for whenever the face or fingerprint can't be used. */
    ON(R.string.passphrase_row_on, canSet = true),

    /** Set, but "Face or fingerprint only" is off: the screen lock is the fallback, so it's never asked for. */
    UNUSED_SWITCH_OFF(R.string.passphrase_row_unused_switch_off, canSet = true),

    /**
     * Set, "Face or fingerprint only" on, and no strong face or fingerprint that can be used now:
     * the passphrase is the way in ([com.android.BioKey.core.security.SecurityEngine.passphraseStandsIn]),
     * not the screen lock. Not changed from here then: the owner check would be the screen lock,
     * which mustn't be enough to choose the secret that stands in for it. Removing it is still allowed.
     */
    ON_NO_BIOMETRIC(R.string.passphrase_row_on_no_biometric, canSet = false);

    val shown: Boolean get() = this != HIDDEN
    val isSet: Boolean get() = this == ON || this == UNUSED_SWITCH_OFF || this == ON_NO_BIOMETRIC
    /** Asked for now, by locked apps and BioKey, when a face or fingerprint isn't what opens them. */
    val inUse: Boolean get() = this == ON || this == ON_NO_BIOMETRIC
}

/**
 * The row's state. Offered while "Face or fingerprint only" is on with a strong face or fingerprint
 * (the same condition as the row under it: a face or fingerprint is what a new one is set with);
 * and always shown while one is set, so it's never kept out of sight, saying when it does nothing.
 */
@VisibleForTesting
internal fun passphraseRowState(isSet: Boolean, biometricOnly: Boolean, strongBiometric: Boolean): PassphraseRowState = when {
    !isSet -> if (biometricOnly && strongBiometric) PassphraseRowState.NOT_SET else PassphraseRowState.HIDDEN
    !biometricOnly -> PassphraseRowState.UNUSED_SWITCH_OFF
    !strongBiometric -> PassphraseRowState.ON_NO_BIOMETRIC
    else -> PassphraseRowState.ON
}

/**
 * Whether a new passphrase may be typed: never where the check before it would be the screen lock
 * (no strong face or fingerprint to use), whatever the row says.
 */
@VisibleForTesting
internal fun passphraseCanBeSet(state: PassphraseRowState, strongBiometric: Boolean): Boolean = state.canSet && strongBiometric

/** Whether [length] characters can be saved as a passphrase. */
@VisibleForTesting
internal fun passphraseLengthOk(length: Int): Boolean = length in OwnerPassphrase.MIN_LENGTH..OwnerPassphrase.MAX_LENGTH

/** What the settings page draws for the row; null when it isn't shown. */
internal class PassphraseRow(val title: String, val subtitle: String, val onClick: () -> Unit) {
    val icon: ImageVector get() = Icons.Default.Key
}

private enum class PassphraseDialog { SET, CHANGE, REMOVE }

/**
 * The row's state and dialogs. Settings asks it for [row] where the row goes, and draws [Dialogs]
 * once, anywhere on the page.
 */
@Stable
internal class PassphraseSetting internal constructor(private val activity: Activity?) {
    internal var isSet by mutableStateOf(false)
    private var dialog by mutableStateOf<PassphraseDialog?>(null)
    /** "Remove passphrase" pressed in the Change dialog: asked over it, Cancel going back to it as it was. */
    private var removingFromChange by mutableStateOf(false)
    private var state = PassphraseRowState.HIDDEN

    /** The row, or null where it isn't shown. Each way in starts with a face or fingerprint check. */
    @Composable
    fun row(biometricOnly: Boolean, strongBiometric: Boolean): PassphraseRow? {
        state = passphraseRowState(isSet, biometricOnly, strongBiometric)
        val subtitle = state.subtitle ?: return null
        val res = LocalResources.current
        return PassphraseRow(stringResource(R.string.passphrase_title), stringResource(subtitle)) {
            val (prompt, next) = when {
                !state.isSet -> R.string.passphrase_set_title to PassphraseDialog.SET
                passphraseCanBeSet(state, strongBiometric) -> R.string.passphrase_confirm_change to PassphraseDialog.CHANGE
                else -> R.string.passphrase_confirm_remove to PassphraseDialog.REMOVE
            }
            // Held for a day after a face or fingerprint is added or removed, or BioKey is reset,
            // like everything else that could hide what someone with the screen lock did: they
            // could add a fingerprint of their own, set a passphrase only they know and take the
            // fingerprint away again, keeping a way in that nothing shows.
            confirmOwner(activity, res.getString(prompt), silencesRecords = true) {
                removingFromChange = false
                dialog = next
            }
        }
    }

    /** The Change dialog, as after the face or fingerprint check: for tests. */
    @VisibleForTesting
    internal fun openChangeForTests() {
        removingFromChange = false
        dialog = PassphraseDialog.CHANGE
    }

    @Composable
    fun Dialogs() {
        val res = LocalResources.current
        val remove = @Composable { onCancel: () -> Unit ->
            RemovePassphraseDialog(
                text = when (state) {
                    PassphraseRowState.ON -> R.string.passphrase_remove_text_in_use
                    // The screen lock is what opens them then, as before there was a passphrase.
                    PassphraseRowState.ON_NO_BIOMETRIC -> R.string.passphrase_remove_text_no_biometric
                    else -> R.string.passphrase_remove_text_unused
                },
                onRemove = {
                    removingFromChange = false
                    dialog = null
                    val app = activity?.applicationContext ?: return@RemovePassphraseDialog
                    OwnerPassphrase.clear(app)
                    isSet = false
                    // Nothing to undo with: the passphrase itself was never kept.
                    BioKeyMessages.show(res.getString(R.string.passphrase_removed), Icons.Default.Delete)
                },
                onDismiss = onCancel
            )
        }
        when (val showing = dialog) {
            PassphraseDialog.SET, PassphraseDialog.CHANGE -> {
                // Kept while removing is asked about: Cancel there comes back to it, with what was typed.
                PassphraseEntryDialog(
                    changing = showing == PassphraseDialog.CHANGE,
                    covered = removingFromChange,
                    onSaved = {
                        isSet = true
                        dialog = null
                        BioKeyMessages.show(
                            res.getString(if (showing == PassphraseDialog.CHANGE) R.string.passphrase_changed else R.string.passphrase_saved),
                            Icons.Default.CheckCircle
                        )
                    },
                    onRemove = { removingFromChange = true },
                    onDismiss = { dialog = null }
                )
                if (removingFromChange) remove { removingFromChange = false }
            }
            PassphraseDialog.REMOVE -> remove { dialog = null }
            null -> Unit
        }
    }
}

/**
 * The passphrase row's state holder, read afresh on every visit (back from Android's settings, a
 * passphrase may be gone with BioKey's storage) and following [OwnerPassphrase.isSetFlow].
 */
@Composable
internal fun rememberPassphraseSetting(activity: Activity?): PassphraseSetting {
    val context = LocalContext.current
    val setting = remember(activity) { PassphraseSetting(activity) }
    val flowSet by OwnerPassphrase.isSetFlow.collectAsStateWithLifecycle()
    LaunchedEffect(flowSet) { setting.isSet = flowSet }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            setting.isSet = withContext(Dispatchers.IO) { OwnerPassphrase.isSet(context) }
        }
    }
    return setting
}

/** Whether what's in the two fields can be saved: an allowed length, typed the same both times. */
@VisibleForTesting
internal fun passphraseEntryReady(first: CharSequence, again: CharSequence): Boolean =
    passphraseLengthOk(first.length) && first.contentEquals(again)

/**
 * Whether "Type it again" says it doesn't match the first. Not while it's still being typed and
 * could yet match (it's the start of the first): only once it can't, or once it's [finished] (Done
 * pressed on it, or it was left with something in it).
 */
@VisibleForTesting
internal fun passphraseMismatch(first: CharSequence, again: CharSequence, finished: Boolean): Boolean = when {
    again.isEmpty() || first.contentEquals(again) -> false
    finished -> true
    else -> again.length >= first.length || !first.startsWith(again)
}

/**
 * A new passphrase, typed twice: it's the only way in when the face or fingerprint can't be used,
 * so a typo mustn't be what's saved. A sibling of the backup's password dialog to look at (a pill,
 * an eye to read back what was typed, for both fields), and the confirmation dialog's twin in how
 * it treats what's typed: never autofilled, offered to a password manager (it opens locked apps
 * when nothing else can, the password manager maybe among them), suggested, learned, copied or
 * restored; it lives only in [remember], and is overwritten when the dialog goes. [store] is
 * [OwnerPassphrase.set] but in tests.
 */
@Composable
internal fun PassphraseEntryDialog(
    changing: Boolean,
    /** Another dialog is asked over this one ("Remove passphrase"): not drawn meanwhile, but what was typed is kept. */
    covered: Boolean = false,
    onSaved: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    store: (Context, CharArray) -> Boolean = OwnerPassphrase::set
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val first = remember { TextFieldState() }
    val again = remember { TextFieldState() }
    val firstFocus = remember { FocusRequester() }
    val againFocus = remember { FocusRequester() }
    /** One eye for both fields: read back together, they're compared at a glance. */
    var shown by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    /** Next or Save was pressed with the first too short: the rule turns into an error until it's long enough. */
    var tooShortSaid by remember { mutableStateOf(false) }
    /** The last save failed: said under the first field until either is edited. */
    val saveFailed = remember { mutableStateOf(false) }
    /** "Type it again" was finished with (Done, or left with something in it): a mismatch is said at once. */
    var againFinished by remember { mutableStateOf(false) }
    var againFocused by remember { mutableStateOf(false) }
    /** Which field had the cursor last, for it to go back there (after a save that failed, or a dialog over this one). */
    var againLast by remember { mutableStateOf(false) }
    /** [againLast] as it was when a dialog came over this one. */
    var resumeAgain by remember { mutableStateOf<Boolean?>(null) }
    val isCovered by rememberUpdatedState(covered)

    val rule = pluralStringResource(R.plurals.passphrase_min_length, OwnerPassphrase.MIN_LENGTH, OwnerPassphrase.MIN_LENGTH)
    val notSaved = stringResource(R.string.passphrase_not_saved)
    val noMatch = stringResource(R.string.passphrase_no_match)
    val lengthOk = passphraseLengthOk(first.text.length)
    val tooShort = tooShortSaid && !lengthOk
    val firstError = when {
        saveFailed.value -> notSaved
        tooShort -> rule
        else -> null
    }
    val mismatch = passphraseMismatch(first.text, again.text, againFinished)
    val ready = !busy && passphraseEntryReady(first.text, again.text)
    // Either field edited: a failed save's message goes (and typing counts as using BioKey).
    val edited = remember { PassphraseCountsAsUse.then(InputTransformation { saveFailed.value = false }) }
    val obfuscation = if (shown) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped

    fun save() {
        if (busy) return
        if (!passphraseLengthOk(first.text.length)) {
            tooShortSaid = true
            runCatching { firstFocus.requestFocus() }
            return
        }
        if (!first.text.contentEquals(again.text)) {
            againFinished = true
            return
        }
        busy = true
        saveFailed.value = false
        val text = first.text
        // Straight into the array: no String of it is made here.
        val chars = CharArray(text.length) { text[it] }
        val app = context.applicationContext
        // In BioKey's own scope: once started, it's finished and said even if the page closes.
        appScope.launch {
            val saved = withContext(Dispatchers.Default) {
                // Zeroed here, where it's used: a cancelled caller mustn't wipe it mid-save.
                try { runCatching { store(app, chars) }.getOrDefault(false) } finally { chars.fill('\u0000') }
            }
            busy = false
            if (saved) onSaved() else saveFailed.value = true
        }
    }

    // The rule goes back to being help once the first is long enough.
    LaunchedEffect(lengthOk) { if (lengthOk) tooShortSaid = false }
    // "Type it again" emptied: it's typed afresh, and given the same patience as the first time.
    LaunchedEffect(again) { snapshotFlow { again.text.isEmpty() }.collect { if (it) againFinished = false } }
    // Stepping aside for a dialog over this one: which field it was is noted now, as the window
    // coming back may move the focus itself before it's put back.
    LaunchedEffect(covered) { if (covered) resumeAgain = againLast }
    // However the dialog goes (saved, cancelled, the page leaving), what was typed goes with it.
    DisposableEffect(Unit) {
        onDispose {
            wipePassphraseField(first)
            wipePassphraseField(again)
        }
    }

    if (covered) return
    KeyboardSafeDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Key, contentDescription = null) },
        title = { PassphraseDialogTitle(stringResource(if (changing) R.string.passphrase_change_title else R.string.passphrase_set_title)) },
        busy = busy,
        confirmButton = {
            TextButton(onClick = { save() }, enabled = ready) { Text(stringResource(R.string.passphrase_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.prompt_cancel)) }
        },
        // Changing is where an existing one is managed: removing is offered here, with the
        // dialog's buttons at their start, as a profile's "Delete profile" is in its dialog.
        startButton = if (changing) {
            { DestructiveTextButton(stringResource(R.string.passphrase_remove_passphrase), onClick = onRemove, enabled = !busy) }
        } else null
    ) {
        // On opening, back from a dialog over this one, and after a save that didn't go through (the
        // fields were off meanwhile): the cursor in the field it was in, the keyboard up. Once this
        // dialog's window has the focus: until then, a field can't take it.
        val window = LocalWindowInfo.current
        LaunchedEffect(busy) {
            if (busy) return@LaunchedEffect
            withTimeoutOrNull(WINDOW_FOCUS_WAIT_MS) { snapshotFlow { window.isWindowFocused }.first { it } }
            val toAgain = resumeAgain ?: againLast
            resumeAgain = null
            runCatching { (if (toAgain) againFocus else firstFocus).requestFocus() }
            keyboard?.show()
        }
        Text(stringResource(if (changing) R.string.passphrase_change_text else R.string.passphrase_set_text))
        DialogGap(DialogSpacing.Part)
        // The two fields' heights: each kept in view with the other where there's room.
        var firstHeight by remember { mutableIntStateOf(0) }
        var againHeight by remember { mutableIntStateOf(0) }
        val fieldGap = with(LocalDensity.current) { DialogSpacing.Field.roundToPx() }
        InterceptPlatformTextInput(PassphraseHardenedTextInput) {
            Column {
                Column(Modifier.onSizeChanged { firstHeight = it.height }.keepInViewWhileFocused(below = { fieldGap + againHeight })) {
                    OutlinedSecureTextField(
                        state = first,
                        enabled = !busy,
                        label = { Text(stringResource(if (changing) R.string.passphrase_new_field else R.string.passphrase_field)) },
                        trailingIcon = { PassphraseEye(shown, enabled = !busy, onToggle = { shown = !shown }) },
                        isError = firstError != null,
                        inputTransformation = InputTransformation.maxLength(OwnerPassphrase.MAX_LENGTH).then(edited),
                        textObfuscationMode = obfuscation,
                        keyboardOptions = PassphraseKeyboard.copy(imeAction = ImeAction.Next),
                        // On to the second field once the first is long enough; until then, the rule is said.
                        onKeyboardAction = KeyboardActionHandler {
                            if (passphraseLengthOk(first.text.length)) runCatching { againFocus.requestFocus() } else tooShortSaid = true
                        },
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(firstFocus)
                            .onFocusChanged { if (it.isFocused) againLast = false }
                            .semantics {
                                // Not a place for a password manager: it neither fills nor offers to keep it.
                                contentDataType = ContentDataType.None
                                firstError?.let { error(it) }
                            }
                    )
                    // The rule as help; the same words in the error colour once Next or Save came too soon.
                    PassphraseFieldLine(text = firstError ?: rule, isError = firstError != null, announce = firstError != null)
                }
                DialogGap(DialogSpacing.Field)
                Column(Modifier.onSizeChanged { againHeight = it.height }.keepInViewWhileFocused(above = { fieldGap + firstHeight })) {
                    OutlinedSecureTextField(
                        state = again,
                        enabled = !busy,
                        label = { Text(stringResource(R.string.passphrase_again_field)) },
                        isError = mismatch,
                        inputTransformation = InputTransformation.maxLength(OwnerPassphrase.MAX_LENGTH).then(edited),
                        textObfuscationMode = obfuscation,
                        keyboardOptions = PassphraseKeyboard.copy(imeAction = ImeAction.Done),
                        // Done saves when it can; otherwise what's wrong is said, and the keyboard stays.
                        onKeyboardAction = KeyboardActionHandler { save() },
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(againFocus)
                            .onFocusChanged {
                                // Not when it's only the dialog stepping aside for another over it.
                                if (againFocused && !it.isFocused && again.text.isNotEmpty() && !isCovered) againFinished = true
                                againFocused = it.isFocused
                                if (it.isFocused) againLast = true
                            }
                            .semantics {
                                contentDataType = ContentDataType.None
                                if (mismatch) error(noMatch)
                            }
                    )
                    // One line kept even when empty, so the buttons don't move; said politely when it appears.
                    PassphraseFieldLine(text = if (mismatch) noMatch else "", isError = mismatch, announce = mismatch)
                }
            }
        }
    }
}

/** How long a dialog waits for its window to have the focus before putting the cursor in a field anyway. */
private const val WINDOW_FOCUS_WAIT_MS = 500L

/** The keyboard for a passphrase: a password's, never corrected (the hardened input adds no suggestions or learning). */
private val PassphraseKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

/**
 * Removing, after the face or fingerprint check: it says what changes, as it can't be undone. As
 * every dialog in the app that removes something: the icon over a centred title, and the
 * action in the error colour where a dialog's confirming button goes, Cancel beside it.
 */
@Composable
private fun RemovePassphraseDialog(@StringRes text: Int, onRemove: () -> Unit, onDismiss: () -> Unit) {
    KeyboardSafeDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
        title = { PassphraseDialogTitle(stringResource(R.string.passphrase_remove_title)) },
        confirmButton = { DestructiveTextButton(stringResource(R.string.passphrase_remove), onClick = onRemove) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_cancel)) } }
    ) {
        Text(stringResource(text))
    }
}
