package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.FeatureLinks
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.VaultReactions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The vault tells its listeners what changed; it drives no window or service itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultListenerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val vault get() = SecurityVault.getInstance(context)
    private val heard = mutableListOf<String>()

    private val listener = object : SecurityVault.Listener {
        override fun onLocked(context: Context, packages: Collection<String>) { heard += "locked ${packages.sorted()}" }
        override fun onUnlocked(packages: Collection<String>) { heard += "unlocked ${packages.sorted()}" }
        override fun onPaused() { heard += "paused" }
        override fun onNotificationShieldOff() { heard += "shield off" }
    }

    @Before
    fun listen() {
        // A vault of its own: no app locked by an earlier test.
        vault.awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        SecurityVault.addListener(listener)
    }

    @After
    fun stop() {
        SecurityVault.removeListener(listener)
    }

    @Test
    fun lockingAndUnlockingAreHeard() {
        vault.setSetupCompletedSync(true)
        vault.setAppLock("com.example.bank", true)
        assertTrue(heard.toString(), "locked [com.example.bank]" in heard)
        vault.setAppLock("com.example.bank", false)
        assertTrue(heard.toString(), "unlocked [com.example.bank]" in heard)
    }

    @Test
    fun aPauseIsHeard() {
        vault.pauseProtection(60_000L)
        assertEquals(listOf("paused"), heard.filter { it == "paused" })
    }

    @Test
    fun hidingSwitchedOffIsHeard() {
        vault.setNotificationShield(true)
        vault.setNotificationShield(false)
        assertTrue(heard.toString(), "shield off" in heard)
    }

    @Test
    fun theLockAndShieldAreRegisteredAsBioKeyStarts() {
        // BioKeyApplication did it, before this test (or anything) read the vault.
        assertTrue(SecurityVault.isListening(VaultReactions))
        assertTrue(FeatureLinks.engines === com.android.BioKey.feature.lock.LockEngines)
    }

    @Test
    fun aRestoreWithHidingOffIsHeard() {
        vault.setNotificationShield(true)
        val state = vault.exportState().put("notificationShield", false)
        heard.clear()
        vault.restoreState(state)
        assertTrue(heard.toString(), "shield off" in heard)
    }

    @Test
    fun lockedAppsAreHeardAsTheVaultLoads() {
        vault.setAppLock("com.example.bank", true)
        vault.awaitPendingWrites()
        heard.clear()
        SecurityVault.resetForTests()
        vault.isSetupCompleted()
        assertTrue(heard.toString(), heard.any { it.startsWith("locked") && "com.example.bank" in it })
    }
}
