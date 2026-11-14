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

    /** The hooks above as they are outside tests ([restoreHooksForTests]): taken as BioKey starts, so they are said once. */
    private val realHooks: () -> Unit = run {
        val wall = wall
        val elapsed = elapsed
        val networkTime = networkTime
        return@run {
            this.wall = wall
            this.elapsed = elapsed
            this.networkTime = networkTime
        }
    }

    /** Puts back every hook a test replaced, so none outlives it into the next test class in the JVM. */
    @VisibleForTesting
    internal fun restoreHooksForTests() = realHooks()

    private class State(val wallAt: Long, val elapsedAt: Long, val onWall: Boolean, val lastTry: Long)

    @Volatile private var state: State? = null

    /** Whether "now" rests on the network's time, which changing the phone's date doesn't move. */
    fun onNetworkTime(): Boolean = !resolve().onWall

    fun now(): Long {
        val s = resolve()
        return s.wallAt + (elapsed() - s.elapsedAt)
    }

    private fun resolve(): State {
        val current = state
        val since = elapsed()
        if (current != null && (!current.onWall || since - current.lastTry < 60_000L)) return current
        return synchronized(this) {
            val again = state
            val nowSince = elapsed()
            if (again != null && (!again.onWall || nowSince - again.lastTry < 60_000L)) return@synchronized again
            val network = networkTime()
            val updated = when {
                network != null -> State(network, elapsed(), onWall = false, lastTry = nowSince)
                again == null -> State(wall(), elapsed(), onWall = true, lastTry = nowSince)
                else -> State(again.wallAt, again.elapsedAt, onWall = true, lastTry = nowSince)
            }
            state = updated
            updated
        }
    }

    @VisibleForTesting
    internal fun resetForTests() {
        synchronized(this) { state = null }
    }
}
