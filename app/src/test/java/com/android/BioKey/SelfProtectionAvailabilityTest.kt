package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Play build has no self-protection: nothing turns it on there, not its switch, not a backup
 * made by a build that had it, not a setting an earlier build saved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelfProtectionAvailabilityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE)

    @Before
    fun playBuild() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        prefs.edit().clear().commit()
        SecurityVault.resetForTests()
        SecurityVault.selfProtectionAvailable = false
    }

    @After
    fun restore() {
        SecurityVault.selfProtectionAvailable = BuildConfig.SELF_PROTECTION
        SecurityVault.resetForTests()
    }

    private fun freshVault(): SecurityVault {
        SecurityVault.resetForTests()
        return SecurityVault.getInstance(context)
    }

    @Test
    fun debugBuildsHaveIt() = assertTrue(BuildConfig.SELF_PROTECTION)

    @Test
    fun itsSwitchCantTurnItOn() {
        val vault = freshVault()
        vault.setAccessibilitySelfProtectionEnabled(true)
        assertFalse(vault.isAccessibilitySelfProtectionEnabled())
    }

    @Test
    fun aBackupWithItOnRestoresItOff() {
        val vault = freshVault()
        LockStateTracker.setSelfProtectionGranted(true)
        vault.restoreState(
            JSONObject().put("selected", JSONArray()).put("profiles", JSONArray()).put("selfProtection", true)
        )
        assertFalse(vault.isAccessibilitySelfProtectionEnabled())
        assertFalse(LockStateTracker.isSelfProtectionGranted())
    }

    @Test
    fun anEarlierBuildsSavedOnReadsAsOff() {
        prefs.edit().putBoolean("setup_completed", true).putBoolean("accessibility_self_protection_enabled", true).commit()
        assertFalse(freshVault().isAccessibilitySelfProtectionEnabled())
    }
}
