package com.android.BioKey.feature.lock

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Hands a node back once read. Before Android 13 nodes were pooled and had to be recycled; from
 * 13 on recycling does nothing, so it is skipped. Never throws: a node recycled twice must not
 * crash the screen-reading code that called this.
 */
internal fun AccessibilityNodeInfo.release() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        @Suppress("DEPRECATION")
        runCatching { recycle() }
    }
}

internal object AccessibilityNodes {
    /**
     * The words under [root] (each node's text and description, trimmed, blank ones left out), in
     * the order a screen reader would come to them, from at most [maxNodes] nodes. [root] itself
     * stays the caller's to release.
     */
    fun texts(root: AccessibilityNodeInfo, maxNodes: Int): List<String> {
        val found = ArrayList<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            try {
                if (++seen > maxNodes) continue
                node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(found::add)
                node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(found::add)
                for (i in 0 until node.childCount) runCatching { node.getChild(i) }.getOrNull()?.let(queue::add)
            } finally {
                if (node !== root) node.release()
            }
        }
        return found
    }

    /**
     * Where the window under [root] draws what it has to say: the box around every node that
     * shows words or can be tapped, leaving out any as large as the window itself ([window]: a
     * scrim, the root). For a prompt, that is its sheet or dialog, not the dimmed screen around
     * it. Null if nothing of the kind is found among the first [maxNodes] nodes. [root] itself
     * stays the caller's to release.
     */
    fun contentBounds(root: AccessibilityNodeInfo, window: Rect, maxNodes: Int): Rect? {
        var area: Rect? = null
        val box = Rect()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            try {
                if (++seen > maxNodes) continue
                val says = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() || node.isClickable
                if (says && node.isVisibleToUser) {
                    node.getBoundsInScreen(box)
                    val whole = box.width() >= window.width() && box.height() >= window.height()
                    if (!box.isEmpty && !whole) area?.union(box) ?: run { area = Rect(box) }
                }
                for (i in 0 until node.childCount) runCatching { node.getChild(i) }.getOrNull()?.let(queue::add)
            } finally {
                if (node !== root) node.release()
            }
        }
        return area
    }
}
