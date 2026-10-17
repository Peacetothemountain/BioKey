package com.android.BioKey

import android.graphics.Rect
import com.android.BioKey.feature.lock.RecentsWatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Telling a Recents card being closed from one being scrolled away or opened, with the card
 * sizes measured on a Pixel (1344 x 2992 screen, cards about 1100 x 2100).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentsGestureTest {

    private val w = 1344
    private val h = 2992
    private val card = Rect(122, 396, 1222, 2490)

    private fun moved(dx: Int, dy: Int) = Rect(card).apply { offset(dx, dy) }

    @Test
    fun swipedUpIsClosed() {
        assertTrue(RecentsWatcher.sweptAway(card, moved(0, -900), h, w))
    }

    @Test
    fun scrolledSidewaysIsNotClosed() {
        assertFalse(RecentsWatcher.sweptAway(card, moved(-1100, 0), h, w))
        assertFalse(RecentsWatcher.sweptAway(card, moved(900, -20), h, w))
    }

    @Test
    fun openedIsNotClosed() {
        // Opening grows the card to fill the screen: the top rises, but the bottom goes down.
        assertFalse(RecentsWatcher.sweptAway(card, Rect(0, 0, w, h), h, w))
    }

    @Test
    fun aSmallNudgeIsNotClosed() {
        assertFalse(RecentsWatcher.sweptAway(card, moved(0, -120), h, w))
    }
}
