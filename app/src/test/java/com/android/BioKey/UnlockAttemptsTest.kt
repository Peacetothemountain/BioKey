package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.UnlockAttempts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Failed unlocks are kept for the owner, unless they were the owner's own retries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnlockAttemptsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_800_000_000_000L

    @Before
    fun reset() {
        UnlockAttempts.clock = { now }
        UnlockAttempts.setEnabled(context, true)
        UnlockAttempts.clear(context)
    }

    @After
    fun restoreClock() {
        UnlockAttempts.clock = System::currentTimeMillis
    }

    private val kept get() = UnlockAttempts.attempts.value

    @Test
    fun aPromptClosedAfterMismatchesIsKept() {
        UnlockAttempts.record(context, "com.example.bank", failures = 3, lockedOut = false)
        assertEquals(1, kept.size)
        assertEquals("com.example.bank", kept.single().packageName)
        assertEquals("3 tries didn't match", UnlockAttempts.describe(kept.single()))
    }

    @Test
    fun aPromptClosedWithoutTryingIsNothing() {
        UnlockAttempts.record(context, "com.example.bank", failures = 0, lockedOut = false)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun theOwnerUnlockingSoonAfterWasJustRetrying() {
        UnlockAttempts.record(context, "com.example.bank", failures = 1, lockedOut = false)
        now += 30_000L
        UnlockAttempts.onUnlocked(context, "com.example.bank")
        assertTrue(kept.isEmpty())
    }

    @Test
    fun anUnlockLongAfterLeavesTheAttempt() {
        UnlockAttempts.record(context, "com.example.bank", failures = 2, lockedOut = false)
        now += UnlockAttempts.OWNER_RETRY_MS + 1_000L
        UnlockAttempts.onUnlocked(context, "com.example.bank")
        assertEquals(1, kept.size)
    }

    @Test
    fun unlockingAnotherAppLeavesTheAttempt() {
        UnlockAttempts.record(context, "com.example.bank", failures = 2, lockedOut = false)
        UnlockAttempts.onUnlocked(context, "com.example.chat")
        assertEquals(1, kept.size)
    }

    @Test
    fun aLockoutCountsEvenWithoutMismatchesSeen() {
        UnlockAttempts.record(context, "com.example.bank", failures = 0, lockedOut = true)
        assertEquals("face or fingerprint locked out", UnlockAttempts.describe(kept.single()))
    }

    @Test
    fun newestFirstAndAWeekKept() {
        UnlockAttempts.record(context, "com.example.old", failures = 1, lockedOut = false)
        now += 8 * 24 * 60 * 60_000L
        UnlockAttempts.record(context, "com.example.new", failures = 1, lockedOut = false)
        assertEquals(listOf("com.example.new"), kept.map { it.packageName })
    }

    @Test
    fun switchedOffNothingIsKeptAndWhatWasIsGone() {
        UnlockAttempts.record(context, "com.example.bank", failures = 1, lockedOut = false)
        UnlockAttempts.setEnabled(context, false)
        assertTrue(kept.isEmpty())
        UnlockAttempts.record(context, "com.example.bank", failures = 1, lockedOut = false)
        assertTrue(kept.isEmpty())
    }
}
