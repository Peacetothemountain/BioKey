package com.android.BioKey

import android.os.Bundle
import com.android.BioKey.feature.main.SetupWait
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Setup waiting for a screen lock survives a recreation; a fresh start waits on nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupWaitTest {

    private fun roundTrip(wait: SetupWait) = SetupWait.from(Bundle().also(wait::saveTo))

    @Test
    fun waitingForAScreenLockIsKept() {
        val wait = SetupWait(needsScreenLock = true, authError = "Set a screen lock first")
        assertEquals(wait, roundTrip(wait))
    }

    @Test
    fun nothingWaitingStaysSo() {
        assertEquals(SetupWait(), roundTrip(SetupWait()))
        assertEquals(SetupWait(), SetupWait.from(null))
    }
}
