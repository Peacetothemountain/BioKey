package com.android.BioKey

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.android.BioKey.feature.lock.AccessibilityNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Where a prompt draws its sheet, read from its window ([AccessibilityNodes.contentBounds]): the
 * patches over other locked windows keep off it and stay up everywhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptContentBoundsTest {

    private val screen = Rect(0, 0, 1080, 2400)

    private fun node(bounds: Rect, text: String? = null, clickable: Boolean = false, visible: Boolean = true, vararg children: AccessibilityNodeInfo) =
        AccessibilityNodeInfo.obtain().also { n ->
            n.setBoundsInScreen(bounds)
            n.text = text
            n.isClickable = clickable
            n.isVisibleToUser = visible
            children.forEach { shadowOf(n).addChild(it) }
        }

    @Test
    fun itIsTheBoxAroundWhatThePromptSaysAndWhatCanBeTapped() {
        val root = node(
            screen, null, false, true,
            // The scrim: as large as the window, and tapping it cancels. Not the prompt's content.
            node(screen, clickable = true),
            node(Rect(300, 1300, 780, 1400), text = "Unlock Files"),
            node(Rect(440, 1750, 640, 1950), text = null, clickable = true),
            node(Rect(60, 2200, 400, 2330), text = "Use passphrase", clickable = true)
        )
        assertEquals(Rect(60, 1300, 780, 2330), AccessibilityNodes.contentBounds(root, screen, 200))
    }

    @Test
    fun whatCantBeSeenIsLeftOut() {
        val root = node(
            screen, null, false, true,
            node(Rect(300, 1300, 780, 1400), text = "Unlock Files"),
            node(Rect(0, 0, 200, 100), text = "Hidden", visible = false)
        )
        assertEquals(Rect(300, 1300, 780, 1400), AccessibilityNodes.contentBounds(root, screen, 200))
    }

    @Test
    fun withNothingToGoByThereIsNoAnswer() {
        // Only the scrim: where the prompt is can't be told, and every patch on its display hides.
        assertNull(AccessibilityNodes.contentBounds(node(screen, null, false, true, node(screen, clickable = true)), screen, 200))
    }
}
