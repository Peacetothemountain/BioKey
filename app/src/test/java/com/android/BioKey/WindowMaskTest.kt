package com.android.BioKey

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.android.BioKey.feature.lock.WindowMask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

/**
 * Patches over locked apps' windows step aside for a prompt only where the prompt can be: a
 * locked app on another display stays covered while one is up (a fingerprint prompt has no time
 * limit). With the phone locked, every patch hides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowMaskTest {

    class Service : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    @Test
    fun aPromptHidesOnlyThePatchesOnItsOwnDisplay() {
        val external = ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        val masks = WindowMask(Robolectric.setupService(Service::class.java)) {}
        val pip = WindowMask.Target(1, "com.example.bank", Display.DEFAULT_DISPLAY, Rect(0, 0, 300, 200), inPip = true)
        val desktop = WindowMask.Target(2, "com.example.chat", external, Rect(0, 0, 800, 600), inPip = false)
        assertTrue(masks.show(listOf(pip, desktop)).isEmpty())
        assertTrue(masks.isShowing(1) && masks.isShowing(2))

        // A prompt on the phone: its patch would hide the prompt's buttons; the other display's stays.
        masks.setHiddenOn(setOf(Display.DEFAULT_DISPLAY))
        assertFalse(masks.isShowing(1))
        assertTrue(masks.isShowing(2))
        // Windows under the prompt drop out of the list: their patches are kept, not removed.
        masks.show(listOf(desktop), keepOn = setOf(Display.DEFAULT_DISPLAY))
        assertTrue(masks.isMasking(1))

        // The phone locked: every patch hides. Then the prompt and the lock are gone: all show again.
        masks.setHidden(true)
        assertFalse(masks.isShowing(1) || masks.isShowing(2))
        masks.setHidden(false)
        assertTrue(masks.isShowing(1) && masks.isShowing(2))
        masks.clear()
    }
}
