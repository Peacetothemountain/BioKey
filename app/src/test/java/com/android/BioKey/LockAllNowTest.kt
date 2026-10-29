package com.android.BioKey

import android.content.Context
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.LockStateTracker
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

/** What's unlocked right now, and locking all of it at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockAllNowTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bank = "com.example.bank"
    private val chat = "com.example.chat"
    private val fiveMinutes: (String) -> LockTimeoutMode = { LockTimeoutMode.GRACE_5_MINUTES }

    @Before
    fun reset() = LockStateTracker.lockAll()

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
    fun theShortcutLocksEverythingBioKeyIncluded() {
        unlockAndLeave(bank)
        LockStateTracker.onOwnAppUnlocked()
        val activity = Robolectric.buildActivity(LockAllActivity::class.java).create().get()
        assertTrue(activity.isFinishing)
        assertTrue(LockStateTracker.unlockedApps(fiveMinutes).isEmpty())
        assertFalse(LockStateTracker.isOwnAppUnlocked())
    }

    @Test
    fun theShortcutIsDeclared() {
        val shortcuts = context.getSystemService(ShortcutManager::class.java).manifestShortcuts
        // Robolectric doesn't read shortcuts.xml; the resource itself is checked instead.
        val parser = context.resources.getXml(R.xml.shortcuts)
        var target: String? = null
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "intent") {
                target = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "targetClass")
            }
        }
        assertEquals(LockAllActivity::class.java.name, target)
        assertTrue(shortcuts.isEmpty() || shortcuts.any { it.id == "lock_all" })
    }
}
