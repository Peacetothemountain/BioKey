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
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.activity.OnBackPressedCallback
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.core.view.isVisible
import com.android.BioKey.R
import com.android.BioKey.ui.brand.WordmarkView
import com.android.BioKey.ui.security.WindowProtection

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
 * Its words come from the same string resources as BioKey's own lock screen ([BioKeyLockScreen]),
 * and its labels are set as that screen's are (Material's title styles), so the two read as one.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class LockCoverView(context: Context) : FrameLayout(context) {

    /**
     * Material You roles, the same dynamic scheme as BioKey's own screens (Android 12+): [dark]
     * as the locked app's own launch screen is ([CachedAppInfo.dark]), or as the phone is set.
     */
    class Palette(context: Context, dark: Boolean? = null) {
        val isDark = dark ?: isNight(context)
        private val scheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        val surface = scheme.surface.toArgb()
        val onSurface = scheme.onSurface.toArgb()
        val surfaceContainer = scheme.surfaceContainerHigh.toArgb()
        /** The wallpaper's colour washing down from the top. */
        val wash = scheme.primaryContainer.toArgb()
        val primary = scheme.primary.toArgb()
        val onPrimary = scheme.onPrimary.toArgb()
        val tertiary = scheme.tertiary.toArgb()
        val error = scheme.error.toArgb()
        /** The passphrase field's outline and hint, and its eye. */
        val outline = scheme.outline.toArgb()
        val onSurfaceVariant = scheme.onSurfaceVariant.toArgb()
    }

    internal companion object {
        /** The phone's own light or dark mode. */
        private fun isNight(context: Context) =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        private const val ICON_DP = AppInfoCache.ICON_DP
        /** Room kept under the icon and name, for the small mark (or the buttons, on a tall screen). */
        private const val IDENTITY_BOTTOM_DP = 96
        /** Unlock (56), the gap (8), Cancel (48) and the margin under them (24). */
        private const val ACTIONS_DP = 136
        /** Kept between the name or status and Unlock in a short window. */
        private const val ACTIONS_GAP_DP = 12
        /**
         * The smallest the icon or logo gets in a short window, as a share of its size: smaller
         * than that, an app's icon goes and its name stays (BioKey's logo, with no name under it,
         * stays at this size and scrolls).
         */
        private const val MIN_ART_SCALE = 0.5f
        /** Between the app's icon and its name; none when the icon has gone (a short window). */
        private const val NAME_GAP_DP = 22
        /** Kept above the icon in a short window: its ring is drawn a few dp outside it. */
        private const val SHORT_TOP_DP = 12
        /** Round BioKey's key, shown in place of an app icon that couldn't be loaded. */
        private const val PLACEHOLDER_PADDING_DP = 26
        /** BioKey's own logo, when BioKey itself is locked. */
        private const val LOGO_WIDTH_DP = 120
        private const val LOGO_HEIGHT_DP = 136
        /** The fingerprint beside "Unlock", and the room after it, in sp: they grow with the text, as on [BioKeyLockScreen]. */
        internal const val UNLOCK_GLYPH_SP = 18f
        internal const val UNLOCK_GLYPH_GAP_SP = 8f
        /** How long the icon and name glide, and the entry or buttons fade in, as the entry opens or closes or the keyboard comes. */
        /**
         * The passphrase's entry upright, under a name (no icon): the field, its line, Unlock and
         * Back, their gaps and the margins round them, in dp, and the text in them, in sp. A
         * wide window with less height than this has it in one row.
         */
        private const val ENTRY_UPRIGHT_DP = 56 + 8 + 12 + 56 + 8 + 48 + 24 + 12 + 12
        private const val ENTRY_UPRIGHT_TEXT_SP = 20f + 28f
        /** A window at least this wide can have the entry in one row; the widest the row gets. */
        private const val COMPACT_ENTRY_MIN_WIDTH_DP = 480
        private const val COMPACT_ENTRY_MAX_DP = 720
        private const val MOVE_MS = 220L
        private const val FADE_MS = 150L

        /** Unlock's label, as Material's titleMedium (BioKeyLockScreen's): 16sp, medium, 0.15sp apart. */
        internal fun styleAsPrimaryLabel(view: TextView) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            view.letterSpacing = 0.15f / 16f
        }

        /** Cancel, "Use passphrase" and Back, as Material's titleSmall (BioKeyLockScreen's): 14sp, medium, 0.1sp apart. */
        internal fun styleAsSecondaryLabel(view: TextView) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            view.letterSpacing = 0.1f / 14f
        }
    }

    var onUnlock: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onLockDevice: (() -> Unit)? = null
    /** The owner tapped "Use passphrase". */
    var onUsePassphrase: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val backdrop = CoverBackdrop(context)

    /** Large logo: the identity when BioKey itself is locked; screen readers hear "BioKey is locked" ([describeLocked]). */
    private val logo = WordmarkView(context).apply {
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Small logo at the bottom while the system prompt is up: who is asking. Faint, but at least 3:1 on the light wash. */
    private val brandMark = WordmarkView(context).apply { alpha = 0.8f }

    private val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clipToOutline = true
    }

    /** The locked app's name under its icon; screen readers hear "<name> is locked" ([describeLocked]). */
    private val name = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        // A long name wraps once, in two even lines, and ends in "…" after that: never wider than
        // the screen, never more than two lines between the icon and the buttons.
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED
    }

    /** A screen reader stop of its own: the shield's tappable cover would otherwise read it as part of itself. */
    private val status = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        visibility = GONE
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        isScreenReaderFocusable = true
    }

    private val primaryButton = TextView(context).apply {
        styleAsPrimaryLabel(this)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        accessibilityDelegate = ButtonRole
        setOnClickListener { if (lockoutMode) onLockDevice?.invoke() else onUnlock?.invoke() }
    }

    private val cancelButton = TextView(context).apply {
        text = context.getString(R.string.prompt_cancel)
        styleAsSecondaryLabel(this)
        gravity = Gravity.CENTER
        // At least 48dp each way, as for any button: the word alone is narrower than a fingertip.
        minWidth = dp(88)
        setPadding(dp(16), 0, dp(16), 0)
        isClickable = true
        isFocusable = true
        accessibilityDelegate = ButtonRole
        setOnClickListener { onCancel?.invoke() }
    }

    /**
     * The owner passphrase, for when the face or fingerprint can't be used: drawn as Cancel is,
     * beside it (above it, where the two don't fit side by side), so Unlock stays the one obvious
     * way in.
     */
    private val usePassphraseButton = TextView(context).apply {
        text = context.getString(R.string.lock_passphrase_use)
        styleAsSecondaryLabel(this)
        gravity = Gravity.CENTER
        minWidth = dp(88)
        setPadding(dp(16), 0, dp(16), 0)
        isClickable = true
        isFocusable = true
        visibility = GONE
        filterTouchesWhenObscured = true
        WindowProtection.hideFromNonToolAccessibilityServices(this)
        accessibilityDelegate = ButtonRole
        setOnClickListener { onUsePassphrase?.invoke() }
    }

    /** Cancel alone, as it always was; with "Use passphrase", the two side by side, or stacked if they don't fit. */
    private val secondaryActions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(usePassphraseButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
        addView(cancelButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
    }

    private val actions = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(primaryButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        addView(secondaryActions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }

    /** The passphrase's entry, in the buttons' place while it's open ([showPassphraseEntry]). */
    private val passphraseEntry = PassphraseEntryView(context).apply {
        visibility = GONE
        onBack = { backOutOfPassphrase() }
    }

    private var passphraseOffered = false
    /** What the cover's buttons were last set to ([setActionsVisible]): hidden regardless while the entry is open. */
    private var actionsWanted = true
    private var onPassphraseDismiss: (() -> Unit)? = null
    /** How far the keyboard reaches above the bottom system bar: the entry stays above it. */
    private var imeExtra = 0
    /** Back, while the entry is open, goes back to the cover's buttons, not out of the lock screen. */
    private val passphraseBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = backOutOfPassphrase()
    }

    private val identity = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(logo, LinearLayout.LayoutParams(dp(LOGO_WIDTH_DP), dp(LOGO_HEIGHT_DP)))
        addView(icon, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
        addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(NAME_GAP_DP)
        })
        addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })
    }

    /**
     * The icon, name and status, scrollable as a last resort: in a short window (a phone in
     * landscape) at large text they must never run under Unlock, the cover's way out.
     */
    private val identityScroll = android.widget.ScrollView(context).apply {
        isVerticalFadingEdgeEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        // A scroll view takes the window's focus first, and out of touch mode (a key pressed, input
        // driven by a tool) Android shades the focused view: a grey band behind the icon and name.
        // Nothing here is for a keyboard to land on; the buttons are.
        isFocusable = false
        defaultFocusHighlightEnabled = false
        addView(identity, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private var lockoutMode = false
    /** BioKey itself is locked ([bind]): its logo in the icon's place, and no name under it. */
    private var boundAsBioKey = false
    /** The icon is BioKey's key, padded inside the circle, as no app icon was loaded. */
    private var placeholderIcon = false
    private var palette = Palette(context)
    /** The locked app's own scheme ([bind]'s dark), or null to follow the phone's mode. */
    private var appDark: Boolean? = null

    /** Whether the cover is drawn dark now: the status and navigation bar icons go light over it. */
    val isDark: Boolean get() = palette.isDark

    init {
        // Not clickable in itself, or screen readers would offer a double-tap that does nothing:
        // [onTouchEvent] keeps touches from what's below. The shield's tap listener makes it
        // clickable, as a tap there brings the lock screen up, and the lock screen's, as a tap
        // there asks again.
        filterTouchesWhenObscured = true
        // Tappable (the lock screen asks again on a tap), never shaded as focused: see identityScroll.
        defaultFocusHighlightEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        WindowProtection.hideFromNonToolAccessibilityServices(this)

        // The glow is light on the whole cover, under the system bars too ([onMeasure]): drawn
        // past the padding, not cut off in a straight line where it starts. Everything else stays
        // inside the padding by its own layout (the icon and the entry scroll inside their own bounds).
        clipToPadding = false
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(identityScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(32)
            rightMargin = dp(32)
            bottomMargin = dp(IDENTITY_BOTTOM_DP)
        })
        addView(brandMark, LayoutParams(dp(40), dp(44), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(40)
        })
        // Its width follows the window's at every measure ([onMeasure]): the shield's cover lives as
        // long as the service, through folds, unfolds and rotations.
        addView(actions, LayoutParams(dp(360), LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        addView(passphraseEntry, LayoutParams(dp(360), LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        applyPalette()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (WindowProtection.isMotionEventObscured(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    /** Every touch on the cover ends here, tap listener or not: none may reach the app below. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Unlock and Cancel are a held cover's only way out: on a narrow screen (a foldable's cover
        // screen) they must never be wider than it, as they would be at the width of a screen the
        // phone has since folded away.
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - dp(64)
        val width = minOf(available, dp(360)).coerceAtLeast(0)
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            actions.layoutParams.width = width
            // In one row, the entry takes the width it needs for the name, field and both buttons.
            passphraseEntry.layoutParams.width =
                if (passphraseEntry.compact) minOf(available, dp(COMPACT_ENTRY_MAX_DP)).coerceAtLeast(0) else width
        }
        // In one row, closer to the keyboard: the room is short. The row keeps its own room above
        // and below it (up to 12dp), giving it up first ([PassphraseEntryView.setRoom]).
        (passphraseEntry.layoutParams as LayoutParams).bottomMargin = (if (passphraseEntry.compact) 0 else dp(24)) + imeExtra
        if (passphraseOffered) arrangeSecondaryActions(actions.layoutParams.width)
        fitIdentity(widthMeasureSpec, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // The glow is light on the whole cover, under the status bar and beside a camera cutout
        // too: never cut off in a straight line where the padding starts.
        backdrop.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        )
    }

    /**
     * Keeps the icon, name and status clear of Unlock and Cancel, or of the passphrase's entry. As
     * laid out for a phone held upright they're centred well above them; in a short window
     * (landscape, a split screen, large text, a lockout message, the keyboard up) they move into
     * the space above, the icon shrinking (to half at most) as far as they need. Shorter still, an
     * app's icon goes and its name stays; past that they scroll, or, over the passphrase's entry,
     * step aside altogether: a sliver of them is worth less than the field.
     */
    private fun fitIdentity(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val params = identityScroll.layoutParams as LayoutParams
        setArt(1f, shown = true)
        identityScroll.visibility = VISIBLE
        params.gravity = Gravity.CENTER
        params.topMargin = 0
        params.bottomMargin = dp(IDENTITY_BOTTOM_DP)
        // The same whether the buttons show or not (they hide while the system prompt is up), so
        // nothing moves as the prompt comes and goes.
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) return

        val height = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        val innerWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - params.leftMargin - params.rightMargin)
            .coerceAtLeast(0)
        // The entry in one row says which app is locked itself: the icon and name step aside.
        if (isPassphraseEntryShowing && passphraseEntry.compact) {
            identityScroll.visibility = INVISIBLE
            return
        }
        val natural = measureIdentity(innerWidth)
        val above = height - reservedBelowIdentity(innerWidth)
        // As usual: centred, then raised by the margin (FrameLayout's centring takes it off whole).
        val top = (height - natural) / 2 - dp(IDENTITY_BOTTOM_DP)
        if (top >= 0 && top + natural <= above) return

        // A short window: centred in the space above, the art as much smaller as it takes, and
        // the icon's ring (drawn just outside it) kept clear of the top as well.
        val room = above - dp(SHORT_TOP_DP)
        val artHeight = if (boundAsBioKey) dp(LOGO_HEIGHT_DP) else dp(ICON_DP)
        val scale = (artHeight - (natural - room)).toFloat() / artHeight
        if (scale >= MIN_ART_SCALE || boundAsBioKey) setArt(scale.coerceIn(MIN_ART_SCALE, 1f), shown = true)
        else setArt(1f, shown = false)
        val fitted = measureIdentity(innerWidth)
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.topMargin = dp(SHORT_TOP_DP) + ((room - fitted) / 2).coerceAtLeast(0)
        params.bottomMargin = height - above
        if (isPassphraseEntryShowing && fitted > room) identityScroll.visibility = INVISIBLE
    }

    /**
     * The icon (or BioKey's logo) at [scale] of its size, or, not [shown], none at all, with no
     * gap under it. By size alone, not visibility: this runs in every measure pass, and a
     * visibility changed there would ask for another pass, and another.
     */
    private fun setArt(scale: Float, shown: Boolean) {
        val s = if (shown) scale else 0f
        (name.layoutParams as LinearLayout.LayoutParams).topMargin = if (shown) dp(NAME_GAP_DP) else 0
        logo.layoutParams.width = (dp(LOGO_WIDTH_DP) * s).toInt()
        logo.layoutParams.height = (dp(LOGO_HEIGHT_DP) * s).toInt()
        icon.layoutParams.width = (dp(ICON_DP) * s).toInt()
        icon.layoutParams.height = icon.layoutParams.width
        // BioKey's key in place of a missing icon keeps its proportions.
        if (placeholderIcon && shown) (dp(PLACEHOLDER_PADDING_DP) * s).toInt().let { if (it != icon.paddingTop) icon.setPadding(it, it, it, it) }
    }

    /**
     * The icon, name and status's height at [width], as they're set now. Their sizes change here,
     * inside a measure pass, so each is measured afresh: a view's measure cache would otherwise
     * hand back the size it had at an earlier pass with the same constraints (the icon at full
     * size in a window too short for it, and the name pushed out of sight).
     */
    private fun measureIdentity(width: Int): Int {
        identityScroll.forceLayout()
        identity.forceLayout()
        icon.forceLayout()
        logo.forceLayout()
        name.forceLayout()
        identity.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        return identity.measuredHeight
    }

    /**
     * The height kept under the icon and name: Unlock and Cancel's (with "Use passphrase" above
     * Cancel, when stacked), or, while it's open, the passphrase entry's and the keyboard's.
     */
    private fun reservedBelowIdentity(width: Int): Int = when {
        isPassphraseEntryShowing -> {
            val entryWidth = passphraseEntry.layoutParams.width.takeIf { it > 0 } ?: width
            passphraseEntry.measure(
                MeasureSpec.makeMeasureSpec(entryWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            passphraseEntry.measuredHeight + dp(24) + imeExtra + dp(ACTIONS_GAP_DP)
        }
        passphraseOffered && secondaryActions.orientation == LinearLayout.VERTICAL -> dp(ACTIONS_DP + 48 + ACTIONS_GAP_DP)
        else -> dp(ACTIONS_DP + ACTIONS_GAP_DP)
    }

    /** "Use passphrase" and Cancel side by side when both fit in [width] at the current text size; one above the other if not. */
    private fun arrangeSecondaryActions(width: Int) {
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val exact48 = MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY)
        usePassphraseButton.measure(unspecified, exact48)
        cancelButton.measure(unspecified, exact48)
        val sideBySide = usePassphraseButton.measuredWidth + cancelButton.measuredWidth <= width
        val orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (secondaryActions.orientation != orientation) secondaryActions.orientation = orientation
    }

    private val iconLocation = IntArray(2)
    private val backdropLocation = IntArray(2)
    private val entryLocation = IntArray(2)

    /** The cover's size at the last layout: a new size (a rotation, a fold) moves things at once, not gliding. */
    private var laidOutWidth = 0
    private var laidOutHeight = 0
    /** Whether the entry was on screen at the last layout: as it first shows it fades in rather than glides. */
    private var entryLaidOut = false

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val identityWas = identityScroll.top + identityScroll.translationY
        val entryWas = passphraseEntry.top + passphraseEntry.translationY
        val sameSize = width == laidOutWidth && height == laidOutHeight
        val glide = sameSize && laidOutWidth > 0 && isShown && animationsOn()
        val entryGlides = glide && entryLaidOut && isPassphraseEntryShowing
        super.onLayout(changed, left, top, right, bottom)
        laidOutWidth = width
        laidOutHeight = height
        entryLaidOut = isPassphraseEntryShowing
        // The entry opening or closing, the keyboard coming or going, a message: the icon and name
        // (and the entry, above the keyboard) glide to their new place instead of jumping there.
        backdrop.layout(0, 0, width, height)
        if (glide) glideFrom(identityScroll, identityWas) else settle(identityScroll)
        if (entryGlides) glideFrom(passphraseEntry, entryWas) else settle(passphraseEntry)
        placeBackdropFocus()
    }

    private fun animationsOn() = android.animation.ValueAnimator.areAnimatorsEnabled()

    private fun glideFrom(view: View, was: Float) {
        val from = was - view.top
        if (from == 0f) return settle(view)
        view.animate().cancel()
        view.translationY = from
        view.animate().translationY(0f).setDuration(MOVE_MS)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
            .setUpdateListener { placeBackdropFocus() }
            .start()
    }

    private fun settle(view: View) {
        if (view.translationY == 0f) return
        view.animate().cancel()
        view.translationY = 0f
    }

    /** Fades [view] in, as the entry or the buttons take each other's place (at once with animations off). */
    private fun fadeIn(view: View) {
        view.animate().cancel()
        // Not with a screen reader on: it's moved to what fades in at once, and a view not yet
        // drawn can't be landed on.
        val screenReader = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        if (!isShown || !animationsOn() || screenReader) {
            view.alpha = 1f
            return
        }
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(FADE_MS).setUpdateListener(null).start()
    }

    private fun placeBackdropFocus() {
        // The glow and ring surround the app icon. Measured in the backdrop's own coordinates
        // (window positions, not parent offsets), so padding for system bars, margins and the
        // window type can't shift the ring away from the icon; with the icon gliding, each frame.
        // Only an icon that shows: not one stepped aside for the passphrase's entry.
        if (icon.isShown && icon.width > 0) {
            icon.getLocationInWindow(iconLocation)
            backdrop.getLocationInWindow(backdropLocation)
            val y = iconLocation[1] - backdropLocation[1] + icon.height / 2f
            // The field is see-through: the glow fades out above it rather than shining through.
            val reach = if (isPassphraseEntryShowing) {
                passphraseEntry.getLocationInWindow(entryLocation)
                entryLocation[1] - backdropLocation[1] - y
            } else Float.MAX_VALUE
            backdrop.setFocus(iconLocation[0] - backdropLocation[0] + icon.width / 2f, y, icon.width / 2f, reach)
        } else {
            backdrop.setFocus(0f, 0f, 0f, 0f)
        }
    }

    /** Re-resolves colours, e.g. after a dark-mode switch. */
    fun refreshPalette() {
        palette = Palette(context, appDark)
        applyPalette()
    }

    /**
     * Light or dark as the locked app's own launch screen is ([dark]; null: as the phone is set).
     * The design and the icon's glow stay; only the scheme changes, and only if it has to.
     */
    private fun followScheme(dark: Boolean?) {
        appDark = dark
        if ((dark ?: isNight(context)) == palette.isDark) return
        palette = Palette(context, dark)
        applyPalette()
        // The placeholder key is tinted with the scheme's colour.
        if (placeholderIcon) icon.drawable?.setTint(palette.primary)
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
        usePassphraseButton.setTextColor(p.primary)
        usePassphraseButton.background = ripple(Color.TRANSPARENT, p.primary)
        passphraseEntry.applyPalette(p)
        primaryButton.setTextColor(p.onPrimary)
        primaryButton.background = ripple(p.primary, p.onPrimary)
        // The glyph takes the text's colour, so it's drawn again with it.
        if (!lockoutMode) primaryButton.text = unlockLabel()
        logo.setColor(p.primary)
        brandMark.setColor(p.primary)
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
     * main thread, on the way to the cover) only when not passed. [dark]: the app's own launch
     * screen is ([CachedAppInfo.dark]), and the cover follows it; null, as the phone is set.
     */
    fun bind(
        label: String,
        appIcon: Drawable?,
        isBioKey: Boolean,
        accent: Int? = appIcon?.let { AppInfoCache.accentOf(it, resources) },
        dark: Boolean? = null
    ) {
        followScheme(if (isBioKey) null else dark)
        boundAsBioKey = isBioKey
        logo.visibility = if (isBioKey) VISIBLE else GONE
        icon.visibility = if (isBioKey) GONE else VISIBLE
        name.visibility = icon.visibility
        name.text = label
        brandMark.isVisible = !isBioKey && !actions.isVisible && !isPassphraseEntryShowing
        setIcon(appIcon, accent)
        describeLocked(if (isBioKey) context.getString(R.string.lock_screen_locked) else context.getString(R.string.lock_cover_app_locked, label))
        setStatus(null, permanentLockout = false)
        requestLayout()
    }

    /** The app's name, once it has been loaded (a cover put up on a cache miss has none at first). */
    fun setLabel(label: String) {
        name.text = label
        describeLocked(context.getString(R.string.lock_cover_app_locked, label))
    }

    /**
     * What is locked, for screen readers: announced as the cover appears (its pane title), and
     * read from the app's name, or BioKey's logo, as the user moves through the cover. Not the
     * cover's own description, which would be read in place of everything in it.
     */
    private fun describeLocked(text: String) {
        accessibilityPaneTitle = text
        name.contentDescription = text
        logo.contentDescription = text
        // The entry's own copy of the name, for when it's one row and the name above has stepped aside.
        passphraseEntry.setTitle(if (boundAsBioKey) context.getString(R.string.brand_name) else name.text, text)
    }

    /**
     * The passphrase's entry in one row when the window is wide but has too little height for
     * the upright arrangement under the icon and name (a phone in landscape with the keyboard up),
     * so the app's name, the field, its line, Back and Unlock are in view together. Decided as the
     * size or the keyboard changes, never in a measure pass (it moves views).
     */
    private fun arrangeEntry(w: Int = width, h: Int = height) {
        if (w <= 0 || h <= 0) return
        val roomWide = w - paddingLeft - paddingRight
        val roomHigh = h - paddingTop - paddingBottom - imeExtra
        val upright = dp(ENTRY_UPRIGHT_DP) + sp(ENTRY_UPRIGHT_TEXT_SP)
        val compact = roomWide >= dp(COMPACT_ENTRY_MIN_WIDTH_DP) && roomHigh < upright
        // In one row, all of it above the keyboard: the line under the field included.
        passphraseEntry.setRoom(roomHigh.coerceAtLeast(0))
        if (compact != passphraseEntry.compact) {
            passphraseEntry.setCompact(compact)
            requestLayout()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // After this layout pass: it moves the entry's views.
        if (w != oldw || h != oldh) post { arrangeEntry() }
    }

    fun setIcon(appIcon: Drawable?, accent: Int? = appIcon?.let { AppInfoCache.accentOf(it, resources) }) {
        if (appIcon != null) {
            // Cached icons are shared with the other cover; each view needs its own instance.
            icon.setImageDrawable(appIcon.constantState?.newDrawable(resources)?.mutate() ?: appIcon)
            icon.setPadding(0, 0, 0, 0)
            placeholderIcon = false
            backdrop.setAccent(accent)
        } else {
            icon.setImageDrawable(context.getDrawable(R.drawable.ic_lock_key)?.mutate()?.apply { setTint(palette.primary) })
            icon.setPadding(dp(PLACEHOLDER_PADDING_DP), dp(PLACEHOLDER_PADDING_DP), dp(PLACEHOLDER_PADDING_DP), dp(PLACEHOLDER_PADDING_DP))
            placeholderIcon = true
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
        primaryButton.text = if (permanentLockout) context.getString(R.string.lock_screen_lock_phone) else unlockLabel()
        primaryButton.contentDescription = if (permanentLockout) null else context.getString(R.string.lock_screen_unlock)
        // After a lockout the button locks the phone, which only the accessibility service can
        // do: in compatibility mode, Cancel is the way out (the message names the power button).
        primaryButton.isVisible = !permanentLockout || BioKeyAccessibilityService.isRunning
    }

    /** Hides the buttons while the system prompt is up, like Android's own App Lock. */
    fun setActionsVisible(visible: Boolean) {
        actionsWanted = visible
        actions.visibility = if (visible && !isPassphraseEntryShowing) VISIBLE else INVISIBLE
        brandMark.isVisible = !visible && !boundAsBioKey && !isPassphraseEntryShowing
    }

    /** Whether "Use passphrase" is among the cover's actions (shown only when the actions are). */
    fun setPassphraseOffered(offered: Boolean) {
        if (passphraseOffered == offered) return
        passphraseOffered = offered
        usePassphraseButton.isVisible = offered
        if (!offered) secondaryActions.orientation = LinearLayout.HORIZONTAL
        requestLayout()
    }

    /**
     * Shows the entry in place of the cover's buttons, the field focused with the keyboard up.
     * [onSubmit] gets what was typed (the caller zeroes it); [onDismiss] when the owner backs out
     * of it (Back on screen, or the Back gesture).
     */
    fun showPassphraseEntry(onSubmit: (CharArray) -> Unit, onDismiss: () -> Unit) {
        passphraseEntry.onSubmit = onSubmit
        onPassphraseDismiss = onDismiss
        if (isPassphraseEntryShowing) return
        arrangeEntry()
        passphraseEntry.visibility = VISIBLE
        fadeIn(passphraseEntry)
        actions.visibility = INVISIBLE
        brandMark.isVisible = false
        passphraseBack.isEnabled = true
        registerPassphraseBack()
        requestLayout()
        passphraseEntry.open()
    }

    /** A check is running: the field and its button wait. */
    fun setPassphraseBusy(busy: Boolean) = passphraseEntry.setBusy(busy)

    /** Wrong, or tries are paused: says [message] under the field (announced to screen readers) and empties the field. Null clears it. */
    fun setPassphraseError(message: String?) = passphraseEntry.setError(message)

    /**
     * Tries are paused until [untilElapsedRealtime] ([android.os.SystemClock.elapsedRealtime]):
     * the field and Unlock are off and a countdown shows; they come back by themselves. Zero or a
     * past time ends it.
     */
    fun setPassphraseWait(untilElapsedRealtime: Long) = passphraseEntry.setWait(untilElapsedRealtime)

    /** Puts the entry away (what was typed is overwritten) and brings back the cover's buttons. */
    @SuppressLint("AccessibilityFocus") // Asked for: back to the button the owner left from, not the top of the screen.
    fun hidePassphraseEntry() {
        if (!isPassphraseEntryShowing) return
        passphraseEntry.close()
        passphraseEntry.visibility = GONE
        passphraseBack.isEnabled = false
        passphraseBack.remove()
        onPassphraseDismiss = null
        passphraseEntry.onSubmit = null
        setActionsVisible(actionsWanted)
        if (actions.isVisible) fadeIn(actions)
        requestLayout()
        // Screen readers go back to where the owner came from.
        val target = if (usePassphraseButton.isVisible) usePassphraseButton else primaryButton
        if (actions.isVisible) target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }

    val isPassphraseEntryShowing: Boolean get() = passphraseEntry.isVisible

    private fun backOutOfPassphrase() {
        val dismiss = onPassphraseDismiss
        hidePassphraseEntry()
        dismiss?.invoke()
    }

    /** Added after the lock screen's own Back handling, so it comes first while the entry is open. */
    private fun registerPassphraseBack() {
        passphraseBack.remove()
        findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher?.addCallback(passphraseBack)
    }

    /** A tap on the cover around the entry (to put the keyboard away, say) doesn't ask again. */
    override fun performClick(): Boolean = if (isPassphraseEntryShowing) true else super.performClick()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isPassphraseEntryShowing) registerPassphraseBack()
    }

    override fun onDetachedFromWindow() {
        passphraseBack.remove()
        super.onDetachedFromWindow()
    }

    @androidx.annotation.VisibleForTesting
    internal val usePassphraseActionForTests: View get() = usePassphraseButton
    @androidx.annotation.VisibleForTesting
    internal val actionsForTests: View get() = actions
    @androidx.annotation.VisibleForTesting
    internal val passphraseEntryForTests: PassphraseEntryView get() = passphraseEntry
    @androidx.annotation.VisibleForTesting
    internal val nameForTests: TextView get() = name

    /** Overlay windows don't always get insets dispatched; the shield passes them in. */
    fun applySystemBarInsets(left: Int, top: Int, right: Int, bottom: Int) {
        setSymmetricPadding(left, top, right, bottom)
    }

    /**
     * The system bars' and a camera cutout's room, the same on both sides: the icon and buttons
     * sit in the middle of the screen, as on [BioKeyLockScreen], not in the middle of what's
     * beside a cutout.
     */
    private fun setSymmetricPadding(left: Int, top: Int, right: Int, bottom: Int) {
        val side = maxOf(left, right)
        setPadding(side, top, side, bottom)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        setSymmetricPadding(bars.left, bars.top, bars.right, bars.bottom)
        // The keyboard, for the passphrase: the lock screen's window isn't resized for it (edge
        // to edge), so the entry rises above it here.
        val ime = (insets.getInsets(WindowInsets.Type.ime()).bottom - bars.bottom).coerceAtLeast(0)
        if (ime != imeExtra) {
            imeExtra = ime
            arrangeEntry()
            requestLayout()
        }
        return insets
    }

    /**
     * "Unlock" after a fingerprint, centred together, as on BioKey's own lock screen
     * ([BioKeyLockScreen]): one product, one button. Screen readers hear just "Unlock".
     */
    private fun unlockLabel(): CharSequence {
        val word = context.getString(R.string.lock_screen_unlock)
        val glyph = context.getDrawable(R.drawable.ic_fingerprint)?.mutate() ?: return word
        // In sp, so it grows with the text (large text, Android 14's non-linear scaling too), as
        // the Compose twin's does; never taller than the text's own line, which would stretch the
        // line and push the word below the button's centre.
        val line = primaryButton.paint.fontMetricsInt.let { it.descent - it.ascent }
        val size = minOf(sp(UNLOCK_GLYPH_SP), line)
        val gap = sp(UNLOCK_GLYPH_GAP_SP)
        glyph.setTint(primaryButton.currentTextColor)
        // The gap is part of the glyph's span, so it's exact whatever the font's space is.
        val spaced = android.graphics.drawable.InsetDrawable(glyph, 0, 0, gap, 0).apply { setBounds(0, 0, size + gap, size) }
        return SpannableString("${Typography.nbsp}$word").apply {
            setSpan(ImageSpan(spaced, DynamicDrawableSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics).toInt()

    @androidx.annotation.VisibleForTesting
    internal val unlockButtonForTests: TextView get() = primaryButton

    /** Unlock and Cancel are text drawn as buttons: screen readers are told they are buttons. */
    internal object ButtonRole : AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }
}
