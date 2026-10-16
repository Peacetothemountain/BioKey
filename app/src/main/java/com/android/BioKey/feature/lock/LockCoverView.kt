package com.android.BioKey.feature.lock

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import com.android.BioKey.R
import com.android.BioKey.core.security.WindowProtection
import com.android.BioKey.ui.brand.VineView

/**
 * The lock cover, shared by [InstantShieldOverlay] (drawn the instant a locked app shows up) and
 * [LockScreenActivity] (which hosts the system prompt). Both draw this exact view with the same
 * colours as the activity's window background, so the hand-off between them is invisible.
 *
 * The locked app's icon sits in a thin ring over a soft glow in the app's own colour
 * ([CoverBackdrop]), on a background tinted with the wallpaper's Material You colours; no text,
 * since the icon says which app and the system prompt says what to do. A small, still BioKey mark
 * shows who is asking.
 *
 * Plain Views on purpose: no Compose start-up cost on the path between a locked app appearing and
 * the fingerprint/face prompt.
 */
@SuppressLint("ViewConstructor")
class LockCoverView(context: Context) : FrameLayout(context) {

    /** Material You roles, the same dynamic scheme as BioKey's own screens (Android 12+). */
    class Palette(context: Context) {
        private val scheme = if (
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        ) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        val surface = scheme.surface.toArgb()
        val surfaceContainer = scheme.surfaceContainerHigh.toArgb()
        /** The wallpaper's colour washing down from the top. */
        val wash = scheme.primaryContainer.toArgb()
        val primary = scheme.primary.toArgb()
        val onPrimary = scheme.onPrimary.toArgb()
        val tertiary = scheme.tertiary.toArgb()
        val error = scheme.error.toArgb()
    }

    private companion object {
        const val ICON_DP = AppInfoCache.ICON_DP
        const val HUE_BUCKETS = 18
    }

    var onUnlock: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onLockDevice: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val backdrop = CoverBackdrop(context)

    /** BioKey's two-layer wordmark, held still: the cover is no place for motion. */
    private fun wordmark() = FrameLayout(context).apply {
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_title_logo_letters)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(VineView(context, animated = false), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Large logo: the identity when BioKey itself is locked. */
    private val logo = wordmark().apply { visibility = GONE }

    /** Small logo at the bottom while the system prompt is up: who is asking. */
    private val brandMark = wordmark().apply { alpha = 0.55f }

    private val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clipToOutline = true
    }

    private val status = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        visibility = GONE
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private val primaryButton = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { if (lockoutMode) onLockDevice?.invoke() else onUnlock?.invoke() }
    }

    private val cancelButton = TextView(context).apply {
        text = "Cancel"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { onCancel?.invoke() }
    }

    private val actions = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(primaryButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        addView(cancelButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            topMargin = dp(8)
        })
    }

    private val identity = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(logo, LinearLayout.LayoutParams(dp(280), dp(118)))
        addView(icon, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
        addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(36)
        })
    }

    private var lockoutMode = false
    private var palette = Palette(context)

    init {
        isClickable = true
        filterTouchesWhenObscured = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        WindowProtection.hideFromNonToolAccessibilityServices(this)

        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(identity, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(32)
            rightMargin = dp(32)
            bottomMargin = dp(96)
        })
        addView(brandMark, LayoutParams(dp(120), dp(50), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(40)
        })
        addView(actions, LayoutParams(minOf(resources.displayMetrics.widthPixels - dp(64), dp(360)), LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        applyPalette()
    }

    private val iconLocation = IntArray(2)
    private val backdropLocation = IntArray(2)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The glow and ring surround the app icon. Measured in the backdrop's own coordinates
        // (window positions, not parent offsets), so padding for system bars, margins and the
        // window type can't shift the ring away from the icon.
        if (icon.visibility == VISIBLE && icon.width > 0) {
            icon.getLocationInWindow(iconLocation)
            backdrop.getLocationInWindow(backdropLocation)
            backdrop.setFocus(
                iconLocation[0] - backdropLocation[0] + icon.width / 2f,
                iconLocation[1] - backdropLocation[1] + icon.height / 2f,
                icon.width / 2f
            )
        } else {
            backdrop.setFocus(0f, 0f, 0f)
        }
    }

    /** Re-resolves colours, e.g. after a dark-mode switch. */
    fun refreshPalette() {
        palette = Palette(context)
        applyPalette()
    }

    private fun applyPalette() {
        val p = palette
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(blend(p.surface, p.wash, 0.5f), blend(p.surface, p.wash, 0.16f), p.surface)
        )
        backdrop.setColors(p.primary, p.surfaceContainer)
        status.setTextColor(p.error)
        cancelButton.setTextColor(p.primary)
        cancelButton.background = ripple(Color.TRANSPARENT, p.primary)
        primaryButton.setTextColor(p.onPrimary)
        primaryButton.background = ripple(p.primary, p.onPrimary)
        for (mark in listOf(logo, brandMark)) {
            (mark.getChildAt(0) as ImageView).setColorFilter(p.primary, PorterDuff.Mode.SRC_IN)
            (mark.getChildAt(1) as VineView).setTint(p.tertiary)
        }
    }

    private fun ripple(fill: Int, rippleColor: Int): Drawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(fill)
        }
        val mask = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(Color.WHITE)
        }
        val alpha = (Color.alpha(rippleColor) * 0.16f).toInt()
        return RippleDrawable(ColorStateList.valueOf(Color.argb(alpha, Color.red(rippleColor), Color.green(rippleColor), Color.blue(rippleColor))), shape, mask)
    }

    /** Shows a locked app's icon (or BioKey's logo when [isBioKey]); [label] is for screen readers. */
    fun bind(label: String, appIcon: Drawable?, isBioKey: Boolean) {
        logo.visibility = if (isBioKey) VISIBLE else GONE
        icon.visibility = if (isBioKey) GONE else VISIBLE
        brandMark.visibility = if (isBioKey || actions.visibility == VISIBLE) GONE else VISIBLE
        setIcon(appIcon)
        contentDescription = if (isBioKey) "BioKey is locked" else "$label is locked"
        setStatus(null, permanentLockout = false)
        requestLayout()
    }

    fun setIcon(appIcon: Drawable?) {
        if (appIcon != null) {
            // Cached icons are shared with the other cover; each view needs its own instance.
            icon.setImageDrawable(appIcon.constantState?.newDrawable(resources)?.mutate() ?: appIcon)
            icon.setPadding(0, 0, 0, 0)
            backdrop.setAccent(appColor(appIcon))
        } else {
            icon.setImageDrawable(context.getDrawable(R.drawable.ic_lock_key)?.mutate()?.apply { setTint(palette.primary) })
            icon.setPadding(dp(26), dp(26), dp(26), dp(26))
            backdrop.setAccent(null)
        }
    }

    /**
     * The icon's own colour for the glow: its most prominent vivid hue (white, black and grey
     * backgrounds don't count), so a red logo on a white tile glows red rather than a muddy
     * average. Null if the icon has no colour (monochrome), and BioKey's own colour is used.
     */
    private fun appColor(drawable: Drawable): Int? = runCatching {
        val sample = drawable.constantState?.newDrawable(resources)?.mutate() ?: return null
        val n = 24
        val bmp = createBitmap(n, n)
        sample.setBounds(0, 0, n, n)
        sample.draw(Canvas(bmp))
        // Hue buckets weighted by how vivid each pixel is; then the average colour of the winner.
        val weight = FloatArray(HUE_BUCKETS)
        val sums = Array(HUE_BUCKETS) { FloatArray(3) }
        val hsv = FloatArray(3)
        for (x in 0 until n) for (y in 0 until n) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) < 160) continue
            Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.25f || hsv[2] < 0.25f) continue
            val bucket = (hsv[0] / 360f * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
            val w = hsv[1] * hsv[2]
            weight[bucket] += w
            sums[bucket][0] += Color.red(c) * w
            sums[bucket][1] += Color.green(c) * w
            sums[bucket][2] += Color.blue(c) * w
        }
        bmp.recycle()
        val best = weight.indices.maxBy { weight[it] }
        if (weight[best] < n * n * 0.03f) return null
        val s = sums[best]
        Color.colorToHSV(Color.rgb((s[0] / weight[best]).toInt(), (s[1] / weight[best]).toInt(), (s[2] / weight[best]).toInt()), hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.65f, 0.95f)
        Color.HSVToColor(hsv)
    }.getOrNull()

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    fun setStatus(message: String?, permanentLockout: Boolean) {
        lockoutMode = permanentLockout
        status.text = message
        status.visibility = if (message.isNullOrBlank()) GONE else VISIBLE
        primaryButton.text = if (permanentLockout) "Lock screen" else "Unlock"
    }

    /** Hides the buttons while the system prompt is up, like Android's own App Lock. */
    fun setActionsVisible(visible: Boolean) {
        actions.visibility = if (visible) VISIBLE else INVISIBLE
        brandMark.visibility = if (visible || logo.visibility == VISIBLE) GONE else VISIBLE
    }

    /** Overlay windows don't always get insets dispatched; the shield passes them in. */
    fun applySystemBarInsets(left: Int, top: Int, right: Int, bottom: Int) {
        setPadding(left, top, right, bottom)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        setPadding(bars.left, bars.top, bars.right, bars.bottom)
        return insets
    }
}
