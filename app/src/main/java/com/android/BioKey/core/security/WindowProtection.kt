package com.android.BioKey.core.security

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Window protection and anti-tapjacking utilities.
 */
object WindowProtection {

    private const val TAG = "WindowProtection"

    /**
     * FLAG_SECURE (no screenshots/recording/recents thumbnails), hides other apps' overlays
     * while this window is visible (needs HIDE_OVERLAY_WINDOWS), and drops obscured touches.
     */
    fun applyWindowProtections(activity: Activity) {
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        try {
            window.setHideOverlayWindows(true)
        } catch (e: Exception) {
            Log.w(TAG, "setHideOverlayWindows failed: ${e.message}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(false)
        }
        window.decorView.filterTouchesWhenObscured = true
        hideFromNonToolAccessibilityServices(window.decorView)
    }

    /**
     * On Android 14 and later, only real accessibility tools (screen readers, switch/voice/Braille
     * input) may read or operate this view and everything inside it: accessibility malware can't
     * read which apps are locked or tap "Pause protection", and TalkBack users are unaffected.
     * Android 12 and 13 have no such protection: there, any accessibility service the user has
     * turned on can read and operate BioKey's screens like any other app's.
     */
    fun hideFromNonToolAccessibilityServices(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
    }

    /**
     * Counts a screen reader or switch user moving through or activating what's in [view] (a
     * window's decor view) as use of BioKey, for its idle lock ([LockStateTracker.OWN_APP_IDLE_MS]):
     * TalkBack users seldom touch a window the usual way. Only what the user does counts: scrolls
     * and text changes can come from BioKey itself, and must never keep its session alive.
     */
    fun countAccessibilityInteractions(view: View) {
        view.accessibilityDelegate = OwnerInteractionDelegate
    }

    /** Whether an accessibility event of [eventType] comes from the user acting on BioKey. */
    private fun isOwnerInteraction(eventType: Int) =
        eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED

    private object OwnerInteractionDelegate : View.AccessibilityDelegate() {
        override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
            if (isOwnerInteraction(event.eventType)) LockStateTracker.onOwnAppInteraction()
            return super.onRequestSendAccessibilityEvent(host, child, event)
        }
    }

    /**
     * Counts touches and key presses in a dialog's or sheet's [window] as use of BioKey (they never
     * reach the activity's own window), and screen readers' too ([countAccessibilityInteractions]).
     * Returns how to stop.
     */
    internal fun countOwnerInteractions(window: Window): () -> Unit {
        val original = window.callback ?: return {}
        val counting = object : Window.Callback by original {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) LockStateTracker.onOwnAppInteraction()
                return original.dispatchTouchEvent(event)
            }

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                LockStateTracker.onOwnAppInteraction()
                return original.dispatchKeyEvent(event)
            }

            // The interface's default methods: passed on explicitly, so the dialog's own run.
            override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
                original.onProvideKeyboardShortcuts(data, menu, deviceId)

            override fun onPointerCaptureChanged(hasCapture: Boolean) = original.onPointerCaptureChanged(hasCapture)
        }
        window.callback = counting
        countAccessibilityInteractions(window.decorView)
        return {
            if (window.callback === counting) window.callback = original
            if (window.decorView.accessibilityDelegate === OwnerInteractionDelegate) window.decorView.accessibilityDelegate = null
        }
    }

    fun isMotionEventObscured(event: MotionEvent): Boolean {
        val obscured = (event.flags and (
            MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        )) != 0
        if (obscured && event.actionMasked == MotionEvent.ACTION_DOWN) {
            Log.w(TAG, "Rejected touch from an obscured or partially obscured window (tapjacking defense)")
        }
        return obscured
    }
}

/** Consumes touches delivered while another app's window covers this one. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.rejectObscuredTouches(onObscuredDetected: () -> Unit = {}): Modifier =
    pointerInteropFilter { motionEvent ->
        if (WindowProtection.isMotionEventObscured(motionEvent)) {
            if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
                onObscuredDetected()
            }
            true
        } else {
            false
        }
    }

/**
 * Gives the window of the dialog or sheet this is called in the same protection as BioKey's
 * main window ([WindowProtection.applyWindowProtections]): dialogs and bottom sheets are windows
 * of their own, and don't inherit it. Using it counts as using BioKey, as in the main window.
 */
@Composable
fun ProtectDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.let {
            it.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            runCatching { it.setHideOverlayWindows(true) }
            it.decorView.filterTouchesWhenObscured = true
            WindowProtection.hideFromNonToolAccessibilityServices(it.decorView)
        }
        val stopCounting = window?.let(WindowProtection::countOwnerInteractions)
        onDispose { stopCounting?.invoke() }
    }
}
