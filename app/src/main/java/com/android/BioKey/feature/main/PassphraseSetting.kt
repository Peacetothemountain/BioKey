package com.android.BioKey.feature.main

import android.app.Activity
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.LocalAutofillHighlightBrush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.BioKey.R
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.ui.security.ProtectDialogWindow
import com.android.BioKey.ui.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.BioKeyMessages
import kotlinx.coroutines.Dispatchers
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
    private var state = PassphraseRowState.HIDDEN

    /** The row, or null where it isn't shown. Each way in starts with a face or fingerprint check. */
    @Composable
    fun row(biometricOnly: Boolean, strongBiometric: Boolean): PassphraseRow? {
        state = passphraseRowState(isSet, biometricOnly, strongBiometric)
        val subtitle = state.subtitle ?: return null
        val res = LocalResources.current
        return PassphraseRow(stringResource(R.string.passphrase_title), stringResource(subtitle)) {
            val (prompt, next) = when {
                !state.isSet -> R.string.passphrase_confirm_set to PassphraseDialog.SET
                passphraseCanBeSet(state, strongBiometric) -> R.string.passphrase_confirm_change to PassphraseDialog.CHANGE
                else -> R.string.passphrase_confirm_remove to PassphraseDialog.REMOVE
            }
            confirmOwner(activity, res.getString(prompt)) { dialog = next }
        }
    }

    @Composable
    fun Dialogs() {
        val res = LocalResources.current
        when (val showing = dialog) {
            PassphraseDialog.SET, PassphraseDialog.CHANGE -> PassphraseEntryDialog(
                changing = showing == PassphraseDialog.CHANGE,
                onSaved = {
                    isSet = true
                    dialog = null
                    BioKeyMessages.show(
                        res.getString(if (showing == PassphraseDialog.CHANGE) R.string.passphrase_changed else R.string.passphrase_saved),
                        Icons.Default.CheckCircle
                    )
                },
                onRemove = { dialog = PassphraseDialog.REMOVE },
                onDismiss = { dialog = null }
            )
            PassphraseDialog.REMOVE -> RemovePassphraseDialog(
                text = when (state) {
                    PassphraseRowState.ON -> R.string.passphrase_remove_text_in_use
                    // The screen lock is what opens them then, as before there was a passphrase.
                    PassphraseRowState.ON_NO_BIOMETRIC -> R.string.passphrase_remove_text_no_biometric
                    else -> R.string.passphrase_remove_text_unused
                },
                onRemove = {
                    dialog = null
                    val app = activity?.applicationContext ?: return@RemovePassphraseDialog
                    OwnerPassphrase.clear(app)
                    isSet = false
                    // Nothing to undo with: the passphrase itself was never kept.
                    BioKeyMessages.show(res.getString(R.string.passphrase_removed), Icons.Default.Delete)
                },
                onDismiss = { dialog = null }
            )
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

/**
 * A new passphrase, typed once: a sibling of the backup's password dialog (a pill, autofill's
 * highlight clear, an eye to read back what was typed since nothing asks for it twice). Unlike a
 * backup's password it is never handed to a password manager: it is what opens locked apps when
 * nothing else can, the password manager maybe among them, and BioKey says it never leaves it.
 * What's typed lives only in [remember]: never in saved state.
 */
@Composable
private fun PassphraseEntryDialog(changing: Boolean, onSaved: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var passphrase by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    /** Said with the field: too short, after Done was pressed on it, or it couldn't be saved. */
    var error by remember { mutableStateOf<String?>(null) }
    val tooShort = pluralStringResource(R.plurals.passphrase_min_length, OwnerPassphrase.MIN_LENGTH, OwnerPassphrase.MIN_LENGTH)
    val notSaved = stringResource(R.string.passphrase_not_saved)
    val ready = passphraseLengthOk(passphrase.length) && !busy
    val save = save@{
        if (busy) return@save
        if (!passphraseLengthOk(passphrase.length)) {
            error = tooShort
            return@save
        }
        busy = true
        error = null
        val chars = passphrase.toCharArray()
        val app = context.applicationContext
        // In BioKey's own scope: once started, it's finished and said even if the page closes.
        appScope.launch {
            val saved = withContext(Dispatchers.Default) {
                // Zeroed here, where it's used: a cancelled caller mustn't wipe it mid-save.
                try { runCatching { OwnerPassphrase.set(app, chars) }.getOrDefault(false) } finally { chars.fill('\u0000') }
            }
            busy = false
            if (saved) {
                onSaved()
            } else {
                error = notSaved
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Key, contentDescription = null) },
        title = { Text(stringResource(if (changing) R.string.passphrase_change_title else R.string.passphrase_set_title)) },
        text = {
            ProtectDialogWindow()
            Column(Modifier.rejectObscuredTouches(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (changing) R.string.passphrase_change_text else R.string.passphrase_set_text))
                CompositionLocalProvider(LocalAutofillHighlightBrush provides SolidColor(Color.Transparent)) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = {
                            // Typing counts as using BioKey: the keyboard's input never passes through
                            // its own touch handling, so a long passphrase could hit the idle lock.
                            LockStateTracker.onOwnAppInteraction()
                            if (it.length <= OwnerPassphrase.MAX_LENGTH) {
                                passphrase = it
                                // A "too short" goes once it's long enough, a failed save once it's edited.
                                if (error != null && (error != tooShort || passphraseLengthOk(it.length))) error = null
                            }
                        },
                        label = { Text(stringResource(if (changing) R.string.passphrase_new_field else R.string.passphrase_field)) },
                        singleLine = true,
                        shape = CircleShape,
                        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { shown = !shown }) {
                                Icon(
                                    if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = stringResource(if (shown) R.string.passphrase_hide else R.string.passphrase_show)
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        // The rule as help, the same words in error colours only once Done was pressed
                        // too soon. Belongs to the field, so screen readers say it with the field.
                        supportingText = { Text(error ?: tooShort) },
                        isError = error != null,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { error?.let { error(it) } }
                    )
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = ready) { Text(stringResource(R.string.passphrase_save)) }
        },
        dismissButton = {
            Row {
                // Changing is where an existing one is managed: removing is beside it.
                if (changing) {
                    TextButton(onClick = onRemove, enabled = !busy) {
                        Text(stringResource(R.string.passphrase_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.prompt_cancel)) }
            }
        }
    )
}

/** Removing, after the face or fingerprint check: it says what changes, as it can't be undone. */
@Composable
private fun RemovePassphraseDialog(@StringRes text: Int, onRemove: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
        title = { Text(stringResource(R.string.passphrase_remove_title)) },
        text = {
            ProtectDialogWindow()
            Text(
                stringResource(text),
                Modifier.rejectObscuredTouches()
            )
        },
        confirmButton = {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.passphrase_remove), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_cancel)) } }
    )
}
