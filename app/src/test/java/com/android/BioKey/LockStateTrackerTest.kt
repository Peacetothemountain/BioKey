package com.android.BioKey

import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.LockStateTracker.ABANDONED_LOCK_COOLDOWN_MS
import com.android.BioKey.core.security.LockStateTracker.ARRIVAL_GRACE_MS
import com.android.BioKey.core.security.LockStateTracker.HOP_MAX_MS
import java.time.Duration
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * The session clock: how long an unlocked app stays unlocked once it leaves the screen, and what
 * ends a session early. Time only moves when a test moves it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockStateTrackerTest {

    private val chat = "com.example.chat"
    private val bank = "com.example.bank"
    private val photoPicker = "com.google.android.photopicker"

    @Before
    fun reset() {
        LockStateTracker.onDeviceLockState(false)
        LockStateTracker.lockAll()
    }

    private fun advance(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))

    /** Unlocked, used, then left for Home or another app. */
    private fun unlockUseAndLeave(pkg: String, mode: LockTimeoutMode, hopTarget: String? = null) {
        LockStateTracker.onAppUnlocked(pkg)
        LockStateTracker.onAppVisible(pkg)
        LockStateTracker.onAppLeft(pkg, mode, hopTarget)
    }

    @Test
    fun graceRunsOutOnTime() {
        val graces = LockTimeoutMode.entries.filter { it.durationMillis > 0L }
        assertEquals(4, graces.size)
        for (mode in graces) {
            LockStateTracker.lockAll()
            unlockUseAndLeave(chat, mode)
            advance(mode.durationMillis - 1_000L)
            assertTrue(mode.name, LockStateTracker.isAppUnlocked(chat, mode))
            advance(2_000L)
            assertFalse(mode.name, LockStateTracker.isAppUnlocked(chat, mode))
        }
    }

    @Test
    fun leavingEndsAnImmediateSessionAtOnce() {
        unlockUseAndLeave(chat, LockTimeoutMode.IMMEDIATE)
        assertFalse(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun anAppOnScreenNeverAges() {
        LockStateTracker.onAppUnlocked(chat)
        LockStateTracker.onAppVisible(chat)
        advance(6 * 60 * 60_000L)
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun aHopIsCappedAtFiveMinutes() {
        unlockUseAndLeave(chat, LockTimeoutMode.IMMEDIATE, hopTarget = photoPicker)
        advance(HOP_MAX_MS - 1_000L)
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
        advance(2_000L)
        assertFalse(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun aHopNeverShortensALongerGrace() {
        unlockUseAndLeave(chat, LockTimeoutMode.GRACE_15_MINUTES, hopTarget = photoPicker)
        advance(LockTimeoutMode.GRACE_15_MINUTES.durationMillis - 1_000L)
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.GRACE_15_MINUTES))
    }

    @Test
    fun aHopEndingElsewhereKeepsOnlyWhatIsLeftOfTheGrace() {
        val mode = LockTimeoutMode.GRACE_1_MINUTE
        unlockUseAndLeave(chat, mode, hopTarget = photoPicker)
        advance(50_000L)
        LockStateTracker.onForegroundChanged("com.example.game", { mode })
        assertTrue(LockStateTracker.isAppUnlocked(chat, mode)) // 50 s of its minute gone
        advance(11_000L)
        assertFalse(LockStateTracker.isAppUnlocked(chat, mode))
    }

    @Test
    fun aHopEndingElsewhereAfterTheGraceEndsTheSession() {
        val mode = LockTimeoutMode.GRACE_30_SECONDS
        unlockUseAndLeave(chat, mode, hopTarget = photoPicker)
        advance(2 * 60_000L) // fine while hopping: a hop allows five minutes
        assertTrue(LockStateTracker.isAppUnlocked(chat, mode))
        LockStateTracker.onForegroundChanged("com.example.game", { mode })
        assertFalse(LockStateTracker.isAppUnlocked(chat, mode))
    }

    @Test
    fun onScreenOffSurvivesBeingAwayButNotScreenOff() {
        val mode = LockTimeoutMode.ON_SCREEN_OFF
        unlockUseAndLeave(chat, mode)
        advance(12 * 60 * 60_000L)
        assertTrue(LockStateTracker.isAppUnlocked(chat, mode))
        LockStateTracker.onScreenTurnedOff()
        assertFalse(LockStateTracker.isAppUnlocked(chat, mode))
    }

    @Test
    fun aSessionWhoseAppNeverArrivesEnds() {
        LockStateTracker.onAppUnlocked(chat)
        advance(ARRIVAL_GRACE_MS - 1_000L)
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.GRACE_5_MINUTES))
        advance(2_000L)
        assertFalse(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.GRACE_5_MINUTES))
    }

    @Test
    fun aSessionWhoseAppArrivedIsNotCutShort() {
        LockStateTracker.onAppUnlocked(chat)
        advance(1_000L)
        LockStateTracker.onAppVisible(chat)
        advance(ARRIVAL_GRACE_MS * 10)
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun passwordManagerAliasesShareOneSession() {
        LockStateTracker.onAppUnlocked(PasswordManager.VIRTUAL_PACKAGE)
        assertTrue(LockStateTracker.isAppUnlocked(PasswordManager.CREDENTIAL_MANAGER, LockTimeoutMode.IMMEDIATE))
        LockStateTracker.onAppLeft(PasswordManager.CREDENTIAL_MANAGER, LockTimeoutMode.IMMEDIATE, null)
        assertFalse(LockStateTracker.isAppUnlocked(PasswordManager.VIRTUAL_PACKAGE, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun anUnlockFromBeforeALockAllIsIgnored() {
        // The phone was locked while the prompt was up: its late success must not count.
        val atPrompt = LockStateTracker.lockGeneration()
        LockStateTracker.lockAll(reason = "screen off")
        assertFalse(LockStateTracker.onAppUnlocked(chat, atPrompt))
        assertFalse(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.GRACE_5_MINUTES))

        assertTrue(LockStateTracker.onAppUnlocked(chat, LockStateTracker.lockGeneration()))
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.GRACE_5_MINUTES))
    }

    @Test
    fun anUnlockRacingALockAllOnAnotherThreadNeverOutlivesIt() {
        repeat(500) {
            val atPrompt = LockStateTracker.lockGeneration()
            val locking = thread { LockStateTracker.lockAll() }
            LockStateTracker.onAppUnlocked(chat, atPrompt)
            locking.join()
            assertFalse("round $it", LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.GRACE_5_MINUTES))
        }
    }

    @Test
    fun lockingTheDeviceEndsEverySessionEveryTime() {
        LockStateTracker.onAppUnlocked(chat)
        LockStateTracker.onDeviceLockState(true) // e.g. Lockdown, with the screen still on
        assertFalse(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.GRACE_5_MINUTES))

        LockStateTracker.onDeviceLockState(false) // unlocked again
        LockStateTracker.onAppUnlocked(chat)
        assertTrue(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.GRACE_5_MINUTES))
        LockStateTracker.onDeviceLockState(true)
        assertFalse(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.GRACE_5_MINUTES))
        LockStateTracker.onDeviceLockState(false)
    }

    @Test
    fun theDeviceLockEndsSessionsOnceNotOnEveryReport() {
        LockStateTracker.onDeviceLockState(true)
        val locked = LockStateTracker.lockGeneration()
        LockStateTracker.onDeviceLockState(true)
        assertEquals(locked, LockStateTracker.lockGeneration())
        LockStateTracker.onDeviceLockState(false)
        assertEquals(locked, LockStateTracker.lockGeneration())
    }

    @Test
    fun lockAllEndsTheSelfProtectionLeaseAndBioKeysOwnSession() {
        LockStateTracker.onOwnAppUnlocked()
        LockStateTracker.setSelfProtectionGranted(true)
        LockStateTracker.lockAll(includeOwnApp = false)
        assertTrue(LockStateTracker.isOwnAppUnlocked())
        assertFalse(LockStateTracker.isSelfProtectionGranted())

        LockStateTracker.setSelfProtectionGranted(true)
        LockStateTracker.lockAll()
        assertFalse(LockStateTracker.isOwnAppUnlocked())
        assertFalse(LockStateTracker.isSelfProtectionGranted())
    }

    @Test
    fun bioKeysSessionSurvivesOneTripToTheFilePicker() {
        LockStateTracker.onOwnAppUnlocked()
        LockStateTracker.beginOwnPicker()
        LockStateTracker.onOwnAppStopped()
        advance(119_000L)
        assertTrue(LockStateTracker.isOwnPickerActive())
        advance(2_000L)
        assertFalse(LockStateTracker.isOwnPickerActive())
    }

    @Test
    fun theFilePickerLeaseEndsOnReturnAndOnLockAll() {
        LockStateTracker.onOwnAppUnlocked()
        LockStateTracker.beginOwnPicker()
        LockStateTracker.onOwnAppResumed()
        assertFalse(LockStateTracker.isOwnPickerActive())

        LockStateTracker.beginOwnPicker()
        LockStateTracker.lockAll()
        assertFalse(LockStateTracker.isOwnPickerActive())

        // Without an owner session in front there is nothing to keep.
        LockStateTracker.beginOwnPicker()
        assertFalse(LockStateTracker.isOwnPickerActive())
    }

    @Test
    fun anAbandonedLockScreenCoolsDownThenTellsTheEngines() {
        val told = mutableListOf<String>()
        val listener: (String) -> Unit = { told += it }
        LockStateTracker.addLockAbandonedListener(listener)
        try {
            LockStateTracker.markLockAbandoned(chat)
            assertEquals(listOf(chat), told)
            assertEquals(ABANDONED_LOCK_COOLDOWN_MS, LockStateTracker.abandonCooldownRemainingMs(chat))
            advance(400L)
            assertEquals(ABANDONED_LOCK_COOLDOWN_MS - 400L, LockStateTracker.abandonCooldownRemainingMs(chat))
            assertTrue(LockStateTracker.isRecentlyAbandoned(chat))
            advance(ABANDONED_LOCK_COOLDOWN_MS)
            assertEquals(0L, LockStateTracker.abandonCooldownRemainingMs(chat))
            assertFalse(LockStateTracker.isRecentlyAbandoned(chat))

            LockStateTracker.markLockAbandoned(PasswordManager.VIRTUAL_PACKAGE)
            assertTrue(LockStateTracker.isRecentlyAbandoned(PasswordManager.CREDENTIAL_MANAGER))
            LockStateTracker.clearAbandoned(PasswordManager.CREDENTIAL_MANAGER)
            assertFalse(LockStateTracker.isRecentlyAbandoned(PasswordManager.VIRTUAL_PACKAGE))
        } finally {
            LockStateTracker.removeLockAbandonedListener(listener)
        }
    }

    @Test
    fun lockingABatchEndsEverySessionBeforeTellingAnyone() {
        val batches = mutableListOf<Set<String>>()
        val singles = mutableListOf<String>()
        val batchListener: (Set<String>) -> Unit = { pkgs ->
            assertFalse(pkgs.any { LockStateTracker.isAppUnlocked(it, LockTimeoutMode.GRACE_5_MINUTES) })
            batches += pkgs
        }
        val singleListener: (String) -> Unit = { singles += it }
        LockStateTracker.addAppsLockListener(batchListener)
        LockStateTracker.addAppLockListener(singleListener)
        try {
            LockStateTracker.onAppUnlocked(chat)
            LockStateTracker.onAppUnlocked(bank)
            LockStateTracker.lockPackages(setOf(chat, bank), "test")
            assertEquals(listOf(setOf(chat, bank)), batches)
            assertEquals(setOf(chat, bank), singles.toSet())

            LockStateTracker.lockPackage(chat)
            assertEquals(listOf(setOf(chat, bank), setOf(chat)), batches)
        } finally {
            LockStateTracker.removeAppsLockListener(batchListener)
            LockStateTracker.removeAppLockListener(singleListener)
        }
    }
}
