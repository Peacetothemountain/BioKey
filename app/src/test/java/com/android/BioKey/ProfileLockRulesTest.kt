package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.security.SecurityVault
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How an app's own lock switch, its profile and the profile's switch combine: an app is locked
 * when its switch is on and its profile (if any) is on; neither switch changes membership.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileLockRulesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bank = "com.example.bank"
    private val chat = "com.example.chat"

    /** A vault read fresh from storage, as after a restart. */
    private fun freshVault(): SecurityVault {
        SecurityVault::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        return SecurityVault.getInstance(context)
    }

    @Before
    fun clear() {
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun waitForWrites() {
        // The vault writes on its own thread; let it finish before reading storage back.
        Thread.sleep(200)
    }

    @Test
    fun turningAnAppOffKeepsItInItsProfile() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_FINANCE, listOf(bank))
        assertTrue(vault.isAppLocked(bank))

        vault.setAppLock(bank, false)

        assertFalse(vault.isAppLocked(bank))
        assertEquals(SecurityProfile.PRESET_FINANCE, vault.profileOf(bank)?.id)
        vault.setAppLock(bank, true)
        assertTrue(vault.isAppLocked(bank))
    }

    @Test
    fun aProfileSwitchedOffUnlocksItsAppsAndRemembersEachOne() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_FINANCE, listOf(bank, chat))
        vault.setAppLock(chat, false)

        vault.setProfileEnabled(SecurityProfile.PRESET_FINANCE, false)
        assertFalse(vault.isAppLocked(bank))
        assertFalse(vault.isAppLocked(chat))
        assertTrue(bank in vault.selectedAppsFlow.value) // its own switch is still on

        vault.setProfileEnabled(SecurityProfile.PRESET_FINANCE, true)
        assertTrue(vault.isAppLocked(bank))
        assertFalse(vault.isAppLocked(chat)) // its own switch was off, and stays off
    }

    @Test
    fun appsOutsideProfilesAreUnaffectedByProfileSwitches() {
        val vault = freshVault()
        vault.setAppLock(chat, true)
        vault.setProfileEnabled(SecurityProfile.PRESET_FINANCE, false)
        assertTrue(vault.isAppLocked(chat))
    }

    @Test
    fun switchesSurviveARestart() {
        val vault = freshVault()
        vault.addAppsToProfile(SecurityProfile.PRESET_FINANCE, listOf(bank))
        vault.setAppLock(bank, false)
        vault.setProfileEnabled(SecurityProfile.PRESET_SOCIAL, false)
        waitForWrites()

        val again = freshVault()
        assertFalse(again.isAppLocked(bank))
        assertEquals(SecurityProfile.PRESET_FINANCE, again.profileOf(bank)?.id)
        assertFalse(again.profilesFlow.value.first { it.id == SecurityProfile.PRESET_SOCIAL }.enabled)
    }

    @Test
    fun earlierDataKeepsEveryProfileAppLocked() {
        // Before profiles had switches, an app in a profile was locked without being in the set.
        val profiles = JSONArray().put(SecurityProfile.DEFAULTS[1].copy(packages = setOf(bank)).toJson().apply { remove("enabled") })
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit()
            .putString("security_profiles_json", profiles.toString())
            .putStringSet("locked_apps_set", setOf(chat))
            .commit()

        val vault = freshVault()
        assertTrue(vault.isAppLocked(bank))
        assertTrue(vault.isAppLocked(chat))
        assertTrue(vault.profilesFlow.value.all { it.enabled })
    }
}
