package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BioKey keeps its own session across a trip it started itself (a settings page, the file picker)
 * only where something is running that would notice the owner going elsewhere instead of coming
 * back, and end it ([ProtectionEngine.watchesTrips]). Where nothing would, leaving BioKey locks it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripWatchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
    }

    @After
    fun tearDown() {
        ProtectionEngine.overrideModeForTests = null
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
    }

    @Test
    fun theAccessibilityEngineAlwaysWatches() {
        ProtectionEngine.overrideModeForTests = ProtectionEngine.Mode.ACCESSIBILITY
        assertTrue(ProtectionEngine.watchesTrips(context))
        // Paused too: it still hears every app that comes to the front.
        SecurityVault.getInstance(context).pauseProtection(null)
        assertTrue(ProtectionEngine.watchesTrips(context))
    }

    @Test
    fun withNoEngineNothingWatches() {
        for (mode in listOf(ProtectionEngine.Mode.OFF, ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING, ProtectionEngine.Mode.NEEDS_COMPATIBILITY_SETUP)) {
            ProtectionEngine.overrideModeForTests = mode
            assertFalse(mode.name, ProtectionEngine.watchesTrips(context))
        }
    }

    @Test
    fun compatibilityModeDoesNotWatchWhileItRests() {
        ProtectionEngine.overrideModeForTests = ProtectionEngine.Mode.COMPATIBILITY
        val vault = SecurityVault.getInstance(context)
        // Nothing locked: it has nothing to look for, so it doesn't look.
        assertFalse(ProtectionEngine.watchesTrips(context))
        vault.setAppLock("com.example.bank", true)
        // Paused: likewise.
        vault.pauseProtection(null)
        assertFalse(ProtectionEngine.watchesTrips(context))
        // And its service isn't running in this test at all, which never counts as watching.
        vault.resumeProtection()
        assertFalse(ProtectionEngine.watchesTrips(context))
    }
}
