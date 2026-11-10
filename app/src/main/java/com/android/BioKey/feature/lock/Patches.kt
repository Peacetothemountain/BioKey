package com.android.BioKey.feature.lock

/** The patches [MaskController] puts up and takes down: [WindowMask] on the phone, a fake in tests. */
internal interface Patches {
    val isEmpty: Boolean
    fun isMasking(windowId: Int): Boolean
    /** Puts up [targets] (keeping others only on [keepOn]); returns those that couldn't go up. */
    fun show(targets: Collection<WindowMask.Target>, keepOn: Set<Int> = emptySet()): List<WindowMask.Target>
    /** Adds [targets] to what's up, without taking any down; returns those that couldn't go up. */
    fun add(targets: Collection<WindowMask.Target>): List<WindowMask.Target>
    fun setHidden(hidden: Boolean)
    fun setHiddenOn(displays: Set<Int>)
    fun forget(pkg: String)
    fun clear()
    fun refreshPalette()
}
