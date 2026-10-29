package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.SETTINGS_PACKAGE
import com.android.BioKey.feature.main.isSettingsAppLocked
import com.android.BioKey.feature.main.lockSettingsApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Lock Android Settings" locks it whatever kept it unlocked, and Undo puts things back exactly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockSettingsAppTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var vault: SecurityVault

    @Before
    fun fresh() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        vault = SecurityVault.getInstance(context)
    }

    @Test
    fun itLocksAndUndoesPlainly() {
        assertFalse(vault.isSettingsAppLocked())
        val undo = vault.lockSettingsApp()
        assertTrue(vault.isSettingsAppLocked())
        undo()
        assertFalse(vault.isSettingsAppLocked())
        assertFalse(SETTINGS_PACKAGE in vault.selectedAppsFlow.value)
    }

    @Test
    fun inAProfileThatsOffItsTakenOutAndLocked() {
        vault.addAppsToProfile(SecurityProfile.PRESET_WORK, listOf(SETTINGS_PACKAGE))
        vault.setProfileEnabled(SecurityProfile.PRESET_WORK, false)
        assertFalse(vault.isSettingsAppLocked())

        val undo = vault.lockSettingsApp()
        assertTrue(vault.isSettingsAppLocked())

        // Undo: back in its profile, its own switch as it was (on), still not locked (profile off).
        undo()
        assertEquals(SecurityProfile.PRESET_WORK, vault.profileOf(SETTINGS_PACKAGE)?.id)
        assertTrue(SETTINGS_PACKAGE in vault.selectedAppsFlow.value)
        assertFalse(vault.isSettingsAppLocked())
    }
}
