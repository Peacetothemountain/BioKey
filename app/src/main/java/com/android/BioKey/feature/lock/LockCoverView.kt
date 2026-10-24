package com.android.BioKey.feature.lock

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
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
import androidx.core.view.isVisible
import com.android.BioKey.R
import com.android.BioKey.core.security.WindowProtection
import com.android.BioKey.ui.brand.WordmarkView

/**
 * The lock cover, shared by [InstantShieldOverlay] (drawn the instant a locked app shows up) and
 * [LockScreenActivity] (which hosts the system prompt). Both draw this exact view with the same
 * colours as the activity's window background, so the hand-off between them is invisible.
 *
 * The locked app's icon sits in a thin ring over a soft glow in the app's own colour
 * ([CoverBackdrop]), with the app's name beneath, on a background tinted with the wallpaper's
 * Material You colours. The system prompt says what to do. A small, still BioKey mark shows who
 * is asking.
 *
 * Plain Views on purpose: no Compose start-up cost on the path between a locked app appearing and
 * the fingerprint/face prompt.
 *
 * Its words ("Unlock", "Cancel", "Lock screen") are in English like the rest of BioKey, which has
 * no translations: they'd move to string resources together, never one alone.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class LockCoverView(context: Context) : FrameLayout(context) {

    /** Material You roles, the same dynamic scheme as BioKey's own screens (Android 12+). */
    class Palette(context: Context) {
        private val scheme = if (
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        ) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        val surface = scheme.surface.toArgb()
        val onSurface = scheme.onSurface.toArgb()
        val surfaceContainer = scheme.surfaceContainerHigh.toArgb()
        /** The wallpaper's colour washing down from the top. */
        val wash = scheme.primaryContainer.toArgb()
        val primary = scheme.primary.toArgb()
        val onPrimary = scheme.onPrimary.toArgb()
        val secondary = scheme.secondary.toArgb()
        val tertiary = scheme.tertiary.toArgb()
        val error = scheme.error.toArgb()
    }

    private companion object {
        const val ICON_DP = AppInfoCache.ICON_DP
    }

    var onUnlock: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onLockDevice: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val backdrop = CoverBackdrop(context)

    /** Large logo: the identity when BioKey itself is locked. */
    private val logo = WordmarkView(context).apply { visibility = GONE }

    /** Small logo at the bottom while the system prompt is up: who is asking. */
    private val brandMark = WordmarkView(context).apply { alpha = 0.55f }

    private val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clipToOutline = true
    }

    /** The locked app's name under its icon. */
    private val name = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
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
        addView(logo, LinearLayout.LayoutParams(dp(120), dp(136)))
        addView(icon, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
        addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(22)
        })
        addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
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
        addView(brandMark, LayoutParams(dp(40), dp(44), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(40)
        })
        // Its width follows the window's at every measure ([onMeasure]): the shield's cover lives as
        // long as the service, through folds, unfolds and rotations.
        addView(actions, LayoutParams(dp(360), LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        applyPalette()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Unlock and Cancel are a held cover's only way out: on a narrow screen (a foldable's cover
        // screen) they must never be wider than it, as they would be at the width of a screen the
        // phone has since folded away.
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - dp(64)
        val width = minOf(available, dp(360)).coerceAtLeast(0)
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) actions.layoutParams.width = width
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private val iconLocation = IntArray(2)
    private val backdropLocation = IntArray(2)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The glow and ring surround the app icon. Measured in the backdrop's own coordinates
        // (window positions, not parent offsets), so padding for system bars, margins and the
        // window type can't shift the ring away from the icon.
        if (icon.isVisible && icon.width > 0) {
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
        name.setTextColor(p.onSurface)
        status.setTextColor(p.error)
        cancelButton.setTextColor(p.primary)
        cancelButton.background = ripple(Color.TRANSPARENT, p.primary)
        primaryButton.setTextColor(p.onPrimary)
        primaryButton.background = ripple(p.primary, p.onPrimary)
        logo.setColors(p.primary, p.secondary, p.tertiary)
        brandMark.setColors(p.primary, p.secondary, p.tertiary)
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
     * Shows a locked app's icon (or BioKey's logo when [isBioKey]); [label] is for screen readers.
     * [accent] is the icon's glow colour, ready-made from [AppInfoCache]; worked out here (on the
     * main thread, on the way to the cover) only when not passed.
     */
    fun bind(
        label: String,
        appIcon: Drawable?,
        isBioKey: Boolean,
        accent: Int? = appIcon?.let { AppInfoCache.accentOf(it, resources) }
    ) {
        logo.visibility = if (isBioKey) VISIBLE else GONE
        icon.visibility = if (isBioKey) GONE else VISIBLE
        name.visibility = icon.visibility
        name.text = label
        brandMark.isVisible = !isBioKey && !actions.isVisible
        setIcon(appIcon, accent)
        contentDescription = if (isBioKey) "BioKey is locked" else "$label is locked"
        setStatus(null, permanentLockout = false)
        requestLayout()
    }

    /** The app's name, once it has been loaded (a cover put up on a cache miss has none at first). */
    fun setLabel(label: String) {
        name.text = label
        contentDescription = "$label is locked"
    }

    fun setIcon(appIcon: Drawable?, accent: Int? = appIcon?.let { AppInfoCache.accentOf(it, resources) }) {
        if (appIcon != null) {
            // Cached icons are shared with the other cover; each view needs its own instance.
            icon.setImageDrawable(appIcon.constantState?.newDrawable(resources)?.mutate() ?: appIcon)
            icon.setPadding(0, 0, 0, 0)
            backdrop.setAccent(accent)
        } else {
            icon.setImageDrawable(context.getDrawable(R.drawable.ic_lock_key)?.mutate()?.apply { setTint(palette.primary) })
            icon.setPadding(dp(26), dp(26), dp(26), dp(26))
            backdrop.setAccent(null)
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    fun setStatus(message: String?, permanentLockout: Boolean) {
        lockoutMode = permanentLockout
        status.text = message
        status.isVisible = !message.isNullOrBlank()
        primaryButton.text = if (permanentLockout) "Lock screen" else "Unlock"
        // After a lockout the button locks the phone, which only the accessibility service can
        // do: in compatibility mode, Cancel is the way out (the message names the power button).
        primaryButton.isVisible = !permanentLockout || BioKeyAccessibilityService.isRunning
    }

    /** Hides the buttons while the system prompt is up, like Android's own App Lock. */
    fun setActionsVisible(visible: Boolean) {
        actions.visibility = if (visible) VISIBLE else INVISIBLE
        brandMark.isVisible = !visible && !logo.isVisible
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
