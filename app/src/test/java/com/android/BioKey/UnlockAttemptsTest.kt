package com.android.BioKey

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.UnlockAttempts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Failed unlocks are kept for the owner: unless a face or fingerprint that matched soon after
 * shows they were the owner's, notified only once the owner can't be retrying, never naming
 * the app, and only once the owner has said yes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnlockAttemptsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_800_000_000_000L
    private val bank = "com.example.bank"
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before
    fun reset() {
        context.getSharedPreferences("biokey_unlock_attempts", Context.MODE_PRIVATE).edit().clear().commit()
        UnlockAttempts.resetForTests()
        UnlockAttempts.clock = { now }
        UnlockAttempts.setEnabled(context, true)
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun restoreClock() {
        UnlockAttempts.clock = System::currentTimeMillis
        UnlockAttempts.resetForTests()
    }

    private val kept get() = UnlockAttempts.attempts.value
    private fun miss(pkg: String = bank) = UnlockAttempts.onBiometricFailed(context, pkg, "Bank")

    @Test
    fun offUntilTheOwnerSaysSo() {
        context.getSharedPreferences("biokey_unlock_attempts", Context.MODE_PRIVATE).edit().clear().commit()
        UnlockAttempts.resetForTests()
        UnlockAttempts.load(context)
        assertFalse(UnlockAttempts.chosen.value)
        miss()
        assertTrue(kept.isEmpty())
    }

    @Test
    fun missesOnOnePromptAreOneAttempt() {
        miss(); now += 5_000L; miss(); now += 5_000L; miss()
        assertEquals(1, kept.size)
        assertEquals(3, kept.single().failures)
        assertEquals("3 tries didn't match", UnlockAttempts.describe(context, kept.single()))
    }

    @Test
    fun aRestartedPromptAddsToTheSameAttempt() {
        miss()
        now += 30_000L
        miss()
        assertEquals(1, kept.size)
    }

    @Test
    fun missesFarApartAreSeparateAttempts() {
        miss()
        now += 5 * 60_000L
        miss()
        assertEquals(2, kept.size)
    }

    @Test
    fun theOwnersFaceOrFingerprintSoonAfterClearsThem() {
        miss()
        now += 30_000L
        UnlockAttempts.onUnlocked(context, bank, biometric = true)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun theScreenLockSoonAfterKeepsThemMarked() {
        miss(); miss()
        now += 30_000L
        UnlockAttempts.onUnlocked(context, bank, biometric = false)
        val attempt = kept.single()
        assertTrue(attempt.openedWithScreenLock)
        assertFalse(attempt.unanswered)
        assertEquals("opened with your screen lock after 2 tries", UnlockAttempts.describe(context, attempt))
    }

    @Test
    fun anUnlockLongAfterLeavesTheAttempt() {
        miss()
        now += UnlockAttempts.OWNER_RETRY_MS + 1_000L
        UnlockAttempts.onUnlocked(context, bank, biometric = true)
        assertEquals(1, kept.size)
    }

    @Test
    fun unlockingAnotherAppLeavesTheAttempt() {
        miss()
        UnlockAttempts.onUnlocked(context, "com.example.chat", biometric = true)
        assertEquals(1, kept.size)
    }

    @Test
    fun aLockoutIsKept() {
        UnlockAttempts.onLockedOut(context, bank, "Bank")
        assertEquals("face and fingerprint locked out", UnlockAttempts.describe(context, kept.single()))
    }

    @Test
    fun aWeekIsKept() {
        miss("com.example.old")
        now += 8 * 24 * 60 * 60_000L
        miss("com.example.new")
        assertEquals(listOf("com.example.new"), kept.map { it.packageName })
    }

    @Test
    fun notifiedOnlyOnceTheOwnerCantBeRetryingAndWithoutTheAppsName() {
        miss()
        UnlockAttempts.updateNotification(context)
        assertTrue("not while the owner may still be retrying", notifications.allNotifications.isEmpty())

        now += UnlockAttempts.OWNER_RETRY_MS + 1_000L
        UnlockAttempts.updateNotification(context)
        val posted = notifications.allNotifications.single()
        val text = posted.extras.getCharSequence("android.text").toString() + posted.extras.getCharSequence("android.title")
        assertFalse("which app is never said", text.contains("Bank") || text.contains(bank))
    }

    @Test
    fun openedWithTheScreenLockIsShownButNotNotified() {
        miss()
        UnlockAttempts.onUnlocked(context, bank, biometric = false)
        now += UnlockAttempts.OWNER_RETRY_MS + 1_000L
        UnlockAttempts.updateNotification(context)
        assertTrue(notifications.allNotifications.isEmpty())
        assertEquals(1, kept.size)
    }

    @Test
    fun keptAcrossARestartOfBioKey() {
        miss(); miss()
        UnlockAttempts.resetForTests()
        UnlockAttempts.load(context)
        assertEquals(2, kept.single().failures)
        assertEquals("Bank", kept.single().label)
    }

    @Test
    fun clearCanBeUndone() {
        miss()
        val removed = UnlockAttempts.clear(context)
        assertTrue(kept.isEmpty())
        UnlockAttempts.restore(context, removed)
        assertEquals(1, kept.size)
    }

    @Test
    fun switchedOffNothingIsKeptAndWhatWasIsGone() {
        miss()
        UnlockAttempts.setEnabled(context, false)
        assertTrue(kept.isEmpty())
        miss()
        assertTrue(kept.isEmpty())
    }
}
