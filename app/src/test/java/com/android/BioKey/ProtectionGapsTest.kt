package com.android.BioKey

import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault
import org.junit.After
import org.junit.Assert.assertEquals
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
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        ProtectionGaps.onEngineUp(context)
    }

    @After
    fun restore() {
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
