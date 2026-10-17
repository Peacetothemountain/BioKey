package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.VaultBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A backup restores exactly what was saved, and only with the right password. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val password = "correct horse".toCharArray()

    private fun freshVault(): SecurityVault {
        SecurityVault::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        return SecurityVault.getInstance(context)
    }

    @Before
    fun clear() {
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun aBackupRestoresEverythingItSaved() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_FINANCE, listOf("com.example.bank"))
        vault.setAppLock("com.example.chat", true)
        vault.setAppLock("com.example.bank", false)
        vault.setProfileEnabled(SecurityProfile.PRESET_SOCIAL, false)
        vault.setTimeoutMode(LockTimeoutMode.GRACE_5_MINUTES)
        val file = VaultBackup.seal(vault.exportState(), password)

        // A new phone: nothing set up.
        clear()
        val fresh = freshVault()
        assertFalse(fresh.isAppLocked("com.example.chat"))

        fresh.restoreState(VaultBackup.open(file, password))
        assertTrue(fresh.isAppLocked("com.example.chat"))
        assertFalse(fresh.isAppLocked("com.example.bank"))
        assertEquals(SecurityProfile.PRESET_FINANCE, fresh.profileOf("com.example.bank")?.id)
        assertFalse(fresh.profilesFlow.value.first { it.id == SecurityProfile.PRESET_SOCIAL }.enabled)
        assertEquals(LockTimeoutMode.GRACE_5_MINUTES, fresh.timeoutModeFlow.value)
    }

    @Test
    fun theFileDoesNotGiveAwayWhichAppsAreLocked() {
        val vault = freshVault()
        vault.setAppLock("com.example.secretapp", true)
        val file = VaultBackup.seal(vault.exportState(), password)
        assertFalse(String(file, Charsets.ISO_8859_1).contains("secretapp"))
    }

    @Test
    fun aWrongPasswordIsRefused() {
        val file = VaultBackup.seal(freshVault().exportState(), password)
        try {
            VaultBackup.open(file, "wrong password".toCharArray())
            fail()
        } catch (_: VaultBackup.WrongPasswordException) {
        }
    }

    @Test
    fun aChangedFileIsRefused() {
        val file = VaultBackup.seal(freshVault().exportState(), password)
        file[file.size - 5] = (file[file.size - 5].toInt() xor 1).toByte()
        try {
            VaultBackup.open(file, password)
            fail()
        } catch (_: VaultBackup.WrongPasswordException) {
        }
    }

    @Test
    fun otherFilesAreNotBackups() {
        try {
            VaultBackup.open("hello, this is a photo".toByteArray(), password)
            fail()
        } catch (_: VaultBackup.NotABackupException) {
        }
    }
}
