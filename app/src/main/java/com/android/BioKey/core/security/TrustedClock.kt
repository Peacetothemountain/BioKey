package com.android.BioKey.core.security

import android.os.SystemClock
import androidx.annotation.VisibleForTesting

/**
 * "Now" for deciding how old a record is, not moved by someone changing the phone's date: the
 * wall clock as it was when BioKey's process started, advanced by the clock since boot, which
 * nothing but a restart can touch. Shown times still use the wall clock.
 *
 * A date changed before BioKey's process started (which needs a restart of BioKey, so a force
 * stop, which is itself recorded) still counts; one changed while it runs doesn't.
 */
object TrustedClock {

    @VisibleForTesting
    internal var wall: () -> Long = System::currentTimeMillis
    @VisibleForTesting
    internal var elapsed: () -> Long = SystemClock::elapsedRealtime

    @Volatile private var anchor: Pair<Long, Long>? = null

    fun now(): Long {
        val (wallAt, elapsedAt) = anchor ?: (wall() to elapsed()).also { anchor = it }
        return wallAt + (elapsed() - elapsedAt)
    }

    @VisibleForTesting
    internal fun resetForTests() {
        anchor = null
    }
}
