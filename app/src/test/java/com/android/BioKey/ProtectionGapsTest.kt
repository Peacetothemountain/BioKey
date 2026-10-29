package com.android.BioKey

import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.TrustedClock
import com.android.BioKey.core.security.UnlockAttempts
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Times app locking was off: switched off, or force-stopped (as Android records it). Not a stop
 * by Android itself, a restart, a moment's handover; and a changed clock doesn't shorten one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtectionGapsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_800_000_000_000L
    private var sinceBoot = 50_000_000L
    private var boot = 7
    private var exits = listOf<Pair<Long, Int>>()

    @Before
    fun reset() {
        context.getSharedPreferences("biokey_protection_gaps", Context.MODE_PRIVATE).edit().clear().commit()
        ProtectionGaps.resetForTests()
        ProtectionGaps.clock = { now }
        ProtectionGaps.elapsed = { sinceBoot }
        ProtectionGaps.bootCount = { boot }
        ProtectionGaps.exits = { exits }
        ProtectionGaps.updatedAt = { 0L }
        ProtectionGaps.wallNow = { now }
        // A test's storage counts as new unless a test says otherwise.
        ProtectionGaps.storageFromBefore = { false }
        TrustedClock.resetForTests()
        context.getSharedPreferences("biokey_unlock_attempts", Context.MODE_PRIVATE).edit().clear().commit()
        UnlockAttempts.resetForTests()
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        ProtectionGaps.onEngineUp(context)
    }

    @After
    fun restore() {
        ProtectionGaps.storageFromBefore = { true }
        ProtectionGaps.sdk = { android.os.Build.VERSION.SDK_INT }
        ProtectionGaps.clock = System::currentTimeMillis
        ProtectionGaps.resetForTests()
    }

    private val gaps get() = ProtectionGaps.gaps.value
    private fun pass(ms: Long) {
        now += ms
        sinceBoot += ms
    }

    @Test
    fun switchedOffThenBackOnIsAGap() {
        pass(60_000L)
        ProtectionGaps.onEngineDown(context)
        pass(8 * 60_000L)
        ProtectionGaps.onEngineUp(context)
        val gap = gaps.single()
        assertEquals(8 * 60_000L, gap.to - gap.from)
        assertEquals(ProtectionGaps.Kind.SWITCHED_OFF, gap.kind)
    }

    @Test
    fun aShortSwitchOffStillCounts() {
        ProtectionGaps.onEngineDown(context)
        pass(20_000L)
        ProtectionGaps.onEngineUp(context)
        assertEquals(1, gaps.size)
    }

    @Test
    fun aHandoverIsNoGap() {
        ProtectionGaps.onEngineDown(context)
        pass(2_000L)
        ProtectionGaps.onEngineUp(context)
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun settingTheClockBackDoesntShortenIt() {
        ProtectionGaps.onEngineDown(context)
        sinceBoot += 10 * 60_000L
        now -= 60 * 60_000L
        ProtectionGaps.onEngineUp(context)
        assertEquals(10 * 60_000L, gaps.single().let { it.to - it.from })
    }

    @Test
    fun aForceStopIsAGap() {
        pass(60_000L)
        val stoppedAt = now
        exits = listOf(stoppedAt to ApplicationExitInfo.REASON_USER_REQUESTED)
        pass(5 * 60_000L)
        ProtectionGaps.onEngineUp(context)
        val gap = gaps.single()
        assertEquals(ProtectionGaps.Kind.FORCE_STOPPED, gap.kind)
        assertEquals(stoppedAt, gap.from)
    }

    @Test
    fun androidStoppingItIsNoGap() {
        pass(60_000L)
        exits = listOf(now to ApplicationExitInfo.REASON_LOW_MEMORY, now + 1 to ApplicationExitInfo.REASON_OTHER)
        pass(5 * 60_000L)
        ProtectionGaps.onEngineUp(context)
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun aForceStopIsCountedOnce() {
        pass(60_000L)
        exits = listOf(now to ApplicationExitInfo.REASON_USER_REQUESTED)
        pass(5 * 60_000L)
        ProtectionGaps.onEngineUp(context)
        pass(60_000L)
        ProtectionGaps.onEngineUp(context)
        assertEquals(1, gaps.size)
    }

    @Test
    fun theStopThatComesWithAnUpdateIsNoGap() {
        // Before Android 14, an update's stop is recorded the same way as a force stop.
        ProtectionGaps.sdk = { 33 }
        pass(60_000L)
        val updated = now
        ProtectionGaps.updatedAt = { updated }
        exits = listOf(updated + 2_000L to ApplicationExitInfo.REASON_USER_REQUESTED)
        pass(5 * 60_000L)
        ProtectionGaps.onEngineUp(context)
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun beforeAndroid14TheDateSetToTheLastUpdateDoesntExcuseAForceStop() {
        // No update since the engine last ran: a stop "near" the old update time is a force stop.
        ProtectionGaps.sdk = { 33 }
        val updated = now - 60 * 60_000L
        ProtectionGaps.updatedAt = { updated }
        ProtectionGaps.onEngineUp(context)
        exits = listOf(updated + 2_000L to ApplicationExitInfo.REASON_USER_REQUESTED)
        pass(5 * 60_000L)
        ProtectionGaps.onEngineUp(context)
        assertEquals(ProtectionGaps.Kind.FORCE_STOPPED, gaps.single().kind)
    }

    @Test
    fun aChangeDuringAHoldSaysSo() {
        val detail = ProtectionGaps.Change.LOCK_OFF.encodeDuringHold("Chase")
        assertTrue(ProtectionGaps.Change.duringHold(detail))
        assertEquals(ProtectionGaps.Change.LOCK_OFF to listOf("Chase"), ProtectionGaps.Change.decode(detail))
        assertFalse(ProtectionGaps.Change.duringHold(ProtectionGaps.Change.LOCK_OFF.encode("Chase")))
    }

    @Test
    fun onAndroid14AForceStopRightAfterAnUpdateStillCounts() {
        // Android 14+ tells an update apart: a force stop just after one is still a force stop.
        ProtectionGaps.sdk = { 34 }
        pass(60_000L)
        val updated = now
        ProtectionGaps.updatedAt = { updated }
        exits = listOf(updated + 2_000L to ApplicationExitInfo.REASON_USER_REQUESTED)
        pass(5 * 60_000L)
        ProtectionGaps.onEngineUp(context)
        assertEquals(ProtectionGaps.Kind.FORCE_STOPPED, gaps.single().kind)
    }

    @Test
    fun aForceStopWhoseStartCantBeToldSaysSo() {
        // Stopped; the clock then set back an hour, and BioKey's clock now rests on the phone's.
        var wall = now
        ProtectionGaps.wallNow = { wall }
        exits = listOf(now to ApplicationExitInfo.REASON_USER_REQUESTED)
        context.getSharedPreferences("biokey_protection_gaps", Context.MODE_PRIVATE).edit().remove("clock_offset").commit()
        wall = now - 60 * 60_000L
        now -= 30 * 60_000L
        ProtectionGaps.onEngineUp(context)
        assertTrue(gaps.single().unknownStart)
    }

    @Test
    fun aChangeIsStoredAsACodeAndItsNames() {
        val detail = ProtectionGaps.Change.LOCK_OFF.encode("WhatsApp")
        assertEquals(ProtectionGaps.Change.LOCK_OFF to listOf("WhatsApp"), ProtectionGaps.Change.decode(detail))
        // Older records, written as words, aren't codes.
        assertEquals(null, ProtectionGaps.Change.decode("WhatsApp's lock switched off"))
    }

    @Test
    fun theEarliestForceStopStartsTheGap() {
        pass(60_000L)
        val first = now
        exits = listOf(first to ApplicationExitInfo.REASON_USER_REQUESTED, first + 60_000L to ApplicationExitInfo.REASON_USER_REQUESTED)
        pass(5 * 60_000L)
        ProtectionGaps.onEngineUp(context)
        assertEquals(first, gaps.single().from)
    }

    @Test
    fun aForceStopDatedAfterNowStillCounts() {
        // Stopped, then the clock was set back an hour before switching back on.
        exits = listOf(now + 60 * 60_000L to ApplicationExitInfo.REASON_USER_REQUESTED)
        pass(5 * 60_000L)
        ProtectionGaps.onEngineUp(context)
        assertEquals(ProtectionGaps.Kind.FORCE_STOPPED, gaps.single().kind)
    }

    @Test
    fun settingTheClockBackBeforeAForceStopDoesntHideIt() {
        // The engine came up at "now"; then the date was set back a day and BioKey force-stopped.
        val stoppedAt = now - 24 * 60 * 60_000L
        exits = listOf(stoppedAt to ApplicationExitInfo.REASON_USER_REQUESTED)
        pass(5 * 60_000L)
        ProtectionGaps.onEngineUp(context)
        assertEquals(ProtectionGaps.Kind.FORCE_STOPPED, gaps.single().kind)
    }

    @Test
    fun settingTheClockBackAfterAForceStopDoesntShortenIt() {
        // The engine ran with the phone's clock right; BioKey was force-stopped, then the phone's
        // clock set back an hour before BioKey came back ten minutes later.
        var wall = now
        ProtectionGaps.wallNow = { wall }
        val stoppedAt = now
        exits = listOf(stoppedAt to ApplicationExitInfo.REASON_USER_REQUESTED)
        pass(10 * 60_000L)
        wall = now - 60 * 60_000L
        ProtectionGaps.onEngineUp(context)
        assertEquals(10 * 60_000L, gaps.single().let { it.to - it.from })
    }

    @Test
    fun settingTheClockAheadBeforeAForceStopDoesntShortenIt() {
        // The phone's clock set an hour ahead while the engine ran, then BioKey force-stopped.
        var wall = now + 60 * 60_000L
        ProtectionGaps.wallNow = { wall }
        exits = listOf(wall to ApplicationExitInfo.REASON_USER_REQUESTED)
        pass(10 * 60_000L)
        wall += 10 * 60_000L
        ProtectionGaps.onEngineUp(context)
        assertEquals(10 * 60_000L, gaps.single().let { it.to - it.from })
    }

    @Test
    fun settingTheClockAheadBeforeAForceStopAndBackAfterDoesntHideIt() {
        // Ahead 30 minutes while the engine runs (BioKey hears of it), force-stopped, then the
        // clock put right before BioKey came back 30 minutes later.
        var wall = now
        ProtectionGaps.wallNow = { wall }
        wall += 30 * 60_000L
        ProtectionGaps.onClockChanged(context)
        exits = listOf(wall to ApplicationExitInfo.REASON_USER_REQUESTED)
        pass(30 * 60_000L)
        wall = now
        ProtectionGaps.onEngineUp(context)
        assertEquals(30 * 60_000L, gaps.single().let { it.to - it.from })
    }

    @Test
    fun aMomentsEventIsRecorded() {
        ProtectionGaps.recordEvent(context, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK)
        assertEquals(ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, gaps.single().kind)
    }

    @Test
    fun clearCanBeUndone() {
        ProtectionGaps.onEngineDown(context)
        pass(5 * 60_000L)
        ProtectionGaps.onEngineUp(context)
        val removed = ProtectionGaps.clear(context)
        assertTrue(gaps.isEmpty())
        ProtectionGaps.restore(context, removed)
        assertEquals(1, gaps.size)
    }

    /** Storage as a clearing leaves it: nothing of BioKey's, not set up (the hooks say it's new). */
    private fun clearedStorage() {
        context.getSharedPreferences("biokey_protection_gaps", Context.MODE_PRIVATE).edit().clear().commit()
        ProtectionGaps.resetForTests()
        SecurityVault.getInstance(context).setSetupCompletedSync(false)
    }

    @Test
    fun clearedStorageIsToldThenRecordedOnceSetUpAgain() {
        // Nothing of BioKey's on the storage, yet Android remembers it running: cleared.
        clearedStorage()
        exits = listOf(now - 60_000L to ApplicationExitInfo.REASON_USER_REQUESTED)
        ProtectionGaps.checkStorageCleared(context)
        assertEquals(now - 60_000L, ProtectionGaps.clearedAt(context))
        assertTrue(ProtectionGaps.awaitingSetupAfterClear(context))
        // Looked at once per storage: a later start doesn't move it.
        exits = listOf(now - 10_000L to ApplicationExitInfo.REASON_USER_REQUESTED)
        ProtectionGaps.checkStorageCleared(context)
        assertEquals(now - 60_000L, ProtectionGaps.clearedAt(context))
        // Set up again with an engine running: recorded, from then until now, and the mark goes.
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        ProtectionGaps.onEngineUp(context)
        assertEquals(1, gaps.count { it.kind == ProtectionGaps.Kind.DATA_CLEARED })
        assertEquals(null, ProtectionGaps.clearedAt(context))
        assertFalse(ProtectionGaps.awaitingSetupAfterClear(context))
    }

    @Test
    fun aFreshInstallOrASetUpOneIsntCleared() {
        // Fresh: Android remembers nothing of BioKey.
        clearedStorage()
        exits = emptyList()
        ProtectionGaps.checkStorageCleared(context)
        assertEquals(null, ProtectionGaps.clearedAt(context))
        // Already set up (a version from before this check): its exits aren't a clearing.
        clearedStorage()
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        exits = listOf(now - 60_000L to ApplicationExitInfo.REASON_OTHER)
        ProtectionGaps.checkStorageCleared(context)
        assertEquals(null, ProtectionGaps.clearedAt(context))
    }

    @Test
    fun storageFromBeforeIsntAClearing() {
        // An earlier version, opened but never set up, then updated: its files are older than this process.
        clearedStorage()
        ProtectionGaps.storageFromBefore = { true }
        exits = listOf(now - 3_600_000L to ApplicationExitInfo.REASON_OTHER)
        ProtectionGaps.checkStorageCleared(context)
        assertEquals(null, ProtectionGaps.clearedAt(context))
    }

    @Test
    fun anUpdatesOwnStopIsntAClearing() {
        // Android 14+ marks the stop an update makes: alone, it isn't a clearing.
        clearedStorage()
        ProtectionGaps.sdk = { 34 }
        exits = listOf(now - 60_000L to ApplicationExitInfo.REASON_PACKAGE_UPDATED)
        ProtectionGaps.checkStorageCleared(context)
        assertEquals(null, ProtectionGaps.clearedAt(context))
    }

    @Test
    fun aClearingLongAgoOnNewStorageStillCounts() {
        // The clock set back before clearing, or Play updating BioKey afterwards, dates the exit
        // early; the storage is new all the same, so it's a clearing.
        clearedStorage()
        ProtectionGaps.updatedAt = { now }
        exits = listOf(now - 86_400_000L to ApplicationExitInfo.REASON_USER_REQUESTED)
        ProtectionGaps.checkStorageCleared(context)
        assertEquals(now - 86_400_000L, ProtectionGaps.clearedAt(context))
    }

    @Test
    fun aClearingDatedInTheFutureIsAtATimeThatCantBeTold() {
        // The date set ahead to clear, then back: the exit is "after now".
        clearedStorage()
        exits = listOf(now + 3_600_000L to ApplicationExitInfo.REASON_USER_REQUESTED)
        ProtectionGaps.checkStorageCleared(context)
        assertEquals(false, ProtectionGaps.cleared.value?.known)
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        ProtectionGaps.onEngineUp(context)
        assertTrue(gaps.single { it.kind == ProtectionGaps.Kind.DATA_CLEARED }.unknownStart)
    }

    @Test
    fun aResetHoldsForADayAndCantBeCleared() {
        clearedStorage()
        exits = listOf(now - 600_000L to ApplicationExitInfo.REASON_USER_REQUESTED)
        ProtectionGaps.checkStorageCleared(context)
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        ProtectionGaps.onEngineUp(context)
        // Whoever reset it may have added a face or fingerprint first: nothing proves the owner for a day.
        assertTrue(UnlockAttempts.heldUntil(context) != null)
        assertEquals(ProtectionGaps.Kind.DATA_CLEARED, UnlockAttempts.heldBy(context))
        ProtectionGaps.clear(context, Long.MAX_VALUE)
        assertEquals(1, gaps.count { it.kind == ProtectionGaps.Kind.DATA_CLEARED })
        // Made meanwhile: marked as during a reset's hold.
        val detail = ProtectionGaps.Change.LOCK_OFF.encodeDuringHold("Chase", byReset = true)
        assertTrue(ProtectionGaps.Change.duringReset(detail))
        assertTrue(ProtectionGaps.Change.duringHold(detail))
        assertEquals(ProtectionGaps.Change.LOCK_OFF, ProtectionGaps.Change.decode(detail)?.first)
    }

    @Test
    fun storageAgeIsToldFromItsFiles() {
        // The rule itself, on real files: one written before the process started means storage from before.
        val dir = Files.createTempDirectory("storage").toFile()
        try {
            val started = System.currentTimeMillis()
            val prefs = File(dir, "shared_prefs").apply { mkdirs() }
            File(prefs, "new.xml").apply { writeText("x"); setLastModified(started + 1_000L) }
            assertFalse(ProtectionGaps.hasFileOlderThan(dir, started))
            // Nested, and older: from before.
            File(prefs, "old.xml").apply { writeText("x"); setLastModified(started - 60_000L) }
            assertTrue(ProtectionGaps.hasFileOlderThan(dir, started))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun anEmptyStorageIsntFromBefore() {
        val dir = Files.createTempDirectory("storage").toFile()
        try {
            File(dir, "files").mkdirs()
            assertFalse(ProtectionGaps.hasFileOlderThan(dir, System.currentTimeMillis()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aLinkedOldFileIsntBioKeysStorage() {
        // A link to old files elsewhere (an old-style lib link, say) mustn't make cleared storage look old.
        val outside = Files.createTempDirectory("outside").toFile()
        val dir = Files.createTempDirectory("storage").toFile()
        try {
            val started = System.currentTimeMillis()
            File(outside, "old.so").apply { writeText("x"); setLastModified(started - 60_000L) }
            val linked = runCatching {
                Files.createSymbolicLink(File(dir, "lib").toPath(), outside.toPath())
                Files.createSymbolicLink(File(dir, "old.so").toPath(), File(outside, "old.so").toPath())
            }.isSuccess
            // Where links can't be made (Windows without the right), there's nothing to check.
            Assume.assumeTrue(linked)
            assertFalse(ProtectionGaps.hasFileOlderThan(dir, started))
        } finally {
            dir.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun whatALinkPointsAtIsntLookedAt() {
        // The rule on every platform: a folder or file taken as a link is skipped, old or not.
        val dir = Files.createTempDirectory("storage").toFile()
        try {
            val started = System.currentTimeMillis()
            val lib = File(dir, "lib").apply { mkdirs() }
            File(lib, "old.so").apply { writeText("x"); setLastModified(started - 60_000L) }
            File(dir, "old.xml").apply { writeText("x"); setLastModified(started - 60_000L) }
            val links = setOf(lib.name, "old.xml")
            assertFalse(ProtectionGaps.hasFileOlderThan(dir, started) { it.name in links })
            // Not links: they're BioKey's, and old.
            assertTrue(ProtectionGaps.hasFileOlderThan(dir, started) { false })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun storageThatCantBeReadCountsAsFromBefore() {
        // A failure can miss a clearing, never claim one.
        val missing = File(Files.createTempDirectory("storage").toFile(), "gone")
        assertTrue(ProtectionGaps.hasFileOlderThan(missing, System.currentTimeMillis()))
    }

    @Test
    fun remindersResumeOnceNotificationsAreAllowedAgain() {
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        scheduler.cancelAll()
        clearedStorage()
        exits = listOf(now - 60_000L to ApplicationExitInfo.REASON_USER_REQUESTED)
        ProtectionGaps.checkStorageCleared(context)
        val shadow = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
        // Taken back with the clearing: nothing scheduled.
        shadow.setNotificationsEnabled(false)
        scheduler.cancelAll()
        ProtectionAlerts.resumeStorageClearedReminders(context)
        assertTrue(scheduler.allPendingJobs.isEmpty())
        // Allowed again in setup: the reminders start.
        shadow.setNotificationsEnabled(true)
        ProtectionAlerts.resumeStorageClearedReminders(context)
        assertEquals(1, scheduler.allPendingJobs.size)
        // Set up: nothing more to remind of.
        scheduler.cancelAll()
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        ProtectionAlerts.resumeStorageClearedReminders(context)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }
}
