package com.android.BioKey.core.security

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter

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
     * Only real accessibility tools (screen readers, switch/voice/Braille input) may read or
     * operate this view and everything inside it. Accessibility malware can't read which apps are
     * locked or tap "Pause protection"; TalkBack users are unaffected.
     */
    fun hideFromNonToolAccessibilityServices(view: android.view.View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.setAccessibilityDataSensitive(android.view.View.ACCESSIBILITY_DATA_SENSITIVE_YES)
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
