package com.android.BioKey.feature.main

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateFormat
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.LocalAutofillHighlightBrush
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.then
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.android.BioKey.R
import com.android.BioKey.core.data.AppText
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.core.security.VaultBackup
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.ui.security.ProtectDialogWindow
import com.android.BioKey.ui.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.BioKeyMessageHost
import com.android.BioKey.ui.theme.BioKeyMessages
import com.android.BioKey.ui.theme.GroupInset
import com.android.BioKey.ui.theme.DialogGap
import com.android.BioKey.ui.theme.DialogSpacing
import com.android.BioKey.ui.theme.KeyboardSafeDialog
import com.android.BioKey.ui.theme.keepInViewWhileFocused
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.drop
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

    /** A restore left "Face or fingerprint only" on with no passphrase: said once, wherever Settings is then. */
    var passphraseNotice by mutableStateOf(false)

    init {
        // BioKey locking itself (a trip that ran out, the screen off, "Re-lock now") takes the
        // password with it: it doesn't wait in memory behind the lock for a picker to answer.
        viewModelScope.launch { LockStateTracker.ownLocks.drop(1).collect { wipe() } }
    }

    private fun wipe() {
        password?.fill(' ')
        password = null
    }

    override fun onCleared() = wipe()

    /** The picker's answer: where to save ([uri]), or null if it was backed out of. */
    fun created(app: Context, uri: Uri?) {
        val password = password
        this.password = null
        if (uri == null) {
            password?.fill(' ')
            return
        }
        // BioKey locked itself while the picker was up (the trip ran out, a "Re-lock now"): whoever is
        // choosing where to save now isn't known to be the owner, so nothing is written. The
        // password went with the lock ([wipe]); checked as well, in case the answer came first.
        if (password == null || !LockStateTracker.isOwnAppUnlocked()) {
            password?.fill(' ')
            viewModelScope.launch(Dispatchers.IO) { discard(app, uri) }
            BioKeyMessages.show(app.getString(R.string.backup_cancelled), Icons.Default.ErrorOutline)
            return
        }
        val vault = SecurityVault.getInstance(app)
        viewModelScope.launch {
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
                BioKeyMessages.show(app.getString(R.string.backup_saved), Icons.Default.CheckCircle)
            } else {
                withContext(Dispatchers.IO) { discard(app, uri) }
                BioKeyMessages.show(app.getString(R.string.backup_save_failed), Icons.Default.ErrorOutline)
            }
        }
    }

    companion object {
        /** The one name the file picker's answer is registered under. */
        const val CREATE_KEY = "biokey_backup_create"
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
internal fun rememberBackupActions(): BackupActions {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val vault = remember { SecurityVault.getInstance(context) }
    val activity = LocalActivity.current as ComponentActivity
    val pending = remember(activity) { ViewModelProvider(activity)[PendingBackup::class.java] }
    var step by remember { mutableStateOf<BackupStep?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Registered with the activity under one fixed name, and left registered: the answer comes
    // even if Settings has gone from the screen meanwhile (BioKey locked itself while the picker
    // was up), so the file the picker made is never left behind empty and the owner is told.
    val createFile = remember(activity) {
        val app = context.applicationContext
        activity.activityResultRegistry.register(PendingBackup.CREATE_KEY, ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            pending.created(app, uri)
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
                error = null
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
            onDismiss = { if (!busy) step = null },
            // A wrong password's message goes as soon as another is typed.
            onEdited = { error = null }
        )
        is BackupStep.ConfirmRestore -> KeyboardSafeDialog(
            onDismissRequest = { step = null },
            icon = { Icon(Icons.Default.Unarchive, contentDescription = null) },
            title = { PassphraseDialogTitle(stringResource(R.string.backup_confirm_restore_title)) },
            confirmButton = {
                TextButton(onClick = {
                    // Closed first, so a second tap can't restore twice.
                    step = null
                    // A restore replaces every lock and setting at once: the owner's to do, with a face or
                    // fingerprint (the screen lock where none is set up).
                    val restore = {
                    // Off the main thread (restoring sweeps notifications), and in the backup's
                    // scope, so leaving Settings meanwhile doesn't stop it halfway to its message.
                    val app = activity.applicationContext
                    pending.viewModelScope.launch {
                        // Checked again as it runs: a face or fingerprint change recorded meanwhile refuses it.
                        if (withContext(Dispatchers.Default) { UnlockAttempts.holding(app) }) {
                            BioKeyMessages.show(heldRefused(app, UnlockAttempts.heldUntil(app) ?: 0L), Icons.Default.ErrorOutline)
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
                            BioKeyMessages.show(res.getString(R.string.backup_restored), Icons.Default.CheckCircle)
                            // A backup never holds a passphrase: if it turned on "Face or fingerprint
                            // only" with none set here, the screen lock is the fallback, and the owner is told.
                            pending.passphraseNotice = withContext(Dispatchers.Default) {
                                needsPassphraseNotice(
                                    biometricOnly = vault.biometricOnlyFlow.value,
                                    passphraseSet = OwnerPassphrase.isSet(app),
                                    strongBiometric = SecurityEngine.hasStrongBiometric(app)
                                )
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
        ) {
            Text(stringResource(R.string.backup_confirm_restore_text))
        }
        null -> Unit
    }

    var settingPassphrase by remember { mutableStateOf(false) }
    if (pending.passphraseNotice) {
        KeyboardSafeDialog(
            onDismissRequest = { pending.passphraseNotice = false },
            icon = { Icon(Icons.Default.Key, contentDescription = null) },
            title = { PassphraseDialogTitle(stringResource(R.string.backup_restored_no_passphrase_title)) },
            confirmButton = {
                TextButton(onClick = {
                    // Said once, whichever way it's answered.
                    pending.passphraseNotice = false
                    // As Settings' own "Passphrase" row: a face or fingerprint first, and not within
                    // a day of a face or fingerprint change or a reset.
                    confirmOwner(activity, res.getString(R.string.passphrase_set_title), silencesRecords = true) { settingPassphrase = true }
                }) { Text(stringResource(R.string.passphrase_set_title)) }
            },
            dismissButton = {
                TextButton(onClick = { pending.passphraseNotice = false }) { Text(stringResource(R.string.common_not_now)) }
            }
        ) {
            Text(stringResource(R.string.backup_restored_no_passphrase_text))
        }
    }
    if (settingPassphrase) {
        PassphraseEntryDialog(
            changing = false,
            onSaved = {
                settingPassphrase = false
                BioKeyMessages.show(res.getString(R.string.passphrase_saved), Icons.Default.CheckCircle)
            },
            onRemove = { settingPassphrase = false },
            onDismiss = { settingPassphrase = false }
        )
    }

    return remember {
        BackupActions(
            backUp = { step = BackupStep.ChoosePassword },
            restore = { beginRestore(context) { openPicker(res) { openFile.launch(arrayOf("*/*")) } } }
        )
    }
}

/**
 * Whether, after a restore, the owner is told that "Face or fingerprint only" is on with no
 * passphrase to stand in for it (backups never hold one), so the screen lock is the fallback. Only
 * where a passphrase can be set now: that takes a strong face or fingerprint.
 */
internal fun needsPassphraseNotice(biometricOnly: Boolean, passphraseSet: Boolean, strongBiometric: Boolean): Boolean =
    biometricOnly && !passphraseSet && strongBiometric

/**
 * A restore's first step. Within the day's hold after a face or fingerprint change, or BioKey
 * reset ([UnlockAttempts.heldUntil]), it's refused at once, in the words the last step would use,
 * before a file is chosen or a password typed; otherwise [openPicker]. Returns whether it went on.
 * The last step checks again: a hold can start while the dialogs are up.
 */
internal fun beginRestore(context: Context, openPicker: () -> Unit): Boolean {
    val until = UnlockAttempts.heldUntil(context)
    if (until != null) {
        BioKeyMessages.show(heldRefused(context, until), Icons.Default.ErrorOutline)
        return false
    }
    openPicker()
    return true
}

/** Opens the system file picker, keeping BioKey's session for the trip. */
private fun openPicker(res: Resources, launch: () -> Unit): Result<Unit> {
    LockStateTracker.beginOwnPicker(ForegroundRules.DOCUMENTS_UI)
    return runCatching(launch).onFailure {
        // No file picker on this phone (or it's disabled).
        LockStateTracker.onOwnAppResumed()
        BioKeyMessages.show(res.getString(R.string.backup_no_file_picker), Icons.Default.ErrorOutline)
    }
}

/** Deletes the file the picker created for a backup that wasn't written. */
private fun discard(context: Context, uri: Uri) {
    if (runCatching { validateBackupUri(context, uri) }.isFailure) return
    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
}

/**
 * Rejects non-`content://` URIs and any provider authority owned by BioKey itself, so a spoofed
 * picker result cannot point backup reads or writes at BioKey's own files.
 */
internal fun validateBackupUri(context: Context, uri: Uri) {
    require(uri.scheme == android.content.ContentResolver.SCHEME_CONTENT) { "Backup URI must use content://" }
    val authority = uri.authority?.lowercase(Locale.ROOT).orEmpty()
    val ownPkg = context.packageName.lowercase(Locale.ROOT)
    require(authority.isNotEmpty() && authority != ownPkg && !authority.startsWith("$ownPkg.")) {
        "Refusing internal backup URI authority"
    }
}

/**
 * Rejects file descriptors owned by BioKey's own UID or resolving inside BioKey's private data
 * directories (confused-deputy symlink/FD defense). [fdNumber] is the descriptor's number where
 * the caller has it ([android.os.ParcelFileDescriptor.getFd]): what it leads to is looked up by it.
 */
internal fun checkSafeExternalFd(
    context: Context,
    fd: java.io.FileDescriptor,
    myUid: Int = android.os.Process.myUid(),
    fdNumber: Int? = null
) {
    val stat = runCatching { android.system.Os.fstat(fd) }.getOrNull()
    if (stat != null && myUid >= android.os.Process.FIRST_APPLICATION_UID) {
        check(stat.st_uid != myUid) { "Refusing file descriptor owned by BioKey" }
    }
    val linkTarget = fdNumber?.let { number ->
        runCatching { java.io.File(android.system.Os.readlink("/proc/self/fd/$number")).canonicalPath }.getOrNull()
    }
    if (linkTarget != null) {
        val ceDir = runCatching { context.dataDir.canonicalPath }.getOrNull()
        val deDir = runCatching { context.createDeviceProtectedStorageContext().dataDir.canonicalPath }.getOrNull()
        for (dir in listOfNotNull(ceDir, deDir)) {
            check(linkTarget != dir && !linkTarget.startsWith("$dir${java.io.File.separator}")) {
                "Refusing file descriptor inside BioKey private storage"
            }
        }
    }
}

internal fun write(context: Context, uri: Uri, bytes: ByteArray) {
    validateBackupUri(context, uri)
    // Some providers (cloud drives) refuse "wt"; the picker has just created the file, so "w" is
    // as good there.
    val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "wt") }.getOrNull()
        ?: context.contentResolver.openFileDescriptor(uri, "w") ?: error("No output file descriptor")
    pfd.use {
        checkSafeExternalFd(context, it.fileDescriptor, fdNumber = it.fd)
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(it).use { out -> out.write(bytes) }
    }
}

internal fun read(context: Context, uri: Uri): ByteArray {
    validateBackupUri(context, uri)
    val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("No input file descriptor")
    return pfd.use {
        checkSafeExternalFd(context, it.fileDescriptor, fdNumber = it.fd)
        // Read up to MAX_BACKUP_BYTES + 1 so oversized files fail fast as NotABackupException
        // before running PBKDF2, rather than truncating to 1 MiB and failing as wrong password.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream ->
            stream.readNBytesCompat(VaultBackup.MAX_BACKUP_BYTES + 1)
        }
    }
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

/** Whether a new backup password can be used: an allowed length, typed the same both times. */
internal fun backupPasswordReady(first: CharSequence, again: CharSequence): Boolean =
    first.length in VaultBackup.MIN_PASSWORD_LENGTH..VaultBackup.MAX_PASSWORD_LENGTH && first.contentEquals(again)

/**
 * Whether "Type it again" says it doesn't match, as the passphrase dialog's does: not while it's
 * still being typed and could yet match (it's the start of the first), only once it can't, or
 * once it's [finished] (Done pressed on it, or it was left with something in it).
 */
internal fun backupPasswordMismatch(first: CharSequence, again: CharSequence, finished: Boolean): Boolean = when {
    again.isEmpty() || first.contentEquals(again) -> false
    finished -> true
    else -> again.length >= first.length || !first.startsWith(again)
}

/**
 * A backup's password: a new one, typed twice ([creating]: one typo would make the backup
 * impossible to open, ever), or the one a backup was saved with, typed once. Looks and behaves as
 * the passphrase dialogs do (a pill, one eye that shows both fields, the help or the error on the
 * line under each field, Next then Done), and treats what's typed as they do: the keyboard is
 * told not to suggest or learn it, it lives only in the fields' state, and it's overwritten when
 * the dialog goes. Unlike a passphrase, it's for a password manager: the fields say which kind of
 * password they hold, and a new one is handed over on confirming, so it can be kept and filled in
 * at restore. [onEdited]: something was typed (a wrong-password [error] can go).
 */
@Composable
internal fun PasswordDialog(
    title: String,
    text: String,
    confirmLabel: String,
    creating: Boolean,
    busy: Boolean,
    error: String?,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    onEdited: () -> Unit = {}
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val autofill = LocalAutofillManager.current
    val first = remember { TextFieldState() }
    val again = remember { TextFieldState() }
    val firstFocus = remember { FocusRequester() }
    val againFocus = remember { FocusRequester() }
    /** One eye for both fields: read back together, they're compared at a glance. */
    var shown by remember { mutableStateOf(false) }
    /** Next or the button came with the first too short: the rule turns into an error until it's long enough. */
    var tooShortSaid by remember { mutableStateOf(false) }
    var againFinished by remember { mutableStateOf(false) }
    var againFocused by remember { mutableStateOf(false) }
    /** Which field had the cursor last, for it to go back there once a check is over. */
    var againLast by remember { mutableStateOf(false) }
    val currentOnEdited by rememberUpdatedState(onEdited)

    val rule = pluralStringResource(R.plurals.backup_password_min_length, VaultBackup.MIN_PASSWORD_LENGTH, VaultBackup.MIN_PASSWORD_LENGTH)
    val noMatch = stringResource(R.string.backup_password_no_match)
    val lengthOk = first.text.length in VaultBackup.MIN_PASSWORD_LENGTH..VaultBackup.MAX_PASSWORD_LENGTH
    val firstError = when {
        error != null -> error
        creating && tooShortSaid && !lengthOk -> rule
        else -> null
    }
    val mismatch = creating && backupPasswordMismatch(first.text, again.text, againFinished)
    val ready = !busy && if (creating) backupPasswordReady(first.text, again.text) else lengthOk
    val edited = remember { PassphraseCountsAsUse.then(InputTransformation { currentOnEdited() }) }
    val obfuscation = if (shown) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped

    fun confirm() {
        if (busy) return
        if (creating && !lengthOk) {
            tooShortSaid = true
            runCatching { firstFocus.requestFocus() }
            return
        }
        if (creating && !first.text.contentEquals(again.text)) {
            againFinished = true
            return
        }
        if (!ready) return
        // The password manager offers to save a new password once the form is done with.
        if (creating) autofill?.commit()
        val typed = first.text
        // Straight into the array: no String of it is made here.
        onConfirm(CharArray(typed.length) { typed[it] })
    }

    LaunchedEffect(lengthOk) { if (lengthOk) tooShortSaid = false }
    // "Type it again" emptied: it's typed afresh, and given the same patience as the first time.
    LaunchedEffect(again) { snapshotFlow { again.text.isEmpty() }.collect { if (it) againFinished = false } }
    // On opening, and once a check is over (a wrong password: the fields were off meanwhile, and
    // nothing else may look focused): the cursor back in its field, the keyboard up.
    LaunchedEffect(busy) {
        if (busy) return@LaunchedEffect
        runCatching { (if (againLast) againFocus else firstFocus).requestFocus() }
        keyboard?.show()
    }
    // However the dialog goes (confirmed, cancelled, the page leaving), what was typed goes with it.
    DisposableEffect(Unit) {
        onDispose {
            wipePassphraseField(first)
            wipePassphraseField(again)
        }
    }

    KeyboardSafeDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        // The icon of its row in BioKey's settings: saving a backup, or opening one.
        icon = { Icon(if (creating) Icons.Default.Archive else Icons.Default.Unarchive, contentDescription = null) },
        title = { PassphraseDialogTitle(title) },
        busy = busy,
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = ready) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.prompt_cancel)) } }
    ) {
        Text(text)
        DialogGap(DialogSpacing.Part)
        // A password manager's fill isn't tinted, so a filled field looks like any other.
        CompositionLocalProvider(LocalAutofillHighlightBrush provides SolidColor(Color.Transparent)) {
            // The two fields' heights: each kept in view with the other where there's room.
            var firstHeight by remember { mutableIntStateOf(0) }
            var againHeight by remember { mutableIntStateOf(0) }
            val fieldGap = with(LocalDensity.current) { DialogSpacing.Field.roundToPx() }
            InterceptPlatformTextInput(PassphraseHardenedTextInput) {
                Column {
                    Column(Modifier.onSizeChanged { firstHeight = it.height }.keepInViewWhileFocused(below = { if (creating) fieldGap + againHeight else 0 })) {
                        OutlinedSecureTextField(
                            state = first,
                            enabled = !busy,
                            label = { Text(stringResource(R.string.backup_password)) },
                            trailingIcon = {
                                PassphraseEye(
                                    shown,
                                    enabled = !busy,
                                    onToggle = { shown = !shown },
                                    show = R.string.backup_show_password,
                                    hide = R.string.backup_hide_password
                                )
                            },
                            isError = firstError != null,
                            inputTransformation = InputTransformation.maxLength(VaultBackup.MAX_PASSWORD_LENGTH).then(edited),
                            textObfuscationMode = obfuscation,
                            keyboardOptions = BackupPasswordKeyboard.copy(imeAction = if (creating) ImeAction.Next else ImeAction.Done),
                            onKeyboardAction = KeyboardActionHandler {
                                when {
                                    !creating -> confirm()
                                    lengthOk -> runCatching { againFocus.requestFocus() }
                                    else -> tooShortSaid = true
                                }
                            },
                            shape = CircleShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(firstFocus)
                                .onFocusChanged { if (it.isFocused) againLast = false }
                                .semantics {
                                    contentType = if (creating) ContentType.NewPassword else ContentType.Password
                                    firstError?.let { error(it) }
                                }
                        )
                        // The rule as help while choosing one; a wrong password said here, with the field.
                        // One line, kept even when empty, so a message coming never moves the buttons.
                        PassphraseFieldLine(
                            text = firstError ?: if (creating) rule else "",
                            isError = firstError != null,
                            announce = firstError != null
                        )
                    }
                    if (creating) {
                        DialogGap(DialogSpacing.Field)
                        Column(Modifier.onSizeChanged { againHeight = it.height }.keepInViewWhileFocused(above = { fieldGap + firstHeight })) {
                            OutlinedSecureTextField(
                                state = again,
                                enabled = !busy,
                                label = { Text(stringResource(R.string.backup_password_again)) },
                                isError = mismatch,
                                inputTransformation = InputTransformation.maxLength(VaultBackup.MAX_PASSWORD_LENGTH).then(edited),
                                textObfuscationMode = obfuscation,
                                keyboardOptions = BackupPasswordKeyboard.copy(imeAction = ImeAction.Done),
                                // Done goes on when it can; otherwise what's wrong is said, and the keyboard stays.
                                onKeyboardAction = KeyboardActionHandler { confirm() },
                                shape = CircleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(againFocus)
                                    .onFocusChanged {
                                        if (againFocused && !it.isFocused && again.text.isNotEmpty()) againFinished = true
                                        againFocused = it.isFocused
                                        if (it.isFocused) againLast = true
                                    }
                                    .semantics {
                                        contentType = ContentType.NewPassword
                                        if (mismatch) error(noMatch)
                                    }
                            )
                            // Kept even when empty, so the buttons don't move; said politely when it appears.
                            PassphraseFieldLine(text = if (mismatch) noMatch else "", isError = mismatch, announce = mismatch)
                        }
                    }
                }
            }
        }
    }
}

/** A password's keyboard, never corrected (the hardened input adds no suggestions or learning). */
private val BackupPasswordKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

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
    // As the phone shows times: "Nov 14, 6:29:09 PM" on a 12-hour phone, "14 Nov, 18:29:09" on a 24-hour one.
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(locale) { SimpleDateFormat(diagnosticTimePattern(locale, DateFormat.is24HourFormat(context)), locale) }
    // Each line as the owner reads it: BioKey and the apps it names by name (the shared log keeps package names).
    val labels = remember { HashMap<String, String?>() }
    val lines = remember(entries) {
        entries.map { entry -> diagnosticLineForDisplay(entry.text, context.packageName) { pkg -> labels.getOrPut(pkg) { installedLabel(context, pkg) } } }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val logActivity = LocalActivity.current
    val res = LocalResources.current
    // Deleting it waits during the day's hold after a face or fingerprint change or a reset: shown
    // before Delete is tapped (its button dimmed, a line saying until when), and said again on a tap.
    val heldUntil = remember(version) { UnlockAttempts.heldUntil(context) }

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
                            IconButton(onClick = {
                                // It names locked apps and their unlocks: the owner's to send, not only the screen lock's.
                                confirmOwner(logActivity, res.getString(R.string.diagnostics_share_prompt), screenLockIfSensorUnusable = true) {
                                    share(context)
                                }
                            }, enabled = entries.isNotEmpty()) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.diagnostics_share))
                            }
                            IconButton(
                                onClick = { if (heldUntil != null) BioKeyMessages.show(heldRefused(context, heldUntil), Icons.Default.ErrorOutline) else confirmDelete = true },
                                enabled = entries.isNotEmpty(),
                                modifier = if (heldUntil != null) Modifier.alpha(0.38f) else Modifier
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.diagnostics_delete))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = GroupInset + 8.dp, end = GroupInset + 8.dp, top = 0.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // What the log is: read once, then scrolled away with the list, leaving it the screen.
                        item(key = "intro") {
                            Column {
                                Text(
                                    text = stringResource(R.string.diagnostics_explanation),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (heldUntil != null && entries.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.diagnostics_delete_held, com.android.BioKey.ui.format.attemptTime(context, heldUntil)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        if (entries.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(if (DiagnosticLog.isEnabled()) R.string.diagnostics_empty else R.string.diagnostics_off),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        itemsIndexed(entries, key = { i, _ -> keys[i] }) { i, entry ->
                            // Its time and what happened, read as one.
                            Column(Modifier.semantics(mergeDescendants = true) { }) {
                                Text(time.format(Date(entry.at)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(lines[i], style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
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
        KeyboardSafeDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { PassphraseDialogTitle(stringResource(R.string.diagnostics_delete_title)) },
            confirmButton = {
                DestructiveTextButton(stringResource(R.string.common_delete), onClick = {
                    confirmDelete = false
                    // It holds who unlocked what, and when: the owner's to delete.
                    confirmOwner(logActivity, res.getString(R.string.diagnostics_delete_prompt), silencesRecords = true) { DiagnosticLog.clear() }
                })
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.prompt_cancel)) } }
        ) {
            Text(stringResource(R.string.diagnostics_delete_text))
        }
    }
}

/** The log viewer's time pattern: month, day and seconds, in 12 or 24 hours as the phone is set. */
internal fun diagnosticTimePattern(locale: Locale, is24Hour: Boolean): String =
    DateFormat.getBestDateTimePattern(locale, if (is24Hour) "MMMdHHmmss" else "MMMdhmmssa")

/** A package name standing alone in a log line ("com.example.app"), not part of a longer word. */
private val PACKAGE_IN_LINE = Regex("(?<![A-Za-z0-9_.])[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+(?![A-Za-z0-9_])")

/**
 * A log line as the viewer shows it: BioKey's own package name as "BioKey", and another app's as
 * its name with the package in brackets, where [label] knows it ("Calendar (com.google.android.calendar)").
 * Only the viewer: the log itself, and what's shared, keep package names.
 */
internal fun diagnosticLineForDisplay(text: String, ownPackage: String, label: (String) -> String?): String =
    PACKAGE_IN_LINE.replace(text) { match ->
        val pkg = match.value
        when {
            pkg == ownPackage -> "BioKey"
            else -> label(pkg)?.takeIf { it.isNotBlank() && it != pkg }?.let { "$it ($pkg)" } ?: pkg
        }
    }

/** An installed app's name as the launcher shows it; null if it isn't installed (or can't be seen). */
private fun installedLabel(context: Context, pkg: String): String? = runCatching {
    val pm = context.packageManager
    AppText.cleanLabel(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString())
}.getOrNull()?.takeIf { it.isNotEmpty() }

private fun share(context: Context) {
    val report = DiagnosticLog.report(context, ProtectionEngine.mode(context).name.lowercase())
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostics_share_subject))
        .putExtra(Intent.EXTRA_TEXT, report)
    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.diagnostics_share_chooser))) }
        // Recorded once it's been offered to another app (Android doesn't say if it was sent).
        .onSuccess { ProtectionGaps.recordIfScreenLock(context, ProtectionGaps.Change.LOG_SHARED) }
        .onFailure { BioKeyMessages.show(context.getString(R.string.diagnostics_share_failed), Icons.Default.ErrorOutline) }
}
