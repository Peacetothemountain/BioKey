package com.android.BioKey

import com.android.BioKey.feature.main.UrgentCard
import com.android.BioKey.feature.main.urgentCard
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home shows at most one urgent card: the most serious showing, a chosen pause never one. */
class HomeUrgencyTest {

    private fun card(
        notProtecting: Boolean = false,
        paused: Boolean = false,
        activity: Boolean = false,
        muted: Boolean = false,
        noScreenLock: Boolean = false
    ) = urgentCard(notProtecting, paused, activity, muted, noScreenLock)

    @Test
    fun notProtectingOutranksEverything() {
        assertEquals(UrgentCard.NOT_PROTECTING, card(notProtecting = true, activity = true, muted = true))
    }

    @Test
    fun aPauseIsNeverUrgentAndTheNextOneIs() {
        assertEquals(UrgentCard.NONE, card(notProtecting = true, paused = true))
        assertEquals(UrgentCard.ACTIVITY, card(notProtecting = true, paused = true, activity = true))
        assertEquals(UrgentCard.ALERTS_MUTED, card(notProtecting = true, paused = true, muted = true))
    }

    @Test
    fun activityThenAlertsMuted() {
        assertEquals(UrgentCard.ACTIVITY, card(activity = true, muted = true))
        assertEquals(UrgentCard.ALERTS_MUTED, card(muted = true))
        assertEquals(UrgentCard.NONE, card())
    }

    @Test
    fun noScreenLockComesRightAfterNotProtecting() {
        assertEquals(UrgentCard.NOT_PROTECTING, card(notProtecting = true, noScreenLock = true))
        assertEquals(UrgentCard.NO_SCREEN_LOCK, card(notProtecting = true, paused = true, noScreenLock = true, activity = true))
        assertEquals(UrgentCard.NO_SCREEN_LOCK, card(noScreenLock = true, activity = true, muted = true))
    }
}
