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
    /** The anchor came from the phone's clock: network time is tried again until it's there. */
    @Volatile private var anchoredOnWall = false

    fun now(): Long {
        val current = anchor
        if (current == null || anchoredOnWall) {
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
    }
}
