package com.android.BioKey.feature.lock

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import com.android.BioKey.R
import com.android.BioKey.core.security.LockStateTracker

/**
 * Instant full-screen cover displayed the millisecond a locked app comes to foreground.
 *
 * Implements a 1:1 visual match with [LockScreenUIExpressive]:
 * - Dynamic Material surface tone matching user theme
 * - Centered 88dp circular app icon
 * - Bold app label with "$appLabel is locked" subtitle
 * - Full-width 56dp primary pill Unlock button
 * - Clean Exit action button
 *
 * Eliminates all layout jumps, pop-in, and square box anomalies during transition to [LockScreenActivity].
 */
class InstantShieldOverlay(
    private val service: AccessibilityService,
    private val onTimeout: (targetPackage: String, attempt: Int) -> Boolean
) {

    companion object {
        private const val TAG = "InstantShieldOverlay"
        private const val FAST_VERIFY_MS = 45L
        private const val TIMEOUT_MS = 650L
        private const val MAX_ATTEMPTS = 4

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var activeInstance: InstantShieldOverlay? = null

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
    var isShowing = false
        private set
    var targetPackage: String? = null
        private set
    private var attempt = 0

    private val fastVerifyRunnable = Runnable {
        val pkg = targetPackage ?: return@Runnable
        if (!isShowing) return@Runnable
        if (!LockScreenActivity.isLockScreenResumed || LockScreenActivity.activeLockedTarget != pkg) {
            onTimeout(pkg, 0)
        }
    }

    private val timeoutRunnable = Runnable {
        val pkg = targetPackage ?: return@Runnable hide()
        if (LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg) {
            return@Runnable
        }
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
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.CENTER
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun resolveColorScheme(context: Context) =
        if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
            dynamicDarkColorScheme(context)
        else
            dynamicLightColorScheme(context)

    private val bioKeyLettersView by lazy {
        ImageView(service).apply {
            setImageDrawable(service.getDrawable(R.drawable.ic_title_logo_letters))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    private val bioKeyTentaclesView by lazy {
        ImageView(service).apply {
            setImageDrawable(service.getDrawable(R.drawable.ic_title_logo_tentacles))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    private val bioKeyLogoLayout by lazy {
        val density = service.resources.displayMetrics.density
        val w = (340 * density).toInt()
        val h = (144 * density).toInt()
        FrameLayout(service).apply {
            layoutParams = LinearLayout.LayoutParams(w, h).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * density).toInt()
            }
            visibility = View.GONE
            addView(bioKeyLettersView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(bioKeyTentaclesView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
    }

    private val iconView by lazy {
        ImageView(service).apply {
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
    }

    private val titleView by lazy {
        TextView(service).apply {
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
    }

    private val subtitleView by lazy {
        TextView(service).apply {
            textSize = 16f
            gravity = Gravity.CENTER
        }
    }

    private val unlockButtonView by lazy {
        val density = service.resources.displayMetrics.density
        LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28 * density
            }

            val icon = ImageView(service).apply {
                setImageDrawable(service.getDrawable(R.drawable.ic_fingerprint))
                layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt())
            }
            val text = TextView(service).apply {
                setText("Unlock")
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding((10 * density).toInt(), 0, 0, 0)
            }
            addView(icon)
            addView(text)
        }
    }

    private val exitButtonView by lazy {
        val density = service.resources.displayMetrics.density
        TextView(service).apply {
            setText("Exit")
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
        }
    }

    private val contentLayout by lazy {
        val density = service.resources.displayMetrics.density
        val iconSize = (88 * density).toInt()
        val padH = (32 * density).toInt()
        val padV = (24 * density).toInt()
        LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padH, padV, padH, padV)

            addView(bioKeyLogoLayout)
            addView(iconView, LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * density).toInt()
            })
            addView(titleView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(subtitleView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (4 * density).toInt()
                bottomMargin = (36 * density).toInt()
            })
            addView(unlockButtonView)
            addView(exitButtonView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (48 * density).toInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (10 * density).toInt()
            })
        }
    }

    private val frame by lazy {
        val density = service.resources.displayMetrics.density
        // 404dp total width minus 2 * 32dp horizontal padding = 340dp button width (matching LockScreenUIExpressive)
        val maxContentWidth = (404 * density).toInt()

        FrameLayout(service).apply {
            val colorScheme = resolveColorScheme(service)
            setBackgroundColor(colorScheme.surface.toArgb())
            isClickable = true
            isFocusable = false
            filterTouchesWhenObscured = true
            visibility = View.GONE

            val contentParams = FrameLayout.LayoutParams(
                minOf(service.resources.displayMetrics.widthPixels, maxContentWidth),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            addView(contentLayout, contentParams)
        }
    }

    private fun applyWindowInsets() {
        val density = service.resources.displayMetrics.density
        val maxContentWidth = (404 * density).toInt()
        val insets = runCatching {
            windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        }.getOrNull()
        if (insets != null) {
            frame.setPadding(insets.left, insets.top, insets.right, insets.bottom)
        }
        (contentLayout.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = minOf(service.resources.displayMetrics.widthPixels, maxContentWidth)
            contentLayout.layoutParams = lp
        }
    }

    private var isDestroyed = false
    private val preWarmRunnable = Runnable { preWarm() }

    init {
        mainHandler.post(preWarmRunnable)
    }

    private fun preWarm() {
        if (isDestroyed) return
        if (!frame.isAttachedToWindow) {
            try {
                applyWindowInsets()
                windowManager.addView(frame, layoutParams)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-warm shield overlay: ${e.message}")
            }
        }
    }

    fun onConfigurationChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onConfigurationChanged() }
            return
        }
        if (!isShowing || !frame.isAttachedToWindow) return

        try {
            val colorScheme = resolveColorScheme(service)
            frame.setBackgroundColor(colorScheme.surface.toArgb())
            titleView.setTextColor(colorScheme.onSurface.toArgb())
            subtitleView.setTextColor(colorScheme.onSurfaceVariant.toArgb())
            bioKeyLettersView.setColorFilter(colorScheme.primary.toArgb(), PorterDuff.Mode.MULTIPLY)
            bioKeyTentaclesView.setColorFilter(colorScheme.tertiary.toArgb(), PorterDuff.Mode.MULTIPLY)
            (unlockButtonView.background as? GradientDrawable)?.setColor(colorScheme.primary.toArgb())
            (unlockButtonView.getChildAt(0) as? ImageView)?.setColorFilter(colorScheme.onPrimary.toArgb())
            (unlockButtonView.getChildAt(1) as? TextView)?.setTextColor(colorScheme.onPrimary.toArgb())
            exitButtonView.setTextColor(colorScheme.onSurfaceVariant.toArgb())

            applyWindowInsets()
            layoutParams.gravity = Gravity.CENTER
            layoutParams.x = 0
            layoutParams.y = 0
            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
            windowManager.updateViewLayout(frame, layoutParams)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update shield layout on configuration change: ${e.message}")
        }
    }

    fun show(targetPackage: String, onTapped: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(targetPackage, onTapped) }
            return
        }
        if (isShowing && this.targetPackage == targetPackage) return
        if (isShowing) hide()

        this.targetPackage = targetPackage
        attempt = 0

        layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        layoutParams.gravity = Gravity.CENTER
        layoutParams.x = 0
        layoutParams.y = 0
        layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT

        try {
            val colorScheme = resolveColorScheme(service)
            frame.setBackgroundColor(colorScheme.surface.toArgb())
            applyWindowInsets()

            val isBioKey = targetPackage == BioKeyAccessibilityService.SELF_PROTECTION_PENDING_KEY
            val realPkg = if (isPasswordManager(targetPackage)) BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER else targetPackage
            val cached = if (!isBioKey) {
                AppInfoCache.get(realPkg) ?: AppInfoCache.loadAndCache(service, service.packageManager, realPkg)
            } else null

            if (isBioKey) {
                bioKeyLogoLayout.visibility = View.VISIBLE
                iconView.visibility = View.GONE
                bioKeyLettersView.setColorFilter(colorScheme.primary.toArgb(), PorterDuff.Mode.MULTIPLY)
                bioKeyTentaclesView.setColorFilter(colorScheme.tertiary.toArgb(), PorterDuff.Mode.MULTIPLY)
                titleView.visibility = View.GONE
                subtitleView.text = "BioKey is locked"
                subtitleView.setTextColor(colorScheme.onSurfaceVariant.toArgb())
            } else {
                bioKeyLogoLayout.visibility = View.GONE
                iconView.visibility = View.VISIBLE
                titleView.visibility = View.VISIBLE

                val appLabel = when {
                    cached != null -> cached.label
                    realPkg == BioKeyAccessibilityService.PKG_CREDENTIAL_MANAGER -> "Google Password Manager"
                    else -> {
                        runCatching {
                            val appInfo = service.packageManager.getApplicationInfo(realPkg, 0)
                            service.packageManager.getApplicationLabel(appInfo).toString()
                        }.getOrDefault("Protected Application")
                    }
                }

                iconView.setImageDrawable(cached?.iconDrawable ?: service.getDrawable(R.drawable.ic_lock_key))
                titleView.text = appLabel
                titleView.setTextColor(colorScheme.onSurface.toArgb())

                subtitleView.text = "$appLabel is locked"
                subtitleView.setTextColor(colorScheme.onSurfaceVariant.toArgb())
            }

            val pillDrawable = unlockButtonView.background as? GradientDrawable
            pillDrawable?.setColor(colorScheme.primary.toArgb())
            (unlockButtonView.getChildAt(0) as? ImageView)?.setColorFilter(colorScheme.onPrimary.toArgb())
            (unlockButtonView.getChildAt(1) as? TextView)?.setTextColor(colorScheme.onPrimary.toArgb())

            exitButtonView.setTextColor(colorScheme.onSurfaceVariant.toArgb())
            exitButtonView.setOnClickListener {
                LockStateTracker.markLockAbandoned(targetPackage)
                if (targetPackage == BioKeyAccessibilityService.SELF_PROTECTION_PENDING_KEY) {
                    if (!BioKeyAccessibilityService.dismissSelfProtection()) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    }
                } else {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }
                hide()
            }
            unlockButtonView.setOnClickListener { onTapped() }
            frame.setOnClickListener { onTapped() }

            if (!frame.isAttachedToWindow) {
                windowManager.addView(frame, layoutParams)
            } else {
                windowManager.updateViewLayout(frame, layoutParams)
            }
            frame.visibility = View.VISIBLE

            isShowing = true
            activeInstance = this
            mainHandler.removeCallbacks(fastVerifyRunnable)
            mainHandler.postDelayed(fastVerifyRunnable, FAST_VERIFY_MS)
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

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hide() }
            return
        }
        mainHandler.removeCallbacks(fastVerifyRunnable)
        mainHandler.removeCallbacks(timeoutRunnable)
        if (!isShowing) return

        try {
            frame.visibility = View.GONE
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (frame.isAttachedToWindow) {
                windowManager.updateViewLayout(frame, layoutParams)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shield warm hide failed: ${e.message}")
        } finally {
            iconView.setImageDrawable(null)
            titleView.text = null
            subtitleView.text = null
            bioKeyLogoLayout.visibility = View.GONE
            iconView.visibility = View.VISIBLE
            titleView.visibility = View.VISIBLE
            isShowing = false
            targetPackage = null
            if (activeInstance === this) activeInstance = null
        }
    }

    fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { destroy() }
            return
        }
        isDestroyed = true
        mainHandler.removeCallbacks(preWarmRunnable)
        hide()
        if (frame.isAttachedToWindow) {
            runCatching { windowManager.removeViewImmediate(frame) }
        }
    }
}
