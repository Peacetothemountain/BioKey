package com.android.BioKey.core.security

import android.os.SystemClock
import androidx.annotation.VisibleForTesting

/**
 * "Now" for stamping and ageing BioKey's records, not moved by someone changing the phone's date:
 * the time when BioKey's process started, advanced by the clock since boot, which nothing but a
 * restart can touch. That starting time is the network's time where the phone has it (Android
 * 13 and later keep it apart from the user-set clock); otherwise the phone's clock at that moment.
 */
object TrustedClock {

    @VisibleForTesting
    internal var wall: () -> Long = System::currentTimeMillis
    @VisibleForTesting
    internal var elapsed: () -> Long = SystemClock::elapsedRealtime

    /** The network's time, where the phone has it; tests set their own. */
    @VisibleForTesting
    internal var networkTime: () -> Long? = {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            runCatching { SystemClock.currentNetworkTimeClock().millis() }.getOrNull()
        } else null
    }

    @Volatile private var anchor: Pair<Long, Long>? = null
    /** The anchor came from the phone's clock: network time is tried again (once a minute) until it's there. */
    @Volatile private var anchoredOnWall = false
    /** When network time was last asked for (clock since boot): at most once a minute. */
    @Volatile private var lastTry = Long.MIN_VALUE

    /** Whether "now" rests on the network's time, which changing the phone's date doesn't move. */
    fun onNetworkTime(): Boolean {
        now()
        return !anchoredOnWall
    }

    fun now(): Long {
        val current = anchor
        val since = elapsed()
        if (current == null || (anchoredOnWall && since - lastTry >= 60_000L)) {
            lastTry = since
            val network = networkTime()
            if (network != null) {
                anchor = network to elapsed()
                anchoredOnWall = false
            } else if (current == null) {
                anchor = wall() to elapsed()
                anchoredOnWall = true
            }
        }
        val (wallAt, elapsedAt) = anchor!!
        return wallAt + (elapsed() - elapsedAt)
    }

    @VisibleForTesting
    internal fun resetForTests() {
        anchor = null
        anchoredOnWall = false
        lastTry = Long.MIN_VALUE
    }
}
