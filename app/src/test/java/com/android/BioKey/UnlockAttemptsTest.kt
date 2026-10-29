package com.android.BioKey

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.TrustedClock
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
 * fingerprint; told once the phone has been unlocked since and the owner can't be retrying,
 * never naming the app, never on the lock screen; and only once the owner has said yes. Changing
 * the phone's clock neither hides nor deletes them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnlockAttemptsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_800_000_000_000L
    private var sinceBoot = 10_000_000L
    private val bank = "com.example.bank"
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before
    fun reset() {
        context.getSharedPreferences("biokey_unlock_attempts", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("biokey_protection_gaps", Context.MODE_PRIVATE).edit().clear().commit()
        UnlockAttempts.resetForTests()
        ProtectionGaps.resetForTests()
        UnlockAttempts.clock = { now }
        UnlockAttempts.elapsed = { sinceBoot }
        UnlockAttempts.bootCount = { 3 }
        UnlockAttempts.hasStrongBiometric = { true }
        TrustedClock.resetForTests()
        TrustedClock.networkTime = { null }
        TrustedClock.wall = { now }
        TrustedClock.elapsed = { sinceBoot }
        UnlockAttempts.setEnabled(context, true)
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun restore() {
        UnlockAttempts.clock = System::currentTimeMillis
        TrustedClock.wall = System::currentTimeMillis
        TrustedClock.elapsed = android.os.SystemClock::elapsedRealtime
        TrustedClock.resetForTests()
        UnlockAttempts.resetForTests()
    }

    private val kept get() = UnlockAttempts.attempts.value
    private fun pass(ms: Long) {
        now += ms
        sinceBoot += ms
    }

    /** One prompt: its misses, then how it ended. */
    private inner class Prompt(val pkg: String = bank) {
        var record: Long? = null
        val own = mutableMapOf<Long, Int>()
        fun miss() {
            UnlockAttempts.onBiometricFailed(context, pkg, "Bank", record)?.let { record = it; own[it] = (own[it] ?: 0) + 1 }
        }
        fun matched() = UnlockAttempts.onUnlocked(context, pkg, "Bank", biometric = true, ownMisses = own)
        fun screenLock() = UnlockAttempts.onUnlocked(context, pkg, "Bank", biometric = false, ownMisses = own)
    }

    private fun told() {
        UnlockAttempts.onUserPresent(context)
        UnlockAttempts.updateNotification(context)
    }

    @Test
    fun offUntilTheOwnerSaysSo() {
        context.getSharedPreferences("biokey_unlock_attempts", Context.MODE_PRIVATE).edit().clear().commit()
        UnlockAttempts.resetForTests()
        UnlockAttempts.load(context)
        assertFalse(UnlockAttempts.chosen.value)
        Prompt().miss()
        assertTrue(kept.isEmpty())
    }

    @Test
    fun aPromptsMissesAreOneAttempt() {
        val p = Prompt()
        p.miss(); pass(5_000L); p.miss(); pass(5_000L); p.miss()
        assertEquals(3, kept.single().failures)
        assertEquals("3 tries didn't match", UnlockAttempts.describe(context, kept.single()))
    }

    @Test
    fun theOwnersMatchOnTheSamePromptMeansTheMissesWereTheirs() {
        val p = Prompt()
        p.miss(); p.miss()
        p.matched()
        assertTrue(kept.isEmpty())
    }

    @Test
    fun evenAPromptLeftUpAWhile() {
        val p = Prompt()
        p.miss()
        pass(5 * 60_000L)
        p.miss()
        p.matched()
        assertTrue(kept.isEmpty())
    }

    @Test
    fun theOwnersMatchOnALaterPromptKeepsSomeoneElsesMisses() {
        // Someone tried, handed the phone back; the owner opened it 90 s later.
        Prompt().apply { miss(); miss(); miss() }
        pass(90_000L)
        Prompt().matched()
        val attempt = kept.single()
        assertEquals(Ending.BIOMETRIC, attempt.ending)
        assertFalse("answered by the owner's face: not notified", attempt.notifiable)
    }

    @Test
    fun ownerProofDoesntNeedTheRecordOnTop() {
        val mine = Prompt(bank)
        mine.miss()
        Prompt("com.example.chat").miss()
        mine.matched()
        assertEquals(listOf("com.example.chat"), kept.map { it.packageName })
    }

    @Test
    fun theScreenLockAfterMissesIsKeptAndTold() {
        val p = Prompt()
        p.miss(); p.miss()
        pass(30_000L)
        p.screenLock()
        val attempt = kept.single()
        assertEquals(Ending.SCREEN_LOCK, attempt.ending)
        assertTrue("someone may know the PIN", attempt.notifiable)
        pass(UnlockAttempts.OWNER_RETRY_MS)
        told()
        assertEquals(1, notifications.allNotifications.size)
    }

    @Test
    fun screenLockUnlocksAreNotedOnlyWhenAskedFor() {
        Prompt().screenLock()
        assertTrue(kept.isEmpty())
        UnlockAttempts.setScreenLockUnlocks(context, true)
        Prompt().screenLock()
        assertEquals("opened with your screen lock", UnlockAttempts.describe(context, kept.single()))
    }

    @Test
    fun aLockoutAfterAScreenLockUnlockIsANewAttempt() {
        val first = Prompt()
        first.miss()
        first.screenLock()
        pass(10_000L)
        UnlockAttempts.onLockedOut(context, bank, "Bank", promptRecord = null)
        assertEquals(2, kept.size)
        assertTrue(kept.first().unanswered)
    }

    @Test
    fun toldOnlyAfterThePhoneWasUnlockedAgainNeverNamingTheAppNeverOnTheLockScreen() {
        Prompt().miss()
        pass(UnlockAttempts.OWNER_RETRY_MS + 1_000L)
        UnlockAttempts.updateNotification(context)
        assertTrue("not while whoever tried may still hold the phone", notifications.allNotifications.isEmpty())

        told()
        val posted = notifications.allNotifications.single()
        val text = posted.extras.getCharSequence("android.text").toString() + posted.extras.getCharSequence("android.title")
        assertFalse("which app is never said", text.contains("Bank") || text.contains(bank))
        assertEquals(android.app.Notification.VISIBILITY_SECRET, posted.visibility)
    }

    @Test
    fun neverWhileTheOwnerMayStillBeRetrying() {
        Prompt().miss()
        pass(30_000L)
        told()
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test
    fun aMissHeldBackIsntSkippedWhenSomethingLaterIsTold() {
        Prompt("com.example.old").miss()
        pass(UnlockAttempts.OWNER_RETRY_MS + 1_000L)
        Prompt("com.example.new").miss()
        told()
        assertEquals(1, notifications.allNotifications.size)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        // The newer one, held back then, is told once its window has passed.
        pass(UnlockAttempts.OWNER_RETRY_MS + 1_000L)
        told()
        assertEquals(1, notifications.allNotifications.size)
    }

    @Test
    fun settingTheClockAheadDeletesNothingUnseen() {
        Prompt().miss()
        now += 30L * 24 * 60 * 60_000L
        Prompt("com.example.chat").miss()
        assertEquals(2, kept.size)
        UnlockAttempts.resetForTests()
        UnlockAttempts.load(context)
        assertEquals(2, kept.size)
    }

    @Test
    fun settingTheClockBackHidesNothing() {
        Prompt().miss()
        pass(UnlockAttempts.OWNER_RETRY_MS + 1_000L)
        told()
        context.getSystemService(NotificationManager::class.java).cancelAll()
        now -= 10L * 24 * 60 * 60_000L
        Prompt("com.example.chat").miss()
        pass(UnlockAttempts.OWNER_RETRY_MS + 1_000L)
        told()
        assertEquals(1, notifications.allNotifications.size)
    }

    @Test
    fun seenOnesAgeOut() {
        TrustedClock.networkTime = { now }
        Prompt().miss()
        UnlockAttempts.markSeen(context)
        pass(8L * 24 * 60 * 60_000L)
        UnlockAttempts.prune(context)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun aMutedChannelTellsNothingAndCountsNothingAsTold() {
        UnlockAttempts.ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.getNotificationChannel(UnlockAttempts.CHANNEL_ID).importance.let { }
        nm.createNotificationChannel(nm.getNotificationChannel(UnlockAttempts.CHANNEL_ID).apply { importance = NotificationManager.IMPORTANCE_NONE })
        assertTrue(UnlockAttempts.channelMuted(context))
    }

    @Test
    fun withoutNetworkTimeSeenOnesDontAgeOut() {
        // On the phone's own clock, a date set ahead mustn't be enough to age the record away.
        Prompt().miss()
        UnlockAttempts.markSeen(context)
        pass(8L * 24 * 60 * 60_000L)
        UnlockAttempts.prune(context)
        assertEquals(1, kept.size)
    }

    @Test
    fun swipedAwayItComesBackAtTheNextUnlock() {
        Prompt().miss()
        pass(UnlockAttempts.OWNER_RETRY_MS + 1_000L)
        told()
        assertEquals(1, notifications.allNotifications.size)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        told()
        assertEquals(1, notifications.allNotifications.size)
    }

    @Test
    fun afterReviewItDoesntComeBack() {
        Prompt().miss()
        pass(UnlockAttempts.OWNER_RETRY_MS + 1_000L)
        told()
        UnlockAttempts.markSeen(context)
        told()
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test
    fun keptAcrossARestartOfBioKey() {
        Prompt().apply { miss(); miss() }
        UnlockAttempts.resetForTests()
        UnlockAttempts.load(context)
        assertEquals(2, kept.single().failures)
        assertEquals("Bank", kept.single().label)
    }

    @Test
    fun switchingOffDeletesThemWithUndo() {
        Prompt().miss()
        val removed = UnlockAttempts.setEnabled(context, false)
        assertTrue(kept.isEmpty())
        UnlockAttempts.setEnabled(context, true)
        UnlockAttempts.restore(context, removed)
        assertEquals(1, kept.size)
    }

    @Test
    fun aRestoredBackupSayingOffKeepsThisPhonesRecords() {
        Prompt().miss()
        UnlockAttempts.setEnabled(context, false, keepRecords = true)
        assertEquals(1, kept.size)
    }
}
