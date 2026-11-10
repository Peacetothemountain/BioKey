package com.android.BioKey

import android.os.SystemClock
import com.android.BioKey.core.security.TrustedClock

/** Puts back the clocks a test replaced, so they don't outlive it into the next test class in the JVM. */
internal object TestClocks {

    /**
     * [TrustedClock]'s hooks back to its own (the defaults in TrustedClock.kt, repeated here, as
     * its resetForTests forgets only the anchor), then the anchor forgotten.
     */
    fun restoreTrustedClock() {
        TrustedClock.wall = System::currentTimeMillis
        TrustedClock.elapsed = SystemClock::elapsedRealtime
        TrustedClock.networkTime = {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                runCatching { SystemClock.currentNetworkTimeClock().millis() }.getOrNull()
            } else null
        }
        TrustedClock.resetForTests()
    }
}
