package com.android.BioKey.feature.lock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.android.BioKey.core.security.LockStateTracker

/**
 * Full-screen cover drawn the instant a locked app reaches the screen, before
 * [LockScreenActivity] has had time to start. It sits above the app's first frames (as an
 * accessibility overlay, or an application overlay in compatibility mode) and draws the same
 * [LockCoverView] as the activity, so the hand-off is seamless.
 *
 * The window is attached once and kept (hidden) for the service's lifetime: showing it is a
 * visibility flip, never a window add. Nothing slow happens before it is visible; a missing icon is
 * loaded afterwards.
 */
class InstantShieldOverlay(
    private val service: Context,
    windowType: Int,
    /**
     * Keep the window on screen while hidden, fully transparent and untouchable, and show the cover
     * by making it opaque. Android holds back an app overlay that *appears* during an app-opening
     * animation until the animation ends; one that is already there just changes opacity, at once.
     * A transparent, untouchable window doesn't count as covering anything, so touches reach the
     * apps below (even ones that refuse touches through overlays). Used by compatibility mode,
     * which only learns of a launch once that animation is under way.
     */
    private val keepWindowShown: Boolean = false,
    private val goHome: () -> Unit,
    private val onTimeout: (targetPackage: String, attempt: Int) -> Boolean
) {

    companion object {
        private const val TAG = "InstantShieldOverlay"
        private const val FAST_VERIFY_MS = 60L
        private const val TIMEOUT_MS = 700L
        private const val MAX_ATTEMPTS = 4

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var activeInstance: InstantShieldOverlay? = null

        fun dismiss(targetPackage: String? = null) {
            val current = activeInstance ?: return
            if (targetPackage == null || current.targetPackage == targetPackage) {
                current.hide()
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val cover = LockCoverView(service).apply {
        visibility = if (keepWindowShown) View.VISIBLE else View.GONE
        setMotionPaused(keepWindowShown)
    }

    var isShowing = false
        private set
    var targetPackage: String? = null
        private set
    private var attempt = 0
    private var isDestroyed = false

    private val fastVerifyRunnable = Runnable {
        val pkg = targetPackage ?: return@Runnable
        if (isShowing && (!LockScreenActivity.isLockScreenResumed || LockScreenActivity.activeLockedTarget != pkg)) {
            onTimeout(pkg, 0)
        }
    }

    private val timeoutRunnable = Runnable {
        val pkg = targetPackage ?: return@Runnable hide()
        if (LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg) return@Runnable
        attempt++
        if (attempt < MAX_ATTEMPTS && onTimeout(pkg, attempt)) {
            armTimeout()
        } else {
            Log.w(TAG, "Lock screen did not appear for $pkg; failing closed")
            if (attempt >= MAX_ATTEMPTS) goHome()
            hide()
        }
    }

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        windowType,
        // Screenshot-proof while the cover shows. A window kept on screen while hidden only gets it
        // when shown, or it could blank screenshots and recordings of everything beneath it.
        (if (keepWindowShown) 0 else WindowManager.LayoutParams.FLAG_SECURE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        // Translucent so the window is fully see-through while the cover is hidden.
        PixelFormat.TRANSLUCENT
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (keepWindowShown) alpha = 0f
        windowAnimations = 0
        title = "BioKeyShield"
    }

    init {
        mainHandler.post(::attach)
    }

    private fun attach() {
        if (isDestroyed || cover.isAttachedToWindow) return
        try {
            applyInsets()
            windowManager.addView(cover, layoutParams)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach shield overlay: ${e.message}")
        }
    }

    private fun applyInsets() {
        runCatching {
            val bars = windowManager.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            cover.applySystemBarInsets(bars.left, bars.top, bars.right, bars.bottom)
        }
    }

    fun onConfigurationChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::onConfigurationChanged)
            return
        }
        cover.refreshPalette()
        applyInsets()
        if (cover.isAttachedToWindow) runCatching { windowManager.updateViewLayout(cover, layoutParams) }
    }

    /** True while covering for a launch that was predicted from a tap but hasn't appeared yet. */
    var isPredicted = false
        private set
    private var predictionExpired: ((String) -> Unit)? = null
    private val predictionRunnable = Runnable {
        val pkg = targetPackage
        if (isShowing && isPredicted && pkg != null) predictionExpired?.invoke(pkg)
    }

    /**
     * Covers the screen the moment the user taps a locked app, before the app has a window.
     * Waits for the app to actually appear (then [show] takes over and the lock screen starts)
     * or for [waitMs], after which [onExpired] decides whether anything needs covering.
     */
    fun showPredicted(targetPackage: String, waitMs: Long, onTapped: () -> Unit, onExpired: (String) -> Unit) {
        if (isDestroyed || isShowing) return
        if (!showCover(targetPackage, onTapped)) return
        isPredicted = true
        predictionExpired = onExpired
        mainHandler.removeCallbacks(predictionRunnable)
        mainHandler.postDelayed(predictionRunnable, waitMs)
    }

    fun show(targetPackage: String, onTapped: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(targetPackage, onTapped) }
            return
        }
        if (isDestroyed) return
        if (isShowing && this.targetPackage == targetPackage) {
            if (isPredicted) {
                // The predicted app arrived: from here on it's a regular cover.
                endPrediction()
                cover.setOnClickListener { onTapped() }
                cover.onUnlock = onTapped
                startVerification()
            }
            return
        }
        if (isShowing) endPrediction()
        if (showCover(targetPackage, onTapped)) startVerification()
    }

    private fun endPrediction() {
        isPredicted = false
        predictionExpired = null
        mainHandler.removeCallbacks(predictionRunnable)
    }

    private fun startVerification() {
        attempt = 0
        mainHandler.removeCallbacks(fastVerifyRunnable)
        mainHandler.postDelayed(fastVerifyRunnable, FAST_VERIFY_MS)
        armTimeout()
    }

    private fun showCover(targetPackage: String, onTapped: () -> Unit): Boolean {
        this.targetPackage = targetPackage
        attempt = 0
        bindTarget(targetPackage)
        // Same look as the lock activity while its prompt comes up: identity only, no buttons.
        cover.setActionsVisible(false)
        cover.setOnClickListener { onTapped() }
        cover.onUnlock = onTapped
        cover.onCancel = {
            LockStateTracker.markLockAbandoned(targetPackage)
            if (targetPackage != BioKeyAccessibilityService.SELF_PROTECTION_PENDING_KEY ||
                !BioKeyAccessibilityService.dismissSelfProtection()
            ) {
                goHome()
            }
            hide()
        }
        return try {
            cover.visibility = View.VISIBLE
            layoutParams.alpha = 1f
            cover.setMotionPaused(false)
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_SECURE
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            if (cover.isAttachedToWindow) {
                windowManager.updateViewLayout(cover, layoutParams)
            } else {
                applyInsets()
                windowManager.addView(cover, layoutParams)
            }
            isShowing = true
            activeInstance = this
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not show shield: ${e.message}")
            false
        }
    }

    private fun bindTarget(target: String) {
        if (target == BioKeyAccessibilityService.SELF_PROTECTION_PENDING_KEY) {
            cover.bind("BioKey", null, isBioKey = true)
            return
        }
        val cached = AppInfoCache.get(target)
        if (cached != null) {
            cover.bind(cached.label, cached.icon, isBioKey = false)
            return
        }
        cover.bind(AppInfoCache.quickLabel(service, target), null, isBioKey = false)
        AppInfoCache.load(service, target) { loaded ->
            if (isShowing && targetPackage == target) cover.setIcon(loaded.icon)
        }
    }

    fun isShowingFor(pkg: String): Boolean = isShowing && targetPackage == pkg

    private fun armTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::hide)
            return
        }
        mainHandler.removeCallbacks(fastVerifyRunnable)
        mainHandler.removeCallbacks(timeoutRunnable)
        endPrediction()
        if (!isShowing) return
        isShowing = false
        targetPackage = null
        if (activeInstance === this) activeInstance = null
        try {
            if (keepWindowShown) {
                layoutParams.alpha = 0f
                cover.setMotionPaused(true)
                layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            } else {
                cover.visibility = View.GONE
            }
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (cover.isAttachedToWindow) windowManager.updateViewLayout(cover, layoutParams)
        } catch (e: Exception) {
            Log.w(TAG, "Shield hide failed: ${e.message}")
        }
    }

    fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::destroy)
            return
        }
        isDestroyed = true
        hide()
        if (cover.isAttachedToWindow) runCatching { windowManager.removeViewImmediate(cover) }
    }
}
