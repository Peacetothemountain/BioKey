package com.android.BioKey.feature.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectDialogWindow
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.VaultBackup
import com.android.BioKey.core.security.rejectObscuredTouches
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
 * The backup is sealed and written in its scope too, so leaving Settings meanwhile doesn't stop it.
 */
internal class PendingBackup : ViewModel() {
    var password: CharArray? = null

    override fun onCleared() {
        password?.fill(' ')
        password = null
    }
}

/**
 * Backing up: a password (twice), then where to save the file. Restoring: the file, its
 * password, then a confirmation, since it replaces the current setup. The sealing and opening
 * run off the main thread ([VaultBackup] is slow on purpose).
 *
 * The system file picker covers BioKey, which would end BioKey's session; each launch is
 * announced first ([LockStateTracker.beginOwnPicker]) so the session survives that one trip.
 */
@Composable
internal fun rememberBackupActions(): BackupActions {
    val context = LocalContext.current
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
            BioKeyMessages.show("Backup cancelled, try again", Icons.Default.ErrorOutline)
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
                BioKeyMessages.show("Backup saved", Icons.Default.CheckCircle)
            } else {
                withContext(Dispatchers.IO) { discard(app, uri) }
                BioKeyMessages.show("Couldn't save the backup there", Icons.Default.ErrorOutline)
            }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { read(context, uri) }.getOrNull() }
            if (file == null) {
                BioKeyMessages.show("Couldn't open that file", Icons.Default.ErrorOutline)
            } else {
                error = null
                step = BackupStep.EnterPassword(file)
            }
        }
    }

    when (val current = step) {
        BackupStep.ChoosePassword -> PasswordDialog(
            title = "Back up settings",
            text = "Your locked apps, profiles and settings go into a file protected by this password. You'll need it to restore.",
            confirmLabel = "Choose where to save",
            creating = true,
            busy = false,
            error = null,
            onConfirm = { password ->
                pending.password?.fill(' ')
                pending.password = password
                step = null
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                openPicker { createFile.launch("BioKey backup $date.${VaultBackup.FILE_EXTENSION}") }
                    .onFailure {
                        pending.password?.fill(' ')
                        pending.password = null
                    }
            },
            onDismiss = { step = null }
        )
        is BackupStep.EnterPassword -> PasswordDialog(
            title = "Restore from a backup",
            text = "Enter the password this backup was saved with.",
            confirmLabel = "Open",
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
                        if (e is VaultBackup.WrongPasswordException) {
                            error = "That password doesn't open this backup"
                        } else {
                            step = null
                            BioKeyMessages.show("That file isn't a BioKey backup", Icons.Default.ErrorOutline)
                        }
                    }
                }
            },
            onDismiss = { if (!busy) step = null }
        )
        is BackupStep.ConfirmRestore -> AlertDialog(
            onDismissRequest = { step = null },
            icon = { Icon(Icons.Default.Restore, contentDescription = null) },
            title = { Text("Restore this backup?") },
            text = {
                ProtectDialogWindow()
                Text("Your locked apps, profiles and BioKey settings will be replaced with the backup's.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val restored = runCatching { vault.restoreState(current.state) }
                    step = null
                    if (restored.isSuccess) {
                        // Let auto-sort go through the restored profiles on the next unlock.
                        InstalledAppsCache.autoSortedVersion = -1
                        BioKeyMessages.show("Backup restored", Icons.Default.CheckCircle)
                    } else {
                        BioKeyMessages.show("That backup couldn't be restored", Icons.Default.ErrorOutline)
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { step = null }) { Text("Cancel") } }
        )
        null -> Unit
    }

    return remember {
        BackupActions(
            backUp = { step = BackupStep.ChoosePassword },
            restore = { openPicker { openFile.launch(arrayOf("*/*")) } }
        )
    }
}

/** Opens the system file picker, keeping BioKey's session for the trip. */
private fun openPicker(launch: () -> Unit): Result<Unit> {
    LockStateTracker.beginOwnPicker()
    return runCatching(launch).onFailure {
        // No file picker on this phone (or it's disabled).
        LockStateTracker.onOwnAppResumed()
        BioKeyMessages.show("Couldn't open the file picker", Icons.Default.ErrorOutline)
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
    var again by remember { mutableStateOf("") }
    val longEnough = password.length >= VaultBackup.MIN_PASSWORD_LENGTH
    val matches = !creating || password == again
    val ready = longEnough && matches && !busy
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text(title) },
        text = {
            ProtectDialogWindow()
            Column(Modifier.rejectObscuredTouches(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (creating) ImeAction.Next else ImeAction.Done),
                    supportingText = if (creating) {
                        { Text("At least ${VaultBackup.MIN_PASSWORD_LENGTH} characters") }
                    } else null,
                    isError = error != null,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                if (creating) {
                    OutlinedTextField(
                        value = again,
                        onValueChange = { again = it },
                        label = { Text("Password again") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        isError = again.isNotEmpty() && !matches,
                        supportingText = if (again.isNotEmpty() && !matches) {
                            { Text("The passwords don't match") }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password.toCharArray()) }, enabled = ready) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
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
    val version by DiagnosticLog.version.collectAsState()
    val entries = remember(version) { DiagnosticLog.entries().asReversed() }
    val time = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }
    var confirmDelete by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ProtectDialogWindow()
        EdgeToEdgeDialogBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
                    .rejectObscuredTouches()
            ) {
                TopAppBar(
                    title = { Text("Diagnostic log") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    },
                    actions = {
                        IconButton(onClick = { share(context) }, enabled = entries.isNotEmpty()) {
                            Icon(Icons.Default.Share, contentDescription = "Share the log")
                        }
                        IconButton(onClick = { confirmDelete = true }, enabled = entries.isNotEmpty()) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete the log")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
                Text(
                    text = "BioKey's lock decisions about your locked apps over the last 3 days, kept on this phone. Nothing about other apps. Share it with whoever is helping you with a problem; it lists your locked apps by package name.",
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
                                text = if (DiagnosticLog.isEnabled()) "Nothing recorded yet" else "The diagnostic log is off",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(entries) { entry ->
                        Column {
                            Text(time.format(Date(entry.at)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(entry.text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete the diagnostic log?") },
            text = {
                ProtectDialogWindow()
                Text("Everything recorded so far is deleted. New entries are still kept while the log is on.")
            },
            confirmButton = {
                TextButton(onClick = {
                    DiagnosticLog.clear()
                    confirmDelete = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

private fun share(context: Context) {
    val report = DiagnosticLog.report(context, ProtectionEngine.mode(context).name.lowercase())
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "BioKey diagnostic log")
        .putExtra(Intent.EXTRA_TEXT, report)
    runCatching { context.startActivity(Intent.createChooser(send, "Share diagnostic log")) }
        .onFailure { BioKeyMessages.show("Couldn't open sharing", Icons.Default.ErrorOutline) }
}
