package com.android.BioKey

import android.service.quicksettings.Tile
import com.android.BioKey.feature.tile.LockAllTileService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Re-lock now"'s tile: a tap shows on a compact tile too, and over the lock screen it says nothing about protection. */
class LockAllTileTest {

    private fun look(locked: Boolean, flashing: Boolean, outcome: String? = null, resting: String? = null) =
        LockAllTileService.appearance(locked, flashing, outcome, resting)

    @Test
    fun aTapLightsTheTileThenItRestsAgain() {
        assertEquals(Tile.STATE_ACTIVE, look(locked = false, flashing = true, outcome = "Locked").state)
        assertEquals("Locked", look(locked = false, flashing = true, outcome = "Locked").subtitle)
        assertEquals(Tile.STATE_INACTIVE, look(locked = false, flashing = false, resting = "Paused").state)
        assertEquals("Paused", look(locked = false, flashing = false, resting = "Paused").subtitle)
        assertEquals(2_000L, LockAllTileService.FlashMillis)
    }

    @Test
    fun overTheLockScreenItLooksTheSameWhateverProtectionIsAndWhateverTheTapDid() {
        val outcomes = listOf(null, "Locked", "Resumed")
        val resting = listOf(null, "Paused", "Off", "Not running")
        for (o in outcomes) for (r in resting) {
            val lit = look(locked = true, flashing = true, outcome = o, resting = r)
            assertEquals(Tile.STATE_ACTIVE, lit.state)
            assertNull(lit.subtitle)
            val rest = look(locked = true, flashing = false, outcome = o, resting = r)
            assertEquals(Tile.STATE_INACTIVE, rest.state)
            assertNull(rest.subtitle)
        }
    }
}
