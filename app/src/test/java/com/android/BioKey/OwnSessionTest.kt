package com.android.BioKey

import android.app.Application
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.WindowProtection
import com.android.BioKey.feature.main.IntentNavigationHelper
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * BioKey's own session: what counts as using BioKey for its idle lock, and what a trip to a
 * settings page leaves behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OwnSessionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() = LockStateTracker.lockAll()

    private fun advance(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))

    /** Unlocks BioKey and lets it sit idle until its session is about to end. */
    private fun unlockAndIdle() {
        LockStateTracker.onOwnAppUnlocked()
        advance(LockStateTracker.OWN_APP_IDLE_MS - 10_000)
    }

    /** An accessibility event from a view inside a window whose decor view counts interactions. */
    private fun sendFromWindow(eventType: Int) {
        val root = FrameLayout(context)
        val decor = FrameLayout(context)
        val content = View(context)
        root.addView(decor)
        decor.addView(content)
        WindowProtection.countAccessibilityInteractions(decor)
        decor.requestSendAccessibilityEvent(content, AccessibilityEvent(eventType))
    }

    @Test
    fun aScreenReaderMovingThroughBioKeyKeepsItUnlocked() {
        unlockAndIdle()
        sendFromWindow(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        advance(30_000)
        assertTrue(LockStateTracker.isOwnAppUnlocked())

        advance(LockStateTracker.OWN_APP_IDLE_MS - 40_000)
        sendFromWindow(AccessibilityEvent.TYPE_VIEW_CLICKED)
        advance(30_000)
        assertTrue(LockStateTracker.isOwnAppUnlocked())
    }

    @Test
    fun whatBioKeyDoesByItselfDoesNotKeepItUnlocked() {
        unlockAndIdle()
        sendFromWindow(AccessibilityEvent.TYPE_VIEW_SCROLLED)
        sendFromWindow(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        sendFromWindow(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        advance(30_000)
        assertFalse(LockStateTracker.isOwnAppUnlocked())
    }

    @Test
    fun usingADialogKeepsBioKeyUnlocked() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val dialog = Dialog(activity).apply {
            setContentView(View(activity))
            show()
        }
        val window = dialog.window!!
        val stopCounting = WindowProtection.countOwnerInteractions(window)

        unlockAndIdle()
        val now = SystemClock.uptimeMillis()
        window.callback.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1f, 1f, 0))
        advance(30_000)
        assertTrue(LockStateTracker.isOwnAppUnlocked())

        advance(LockStateTracker.OWN_APP_IDLE_MS - 40_000)
        window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        advance(30_000)
        assertTrue(LockStateTracker.isOwnAppUnlocked())

        stopCounting()
        assertSame(dialog, window.callback)
        dialog.dismiss()
    }

    @Test
    fun aSettingsPageThatWontOpenTakesBackItsPasses() {
        shadowOf(context as Application).checkActivities(true)
        LockStateTracker.onOwnAppUnlocked()

        assertFalse(IntentNavigationHelper.launchFallbackCascade(context, listOf(Intent("com.example.NO_SUCH_PAGE"))))
        // Still in BioKey, still unlocked, but neither a leave pass nor self-protection is left over.
        assertTrue(LockStateTracker.hasActiveOwnerSession())
        assertFalse(LockStateTracker.isExternalNavigationActive())
        assertFalse(LockStateTracker.isSelfProtectionGranted())
    }

    @Test
    fun aSettingsPageThatOpensKeepsThem() {
        val page = ComponentName("com.example.settings", "com.example.settings.PageActivity")
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(page)
        pm.addIntentFilterForActivity(page, IntentFilter("com.example.SETTINGS_PAGE").apply { addCategory(Intent.CATEGORY_DEFAULT) })
        shadowOf(context as Application).checkActivities(true)
        LockStateTracker.onOwnAppUnlocked()

        assertTrue(IntentNavigationHelper.launchFallbackCascade(context, listOf(Intent("com.example.SETTINGS_PAGE"))))
        assertTrue(LockStateTracker.isExternalNavigationActive())
        assertTrue(LockStateTracker.isSelfProtectionGranted())
    }
}
