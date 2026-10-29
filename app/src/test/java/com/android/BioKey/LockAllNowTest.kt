package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.LockAllNow
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.LockAllActivity
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.xmlpull.v1.XmlPullParser

/** What's unlocked right now, and locking all of it at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockAllNowTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bank = "com.example.bank"
    private val chat = "com.example.chat"
    private val fiveMinutes: (String) -> LockTimeoutMode = { LockTimeoutMode.GRACE_5_MINUTES }
    private lateinit var vault: SecurityVault

    @Before
    fun reset() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        LockStateTracker.lockAll()
    }

    /** Compatibility mode, its permissions granted: an engine that counts as running. */
    private fun protecting() {
        ProtectionEngine.overrideModeForTests = ProtectionEngine.Mode.ACCESSIBILITY
    }

    private fun unlockAndLeave(pkg: String) {
        LockStateTracker.onAppUnlocked(pkg)
        LockStateTracker.onAppVisible(pkg)
        LockStateTracker.onAppLeft(pkg, LockTimeoutMode.GRACE_5_MINUTES, hopTarget = null)
    }

    @Test
    fun appsUnlockedAndInTheirDelayAreListed() {
        unlockAndLeave(bank)
        unlockAndLeave(chat)
        assertEquals(setOf(bank, chat), LockStateTracker.unlockedApps(fiveMinutes))
    }

    @Test
    fun anAppPastItsDelayIsNotListed() {
        unlockAndLeave(bank)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
        assertTrue(LockStateTracker.unlockedApps(fiveMinutes).isEmpty())
    }

    @Test
    fun theCountdownIsWhatsLeftOfTheDelay() {
        unlockAndLeave(bank)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        assertEquals(3 * 60_000L, LockStateTracker.awayRemainingMs(bank, LockTimeoutMode.GRACE_5_MINUTES))
    }

    @Test
    fun onScreenThereIsNoCountdown() {
        LockStateTracker.onAppUnlocked(bank)
        LockStateTracker.onAppVisible(bank)
        assertNull(LockStateTracker.awayRemainingMs(bank, LockTimeoutMode.GRACE_5_MINUTES))
    }

    @Test
    fun lockingAllEndsEverySession() {
        protecting()
        try {
            unlockAndLeave(bank)
            assertEquals(LockAllNow.Outcome.LOCKED, LockAllNow.run(context, includeOwnApp = false, reason = "test"))
            assertTrue(LockStateTracker.unlockedApps(fiveMinutes).isEmpty())
        } finally {
            ProtectionEngine.overrideModeForTests = null
        }
    }

    @Test
    fun pausedItResumesRatherThanClaimingAppsAreLocked() {
        protecting()
        try {
            vault.pauseProtection(null)
            assertEquals(LockAllNow.Outcome.RESUMED_AND_LOCKED, LockAllNow.run(context, includeOwnApp = true, reason = "test"))
            assertTrue(vault.isProtectionActive())
        } finally {
            ProtectionEngine.overrideModeForTests = null
        }
    }

    @Test
    fun withNothingProtectingItSaysSo() {
        ProtectionEngine.overrideModeForTests = ProtectionEngine.Mode.OFF
        try {
            assertEquals(LockAllNow.Outcome.NOT_PROTECTING, LockAllNow.run(context, includeOwnApp = true, reason = "test"))
        } finally {
            ProtectionEngine.overrideModeForTests = null
        }
    }

    @Test
    fun theShortcutLocksEverythingBioKeyIncluded() {
        protecting()
        try {
            unlockAndLeave(bank)
            LockStateTracker.onOwnAppUnlocked()
            val activity = Robolectric.buildActivity(LockAllActivity::class.java).create().get()
            assertTrue(activity.isFinishing)
            assertTrue(LockStateTracker.unlockedApps(fiveMinutes).isEmpty())
            assertFalse(LockStateTracker.isOwnAppUnlocked())
        } finally {
            ProtectionEngine.overrideModeForTests = null
        }
    }

    @Test
    fun theShortcutPointsAtThisBuildsLockAll() {
        val parser = context.resources.getXml(R.xml.shortcuts)
        var target: Pair<String?, String?>? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "intent") {
                val ns = "http://schemas.android.com/apk/res/android"
                target = parser.getAttributeValue(ns, "targetPackage") to parser.getAttributeValue(ns, "targetClass")
            }
        }
        // shortcuts.xml can't use the build's application ID: a suffix added later must change it too.
        assertEquals(BuildConfig.APPLICATION_ID to LockAllActivity::class.java.name, target)
    }
}
