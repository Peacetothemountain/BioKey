package com.android.BioKey

import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        com.android.BioKey.core.security.TrustedClock.resetForTests()
        context.getSharedPreferences("biokey_unlock_attempts", Context.MODE_PRIVATE).edit().clear().commit()
        com.android.BioKey.core.security.UnlockAttempts.resetForTests()
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        ProtectionGaps.onEngineUp(context)
    }

    @After
    fun restore() {
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
}
