package com.android.BioKey.feature.lock

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.BioKey.R

/**
 * Opaque cover shown the instant a locked app comes to the foreground, so its content is hidden
 * during the few frames before [LockScreenActivity] draws.
 *
 * Implements a solid, opaque surface (0xFF121316) matching native Android App Lock.
 * It is an accessibility overlay (no SYSTEM_ALERT_WINDOW needed), consumes every touch, and is
 * removed by the lock screen once it has drawn. If the lock screen never appears, the shield
 * fails closed: [onTimeout] decides whether to relaunch the lock or send the user home; the app
 * underneath is never simply revealed.
 */
class InstantShieldOverlay(
    private val service: AccessibilityService,
    private val onTimeout: (targetPackage: String, attempt: Int) -> Boolean
) {

    companion object {
        private const val TAG = "InstantShieldOverlay"
        private const val TIMEOUT_MS = 2_500L
        private const val MAX_ATTEMPTS = 3

        // Only set while the shield is on screen and cleared in hide(), so it never outlives the window.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var activeInstance: InstantShieldOverlay? = null

        private val iconCache = LruCache<String, Drawable>(48)

        /**
         * Dismiss the active shield. If [targetPackage] is specified, only dismisses if the active
         * shield actually belongs to that package, eliminating the cross-app dismissal race condition.
         */
        fun dismiss(targetPackage: String? = null) {
            val current = activeInstance ?: return
            if (targetPackage == null || current.targetPackage == targetPackage) {
                current.hide()
            } else {
                Log.d(TAG, "Ignoring dismiss for $targetPackage; active shield is for ${current.targetPackage}")
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var isShowing = false
    private var targetPackage: String? = null
    private var currentBounds: Rect? = null
    private var attempt = 0

    private val timeoutRunnable = Runnable {
        val pkg = targetPackage ?: return@Runnable hide()
        attempt++
        val keepShowing = attempt < MAX_ATTEMPTS && onTimeout(pkg, attempt)
        if (keepShowing) {
            armTimeout()
        } else {
            Log.w(TAG, "Lock screen did not appear for $pkg; failing closed")
            if (attempt >= MAX_ATTEMPTS) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            hide()
        }
    }

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.OPAQUE
    ).apply {
        gravity = Gravity.CENTER
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private val iconView by lazy { ImageView(service) }

    private val frame by lazy {
        val density = service.resources.displayMetrics.density
        val iconSize = (84 * density).toInt().coerceAtLeast(168)

        FrameLayout(service).apply {
            setBackgroundColor(0xFF121316.toInt())
            isClickable = true
            isFocusable = false
            filterTouchesWhenObscured = true
            // Centered adaptive icon matching native Android splash window
            addView(iconView, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        }
    }

    fun onConfigurationChanged() {
        if (isShowing && frame.isAttachedToWindow) {
            try {
                if (currentBounds != null) {
                    layoutParams.gravity = Gravity.CENTER
                    layoutParams.x = 0
                    layoutParams.y = 0
                    layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
                    layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
                }
                windowManager.updateViewLayout(frame, layoutParams)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update shield layout on configuration change: ${e.message}")
            }
        }
    }

    fun show(targetPackage: String, bounds: Rect? = null, onTapped: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(targetPackage, bounds, onTapped) }
            return
        }
        if (isShowing && this.targetPackage == targetPackage) return
        if (isShowing) hide()

        this.targetPackage = targetPackage
        this.currentBounds = bounds
        attempt = 0

        if (bounds != null && !bounds.isEmpty) {
            layoutParams.gravity = Gravity.TOP or Gravity.START
            layoutParams.x = bounds.left
            layoutParams.y = bounds.top
            layoutParams.width = bounds.width()
            layoutParams.height = bounds.height()
        } else {
            layoutParams.gravity = Gravity.CENTER
            layoutParams.x = 0
            layoutParams.y = 0
            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        }

        try {
            frame.setOnClickListener { onTapped() }
            iconView.setImageDrawable(loadIcon(targetPackage))
            if (frame.isAttachedToWindow) {
                runCatching { windowManager.removeViewImmediate(frame) }
            }
            windowManager.addView(frame, layoutParams)
            isShowing = true
            activeInstance = this
            armTimeout()
        } catch (e: Exception) {
            Log.e(TAG, "Could not attach shield: ${e.message}")
        }
    }

    fun isShowingFor(pkg: String): Boolean = isShowing && targetPackage == pkg

    private fun armTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    private fun isPasswordManager(pkg: String) =
        pkg == BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS || pkg == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER

    private fun loadIcon(targetPackage: String): Drawable? {
        val realPkg = if (isPasswordManager(targetPackage)) BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER else targetPackage
        iconCache.get(realPkg)?.let { return it }
        return try {
            service.packageManager.getApplicationIcon(realPkg).also { iconCache.put(realPkg, it) }
        } catch (e: Exception) {
            service.getDrawable(R.drawable.ic_lock_key)
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hide() }
            return
        }
        mainHandler.removeCallbacks(timeoutRunnable)
        if (!isShowing) return
        try {
            windowManager.removeViewImmediate(frame)
        } catch (e: Exception) {
            Log.w(TAG, "Shield removal failed: ${e.message}")
        } finally {
            iconView.setImageDrawable(null)
            isShowing = false
            targetPackage = null
            currentBounds = null
            if (activeInstance === this) activeInstance = null
        }
    }
}
