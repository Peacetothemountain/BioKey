package com.android.BioKey.feature.main

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.LocalAutofillHighlightBrush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.android.BioKey.R
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectDialogWindow
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.core.security.VaultBackup
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.BioKeyMessageHost
import com.android.BioKey.ui.theme.BioKeyMessages
import com.android.BioKey.ui.theme.GroupInset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Where Settings' backup flow is: choosing a password, opening a backup, confirming a restore. */
private sealed interface BackupStep {
    data object ChoosePassword : BackupStep
    class EnterPassword(val file: ByteArray) : BackupStep
    class ConfirmRestore(val state: JSONObject) : BackupStep
}

/** Settings' "Back up" and "Restore" actions, with their dialogs. */
internal class BackupActions(val backUp: () -> Unit, val restore: () -> Unit)

/**
 * The password chosen for a backup, held while the user picks where to save it, so a rotation or
 * a theme change meanwhile doesn't lose it. Never saved; wiped if BioKey's screen goes for good.
 * The backup is sealed and written in its scope too, and a confirmed restore applied there, so
 * leaving Settings meanwhile doesn't stop either.
 */
internal class PendingBackup : ViewModel() {
    var password: CharArray? = null

    override fun onCleared() {
        password?.fill(' ')
        password = null
    }
}

/**
 * Backing up: a password, then where to save the file. Restoring: the file, its
 * password, then a confirmation, since it replaces the current setup. The sealing, opening and
 * restoring run off the main thread ([VaultBackup] is slow on purpose).
 *
 * The system file picker covers BioKey, which would end BioKey's session; each launch is
 * announced first ([LockStateTracker.beginOwnPicker]) so the session survives that one trip.
 */
@Composable
internal fun rememberBackupActions(
    /** Turns the notification shield on (with its explanation), from the restore's message. */
    onTurnOnShield: () -> Unit = {}
): BackupActions {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val vault = remember { SecurityVault.getInstance(context) }
    val activity = LocalActivity.current as ComponentActivity
    val pending = remember(activity) { ViewModelProvider(activity)[PendingBackup::class.java] }
    var step by remember { mutableStateOf<BackupStep?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = pending.password
        pending.password = null
        if (uri == null) {
            password?.fill(' ')
            return@rememberLauncherForActivityResult
        }
        val app = context.applicationContext
        if (password == null) {
            // The picker created the file, but the password is gone (BioKey was closed meanwhile).
            pending.viewModelScope.launch(Dispatchers.IO) { discard(app, uri) }
            BioKeyMessages.show(res.getString(R.string.backup_cancelled), Icons.Default.ErrorOutline)
            return@rememberLauncherForActivityResult
        }
        pending.viewModelScope.launch {
            val saved = try {
                val file = withContext(Dispatchers.Default) { VaultBackup.seal(vault.exportState(), password) }
                withContext(Dispatchers.IO) { write(app, uri, file) }
                true
            } catch (e: CancellationException) {
                // BioKey's screen closed for good meanwhile: leave no empty file behind.
                withContext(NonCancellable + Dispatchers.IO) { discard(app, uri) }
                throw e
            } catch (_: Exception) {
                false
            } finally {
                password.fill(' ')
            }
            if (saved) {
                DiagnosticLog.log("Settings backed up")
                BioKeyMessages.show(res.getString(R.string.backup_saved), Icons.Default.CheckCircle)
            } else {
                withContext(Dispatchers.IO) { discard(app, uri) }
                BioKeyMessages.show(res.getString(R.string.backup_save_failed), Icons.Default.ErrorOutline)
            }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { read(context, uri) }.getOrNull() }
            if (file == null) {
                BioKeyMessages.show(res.getString(R.string.backup_open_file_failed), Icons.Default.ErrorOutline)
            } else {
                error = null
                step = BackupStep.EnterPassword(file)
            }
        }
    }

    when (val current = step) {
        BackupStep.ChoosePassword -> PasswordDialog(
            title = stringResource(R.string.backup_title),
            text = stringResource(R.string.backup_text),
            confirmLabel = stringResource(R.string.backup_choose_where),
            creating = true,
            busy = false,
            error = null,
            onConfirm = { password ->
                pending.password?.fill(' ')
                pending.password = password
                step = null
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                openPicker(res) { createFile.launch("BioKey backup $date.${VaultBackup.FILE_EXTENSION}") }
                    .onFailure {
                        pending.password?.fill(' ')
                        pending.password = null
                    }
            },
            onDismiss = { step = null }
        )
        is BackupStep.EnterPassword -> PasswordDialog(
            title = stringResource(R.string.backup_restore_title),
            text = stringResource(R.string.backup_restore_text),
            confirmLabel = stringResource(R.string.backup_restore_open),
            creating = false,
            busy = busy,
            error = error,
            onConfirm = { password ->
                busy = true
                scope.launch {
                    val opened = try {
                        Result.success(withContext(Dispatchers.Default) { VaultBackup.open(current.file, password) })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    } finally {
                        password.fill(' ')
                    }
                    busy = false
                    opened.onSuccess { step = BackupStep.ConfirmRestore(it) }
                    opened.onFailure { e ->
                        when (e) {
                            is VaultBackup.WrongPasswordException -> error = res.getString(R.string.backup_wrong_password)
                            is VaultBackup.UnsupportedVersionException -> {
                                step = null
                                BioKeyMessages.show(res.getString(R.string.backup_newer_version), Icons.Default.ErrorOutline)
                            }
                            else -> {
                                step = null
                                BioKeyMessages.show(res.getString(R.string.backup_not_a_backup), Icons.Default.ErrorOutline)
                            }
                        }
                    }
                }
            },
            onDismiss = { if (!busy) step = null }
        )
        is BackupStep.ConfirmRestore -> AlertDialog(
            onDismissRequest = { step = null },
            icon = { Icon(Icons.Default.Restore, contentDescription = null) },
            title = { Text(stringResource(R.string.backup_confirm_restore_title)) },
            text = {
                ProtectDialogWindow()
                Text(stringResource(R.string.backup_confirm_restore_text))
            },
            confirmButton = {
                TextButton(onClick = {
                    // Closed first, so a second tap can't restore twice.
                    step = null
                    // A restore replaces every lock and setting at once: the owner's to do, with a face or
                    // fingerprint (the screen lock where none is set up).
                    val restore = {
                    // Off the main thread (restoring sweeps notifications), and in the backup's
                    // scope, so leaving Settings meanwhile doesn't stop it halfway to its message.
                    pending.viewModelScope.launch {
                        // Checked again as it runs: a face or fingerprint change recorded meanwhile refuses it.
                        val app = activity.applicationContext
                        if (withContext(Dispatchers.Default) { UnlockAttempts.holding(app) }) {
                            BioKeyMessages.show(res.getString(R.string.held_refused, attemptTime(app, UnlockAttempts.heldUntil(app) ?: 0L)), Icons.Default.ErrorOutline)
                            return@launch
                        }
                        val restored = try {
                            withContext(Dispatchers.Default) { vault.restoreState(current.state) }
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        if (restored) {
                            // Let auto-sort go through the restored profiles on the next unlock.
                            InstalledAppsCache.autoSortedVersion = -1
                            // The shield waits for its explanation on this phone: say so, or the owner
                            // would think locked apps' notifications are hidden.
                            if (current.state.optBoolean("notificationShield", false) && !vault.isNotificationShieldEnabled()) {
                                BioKeyMessages.show(
                                    res.getString(R.string.backup_restored_turn_on_shield), Icons.Default.CheckCircle,
                                    actionLabel = res.getString(R.string.common_turn_on), onAction = onTurnOnShield
                                )
                            } else {
                                BioKeyMessages.show(res.getString(R.string.backup_restored), Icons.Default.CheckCircle)
                            }
                        } else {
                            BioKeyMessages.show(res.getString(R.string.backup_restore_failed), Icons.Default.ErrorOutline)
                        }
                    }
                    }
                    confirmOwner(activity, res.getString(R.string.backup_confirm_restore_prompt), silencesRecords = true) { restore() }
                }) { Text(stringResource(R.string.backup_restore)) }
            },
            dismissButton = { TextButton(onClick = { step = null }) { Text(stringResource(R.string.prompt_cancel)) } }
        )
        null -> Unit
    }

    return remember {
        BackupActions(
            backUp = { step = BackupStep.ChoosePassword },
            restore = { openPicker(res) { openFile.launch(arrayOf("*/*")) } }
        )
    }
}

/** Opens the system file picker, keeping BioKey's session for the trip. */
private fun openPicker(res: Resources, launch: () -> Unit): Result<Unit> {
    LockStateTracker.beginOwnPicker()
    return runCatching(launch).onFailure {
        // No file picker on this phone (or it's disabled).
        LockStateTracker.onOwnAppResumed()
        BioKeyMessages.show(res.getString(R.string.backup_no_file_picker), Icons.Default.ErrorOutline)
    }
}

/** Deletes the file the picker created for a backup that wasn't written. */
private fun discard(context: Context, uri: Uri) {
    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
}

private fun write(context: Context, uri: Uri, bytes: ByteArray) {
    val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("No output stream")
    out.use { it.write(bytes) }
}

private fun read(context: Context, uri: Uri): ByteArray {
    val input = context.contentResolver.openInputStream(uri) ?: error("No input stream")
    // A backup is a few kilobytes: anything far bigger isn't one.
    return input.use { stream -> stream.readNBytesCompat(1 shl 20) }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val n = read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/**
 * A password, once: backing up ([creating]) or opening a backup. The field tells an autofill
 * service which it is (a new password, or the one a backup was saved with), and a new one is
 * handed to it on Confirm, so a password manager can offer to keep it and fill it in at restore.
 * The field is a pill, and autofill's highlight is clear, so a filled field looks like any other;
 * its eye shows what was typed, as nothing asks for it twice.
 */
@Composable
private fun PasswordDialog(
    title: String,
    text: String,
    confirmLabel: String,
    creating: Boolean,
    busy: Boolean,
    error: String?,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    // Shown on request: with the password asked only once, a typo is caught by reading it.
    var shown by remember { mutableStateOf(false) }
    val ready = password.length >= VaultBackup.MIN_PASSWORD_LENGTH && !busy
    val autofill = LocalAutofillManager.current
    val confirm = {
        if (ready) {
            // The password manager offers to save a new password once the form is done with.
            if (creating) autofill?.commit()
            onConfirm(password.toCharArray())
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text(title) },
        text = {
            ProtectDialogWindow()
            Column(Modifier.rejectObscuredTouches(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text)
                CompositionLocalProvider(LocalAutofillHighlightBrush provides SolidColor(Color.Transparent)) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            // Typing counts as using BioKey: the soft keyboard's input never passes
                            // through its own touch handling, so a long password could hit the idle lock.
                            LockStateTracker.onOwnAppInteraction()
                            password = it
                        },
                        label = { Text(stringResource(R.string.backup_password)) },
                        singleLine = true,
                        shape = CircleShape,
                        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { shown = !shown }) {
                                Icon(
                                    if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = stringResource(if (shown) R.string.backup_hide_password else R.string.backup_show_password)
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                        // The error belongs to the field, so screen readers say it with the field.
                        supportingText = when {
                            error != null -> { { Text(error) } }
                            creating -> { { Text(pluralStringResource(R.plurals.backup_password_min_length, VaultBackup.MIN_PASSWORD_LENGTH, VaultBackup.MIN_PASSWORD_LENGTH)) } }
                            else -> null
                        },
                        isError = error != null,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentType = if (creating) ContentType.NewPassword else ContentType.Password
                                if (error != null) error(error)
                            }
                    )
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = ready) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.prompt_cancel)) } }
    )
}

/**
 * The diagnostic log, newest first, as a full-screen page (like the app picker), with Share
 * (Android's share sheet, so the user picks where it goes) and Delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticLogScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version by DiagnosticLog.version.collectAsStateWithLifecycle()
    val entries = remember(version) { DiagnosticLog.entries().asReversed() }
    // Each row's key: its entry's time, numbered among any logged in the same millisecond, oldest
    // first, so new entries leave the keys of the rows below them as they were.
    val keys = remember(entries) {
        val seen = HashMap<Long, Int>()
        entries.asReversed().map { "${it.at}/${seen.merge(it.at, 1, Int::plus)}" }.asReversed()
    }
    val time = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val logActivity = LocalActivity.current
    val res = LocalResources.current

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ProtectDialogWindow()
        EdgeToEdgeDialogBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .imePadding()
                        .rejectObscuredTouches()
                ) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.diagnostics_title)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close)) }
                        },
                        actions = {
                            IconButton(onClick = { share(context) }, enabled = entries.isNotEmpty()) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.diagnostics_share))
                            }
                            IconButton(onClick = { confirmDelete = true }, enabled = entries.isNotEmpty()) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.diagnostics_delete))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                    Text(
                        text = stringResource(R.string.diagnostics_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = GroupInset + 8.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = GroupInset + 8.dp, end = GroupInset + 8.dp, top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (entries.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(if (DiagnosticLog.isEnabled()) R.string.diagnostics_empty else R.string.diagnostics_off),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        itemsIndexed(entries, key = { i, _ -> keys[i] }) { _, entry ->
                            // Its time and what happened, read as one.
                            Column(Modifier.semantics(mergeDescendants = true) { }) {
                                Text(time.format(Date(entry.at)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(entry.text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                            }
                        }
                    }
                }
                // Share's "Couldn't open sharing" shows here: the screen's own bar is behind this page.
                BioKeyMessageHost(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.diagnostics_delete_title)) },
            text = {
                ProtectDialogWindow()
                Text(stringResource(R.string.diagnostics_delete_text))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    // It holds who unlocked what, and when: the owner's to delete.
                    confirmOwner(logActivity, res.getString(R.string.diagnostics_delete_prompt), silencesRecords = true) { DiagnosticLog.clear() }
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.prompt_cancel)) } }
        )
    }
}

private fun share(context: Context) {
    val report = DiagnosticLog.report(context, ProtectionEngine.mode(context).name.lowercase())
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostics_share_subject))
        .putExtra(Intent.EXTRA_TEXT, report)
    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.diagnostics_share_chooser))) }
        .onFailure { BioKeyMessages.show(context.getString(R.string.diagnostics_share_failed), Icons.Default.ErrorOutline) }
}
