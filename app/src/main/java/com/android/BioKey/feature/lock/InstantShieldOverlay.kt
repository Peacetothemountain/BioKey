package com.android.BioKey.feature.lock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import com.android.BioKey.R
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.diagnostics.EngineTrace
import com.android.BioKey.core.diagnostics.LatencyMarks
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.ui.security.WindowProtection

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
    private val goHome: () -> Unit,
    private val onTimeout: (targetPackage: String, attempt: Int) -> Boolean,
    /**
     * Asked when the lock screen still hasn't appeared after every retry: whether the app is still
     * exposed. If so the cover stays up (with its own Unlock and Cancel) instead of going Home and
     * hiding: Home can be refused as well, and nothing else would cover the app again. For
     * compatibility mode, whose engine hides the cover once something else is in front.
     */
    private val keepCoverWhile: ((targetPackage: String) -> Boolean)? = null,
    /**
     * Take input focus while covering a locked app, so a hardware keyboard, Back and the app's
     * soft keyboard stop reaching it. Not for the accessibility engine, which reads the screen
     * through the focused window. Covers predicted from a tap never take focus.
     */
    private val takeFocus: Boolean = false,
    /**
     * Asked whenever the cover is to be taken down (by a lock screen, the engine, a Cancel):
     * whether its target still shows where only this cover hides it. Compatibility mode's case:
     * a locked app in picture-in-picture, which floats above every app, the lock screen included,
     * and which it can neither see the bounds of nor close. While it does, the cover stays, with
     * Unlock and Cancel once no prompt is up; only an unlock, the window going, the screen going
     * off or protection stopping lets it go.
     */
    private val holdWhile: ((targetPackage: String) -> Boolean)? = null
) {

    companion object {
        private const val TAG = "InstantShieldOverlay"
        private const val FAST_VERIFY_MS = 60L
        private const val TIMEOUT_MS = 700L
        private const val MAX_ATTEMPTS = 4
        /** What a held cover ([holdWhile]) says while its lock screen has nothing else to say. */

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var activeInstance: InstantShieldOverlay? = null

        /** Bumped by every cover put up, so a lock screen can tell its own cover from a newer one. */
        @Volatile
        private var coversShown = 0L

        /** The generation of the cover put up last (see [dismiss]). */
        fun currentGeneration(): Long = coversShown

        /**
         * Hides the cover if it is for [targetPackage] (any, when null) and no newer than
         * [upToGeneration]. A lock screen on its way out passes the generation it took over, so it
         * can't take down a cover an engine has just put up again for the same app.
         */
        fun dismiss(targetPackage: String? = null, upToGeneration: Long = Long.MAX_VALUE) {
            val current = activeInstance ?: return
            if ((targetPackage == null || current.targetPackage == targetPackage) && current.generation <= upToGeneration) {
                current.hide()
            }
        }

        /**
         * A lock screen's prompt for [targetPackage] came up ([showing]) or went, with [message]
         * (an error, or the lockout one when [permanentLockout]) if it left one. A cover held over
         * that lock screen ([holdWhile]) hides its buttons while the prompt is up, and after shows
         * them again with that message, as the lock screen's own cover does under it: it is all
         * the owner sees.
         */
        fun onPrompt(targetPackage: String, showing: Boolean, message: String? = null, permanentLockout: Boolean = false) {
            val current = activeInstance ?: return
            if (!current.isShowing || current.targetPackage != targetPackage) return
            current.heldStatus = if (showing) null else message?.let { it to permanentLockout }
            if (current.isHeld) {
                if (showing) current.cover.setStatus(null, permanentLockout = false) else current.showHeldStatus()
                current.cover.setActionsVisible(!showing)
            } else if (!showing && current.holdWhile?.invoke(targetPackage) == true) {
                // Held without ever being asked to go (put up over a lock screen already there):
                // the prompt went, so the cover offers what the lock screen under it does.
                current.hold(targetPackage)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val cover = LockCoverView(service).apply { visibility = View.GONE }

    var isShowing = false
        private set
    var targetPackage: String? = null
        private set
    private var generation = 0L
    private var attempt = 0
    private var isDestroyed = false
    /** Kept up when asked to go, because [holdWhile] said so; until the next cover or a real hide. */
    private var isHeld = false
    /**
     * What the last prompt for this cover's target left on its lock screen (the message, and
     * whether it is a lockout), for a held cover to say instead of the "still on screen" text ([onPrompt]).
     */
    private var heldStatus: Pair<String, Boolean>? = null
    /** The window has been added (a view only counts as attached after its first traversal). */
    private var windowAdded = false

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
        } else if (attempt < MAX_ATTEMPTS || keepCoverWhile == null) {
            Log.w(TAG, "Lock screen did not appear; failing closed")
            if (attempt >= MAX_ATTEMPTS) goHome()
            hide()
        } else if (keepCoverWhile(pkg)) {
            keepCovering(pkg)
        } else {
            hide() // It has left the screen meanwhile.
        }
    }

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        windowType,
        WindowProtection.secureFlag(service) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        // Translucent so the window is fully see-through while the cover is hidden.
        PixelFormat.TRANSLUCENT
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        // Over the system bars too: an app overlay (compatibility mode) is otherwise fitted inside
        // them, which on a large screen leaves the app showing under the taskbar.
        fitInsetsTypes = 0
        windowAnimations = 0
        // Focused (see takeFocus), the cover has no text field: the covered app's keyboard goes.
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        title = "BioKeyShield"
    }

    init {
        if (takeFocus && Build.VERSION.SDK_INT >= 33) cancelOnBack()
        mainHandler.post(::attach)
    }

    private fun attach() {
        if (isDestroyed || windowAdded) return
        try {
            applyInsets()
            windowManager.addView(cover, layoutParams)
            windowAdded = true
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

    /** Back, while the focused cover is up, is the cover's Cancel (like Back on the lock screen). */
    @RequiresApi(33)
    private fun cancelOnBack() {
        val onBack = OnBackInvokedCallback { if (isShowing && !isPredicted) cover.onCancel?.invoke() }
        cover.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, onBack)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(onBack)
            }
        })
    }

    fun onConfigurationChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::onConfigurationChanged)
            return
        }
        cover.refreshPalette()
        applyInsets()
        if (windowAdded) runCatching { windowManager.updateViewLayout(cover, layoutParams) }
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
        if (!showCover(targetPackage, onTapped, focusable = false)) return
        isPredicted = true
        predictionExpired = onExpired
        mainHandler.removeCallbacks(predictionRunnable)
        mainHandler.postDelayed(predictionRunnable, waitMs)
    }

    /**
     * Covers [targetPackage] and checks that its lock screen appears. Returns false if the cover
     * couldn't be put up. True doesn't mean it is seen: Android hides an application overlay
     * whose permission was withdrawn without telling the app.
     */
    fun show(targetPackage: String, onTapped: () -> Unit): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(targetPackage, onTapped) }
            return true
        }
        if (isDestroyed) return false
        if (isShowing && this.targetPackage == targetPackage) {
            if (isPredicted) {
                // The predicted app arrived: from here on it's a regular cover.
                endPrediction()
                cover.setOnClickListener { tapped(onTapped) }
                cover.onUnlock = { tapped(onTapped) }
                if (takeFocus) {
                    setFocusable(true)
                    runCatching { windowManager.updateViewLayout(cover, layoutParams) }
                }
                startVerification()
            }
            return true
        }
        if (isShowing) endPrediction()
        if (!showCover(targetPackage, onTapped, focusable = takeFocus)) return false
        startVerification()
        return true
    }

    /** A tap on the cover (or its Unlock): what the engine is told, recorded for a replay ([EngineTrace]). */
    private fun tapped(onTapped: () -> Unit) {
        EngineTrace.record("s.tap", targetPackage)
        onTapped()
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

    private fun showCover(targetPackage: String, onTapped: () -> Unit, focusable: Boolean): Boolean {
        this.targetPackage = targetPackage
        attempt = 0
        isHeld = false
        heldStatus = null
        generation = ++coversShown
        bindTarget(targetPackage)
        // Same look as the lock activity while its prompt comes up: identity only, no buttons.
        cover.setActionsVisible(false)
        cover.setOnClickListener { tapped(onTapped) }
        cover.onUnlock = { tapped(onTapped) }
        cover.onCancel = {
            EngineTrace.record("s.cancel", targetPackage)
            LockStateTracker.markLockAbandoned(targetPackage)
            if (targetPackage != BioKeyAccessibilityService.SELF_PROTECTION_PENDING_KEY ||
                !BioKeyAccessibilityService.dismissSelfProtection()
            ) {
                goHome()
            }
            hide()
        }
        // After a lockout (a held cover saying so) the primary button locks the phone, as on the
        // lock screen, where that can be done; it is hidden where it can't.
        cover.onLockDevice = {
            EngineTrace.record("s.lockDevice", targetPackage)
            LockStateTracker.markLockAbandoned(targetPackage)
            if (!BioKeyAccessibilityService.lockDevice()) goHome()
            hide()
        }
        cover.visibility = View.VISIBLE
        layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        setFocusable(focusable)
        if (!putUp()) {
            // Nothing covers: the caller fails closed. The cover mustn't be left touchable and
            // visible in a window that may come back by itself.
            cover.visibility = View.GONE
            layoutParams.flags = layoutParams.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            return false
        }
        isShowing = true
        LatencyMarks.markOnNextDraw(cover, "cover", targetPackage)
        activeInstance = this
        return true
    }

    /**
     * Applies [layoutParams] to the window, adding it if it isn't there. A window Android has lost
     * (the view no longer attached) is added again, once: kept as if it were there, every cover
     * after it would fail the same way until the service restarted.
     */
    private fun putUp(): Boolean {
        if (windowAdded) {
            try {
                windowManager.updateViewLayout(cover, layoutParams)
                return true
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Shield window was lost; adding it again")
                windowAdded = false
            } catch (e: Exception) {
                Log.e(TAG, "Could not show shield: ${e.javaClass.simpleName}")
                return false
            }
        }
        return try {
            applyInsets()
            windowManager.addView(cover, layoutParams)
            windowAdded = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not show shield: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun setFocusable(focusable: Boolean) {
        layoutParams.flags = if (focusable) {
            layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
    }

    private fun bindTarget(target: String) {
        if (target == BioKeyAccessibilityService.SELF_PROTECTION_PENDING_KEY) {
            cover.bind(service.getString(R.string.brand_name), null, isBioKey = true)
            return
        }
        val cached = AppInfoCache.get(target)
        if (cached != null) {
            cover.bind(cached.label, cached.icon, isBioKey = false, accent = cached.accent)
            return
        }
        // Nothing is asked of the package manager before the first frame: the name, when it isn't
        // known yet, comes with the icon a moment later.
        val label = AppInfoCache.cachedLabel(target)
        cover.bind(label.orEmpty(), null, isBioKey = false)
        AppInfoCache.load(service, target) { loaded ->
            if (isShowing && targetPackage == target) {
                if (label == null) cover.setLabel(loaded.label)
                cover.setIcon(loaded.icon, loaded.accent)
            }
        }
    }

    /**
     * Every retry failed and the app is still in front: go Home if Android allows it, and until
     * something else is in front keep covering, now with Unlock (one more try) and Cancel.
     */
    private fun keepCovering(pkg: String) {
        DiagnosticLog.log("Lock screen for $pkg didn't appear; kept it covered")
        // No longer waiting for a lock screen: the engine hides the cover when the app leaves.
        LockStateTracker.clearRegularLockPending(pkg)
        cover.setStatus(cover.context.getString(R.string.cover_prompt_failed), permanentLockout = false)
        cover.setActionsVisible(true)
        goHome()
    }

    private fun armTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    /** Takes the cover down, unless [holdWhile] says its target still needs it. */
    fun hide() = hide(force = false)

    /**
     * Asked to go while [target] still shows where only this cover hides it ([holdWhile]): it
     * stays, without the checks that would bring the lock screen up again by themselves (the
     * owner may have just cancelled it), and with Unlock and Cancel unless a prompt is up.
     */
    private fun hold(target: String) {
        if (!isHeld) DiagnosticLog.log("Kept $target covered: it is still on screen")
        isHeld = true
        val prompting = SecurityEngine.INSTANCE.isAuthenticatingFor(target)
        if (prompting) cover.setStatus(null, permanentLockout = false) else showHeldStatus()
        cover.setActionsVisible(!prompting)
    }

    /** A held cover says what the last prompt left on its lock screen, or else the "still on screen" text. */
    private fun showHeldStatus() {
        val (message, lockout) = heldStatus ?: (cover.context.getString(R.string.cover_held) to false)
        cover.setStatus(message, lockout)
    }

    private fun hide(force: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hide(force) }
            return
        }
        mainHandler.removeCallbacks(fastVerifyRunnable)
        mainHandler.removeCallbacks(timeoutRunnable)
        endPrediction()
        if (!isShowing) return
        val target = targetPackage
        if (!force && !isDestroyed && target != null && holdWhile?.invoke(target) == true) {
            hold(target)
            return
        }
        isShowing = false
        isHeld = false
        targetPackage = null
        if (activeInstance === this) activeInstance = null
        try {
            cover.visibility = View.GONE
            layoutParams.flags = layoutParams.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (windowAdded) windowManager.updateViewLayout(cover, layoutParams)
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
        hide(force = true)
        if (windowAdded) {
            runCatching { windowManager.removeViewImmediate(cover) }
            windowAdded = false
        }
    }
}
