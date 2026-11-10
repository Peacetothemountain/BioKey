package com.android.BioKey

import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.TrustedClock
import com.android.BioKey.core.security.UnlockAttempts

/** Puts back the clocks and other hooks a test replaced, so they don't outlive it into the next test class in the JVM. */
internal object TestClocks {

    /**
     * Every hook of [TrustedClock], [UnlockAttempts] and [ProtectionGaps] back to what it is
     * outside tests (each keeps its own, so nothing is repeated here), and the trusted clock's
     * anchor forgotten. For a test class's @After.
     */
    fun restore() {
        TrustedClock.restoreHooksForTests()
        TrustedClock.resetForTests()
        UnlockAttempts.restoreHooksForTests()
        ProtectionGaps.restoreHooksForTests()
    }
}
