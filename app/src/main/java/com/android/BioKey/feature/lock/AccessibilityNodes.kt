package com.android.BioKey.feature.lock

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
