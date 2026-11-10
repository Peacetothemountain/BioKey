package com.android.BioKey

import com.android.BioKey.core.security.TrustedClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** BioKey's "now": not moved by the phone's date, and on the network's time as soon as there is one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrustedClockTest {

    private var wall = 1_800_000_000_000L
    private var sinceBoot = 50_000_000L
    private var network: Long? = null
    private var asked = 0

    @Before
    fun reset() {
        // Hooks first: a background thread asking for the time after the reset uses these too.
        TrustedClock.wall = { wall }
        TrustedClock.elapsed = { sinceBoot }
        TrustedClock.networkTime = { asked++; network?.let { it + sinceBoot } }
        TrustedClock.resetForTests()
        asked = 0
    }

    @After
    fun restore() {
        TrustedClock.wall = System::currentTimeMillis
        TrustedClock.elapsed = android.os.SystemClock::elapsedRealtime
        TrustedClock.resetForTests()
    }

    @Test
    fun changingThePhonesDateDoesntMoveIt() {
        val start = TrustedClock.now()
        sinceBoot += 60_000L
        wall += 24 * 60 * 60_000L
        assertEquals(start + 60_000L, TrustedClock.now())
    }

    @Test
    fun onThePhonesClockItSaysSo() {
        TrustedClock.now()
        assertFalse(TrustedClock.onNetworkTime())
    }

    @Test
    fun networkTimeIsTakenUpWhenItAppearsButAskedForAtMostOnceAMinute() {
        TrustedClock.now()
        network = wall - 5 * 60_000L - 50_000_000L
        repeat(100) { TrustedClock.now() }
        assertEquals(1, asked)
        assertFalse(TrustedClock.onNetworkTime())

        sinceBoot += 60_000L
        assertTrue(TrustedClock.onNetworkTime())
        assertEquals(wall - 5 * 60_000L + 60_000L, TrustedClock.now())
    }
}
