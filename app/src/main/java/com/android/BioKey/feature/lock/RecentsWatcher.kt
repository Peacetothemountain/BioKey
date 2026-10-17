package com.android.BioKey.feature.lock

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    private val scanRunnable = Runnable(::scan)

    /** The launcher's content changed: look again, once the burst of changes settles. */
    fun onLauncherChanged(pkg: String) {
        launcher = pkg
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 30L)
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
    }

    fun reset() {
        handler.removeCallbacks(scanRunnable)
        cards.clear()
    }

    private fun scan() {
        val pkg = launcher ?: return
        val root = service.rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != pkg) return
            val panel = root.findAccessibilityNodeInfosByViewId("$pkg:id/overview_panel").orEmpty()
            panel.forEach(::release)
            // Not in Recents (the home screen): nothing was closed, whatever disappeared.
            if (panel.isEmpty()) {
                cards.clear()
                return
            }
            val now = HashMap<String, Rect>()
            for (id in CARD_IDS) {
                for (node in root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").orEmpty()) {
                    val name = node.contentDescription?.toString()?.trim().orEmpty()
                    if (name.isNotEmpty() && name !in now) now[name] = Rect().also(node::getBoundsInScreen)
                    release(node)
                }
            }
            val metrics = service.resources.displayMetrics
            val recentlyTapped = tapped.takeIf { SystemClock.elapsedRealtime() - tappedAt < TAP_GRACE_MS }
            for ((name, card) in cards) {
                if (name in now || name == recentlyTapped) continue
                if (sweptAway(card.first, card.last, metrics.heightPixels, metrics.widthPixels)) onClosed(name)
            }
            cards.keys.retainAll(now.keys)
            for ((name, rect) in now) cards.getOrPut(name) { Card(rect) }.last.set(rect)
        } finally {
            release(root)
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

    private fun release(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }
}
