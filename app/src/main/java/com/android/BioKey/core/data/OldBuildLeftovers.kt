package com.android.BioKey.core.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.android.BioKey.core.diagnostics.DiagnosticLog
import java.io.File
import java.security.KeyStore

/**
 * Clears what an abandoned build of 2.0 ("2.0.0-fortress", version code 20, never released) left
 * on a phone it was installed on: its own copy of the settings, kept outside the storage the
 * screen lock protects, and two Keystore keys. Nothing here reads them: that build's settings
 * can't be carried over (it had already dropped the ones before it), so the phone is set up again.
 *
 * Runs as BioKey starts. On every other phone there is nothing to find, and it costs one look
 * for a folder.
 */
object OldBuildLeftovers {

    private const val FOLDER = "vault_v2"
    private val KEYS = listOf("biokey_v2_vault_aead", "biokey_v2_biometric_gate")

    /** Removes that build's Keystore keys; tests set their own (Robolectric has no Keystore). */
    @VisibleForTesting
    internal var deleteKeys: (List<String>) -> Unit = { aliases ->
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (alias in aliases) if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    /** Whether there was anything to clear. Never throws. */
    fun clear(context: Context): Boolean {
        val folders = runCatching {
            val app = context.applicationContext
            val storages = listOf(app.createDeviceProtectedStorageContext(), app)
            storages.flatMap { listOf(File(it.noBackupFilesDir, FOLDER), File(it.filesDir, FOLDER)) }.filter(File::exists)
        }.getOrDefault(emptyList())
        if (folders.isEmpty()) return false
        // The keys first: with the folder gone, a failure here would never be tried again.
        val keysGone = runCatching { deleteKeys(KEYS) }.isSuccess
        if (keysGone) folders.forEach { runCatching { it.deleteRecursively() } }
        DiagnosticLog.log(
            if (keysGone) "Cleared what the unreleased 2.0.0-fortress build left on this phone"
            else "Couldn't clear the keys the unreleased 2.0.0-fortress build left; tried again next start"
        )
        return true
    }
}
