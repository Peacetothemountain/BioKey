package com.android.BioKey.feature.lock

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
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

/**
 * The lock cover, shared by [InstantShieldOverlay] (drawn the instant a locked app shows up) and
 * [LockScreenActivity] (which hosts the system prompt). Both draw this exact view with the same
 * colours as the activity's window background, so the hand-off between them is invisible.
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

    var onUnlock: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onLockDevice: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val logo = FrameLayout(context).apply {
        visibility = GONE
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_title_logo_letters)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_title_logo_tentacles)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clipToOutline = true
    }

    private val title = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        gravity = Gravity.CENTER
        maxLines = 2
    }

    private val lockedLabel = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        compoundDrawablePadding = dp(6)
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

    private var lockoutMode = false
    private var palette = Palette(context)

    init {
        isClickable = true
        filterTouchesWhenObscured = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        val identity = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(logo, LinearLayout.LayoutParams(dp(280), dp(118)).apply { bottomMargin = dp(8) })
            addView(icon, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(20) })
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(lockedLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            })
        }
        addView(identity, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(32)
            rightMargin = dp(32)
            bottomMargin = dp(96)
        })
        addView(actions, LayoutParams(minOf(resources.displayMetrics.widthPixels - dp(64), dp(360)), LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        applyPalette()
    }

    /** Re-resolves colours, e.g. after a dark-mode switch. */
    fun refreshPalette() {
        palette = Palette(context)
        applyPalette()
    }

    private fun applyPalette() {
        val p = palette
        setBackgroundColor(p.surface)
        title.setTextColor(p.onSurface)
        lockedLabel.setTextColor(p.onSurfaceVariant)
        status.setTextColor(p.error)
        cancelButton.setTextColor(p.primary)
        cancelButton.background = ripple(Color.TRANSPARENT, p.primary)
        primaryButton.setTextColor(p.onPrimary)
        primaryButton.background = ripple(p.primary, p.onPrimary)
        lockedLabel.setCompoundDrawablesRelative(lockGlyph(p.onSurfaceVariant), null, null, null)
        (logo.getChildAt(0) as ImageView).setColorFilter(p.primary, PorterDuff.Mode.MULTIPLY)
        (logo.getChildAt(1) as ImageView).setColorFilter(p.tertiary, PorterDuff.Mode.MULTIPLY)
        if (icon.drawable == null) icon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(p.surfaceContainer)
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

    private fun lockGlyph(tint: Int): Drawable? =
        context.getDrawable(R.drawable.ic_lock_key)?.mutate()?.apply {
            setTint(tint)
            setBounds(0, 0, dp(16), dp(16))
        }

    /** Shows [label]/[appIcon] for a locked app, or BioKey's own branding when [isBioKey]. */
    fun bind(label: String, appIcon: Drawable?, isBioKey: Boolean) {
        logo.visibility = if (isBioKey) VISIBLE else GONE
        icon.visibility = if (isBioKey) GONE else VISIBLE
        title.visibility = if (isBioKey) GONE else VISIBLE
        title.text = label
        lockedLabel.text = if (isBioKey) "BioKey is locked" else "Locked"
        setIcon(appIcon)
        contentDescription = if (isBioKey) "BioKey is locked" else "$label is locked"
        setStatus(null, permanentLockout = false)
    }

    fun setIcon(appIcon: Drawable?) {
        if (appIcon != null) {
            icon.background = null
            // Cached icons are shared with the other cover; each view needs its own instance.
            icon.setImageDrawable(appIcon.constantState?.newDrawable(resources)?.mutate() ?: appIcon)
            icon.setPadding(0, 0, 0, 0)
        } else {
            icon.setImageDrawable(context.getDrawable(R.drawable.ic_lock_key)?.mutate()?.apply { setTint(palette.primary) })
            icon.setPadding(dp(20), dp(20), dp(20), dp(20))
            icon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.surfaceContainer)
            }
        }
    }

    fun setStatus(message: String?, permanentLockout: Boolean) {
        lockoutMode = permanentLockout
        status.text = message
        status.visibility = if (message.isNullOrBlank()) GONE else VISIBLE
        primaryButton.text = if (permanentLockout) "Lock screen" else "Unlock"
    }

    /** Hides the buttons while the system prompt is up, like Android's own App Lock. */
    fun setActionsVisible(visible: Boolean) {
        actions.visibility = if (visible) VISIBLE else INVISIBLE
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
