package com.android.BioKey

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.core.security.UnlockAttempts.Ending
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
 * Failed unlocks are kept for the owner: unless the same prompt then matched the owner's face or
 * fingerprint; notified only once the phone has been unlocked since and the owner can't be
 * retrying, never naming the app; and only once the owner has said yes.
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
        context.getSharedPreferences("biokey_protection_gaps", Context.MODE_PRIVATE).edit().clear().commit()
        UnlockAttempts.resetForTests()
        ProtectionGaps.resetForTests()
        UnlockAttempts.clock = { now }
        UnlockAttempts.hasStrongBiometric = { true }
        UnlockAttempts.setEnabled(context, true)
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun restore() {
        UnlockAttempts.clock = System::currentTimeMillis
        UnlockAttempts.resetForTests()
    }

    private val kept get() = UnlockAttempts.attempts.value
    private fun miss(pkg: String = bank) = UnlockAttempts.onBiometricFailed(context, pkg, "Bank")
    private fun unlocked(biometric: Boolean, missesHere: Int = 0, pkg: String = bank) =
        UnlockAttempts.onUnlocked(context, pkg, "Bank", biometric, missesHere)

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
        assertEquals(3, kept.single().failures)
        assertEquals("3 tries didn't match", UnlockAttempts.describe(context, kept.single()))
    }

    @Test
    fun missesFarApartAreSeparateAttempts() {
        miss()
        now += 5 * 60_000L
        miss()
        assertEquals(2, kept.size)
    }

    @Test
    fun theOwnersMatchOnTheSamePromptMeansTheMissesWereTheirs() {
        miss(); miss()
        unlocked(biometric = true, missesHere = 2)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun theOwnersMatchOnALaterPromptKeepsSomeoneElsesMisses() {
        // Someone tried, handed the phone back; the owner opened it 90 s later.
        miss(); miss(); miss()
        now += 90_000L
        unlocked(biometric = true, missesHere = 0)
        val attempt = kept.single()
        assertEquals(Ending.BIOMETRIC, attempt.ending)
        assertFalse("shown, not notified", attempt.unanswered)
        assertEquals("3 tries, then opened with your face or fingerprint", UnlockAttempts.describe(context, attempt))
    }

    @Test
    fun theScreenLockAfterMissesKeepsThemMarked() {
        miss(); miss()
        now += 30_000L
        unlocked(biometric = false)
        val attempt = kept.single()
        assertEquals(Ending.SCREEN_LOCK, attempt.ending)
        assertEquals("2 tries, then opened with your screen lock", UnlockAttempts.describe(context, attempt))
    }

    @Test
    fun screenLockUnlocksAreNotedOnlyWhenAskedFor() {
        unlocked(biometric = false)
        assertTrue(kept.isEmpty())
        UnlockAttempts.setScreenLockUnlocks(context, true)
        unlocked(biometric = false)
        assertEquals("opened with your screen lock", UnlockAttempts.describe(context, kept.single()))
    }

    @Test
    fun aLockoutAfterAScreenLockUnlockIsANewAttempt() {
        miss()
        unlocked(biometric = false)
        now += 10_000L
        UnlockAttempts.onLockedOut(context, bank, "Bank")
        assertEquals(2, kept.size)
        assertTrue(kept.first().unanswered)
    }

    @Test
    fun aWeekIsKept() {
        miss("com.example.old")
        now += 8 * 24 * 60 * 60_000L
        miss("com.example.new")
        assertEquals(listOf("com.example.new"), kept.map { it.packageName })
    }

    @Test
    fun pastTheirWeekTheyGoFromTheDiskTooOnLoad() {
        miss()
        now += 8 * 24 * 60 * 60_000L
        UnlockAttempts.resetForTests()
        UnlockAttempts.load(context)
        assertTrue(kept.isEmpty())
        UnlockAttempts.resetForTests()
        now -= 8 * 24 * 60 * 60_000L
        UnlockAttempts.load(context)
        assertTrue("deleted, not just hidden", kept.isEmpty())
    }

    @Test
    fun notifiedOnlyAfterThePhoneWasUnlockedAgainAndWithoutTheAppsName() {
        miss()
        now += UnlockAttempts.OWNER_RETRY_MS + 1_000L
        UnlockAttempts.updateNotification(context)
        assertTrue("not while whoever tried may still hold the phone", notifications.allNotifications.isEmpty())

        UnlockAttempts.onUserPresent(context)
        UnlockAttempts.updateNotification(context)
        val posted = notifications.allNotifications.single()
        val text = posted.extras.getCharSequence("android.text").toString() + posted.extras.getCharSequence("android.title")
        assertFalse("which app is never said", text.contains("Bank") || text.contains(bank))
        assertEquals("BioKey", posted.publicVersion.extras.getCharSequence("android.title").toString())
    }

    @Test
    fun neverWhileTheOwnerMayStillBeRetrying() {
        UnlockAttempts.onUserPresent(context)
        now += 1_000L
        miss()
        now += 30_000L
        UnlockAttempts.onUserPresent(context)
        UnlockAttempts.updateNotification(context)
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test
    fun openedAfterwardsIsShownButNotNotified() {
        miss()
        unlocked(biometric = false)
        now += UnlockAttempts.OWNER_RETRY_MS + 1_000L
        UnlockAttempts.onUserPresent(context)
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
    fun switchingOffDeletesThemWithUndo() {
        miss()
        val removed = UnlockAttempts.setEnabled(context, false)
        assertTrue(kept.isEmpty())
        UnlockAttempts.setEnabled(context, true)
        UnlockAttempts.restore(context, removed)
        assertEquals(1, kept.size)
    }

    @Test
    fun aRestoredBackupSayingOffKeepsThisPhonesRecords() {
        miss()
        UnlockAttempts.setEnabled(context, false, keepRecords = true)
        assertEquals(1, kept.size)
    }
}
