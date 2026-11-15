package com.android.BioKey

import com.android.BioKey.feature.main.HomeCard
import com.android.BioKey.feature.main.UrgentCard
import com.android.BioKey.feature.main.homeCardOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** The cards that need the owner: the one urgent card (red) leads, whatever else shows with it. */
class HomeCardOrderTest {

    private val urgentFor = mapOf(
        UrgentCard.NOT_PROTECTING to HomeCard.NOT_PROTECTING,
        UrgentCard.NO_SCREEN_LOCK to HomeCard.NO_SCREEN_LOCK,
        UrgentCard.ACTIVITY to HomeCard.ACTIVITY,
        UrgentCard.ALERTS_MUTED to HomeCard.ALERTS_MUTED
    )

    @Test
    fun afterAResetTheRedRecordComesBeforeAlertsOff() {
        assertEquals(
            listOf(HomeCard.ACTIVITY, HomeCard.ALERTS_OFF),
            homeCardOrder(setOf(HomeCard.ALERTS_OFF, HomeCard.ACTIVITY), UrgentCard.ACTIVITY)
        )
    }

    @Test
    fun withNothingUrgentTheyKeepTheirOrder() {
        assertEquals(HomeCard.entries.toList(), homeCardOrder(HomeCard.entries.toSet(), UrgentCard.NONE))
    }

    @Test
    fun everyCombinationPutsTheUrgentCardFirstAndShowsEachOnce() {
        val all = HomeCard.entries
        for (mask in 0 until (1 shl all.size)) {
            val showing = all.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            for (urgent in UrgentCard.entries) {
                val order = homeCardOrder(showing, urgent)
                assertEquals("$showing $urgent", showing, order.toSet())
                assertEquals("$showing $urgent", order.size, order.toSet().size)
                val lead = urgentFor[urgent]
                if (lead != null && lead in showing) assertEquals("$showing $urgent", lead, order.first())
                // The rest keep their own order.
                val rest = order.filter { it != lead }
                assertEquals("$showing $urgent", rest.sortedBy { it.ordinal }, rest)
            }
        }
    }
}
