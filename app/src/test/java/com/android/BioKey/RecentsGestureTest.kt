package com.android.BioKey

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import com.android.BioKey.feature.lock.RecentsWatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Telling a Recents card being closed from one being scrolled away or opened, with the card
 * sizes measured on a Pixel (1344 x 2992 screen, cards about 1100 x 2100). Also how seldom the
 * launcher is asked: each look is a round trip run on its UI thread.
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
    fun swipedUpOffTheTopIsClosedThoughOnlyItsVisiblePartIsReported() {
        // Measured on Android 17's launcher (1080 x 2424): the top stops at the edge, then the
        // height goes below zero as the card leaves the screen.
        val card17 = Rect(175, 304, 905, 1940)
        assertTrue(RecentsWatcher.sweptAway(card17, Rect(178, 12, 902, 1109), 2424, 1080))
        assertTrue(RecentsWatcher.sweptAway(card17, Rect(173, 0, 907, -50), 2424, 1080))
        // Moved over sideways as its neighbour closed, it wasn't closed itself.
        assertFalse(RecentsWatcher.sweptAway(Rect(0, 302, 131, 1949), Rect(173, 302, 907, 1949), 2424, 1080))
    }

    @Test
    fun aCardSlidInFromTheSideIsMeasuredFromWhereItRests() {
        // Recents opened from Home (Android 17, 1080 x 2424): the cards slide in from the left,
        // clipped at the edge, then rest; swiped up from there, the card is closed.
        val c = RecentsWatcher.Card(Rect(0, 302, 421, 1949))
        c.moveTo(Rect(88, 302, 822, 1949))
        c.moveTo(Rect(173, 302, 907, 1949))
        c.moveTo(Rect(173, 302, 907, 1949))
        c.moveTo(Rect(176, 120, 904, 1766))
        c.moveTo(Rect(178, 12, 902, 1109))
        assertTrue(RecentsWatcher.sweptAway(c.first, c.last, 2424, 1080))
        // Seen off the top twice, it's still leaving, not resting there.
        c.moveTo(Rect(173, 0, 907, -50))
        c.moveTo(Rect(173, 0, 907, -50))
        assertTrue(RecentsWatcher.sweptAway(c.first, c.last, 2424, 1080))
        // Only sliding in, it rests where it came to.
        val still = RecentsWatcher.Card(Rect(0, 302, 421, 1949))
        still.moveTo(Rect(173, 302, 907, 1949))
        assertFalse(RecentsWatcher.sweptAway(still.first, still.last, 2424, 1080))
    }

    @Test
    fun anAppShrinkingIntoItsIconOnTheWayHomeIsNotClosed() {
        // Swiping up to go Home scales the app's card down into its icon on the home screen,
        // which can be near the top: the card ends up there smaller, not slid up whole. (Seen on
        // a Pixel: a session ended as "closed from Recents" a second after the app was opened.)
        val card17 = Rect(175, 304, 905, 1940)
        assertFalse(RecentsWatcher.sweptAway(card17, Rect(430, 120, 650, 610), 2424, 1080))
        assertFalse(RecentsWatcher.sweptAway(card17, Rect(300, 40, 780, 1100), 2424, 1080))
        assertFalse(RecentsWatcher.sweptAway(card17, Rect(500, 200, 580, 380), 2424, 1080))
        // Leaving Recents for the home screen scales every card down where it is.
        assertFalse(RecentsWatcher.sweptAway(card17, Rect(320, 700, 760, 1680), 2424, 1080))
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

    @Test
    fun theHomeScreenIsLookedAtLessOftenThanRecents() {
        // In Recents: as soon as a burst of changes settles.
        assertEquals(RecentsWatcher.SETTLE_MS, RecentsWatcher.scanDelayMs(sinceLastScanMs = 0, lastFoundRecents = true))
        // On the home screen (live widgets): no sooner than the interval after the last look...
        assertEquals(RecentsWatcher.HOME_SCAN_INTERVAL_MS - 100, RecentsWatcher.scanDelayMs(sinceLastScanMs = 100, lastFoundRecents = false))
        // ...and never sooner than a burst takes to settle.
        assertEquals(RecentsWatcher.SETTLE_MS, RecentsWatcher.scanDelayMs(sinceLastScanMs = 10_000, lastFoundRecents = false))
    }

    @Test
    fun aWidgetRewordingItselfOnTheHomeScreenIsNotLookedAt() {
        val text = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
        val description = AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION
        val state = AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION
        // A clock, a song title, a weather reading.
        assertFalse(RecentsWatcher.changeNeedsScan(text, lastFoundRecents = false))
        assertFalse(RecentsWatcher.changeNeedsScan(text or description or state, lastFoundRecents = false))
        // Views coming and going could be Recents opening.
        assertTrue(RecentsWatcher.changeNeedsScan(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE, lastFoundRecents = false))
        assertTrue(RecentsWatcher.changeNeedsScan(text or AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE, lastFoundRecents = false))
        assertTrue(RecentsWatcher.changeNeedsScan(AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED, lastFoundRecents = false))
        // A change that doesn't say what changed, or one not known yet, is always looked at.
        assertTrue(RecentsWatcher.changeNeedsScan(AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED, lastFoundRecents = false))
        assertTrue(RecentsWatcher.changeNeedsScan(1 shl 30, lastFoundRecents = false))
        // In Recents every change counts.
        assertTrue(RecentsWatcher.changeNeedsScan(text, lastFoundRecents = true))
    }

    /** A launcher whose cards are under [cardsById]; [asked] records each ID asked for. */
    private class Launcher(val cardsById: Map<String, Map<String, Int>>) {
        val asked = mutableListOf<String>()
        fun read(id: String): Map<String, Int> = cardsById[id].orEmpty().also { asked += id }
    }

    @Test
    fun recentsIsReadWithTheCardIdsThatNamedCardsLastTime() {
        val launcher = Launcher(mapOf("task_thumbnail" to mapOf("Chat" to 1, "Bank" to 2)))
        // The first look asks for every ID, and learns which one names cards.
        val first = RecentsWatcher.readCards(null, emptySet(), launcher::read)
        assertEquals(RecentsWatcher.CARD_IDS, launcher.asked)
        assertEquals(setOf("task_thumbnail"), first.ids)
        assertTrue(first.readEveryId)
        // The next asks only for that one.
        launcher.asked.clear()
        val next = RecentsWatcher.readCards(first.ids, first.cards.keys, launcher::read)
        assertEquals(listOf("task_thumbnail"), launcher.asked)
        assertEquals(mapOf("Chat" to 1, "Bank" to 2), next.cards)
        assertFalse(next.readEveryId)
    }

    @Test
    fun aCardMissingFromTheRememberedIdsIsLookedForUnderEveryId() {
        // The app Recents was opened from turned from a live tile into a plain card.
        val launcher = Launcher(
            mapOf(
                "task_thumbnail" to mapOf("Bank" to 2),
                "snapshot" to mapOf("Chat" to 1)
            )
        )
        val read = RecentsWatcher.readCards(setOf("task_thumbnail"), setOf("Chat", "Bank"), launcher::read)
        assertEquals(mapOf("Chat" to 1, "Bank" to 2), read.cards)
        assertEquals(setOf("snapshot", "task_thumbnail"), read.ids)
        assertTrue(read.readEveryId)
        // Remembered IDs that name nothing any more: every ID too.
        launcher.asked.clear()
        val empty = RecentsWatcher.readCards(setOf("task_thumbnail_live_tile"), emptySet(), launcher::read)
        assertEquals(RecentsWatcher.CARD_IDS.toSet(), launcher.asked.toSet())
        assertEquals(mapOf("Chat" to 1, "Bank" to 2), empty.cards)
    }

    @Test
    fun aCardNamedUnderTwoIdsKeepsTheFirstIdsBounds() {
        val launcher = Launcher(
            mapOf(
                "task_thumbnail" to mapOf("Chat" to 1),
                "task_thumbnail_live_tile" to mapOf("Chat" to 9)
            )
        )
        val read = RecentsWatcher.readCards(setOf("task_thumbnail_live_tile"), setOf("Chat", "Bank"), launcher::read)
        assertEquals(mapOf("Chat" to 1), read.cards)
    }
}
