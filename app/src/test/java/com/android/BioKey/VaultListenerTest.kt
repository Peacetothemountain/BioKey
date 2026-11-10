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
        override fun onPaused() { heard += "paused" }
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
    fun lockingIsHeardEachTimeAnAppBecomesLocked() {
        vault.setSetupCompletedSync(true)
        fun times() = heard.count { it == "locked [com.example.bank]" }
        vault.setAppLock("com.example.bank", true)
        assertEquals(heard.toString(), 1, times())
        // Switched off, nothing to make ready; switched on again, heard again.
        vault.setAppLock("com.example.bank", false)
        assertEquals(1, times())
        vault.setAppLock("com.example.bank", true)
        assertEquals(2, times())
    }

    @Test
    fun aPauseIsHeard() {
        vault.pauseProtection(60_000L)
        assertEquals(listOf("paused"), heard.filter { it == "paused" })
    }

    @Test
    fun theLockIsRegisteredAsBioKeyStarts() {
        // BioKeyApplication did it, before this test (or anything) read the vault.
        assertTrue(SecurityVault.isListening(VaultReactions))
        assertTrue(FeatureLinks.engines === com.android.BioKey.feature.lock.LockEngines)
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
