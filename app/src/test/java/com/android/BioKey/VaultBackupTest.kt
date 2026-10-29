package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.VaultBackup
import java.nio.ByteBuffer
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    /**
     * A version 1 backup, as BioKey made them before the round count went into the header:
     * {"selected":["com.example.chat"],"profiles":[],"defaultTimeout":"GRACE_1_MINUTE"}.
     */
    private val version1File = Base64.getDecoder().decode(
        "QklPS0VZQksBrzOx6zhgl2wWnZrbvvT/wZpUYWvvpqb47aWOuZ29hN6z1cMEjtcXgc08KRhJAIC6NF2Fod1XCt3+" +
            "puUCQO2o7a1AqsaEMrBs9xGEfV9/M6MMi0CxolRbHwd5ZIkKhbeEJvigGhxCuAjTKfIgBo6Itum8Bmu16hPGNhm56Ms="
    )

    // Where things are in a version 2 file.
    private val versionAt = 8
    private val roundsAt = 9
    private val saltAt = 13
    private val nonceAt = 29
    private val sealedAt = 41

    private fun freshVault(): SecurityVault {
        SecurityVault.resetForTests()
        return SecurityVault.getInstance(context)
    }

    @Before
    fun clear() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
    }

    private fun backup(vararg profiles: JSONObject, selected: List<String> = emptyList()): JSONObject = JSONObject()
        .put("selected", JSONArray(selected))
        .put("profiles", JSONArray().apply { profiles.forEach { put(it) } })

    private fun ByteArray.flipped(at: Int) = copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }

    /** A sealed file asking for fewer rounds, so tests that only need a valid header run fast. */
    private fun ByteArray.withRounds(rounds: Int) = copyOf().also { ByteBuffer.wrap(it, roundsAt, 4).putInt(rounds) }

    @Test
    fun aBackupRestoresEverythingItSaved() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_FINANCE, listOf("com.example.bank"))
        vault.setAppLock("com.example.chat", true)
        vault.setAppLock("com.example.bank", false)
        vault.setProfileEnabled(SecurityProfile.PRESET_SOCIAL, false)
        vault.setTimeoutMode(LockTimeoutMode.GRACE_5_MINUTES)
        vault.removeAppFromProfile("com.example.other")
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

        // And all of it is saved.
        fresh.awaitPendingWrites()
        val again = freshVault()
        assertTrue(again.isAppLocked("com.example.chat"))
        assertEquals(LockTimeoutMode.GRACE_5_MINUTES, again.timeoutModeFlow.value)
        assertEquals(listOf("com.example.other"), again.exportState().getJSONArray("autoSortExcluded").let { a -> List(a.length()) { a.getString(it) } })
    }

    @Test
    fun aRestoreDoesntSwitchTheShieldOnWithoutItsExplanation() {
        val vault = freshVault()
        vault.setNotificationShield(true)
        val file = VaultBackup.seal(vault.exportState(), password)

        // A new phone, where the owner hasn't agreed to the shield's explanation yet.
        clear()
        val fresh = freshVault()
        fresh.restoreState(VaultBackup.open(file, password))
        assertFalse(fresh.isNotificationShieldEnabled())

        // Agreed to here already: the backup's choice stands.
        fresh.setNotificationShield(true)
        fresh.restoreState(VaultBackup.open(file, password))
        assertTrue(fresh.isNotificationShieldEnabled())
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
        assertThrows(VaultBackup.WrongPasswordException::class.java) { VaultBackup.open(file, "wrong password".toCharArray()) }
    }

    @Test
    fun aChangedFileIsRefused() {
        val file = VaultBackup.seal(freshVault().exportState(), password)
        assertThrows(VaultBackup.WrongPasswordException::class.java) { VaultBackup.open(file.flipped(file.size - 5), password) }
    }

    @Test
    fun aChangedHeaderSaltOrNonceIsRefused() {
        val file = VaultBackup.seal(freshVault().exportState(), password)
        assertThrows(VaultBackup.NotABackupException::class.java) { VaultBackup.open(file.flipped(0), password) }
        for (at in listOf(saltAt, saltAt + 15, nonceAt, nonceAt + 11)) {
            assertThrows("byte $at", VaultBackup.WrongPasswordException::class.java) { VaultBackup.open(file.flipped(at), password) }
        }
        // The round count is authenticated with the rest of the header.
        assertThrows(VaultBackup.WrongPasswordException::class.java) { VaultBackup.open(file.withRounds(100_000), password) }
        // A version 2 file passed off as version 1 reads the wrong salt.
        assertThrows(VaultBackup.WrongPasswordException::class.java) {
            VaultBackup.open(file.copyOf().also { it[versionAt] = 1 }, password)
        }
    }

    @Test
    fun aRoundCountBioKeyWouldNeverUseIsRefused() {
        val file = VaultBackup.seal(freshVault().exportState(), password)
        assertThrows(VaultBackup.NotABackupException::class.java) { VaultBackup.open(file.withRounds(1), password) }
        assertThrows(VaultBackup.NotABackupException::class.java) { VaultBackup.open(file.withRounds(Int.MAX_VALUE), password) }
    }

    @Test
    fun aVersion1BackupStillOpens() {
        val state = VaultBackup.open(version1File, password)
        assertEquals("com.example.chat", state.getJSONArray("selected").getString(0))
        assertEquals("GRACE_1_MINUTE", state.getString("defaultTimeout"))
    }

    @Test
    fun aBackupFromANewerBioKeyIsToldApart() {
        val file = VaultBackup.seal(freshVault().exportState(), password)
        assertThrows(VaultBackup.UnsupportedVersionException::class.java) {
            VaultBackup.open(file.copyOf().also { it[versionAt] = 3 }, password)
        }
        assertThrows(VaultBackup.NotABackupException::class.java) {
            VaultBackup.open(file.copyOf().also { it[versionAt] = 0 }, password)
        }
    }

    @Test
    fun aFileTooShortToHoldATagIsNotABackup() {
        val header = VaultBackup.seal(freshVault().exportState(), password).withRounds(100_000).copyOfRange(0, sealedAt)
        assertThrows(VaultBackup.NotABackupException::class.java) { VaultBackup.open(header + ByteArray(15), password) }
        // Just long enough: it is read, and fails authentication.
        assertThrows(VaultBackup.WrongPasswordException::class.java) { VaultBackup.open(header + ByteArray(16), password) }
    }

    @Test
    fun otherFilesAreNotBackups() {
        assertThrows(VaultBackup.NotABackupException::class.java) { VaultBackup.open("hello, this is a photo".toByteArray(), password) }
        assertThrows(VaultBackup.NotABackupException::class.java) { VaultBackup.open("BIOKEYBK".toByteArray(), password) }
    }

    @Test
    fun repeatedBlankOrReservedProfileIdsAreRepaired() {
        val vault = freshVault()
        vault.restoreState(
            backup(
                SecurityProfile("profile_a", "A", packages = setOf("com.example.bank")).toJson(),
                SecurityProfile("profile_a", "B").toJson(),
                SecurityProfile("", "  ").toJson(),
                SecurityProfile("new_profile", "C").toJson(),
                SecurityProfile("filter:all", "D".repeat(500)).toJson(),
                selected = listOf("com.example.bank", "not a package", context.packageName)
            )
        )
        val profiles = vault.profilesFlow.value
        assertEquals(5, profiles.map { it.id }.toSet().size)
        assertTrue(profiles.all { it.id.startsWith("profile_") && it.id.length > "profile_".length })
        assertEquals("profile_a", profiles[0].id)
        assertEquals("Profile", profiles[2].name)
        assertTrue(profiles[4].name.length <= 40)
        assertEquals(setOf("com.example.bank"), vault.selectedAppsFlow.value)
        assertTrue(vault.isAppLocked("com.example.bank"))
    }

    @Test
    fun aBackupThatIsntShapedLikeOneChangesNothing() {
        val vault = freshVault()
        vault.setAppLock("com.example.chat", true)
        assertThrows(IllegalArgumentException::class.java) { vault.restoreState(JSONObject().put("selected", JSONArray())) }
        assertThrows(IllegalArgumentException::class.java) {
            vault.restoreState(JSONObject().put("profiles", JSONArray().put("not a profile")))
        }
        assertTrue(vault.isAppLocked("com.example.chat"))
    }

    @Test
    fun anAppInTwoProfilesEndsUpInTheFirst() {
        val vault = freshVault()
        vault.restoreState(
            backup(
                SecurityProfile(SecurityProfile.PRESET_FINANCE, "Finance", packages = setOf("com.example.bank")).toJson(),
                SecurityProfile(SecurityProfile.PRESET_SOCIAL, "Social", packages = setOf("com.example.bank")).toJson()
            )
        )
        assertEquals(SecurityProfile.PRESET_FINANCE, vault.profileOf("com.example.bank")?.id)
        assertTrue(vault.profilesFlow.value.first { it.id == SecurityProfile.PRESET_SOCIAL }.packages.isEmpty())
    }

    @Test
    fun anUnknownDefaultTimeoutRelocksImmediately() {
        val vault = freshVault()
        vault.setTimeoutMode(LockTimeoutMode.GRACE_5_MINUTES)
        vault.restoreState(backup().put("defaultTimeout", "FOREVER"))
        assertEquals(LockTimeoutMode.IMMEDIATE, vault.timeoutModeFlow.value)
    }

    @Test
    fun restoringLeavesAPauseAsItIs() {
        val vault = freshVault()
        vault.pauseProtection(null)
        vault.restoreState(backup())
        assertFalse(vault.isProtectionActive())
    }

    @Test
    fun restoringSelfProtectionOffEndsItsLease() {
        val vault = freshVault()
        vault.setAccessibilitySelfProtectionEnabled(true)
        LockStateTracker.setSelfProtectionGranted(true)
        vault.restoreState(backup().put("selfProtection", false))
        assertFalse(vault.isAccessibilitySelfProtectionEnabled())
        assertFalse(LockStateTracker.isSelfProtectionGranted())
    }
}
