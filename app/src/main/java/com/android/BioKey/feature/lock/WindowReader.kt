package com.android.BioKey.feature.lock

import android.os.SystemClock
import android.util.SparseArray
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.util.forEach

/**
 * How the accessibility service looks at what's on screen, in one place. Nothing is looked at
 * before setup ([setUp]), whatever asks: every look goes through [windowList] or [activeRoot] (the
 * Recents watcher and the self-protection detector are handed them too). Every display's windows
 * are read once per event ([duringEvent]), and whose each window is, once until it can be told.
 */
internal class WindowReader(
    /** Every window, by display (the service's windowsOnAllDisplays). */
    private val readWindows: () -> SparseArray<List<AccessibilityWindowInfo>>,
    /** The active window's root (the service's rootInActiveWindow). */
    private val readActiveRoot: () -> AccessibilityNodeInfo?,
    /** BioKey is set up, and its explanation of what's read has been seen. */
    private val setUp: () -> Boolean,
    /** Whose a window is, already known: from its events, or attributed. */
    private val known: (windowId: Int) -> String?,
    /** A window's app, read from the window itself: remembered as known. */
    private val learn: (windowId: Int, pkg: String) -> Unit,
    /** Each list as read (for the engine trace). */
    private val onRead: (List<AccessibilityWindowInfo>) -> Unit = {},
    /** Asks a window itself whose it is (an IPC into its app). */
    private val readPackage: (AccessibilityWindowInfo) -> String? = ::windowPackage,
    private val clock: () -> Long = SystemClock::uptimeMillis
) {
    /** While an event is handled ([duringEvent]): [windowList] as read for that event. */
    private var handlingEvent = false
    private var eventWindows: List<AccessibilityWindowInfo>? = null

    /** Windows that couldn't be read, and when that was last found ([packageOf]). */
    private val unreadableAt = HashMap<Int, Long>()

    /** The active window's root node, under the same rule as [windowList]. */
    fun activeRoot(): AccessibilityNodeInfo? = if (setUp()) readActiveRoot() else null

    /** Handles one event: the windows are read at most once meanwhile. */
    fun <T> duringEvent(block: () -> T): T {
        handlingEvent = true
        try {
            return block()
        } finally {
            handlingEvent = false
            eventWindows = null
        }
    }

    /**
     * Every window on every display, the phone's own screen first (each display's top first); null
     * before setup, or if they can't be read. Read once per event: handling one asks for them up
     * to four times, and nothing changes on screen in between, as the next event says.
     */
    fun windowList(): List<AccessibilityWindowInfo>? {
        if (!setUp()) return null
        if (handlingEvent) eventWindows?.let { return it }
        val list: List<AccessibilityWindowInfo>? = try {
            val byDisplay = readWindows()
            buildList {
                byDisplay[Display.DEFAULT_DISPLAY]?.let { addAll(it) }
                byDisplay.forEach { display, windows -> if (display != Display.DEFAULT_DISPLAY) addAll(windows) }
            }
        } catch (_: Exception) {
            null
        }
        if (handlingEvent) eventWindows = list
        if (list != null) onRead(list)
        return list
    }

    /**
     * Whose window [w] is: learned from its events, attributed, or read from the window itself. A
     * window that can't be read (BioKey's own, a banking app's) isn't asked again for
     * [UNREADABLE_RECHECK_MS]: every visibility pass would ask it, all the while a lock screen is
     * up. Asked again after that, as one still being made can become readable.
     */
    fun packageOf(w: AccessibilityWindowInfo): String? = packageOf(w.id) { readPackage(w) }

    /** [packageOf], by window id, reading with [read]. */
    fun packageOf(windowId: Int, read: () -> String?): String? {
        known(windowId)?.let { return it }
        val now = clock()
        unreadableAt[windowId]?.let { if (now - it < UNREADABLE_RECHECK_MS) return null }
        val pkg = read()
        if (pkg == null) {
            unreadableAt[windowId] = now
        } else {
            unreadableAt.remove(windowId)
            learn(windowId, pkg)
        }
        return pkg
    }

    /** Found unreadable lately: not asked again yet. */
    fun wasUnreadable(windowId: Int) = windowId in unreadableAt

    /** Windows gone from the screen are forgotten. */
    fun forgetAllBut(liveIds: Set<Int>) {
        unreadableAt.keys.retainAll(liveIds)
    }

    companion object {
        const val UNREADABLE_RECHECK_MS = 1_000L

        /** The window the user is using: the active one, else a focused one. */
        fun frontWindow(list: List<AccessibilityWindowInfo>): AccessibilityWindowInfo? {
            val apps = list.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            return apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused }
        }

        /** Asks the window itself whose it is (an IPC): when it appeared without a state event. */
        fun windowPackage(window: AccessibilityWindowInfo): String? {
            val root = try { window.root } catch (_: Exception) { null } ?: return null
            return root.packageName?.toString().also { root.release() }
        }
    }
}
