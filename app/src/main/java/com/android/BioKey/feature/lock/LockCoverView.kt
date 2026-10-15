package com.android.BioKey.feature.lock

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
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
import com.android.BioKey.R
import com.android.BioKey.core.security.WindowProtection

/**
 * The lock cover, shared by [InstantShieldOverlay] (drawn the instant a locked app shows up) and
 * [LockScreenActivity] (which hosts the system prompt). Both draw this exact view with the same
 * colours as the activity's window background, so the hand-off between them is invisible.
 *
 * The locked app's icon sits in a ring over a breathing glow in the app's own colour, on BioKey's
 * constellation ([CoverBackdrop]); no text, since the icon says which app and the system prompt
 * says what to do. A small BioKey mark shows who is asking.
 *
 * Plain Views on purpose: no Compose start-up cost on the path between a locked app appearing and
 * the fingerprint/face prompt.
 */
@SuppressLint("ViewConstructor")
class LockCoverView(context: Context) : FrameLayout(context) {

    /** Material You roles resolved straight from the system palette (Android 12+). */
    class Palette(private val context: Context) {
        private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        private fun c(light: Int, night: Int) = context.getColor(if (dark) night else light)

        val surface = c(android.R.color.system_neutral1_10, android.R.color.system_neutral1_900)
        val onSurface = c(android.R.color.system_neutral1_900, android.R.color.system_neutral1_100)
        val onSurfaceVariant = c(android.R.color.system_neutral2_700, android.R.color.system_neutral2_200)
        val surfaceContainer = c(android.R.color.system_neutral2_50, android.R.color.system_neutral2_800)
        val primary = c(android.R.color.system_accent1_600, android.R.color.system_accent1_200)
        val onPrimary = c(android.R.color.system_accent1_0, android.R.color.system_accent1_800)
        val tertiary = c(android.R.color.system_accent3_600, android.R.color.system_accent3_200)
        val error = if (dark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
    }

    private companion object {
        const val ICON_DP = 96
    }

    var onUnlock: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onLockDevice: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val backdrop = CoverBackdrop(context)

    /** BioKey's two-layer wordmark (letters + tentacles). */
    private fun wordmark() = FrameLayout(context).apply {
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_title_logo_letters)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_title_logo_tentacles)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        setBackgroundColor(p.surface)
        backdrop.setColors(p.primary, p.tertiary, p.surfaceContainer)
        status.setTextColor(p.error)
        cancelButton.setTextColor(p.primary)
        cancelButton.background = ripple(Color.TRANSPARENT, p.primary)
        primaryButton.setTextColor(p.onPrimary)
        primaryButton.background = ripple(p.primary, p.onPrimary)
        for (mark in listOf(logo, brandMark)) {
            (mark.getChildAt(0) as ImageView).setColorFilter(p.primary, PorterDuff.Mode.MULTIPLY)
            (mark.getChildAt(1) as ImageView).setColorFilter(p.tertiary, PorterDuff.Mode.MULTIPLY)
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

    /**
     * Shows a locked app's icon (or BioKey's logo when [isBioKey]). [packageName] seeds the
     * constellation so both covers draw the same one; [label] is for screen readers.
     */
    fun bind(label: String, appIcon: Drawable?, isBioKey: Boolean, packageName: String = label) {
        logo.visibility = if (isBioKey) VISIBLE else GONE
        icon.visibility = if (isBioKey) GONE else VISIBLE
        brandMark.visibility = if (isBioKey || actions.visibility == VISIBLE) GONE else VISIBLE
        backdrop.setSeed(packageName.hashCode())
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

    /** The icon's dominant colour, vivid enough to glow; null if it has none (e.g. monochrome). */
    private fun appColor(drawable: Drawable): Int? = runCatching {
        val sample = drawable.constantState?.newDrawable(resources)?.mutate() ?: return null
        val bmp = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        sample.setBounds(0, 0, 12, 12)
        sample.draw(Canvas(bmp))
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (x in 0 until 12) for (y in 0 until 12) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) < 160) continue
            r += Color.red(c)
            g += Color.green(c)
            b += Color.blue(c)
            n++
        }
        bmp.recycle()
        if (n == 0) return null
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        if (hsv[1] < 0.15f) return null
        hsv[1] = hsv[1].coerceAtLeast(0.5f)
        hsv[2] = hsv[2].coerceIn(0.6f, 0.95f)
        Color.HSVToColor(hsv)
    }.getOrNull()

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
