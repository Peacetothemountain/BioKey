package com.android.BioKey.feature.lock

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * Notices apps being closed from Recents, so a locked app's session ends there and then rather
 * than only when its re-lock time runs out.
 *
 * Recents shows only the cards on or near the screen, so a card leaving the screen can just mean
 * a scroll. Closing is told apart by how the card leaves: swiped away, it slides *up*, whole
 * (both edges rise, its size holds); scrolled, it moves sideways; opened, it grows to fill the
 * screen (its bottom edge goes down) after a tap. "Clear all" is a tap BioKey sees.
 *
 * Works with launchers built on Android's own (Pixel and others): their Recents cards carry
 * the app's name, and their "Clear all" label is read from the launcher, in the phone's language.
 * Other launchers simply don't trigger it.
 */
internal class RecentsWatcher(
    private val service: AccessibilityService,
    private val onClosed: (appName: String) -> Unit,
    private val onClearAll: () -> Unit
) {
    internal companion object {
        /** Nodes of a Recents card that carry the app's name. */
        val CARD_IDS = listOf("snapshot", "task_thumbnail", "task_thumbnail_live_tile")
        /** How far up, as a share of the screen, a card must have gone to count as swiped away. */
        const val MIN_RISE = 0.12f
        /** A card tapped to open it is never "closed", however it animates. */
        const val TAP_GRACE_MS = 2_000L
        /** A burst of changes in the launcher settles this fast. */
        const val SETTLE_MS = 30L
        /**
         * While the last look found the home screen, not Recents, look at most this often: live
         * widgets (clocks, music, weather) change it all the time.
         */
        const val HOME_SCAN_INTERVAL_MS = 250L

        /**
         * Changes that only reword what is already on screen: a clock ticking, a song's title or
         * a weather reading. Recents opening adds and removes views (subtree and pane changes),
         * so on the home screen these alone are no reason to look.
         */
        private const val COSMETIC_CHANGES = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION

        /** How long to wait before looking again, [sinceLastScanMs] after the last look. */
        fun scanDelayMs(sinceLastScanMs: Long, lastFoundRecents: Boolean): Long =
            if (lastFoundRecents) SETTLE_MS else maxOf(SETTLE_MS, HOME_SCAN_INTERVAL_MS - sinceLastScanMs)

        /**
         * Whether a content change of [changeTypes] is worth a look. In Recents every change is
         * (cards move, come and go); on the home screen only one that could be Recents opening.
         * A change that doesn't say what changed (undefined, 0) is always looked at.
         */
        fun changeNeedsScan(changeTypes: Int, lastFoundRecents: Boolean): Boolean =
            lastFoundRecents || changeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED ||
                (changeTypes and COSMETIC_CHANGES.inv()) != 0

        /**
         * However well the remembered card IDs keep naming cards, every ID is read at least this
         * often, so a card under another ID (the app Recents was opened from, as a live tile)
         * that had no name yet at the first look is soon found.
         */
        const val READ_EVERY_ID_MS = 500L

        /** One look at the cards: by name, the [CARD_IDS] that named one, and whether each ID was read. */
        class CardRead<T>(val cards: Map<String, T>, val ids: Set<String>, val readEveryId: Boolean)

        /**
         * Reads the cards on screen. [read] asks the launcher for one ID's cards by name: a round
         * trip to it, run on its UI thread. A launcher keeps to the same IDs while Recents stays
         * open, so only the [remembered] ones (those that named a card at the last look) are
         * asked for. Every ID is, should they name no card or miss one of the [expected] ones
         * (it may have gone, or moved to another ID): a card is never judged gone unread.
         * Should two IDs name the same card, the first in [CARD_IDS] wins, as with every ID read.
         */
        fun <T> readCards(remembered: Set<String>?, expected: Set<String>, read: (id: String) -> Map<String, T>): CardRead<T> {
            val byId = HashMap<String, Map<String, T>>()
            fun merged() = LinkedHashMap<String, T>().also { cards ->
                for (id in CARD_IDS) byId[id]?.forEach { (name, card) -> cards.putIfAbsent(name, card) }
            }
            val known = CARD_IDS.filter { remembered?.contains(it) == true }
            for (id in known) byId[id] = read(id)
            var cards = merged()
            val readEveryId = cards.isEmpty() || !cards.keys.containsAll(expected)
            if (readEveryId) {
                for (id in CARD_IDS) if (id !in known) byId[id] = read(id)
                cards = merged()
            }
            return CardRead(cards, byId.filterValues { it.isNotEmpty() }.keys, readEveryId)
        }

        /** Slid up, whole: both edges rose, it kept its size and stayed in its column. */
        fun sweptAway(first: Rect, last: Rect, screenHeight: Int, screenWidth: Int): Boolean {
            val rise = screenHeight * MIN_RISE
            return first.top - last.top > rise &&
                first.bottom - last.bottom > rise &&
                abs(last.height() - first.height()) < first.height() * 0.15f &&
                abs(last.left - first.left) < screenWidth * 0.15f
        }
    }

    private class Card(val first: Rect) {
        val last = Rect(first)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val cards = HashMap<String, Card>()
    private var launcher: String? = null
    private var tapped: String? = null
    private var tappedAt = 0L
    private var clearAllLabels: Pair<String, Set<String>>? = null
    private var inRecents = false
    private var scannedAt = 0L
    /** The launcher, and the card IDs that named a card at the last look in its Recents. */
    private var cardIds: Pair<String, Set<String>>? = null
    private var everyCardIdReadAt = 0L
    private val scanRunnable = Runnable(::scan)

    /**
     * The launcher's content changed ([changeTypes] says how): look again, once the burst of
     * changes settles. On the home screen a widget rewording itself is no reason to.
     */
    fun onLauncherChanged(pkg: String, changeTypes: Int) {
        if (!changeNeedsScan(changeTypes, inRecents)) return
        launcher = pkg
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, scanDelayMs(SystemClock.elapsedRealtime() - scannedAt, inRecents))
    }

    /** The launcher came to the front or its windows changed: Recents may be opening, so look now. */
    fun scanSoon(pkg: String) {
        launcher = pkg
        // Recents may be opening from an app, whose card can carry an ID of its own (a live tile).
        cardIds = null
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, SETTLE_MS)
    }

    /** A tap in the launcher: "Clear all", or a card being opened. */
    fun onLauncherClicked(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val words = buildList {
            event.text.forEach { add(it.toString()) }
            event.contentDescription?.let { add(it.toString()) }
        }.map { it.trim() }.filter { it.isNotEmpty() }
        if (cards.isNotEmpty() && words.any { it.lowercase() in clearAllLabels(pkg) }) {
            onClearAll()
            reset()
            return
        }
        words.firstOrNull { it in cards }?.let {
            tapped = it
            tappedAt = SystemClock.elapsedRealtime()
        }
        scanSoon(pkg)
    }

    fun reset() {
        handler.removeCallbacks(scanRunnable)
        cards.clear()
        cardIds = null
        inRecents = false
    }

    private fun scan() {
        val pkg = launcher ?: return
        scannedAt = SystemClock.elapsedRealtime()
        val root = service.rootInActiveWindow
        if (root == null) {
            cardIds = null
            return
        }
        try {
            if (root.packageName?.toString() != pkg) {
                // Another app is in front: whatever Recents shows next is read with every ID.
                cardIds = null
                return
            }
            val panel = root.findAccessibilityNodeInfosByViewId("$pkg:id/overview_panel").orEmpty()
            panel.forEach { it.release() }
            inRecents = panel.isNotEmpty()
            // Not in Recents (the home screen): nothing was closed, whatever disappeared.
            if (!inRecents) {
                cards.clear()
                cardIds = null
                return
            }
            val remembered = cardIds?.takeIf { it.first == pkg && scannedAt - everyCardIdReadAt < READ_EVERY_ID_MS }?.second
            val read = readCards(remembered, cards.keys) { id ->
                val found = HashMap<String, Rect>()
                for (node in root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").orEmpty()) {
                    val name = node.contentDescription?.toString()?.trim().orEmpty()
                    if (name.isNotEmpty() && name !in found) found[name] = Rect().also(node::getBoundsInScreen)
                    node.release()
                }
                found
            }
            cardIds = pkg to read.ids
            if (read.readEveryId) everyCardIdReadAt = scannedAt
            val now = read.cards
            val metrics = service.resources.displayMetrics
            val recentlyTapped = tapped.takeIf { SystemClock.elapsedRealtime() - tappedAt < TAP_GRACE_MS }
            for ((name, card) in cards) {
                if (name in now || name == recentlyTapped) continue
                if (sweptAway(card.first, card.last, metrics.heightPixels, metrics.widthPixels)) onClosed(name)
            }
            cards.keys.retainAll(now.keys)
            for ((name, rect) in now) cards.getOrPut(name) { Card(rect) }.last.set(rect)
        } finally {
            root.release()
        }
    }

    /** "Clear all", lower-cased, as the launcher labels it in the phone's language (English too). */
    private fun clearAllLabels(pkg: String): Set<String> {
        clearAllLabels?.takeIf { it.first == pkg }?.let { return it.second }
        val local = runCatching {
            val res = service.packageManager.getResourcesForApplication(pkg)
            @Suppress("DiscouragedApi") // Another app's resources: only reachable by name.
            val id = res.getIdentifier("recents_clear_all", "string", pkg)
            if (id == 0) null else res.getString(id).trim().lowercase()
        }.getOrNull()
        return (setOfNotNull("clear all", local)).also { clearAllLabels = pkg to it }
    }
}
