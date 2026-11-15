package com.android.BioKey.feature.lock

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.textclassifier.TextClassifier
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.PathParser
import androidx.core.view.get
import androidx.core.view.isGone
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.view.size
import com.android.BioKey.R
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.ui.format.passphraseWaitText
import com.android.BioKey.ui.security.WindowProtection
import kotlin.math.ceil

/**
 * The longest the line under the passphrase field usually says, "Wrong passphrase. 2 more tries
 * before a wait." or "Wrong passphrase. Try again in 120 minutes.": both lock screens keep its room
 * while the entry is open, so nothing moves as the line changes.
 */
internal fun longestPassphraseNote(res: android.content.res.Resources): String {
    val wrong = res.getString(R.string.lock_passphrase_wrong)
    return listOf(
        res.getString(R.string.lock_passphrase_then, wrong, passphraseWaitText(res, 120 * 60_000L)),
        passphraseMessage(res, wrong, triesBeforeWait = 2, waiting = false)
    ).maxBy { it.length }
}

/**
 * What both lock screens say under the field for [message]: a wrong passphrase that leaves two
 * tries or fewer before a wait ([triesBeforeWait], from [OwnerPassphrase.triesBeforeWait]) says
 * so, "Wrong passphrase. 2 more tries before a wait.", so a wait never comes out of nowhere. Not
 * while one is [waiting]: the countdown says it then. Anything else is said as it is.
 */
internal fun passphraseMessage(
    res: android.content.res.Resources,
    message: String,
    triesBeforeWait: Int?,
    waiting: Boolean
): String {
    if (waiting || triesBeforeWait == null || triesBeforeWait !in 1..2) return message
    if (message != res.getString(R.string.lock_passphrase_wrong)) return message
    return res.getString(
        R.string.lock_passphrase_then,
        message,
        res.getQuantityString(R.plurals.lock_passphrase_tries_left, triesBeforeWait, triesBeforeWait)
    )
}

/**
 * The owner passphrase's entry on the lock cover ([LockCoverView.showPassphraseEntry]): in place of
 * the cover's buttons while it's open, and built like them. A pill-shaped field with an eye (as in
 * BioKey's backup password dialog), a line under it for what went wrong or how long to wait,
 * "Unlock" as the cover's own Unlock is drawn, and "Back" as its Cancel is.
 *
 * In a wide, short window (a phone in landscape with the keyboard up), it's one row ([compact]):
 * which app is locked, the field with its line under it (running on under Back and Unlock when
 * there's no height for it to wrap: large text), Back and Unlock side by side, all in view together
 * above the keyboard, the cover's own icon and name stepping aside for it.
 *
 * A scroll view, as a last resort: on a short window with the keyboard up (a foldable's cover
 * screen, at large text) the field stays in view and the rest scrolls.
 *
 * What's typed is BioKey's own secret on a lock screen: never autofilled, saved, suggested,
 * learned, copied or shared; overwritten in memory as soon as it's handed over or put away.
 */
@SuppressLint("ViewConstructor")
internal class PassphraseEntryView(context: Context) : ScrollView(context) {

    /** Back: the owner leaves the entry for the cover's buttons. */
    var onBack: (() -> Unit)? = null
    /** Unlock or the keyboard's Done, with something typed: the owner's passphrase, a copy for the caller. */
    var onSubmit: ((CharArray) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = LockCoverView.Palette(context)
    private var busy = false
    private var waitUntil = 0L
    private var message: String? = null
    /** How many more wrong tries before a wait, as read when [message] (a wrong passphrase) was said. */
    private var triesBeforeWait: Int? = null
    /** A wait just ended by itself: the line says so until the owner types. */
    private var ended = false

    internal val field = SecretField(context).apply {
        id = generateViewId()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        hint = context.getString(R.string.lock_passphrase_hint)
        isSingleLine = true
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        // Masked, never suggested or remembered by the keyboard, never put in its full-screen
        // copy (landscape), which would also cover the cover.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        transformationMethod = PasswordTransformationMethod.getInstance()
        // After the input type: making a field a password field sets a monospace font on it,
        // which would show in the placeholder. The app's own font, as everywhere else.
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        filters = arrayOf(InputFilter.LengthFilter(OwnerPassphrase.MAX_LENGTH), refuseWhileHeld)
        // BioKey's own secret: no password manager fills or offers to save it (the password
        // manager may itself be a locked app), nothing captures it, nothing restores it.
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        // None of its own (Android 14+ reports "passwordAuto" for any password field without them).
        setAutofillHints(*emptyArray<String>())
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        setTextClassifier(TextClassifier.NO_OP)
        background = null
        // As BioKey's own lock's field (Material's bodyLarge in a 16dp margin top and bottom): it
        // grows with large text the same way, never shorter than 56dp.
        lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics).toInt()
        setPadding(dp(24), dp(16), dp(52), dp(16))
        filterTouchesWhenObscured = true
        WindowProtection.hideFromNonToolAccessibilityServices(this)
    }

    private var revealed = false

    private val eye = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER
        isClickable = true
        isFocusable = true
        filterTouchesWhenObscured = true
        WindowProtection.hideFromNonToolAccessibilityServices(this)
        accessibilityDelegate = LockCoverView.ButtonRole
        // No focus highlight of its own: while the field is off (a check, a wait) the eye is off
        // too ([updateEnabled]), so focus never comes to rest on it as a grey circle.
        defaultFocusHighlightEnabled = false
        setOnClickListener { setRevealed(!revealed) }
    }

    /** The pill the field and eye sit in: its outline thickens and takes the scheme's colour with focus, or an error. */
    private val fieldBox = FrameLayout(context).apply {
        // As tall as the field's text needs, never under 56dp.
        field.minHeight = dp(56)
        addView(field, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        addView(eye, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(4) })
    }

    /** What went wrong and how long to wait, as seen: the countdown changes every second, so screen readers don't hear it. */
    internal val note = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = INVISIBLE
    }

    /**
     * Never seen: the longest the line usually says ("Wrong passphrase. Try again in 120 minutes."),
     * so the line's room is kept from the moment the entry opens and nothing above or below it
     * moves as a message, a countdown or its end comes and goes. Anything longer still shows in
     * full: the room grows for it.
     */
    private val noteRoom = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = INVISIBLE
        text = longestPassphraseNote(context.resources)
    }

    /**
     * The same line, as screen readers have it: announced (politely) only when there's something
     * new to say: a message, a wait starting, a wait over. Read as the line stands now when the
     * owner moves to it. Drawn over [note] with no colour of its own, so its focus box is the line's.
     */
    private val spoken = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setTextColor(Color.TRANSPARENT)
        visibility = INVISIBLE
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        isScreenReaderFocusable = true
        accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.text = note.text
            }
        }
    }

    /** Always there while the entry is (its room kept by [noteRoom]); only its words come and go. */
    private val noteBox = FrameLayout(context).apply {
        addView(noteRoom, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(note, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(spoken, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    internal val submitButton = TextView(context).apply {
        text = context.getString(R.string.lock_screen_unlock)
        LockCoverView.styleAsPrimaryLabel(this)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        filterTouchesWhenObscured = true
        WindowProtection.hideFromNonToolAccessibilityServices(this)
        accessibilityDelegate = LockCoverView.ButtonRole
        setOnClickListener { submit() }
    }

    internal val backButton = TextView(context).apply {
        text = context.getString(R.string.lock_passphrase_back)
        LockCoverView.styleAsSecondaryLabel(this)
        gravity = Gravity.CENTER
        minWidth = dp(88)
        setPadding(dp(16), 0, dp(16), 0)
        isClickable = true
        isFocusable = true
        filterTouchesWhenObscured = true
        WindowProtection.hideFromNonToolAccessibilityServices(this)
        accessibilityDelegate = LockCoverView.ButtonRole
        setOnClickListener { onBack?.invoke() }
    }

    /**
     * Which app is locked, beside the field when [compact] (the cover's own icon and name have
     * stepped aside then); screen readers hear "<name> is locked", as from the cover's name.
     */
    private val title = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED
        visibility = GONE
    }

    /** The field and the line under it, upright ([setCompact] puts them in). */
    private val fieldGroup = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    /** Upright: the field, Unlock under it, Back under that. */
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    /** [compact]: one row, the name, the field with its line, Back and Unlock; see [Row]. */
    private val row = Row()

    /** One row (true) or the upright column; see [setCompact]. */
    var compact = false
        private set

    /** Which app is locked, said beside the field in the one-row arrangement; [description] for screen readers. */
    fun setTitle(text: CharSequence, description: CharSequence) {
        title.text = text
        title.contentDescription = description
    }

    /**
     * How much height the entry has above the keyboard, in pixels: in one row, the line under
     * the field must show whole in it ([Row]).
     */
    fun setRoom(height: Int) {
        if (row.room == height) return
        row.room = height
        if (compact) row.requestLayout()
    }

    /**
     * One row ([compact]) for a wide window with little height (landscape, keyboard up), or the
     * upright column. Not called from a measure pass: it moves views.
     */
    fun setCompact(value: Boolean) {
        if (compact == value && isNotEmpty()) return
        compact = value
        removeAllViews()
        column.removeAllViews()
        fieldGroup.removeAllViews()
        row.removeAllViews()
        if (value) {
            title.visibility = VISIBLE
            title.maxWidth = dp(COMPACT_TITLE_MAX_DP)
            submitButton.setPadding(dp(24), 0, dp(24), 0)
            // 8dp above and below the words, not 16: at large text the room above the keyboard
            // is short (never under 56dp all the same, so at usual sizes it's as upright).
            field.setPadding(dp(24), dp(8), dp(52), dp(8))
            // In the order they're read: what's locked, the field, its line, Back, Unlock.
            for (view in listOf(title, fieldBox, noteBox, backButton, submitButton)) row.addView(view)
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            title.visibility = GONE
            submitButton.minWidth = 0
            submitButton.setPadding(0, 0, 0, 0)
            row.placeLine(wide = false)
            field.setPadding(dp(24), dp(16), dp(52), dp(16))
            fieldGroup.addView(fieldBox, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            fieldGroup.addView(noteBox, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                leftMargin = dp(16)
                rightMargin = dp(16)
            })
            column.addView(fieldGroup, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            column.addView(submitButton, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(12) })
            column.addView(backButton, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(8) })
            addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        // Wherever the focus was, it stays in the field (the views were taken out and put back).
        if (isShown && !field.hasFocus()) field.requestFocus()
    }

    /**
     * The one-row arrangement: the name, the field, Back and Unlock, each centred on the field,
     * and the line under the field, all in view above the keyboard ([room]), nothing else pushed
     * out of it. The room round the row (up to 12dp above and below) goes first; if the line
     * still doesn't fit under the field (large text, where it wraps), it runs on from the field's
     * start under Back and Unlock too, or, if need be, across the whole row, on as few lines as
     * that takes. Its room is the longest it usually says ([noteRoom]), so nothing moves as it
     * changes.
     */
    private inner class Row : android.view.ViewGroup(context) {
        var room = Int.MAX_VALUE
        /** The line runs on wider than the field, from its start (true), or is centred under it. */
        private var wide = false
        private var fieldX = 0
        private var fieldWidth = 0
        private var lineX = 0
        private var gapUnder = 0
        private var pad = 0
        private var fieldTop = 0

        /** The line's words from its start (widened) or centred under the field. */
        fun placeLine(wide: Boolean) {
            this.wide = wide
            val gravity = if (wide) Gravity.START or Gravity.TOP else Gravity.CENTER
            for (view in listOf(note, noteRoom, spoken)) if (view.gravity != gravity) view.gravity = gravity
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val loose = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            fun exactly(v: Int) = MeasureSpec.makeMeasureSpec(v.coerceAtLeast(0), MeasureSpec.EXACTLY)
            title.measure(MeasureSpec.makeMeasureSpec(minOf(dp(COMPACT_TITLE_MAX_DP), width), MeasureSpec.AT_MOST), loose)
            backButton.measure(loose, exactly(dp(48)))
            // As wide as the wider of its two words, so nothing moves as a check starts or ends.
            val words = listOf(R.string.lock_screen_unlock, R.string.lock_passphrase_checking)
                .maxOf { submitButton.paint.measureText(context.getString(it)) }
            val least = maxOf(dp(120), ceil(words).toInt() + submitButton.paddingLeft + submitButton.paddingRight)
            if (submitButton.minWidth != least) submitButton.minWidth = least
            submitButton.measure(loose, exactly(dp(56)))
            val gap = dp(8)
            val lineGap = dp(8)
            val leastGap = dp(2)
            val margin = dp(16)
            val buttons = gap + backButton.measuredWidth + gap + submitButton.measuredWidth
            fieldX = title.measuredWidth + dp(16)
            fieldWidth = (width - fieldX - buttons).coerceAtLeast(0)
            fieldBox.measure(exactly(fieldWidth), loose)
            val fieldHigh = fieldBox.measuredHeight
            val sides = maxOf(title.measuredHeight, backButton.measuredHeight, submitButton.measuredHeight)
            fieldTop = ((sides - fieldHigh) / 2).coerceAtLeast(0)
            // Where the line can go, narrowest first: its x and width. (Its alignment doesn't
            // change its height, so it's set once the choice is made: a measure never asks for another.)
            val spans = listOf(fieldX + margin to fieldWidth - 2 * margin, fieldX + margin to width - fieldX - margin, 0 to width)
            var chosen = spans.lastIndex
            for (i in spans.indices) {
                noteBox.measure(exactly(spans[i].second), loose)
                val under = if (i == 0) lineGap else leastGap
                if (maxOf(fieldTop + fieldHigh + under + noteBox.measuredHeight, fieldTop + (fieldHigh + sides) / 2) <= room) {
                    chosen = i
                    break
                }
            }
            if (chosen != spans.lastIndex) noteBox.measure(exactly(spans[chosen].second), loose)
            placeLine(wide = chosen > 0)
            lineX = spans[chosen].first
            // Widened, the gap under the field goes before anything else does.
            gapUnder = if (!wide) lineGap else (room.toLong() - fieldTop - fieldHigh - noteBox.measuredHeight).coerceIn(leastGap.toLong(), lineGap.toLong()).toInt()
            val high = maxOf(fieldTop + fieldHigh + gapUnder + noteBox.measuredHeight, fieldTop + (fieldHigh + sides) / 2)
            pad = ((room.toLong() - high) / 2).coerceIn(0L, dp(COMPACT_ROOM_DP).toLong()).toInt()
            setMeasuredDimension(width, high + 2 * pad)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            fun place(view: View, x: Int, y: Int) {
                val left = if (rtl) width - x - view.measuredWidth else x
                view.layout(left, y, left + view.measuredWidth, y + view.measuredHeight)
            }
            val middle = pad + fieldTop + fieldBox.measuredHeight / 2
            fun centred(view: View, x: Int) = place(view, x, middle - view.measuredHeight / 2)
            centred(title, 0)
            place(fieldBox, fieldX, pad + fieldTop)
            place(noteBox, lineX, pad + fieldTop + fieldBox.measuredHeight + gapUnder)
            var x = fieldX + fieldWidth + dp(8)
            centred(backButton, x)
            x += backButton.measuredWidth + dp(8)
            centred(submitButton, x)
        }
    }

    /** The outline's colour as last drawn. */
    @androidx.annotation.VisibleForTesting
    internal var outlineForTests = 0
        private set

    @androidx.annotation.VisibleForTesting
    internal val eyeForTests: View get() = eye

    /** Whether the entry last asked for the keyboard (true) or put it away (false). */
    @androidx.annotation.VisibleForTesting
    internal var keyboardForTests = false

    private val tick = Runnable { refresh(announceEnd = true) }
    /** The countdown's clock: the main thread's, not the view's queue, which holds posts until it's attached. */
    private val clock = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        isVerticalFadingEdgeEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        defaultFocusHighlightEnabled = false
        filterTouchesWhenObscured = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        isSaveFromParentEnabled = false
        WindowProtection.hideFromNonToolAccessibilityServices(this)
        setCompact(false)

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // The first character of a new try clears what went wrong (the line and the
                // field's error look) and "You can try again now". A countdown isn't an error and
                // stays: nothing can be typed while it runs.
                // (Not the field's own wipe, which writes over what was typed before emptying it.)
                if (!field.wiping && !s.isNullOrEmpty() && (ended || (message != null && !isWaiting()))) {
                    ended = false
                    message = null
                    triesBeforeWait = null
                    refresh(announceEnd = false)
                } else updateEnabled()
            }
        })
        field.setOnFocusChangeListener { _, _ -> updateOutline() }
        field.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                // Done with nothing typed (or while a check runs) does nothing, and keeps the keyboard.
                if (event == null || event.action == KeyEvent.ACTION_DOWN) submit()
                true
            } else false
        }
        setRevealed(false)
        applyPalette(palette)
        updateEnabled()
    }

    fun applyPalette(p: LockCoverView.Palette) {
        palette = p
        field.setTextColor(p.onSurface)
        title.setTextColor(p.onSurface)
        field.setHintTextColor(p.onSurfaceVariant)
        field.highlightColor = withAlpha(p.primary, 0.3f)
        field.textCursorDrawable = GradientDrawable().apply {
            setColor(p.primary)
            setSize(dp(2), 0)
        }
        field.textSelectHandle?.setTint(p.primary)
        field.textSelectHandleLeft?.setTint(p.primary)
        field.textSelectHandleRight?.setTint(p.primary)
        eye.background = RippleDrawable(ColorStateList.valueOf(withAlpha(p.onSurface, 0.16f)), null, GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        })
        note.setTextColor(if (errorTone()) p.error else p.onSurfaceVariant)
        backButton.setTextColor(p.primary)
        backButton.background = ripple(Color.TRANSPARENT, p.primary)
        updateOutline()
        updateEnabled()
    }

    /** Opened afresh: masked, nothing said, the field focused with the keyboard up. */
    fun open() {
        setRevealed(false)
        ended = false
        setMessage(null)
        scrollTo(0, 0)
        refresh(announceEnd = false)
        focusField(screenReader = true)
    }

    /** Put away: what was typed is overwritten and dropped, the keyboard goes. */
    fun close() {
        clock.removeCallbacks(tick)
        hideKeyboard()
        wipe()
        setRevealed(false)
        setMessage(null)
        // A check still running when it was put away answers to nothing: it mustn't leave "Checking…" behind.
        setBusy(false)
        field.clearFocus()
    }

    fun setBusy(value: Boolean) {
        if (busy == value) return
        busy = value
        submitButton.text = context.getString(if (value) R.string.lock_passphrase_checking else R.string.lock_screen_unlock)
        updateEnabled()
        if (!value && isAttachedToWindow && isShown && !isWaiting()) focusField(screenReader = false)
    }

    fun setError(text: String?) {
        if (text != null) wipe()
        // Read as a wrong passphrase is said: how many more wrong tries there are before one starts a wait.
        triesBeforeWait = if (text == context.getString(R.string.lock_passphrase_wrong)) OwnerPassphrase.triesBeforeWait(context) else null
        setMessage(text)
    }

    fun setWait(untilElapsedRealtime: Long) {
        val starting = untilElapsedRealtime > SystemClock.elapsedRealtime() && untilElapsedRealtime != waitUntil
        waitUntil = untilElapsedRealtime
        // The keyboard stays up and the field keeps focus (it refuses what's typed meanwhile):
        // nothing on the screen moves as a wait starts, and nothing else is left looking focused.
        if (starting) wipe()
        refresh(announceEnd = false)
        if (starting) spoken.text = noteText()
    }

    /** Overwrites what was typed in the field's own buffer, then empties it (and its undo history). */
    fun wipe() {
        val text = field.text ?: return
        // Past the filter that holds the field during a check or a wait: overwriting must happen.
        field.wiping = true
        try {
            val length = text.length
            if (length > 0) text.replace(0, length, String(CharArray(length)))
            text.clear()
            text.clearSpans()
            field.setText("")
        } finally {
            field.wiping = false
        }
    }

    private fun submit() {
        if (!submitButton.isEnabled) return
        val text = field.text ?: return
        val length = text.length
        if (length == 0) return
        val typed = CharArray(length)
        // Straight into the array: no String of it is ever made.
        android.text.TextUtils.getChars(text, 0, length, typed, 0)
        wipe()
        onSubmit?.invoke(typed)
    }

    private fun isWaiting() = waitUntil > SystemClock.elapsedRealtime()

    private fun setMessage(text: String?) {
        message = text?.takeIf { it.isNotBlank() }
        ended = false
        refresh(announceEnd = false)
        if (message != null) spoken.text = noteText()
    }

    /**
     * The line under the field, the outline and what can be used, as they are now. A countdown
     * schedules its next change: each second in the last minute, otherwise as its number changes.
     */
    private fun refresh(announceEnd: Boolean) {
        clock.removeCallbacks(tick)
        val remaining = waitUntil - SystemClock.elapsedRealtime()
        var justEnded = false
        if (remaining > 0) {
            val unit = if (remaining <= 60_000L) 1_000L else if (remaining <= 120 * 60_000L) 60_000L else 3_600_000L
            clock.postDelayed(tick, (remaining - 1) % unit + 1)
        } else if (waitUntil != 0L) {
            waitUntil = 0L
            if (announceEnd) {
                // What it said about the wait is stale now.
                message = null
                ended = true
                justEnded = true
            }
        }
        val text = noteText()
        note.text = text
        note.setTextColor(if (errorTone()) palette.error else palette.onSurfaceVariant)
        // The line's room stays (noteRoom); only the words go, and with them the screen reader's stop.
        val saying = text.isNotEmpty()
        // Emptied before it goes: nothing stale is left for a screen reader to read.
        if (!saying) spoken.text = ""
        note.visibility = if (saying) VISIBLE else INVISIBLE
        spoken.visibility = note.visibility
        if (justEnded) spoken.text = text
        updateOutline()
        updateEnabled()
        // The wait over: the field is focused with the keyboard up, as after a check, however
        // the owner left it meanwhile (the keyboard put away, say).
        if (justEnded && isAttachedToWindow && isShown && !busy) focusField(screenReader = false)
    }

    private fun noteText(): String {
        val remaining = waitUntil - SystemClock.elapsedRealtime()
        val countdown = if (remaining > 0) passphraseWaitText(resources, remaining) else null
        val over = if (ended && message == null && countdown == null) context.getString(R.string.lock_passphrase_wait_over) else null
        // A message and a countdown are two sentences: "Wrong passphrase. Try again in 28 seconds."
        val said = message?.let { passphraseMessage(resources, it, triesBeforeWait, waiting = countdown != null) }
        if (said != null && countdown != null) return context.getString(R.string.lock_passphrase_then, said, countdown)
        return listOfNotNull(said, countdown, over).joinToString(" ")
    }


    /**
     * Something went wrong, or tries are paused: the line under the field and the field's outline
     * both take the error colour. A check running and "You can try again now" don't.
     */
    private fun errorTone() = message != null || isWaiting()

    private fun updateEnabled() {
        val open = !busy && !isWaiting()
        // Held, not turned off: a turned-off field gives up focus (to Back, drawn as a grey pill
        // out of touch mode) and the keyboard goes, re-flowing the screen. Held, it keeps both
        // and refuses what's typed; drawn and announced as off all the same.
        field.acceptsInput = open
        // The eye goes with the field: nothing to show while it can't be typed in, and nothing
        // left focused (a grey circle) while it waits.
        eye.isEnabled = open
        eye.isFocusable = open
        val canSubmit = open && (field.text?.isNotEmpty() == true)
        submitButton.isEnabled = canSubmit
        val p = palette
        if (canSubmit) {
            submitButton.setTextColor(p.onPrimary)
            submitButton.background = ripple(p.primary, p.onPrimary)
        } else {
            // Material's disabled button: the surface's own text colour, faint, on a fainter pill.
            submitButton.setTextColor(withAlpha(p.onSurface, 0.38f))
            submitButton.background = ripple(withAlpha(p.onSurface, 0.12f), p.onSurface)
        }
        // Material's disabled field, as BioKey's own lock draws it: the words and the eye faint.
        val faint = withAlpha(p.onSurface, 0.38f)
        field.setTextColor(if (open) p.onSurface else faint)
        field.setHintTextColor(if (open) p.onSurfaceVariant else faint)
        eye.imageTintList = ColorStateList.valueOf(if (open) p.onSurfaceVariant else faint)
        updateOutline()
    }

    private fun updateOutline() {
        val p = palette
        val (width, color) = when {
            errorTone() -> dp(2) to p.error
            // As BioKey's own lock draws a held field: as thick as focus makes it, faint.
            busy -> (if (field.isFocused) dp(2) else dp(1)) to withAlpha(p.onSurface, 0.12f)
            field.isFocused -> dp(2) to p.primary
            else -> dp(1) to p.outline
        }
        outlineForTests = color
        fieldBox.background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(width, color)
        }
    }

    private fun setRevealed(show: Boolean) {
        revealed = show
        field.revealed = show
        val selection = field.selectionEnd.coerceAtLeast(0)
        field.transformationMethod = if (show) null else PasswordTransformationMethod.getInstance()
        if (selection <= (field.text?.length ?: 0)) field.setSelection(selection)
        eye.setImageDrawable(IconDrawable(if (show) VISIBILITY_OFF else VISIBILITY, dp(24)))
        eye.contentDescription = context.getString(if (show) R.string.passphrase_hide else R.string.passphrase_show)
    }

    @SuppressLint("AccessibilityFocus") // Asked for: the entry opens in place of the button the owner was on.
    private fun focusField(screenReader: Boolean) {
        field.requestFocus()
        // Screen readers land on the field too as it opens; not after each check, which would move them.
        if (screenReader) field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        field.post {
            if (field.hasFocus()) {
                field.windowInsetsController?.show(WindowInsets.Type.ime())
                keyboardForTests = true
            }
        }
    }

    private fun hideKeyboard() {
        if (field.hasFocus()) field.windowInsetsController?.hide(WindowInsets.Type.ime())
        keyboardForTests = false
    }

    override fun onDetachedFromWindow() {
        clock.removeCallbacks(tick)
        wipe()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isVisible) refresh(announceEnd = false)
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
        return RippleDrawable(ColorStateList.valueOf(withAlpha(rippleColor, 0.16f)), shape, mask)
    }

    private fun withAlpha(color: Int, alpha: Float) =
        Color.argb((Color.alpha(color) * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /**
     * The field. Pasting in is allowed (a passphrase kept in a password manager); nothing comes
     * back out: no copy, cut, share, autofill or undo (which could bring back what was wiped),
     * and, while shown in the clear, no selection to drag elsewhere. Masked, Android already
     * copies and drags only the dots.
     */
    @SuppressLint("AppCompatCustomView")
    internal class SecretField(context: Context) : EditText(context) {
        var revealed = false

        /**
         * False while a check runs or tries are paused: the field keeps its focus and the keyboard
         * (so nothing moves, and focus isn't left on Back), but nothing typed or pasted goes in,
         * no cursor blinks, and screen readers hear it as off.
         */
        var acceptsInput = true
            set(value) {
                if (field == value) return
                field = value
                isCursorVisible = value
                if (!value) cancelLongPress()
            }

        /** [PassphraseEntryView.wipe] is overwriting the buffer: let it, held or not. */
        internal var wiping = false

        /** Keeps every edit out while held, deletions too: the text stays as it is (empty, by then). */
        val refuseWhileHeld = InputFilter { _, _, _, dest, dstart, dend ->
            if (acceptsInput || wiping) null else dest.subSequence(dstart, dend)
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (!acceptsInput) info.isEnabled = false
        }

        init {
            val keepOnlyPaste = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu) = prune(menu).let { true }
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = prune(menu).let { true }
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
                override fun onDestroyActionMode(mode: ActionMode) = Unit
            }
            customSelectionActionModeCallback = keepOnlyPaste
            customInsertionActionModeCallback = keepOnlyPaste
        }

        private fun prune(menu: Menu) {
            for (i in menu.size - 1 downTo 0) {
                val id = menu[i].itemId
                if (!isAllowed(id)) menu.removeItem(id)
            }
        }

        private fun isAllowed(id: Int) = when (id) {
            android.R.id.paste, android.R.id.pasteAsPlainText -> true
            android.R.id.selectAll -> !revealed
            else -> false
        }

        override fun onTextContextMenuItem(id: Int): Boolean = if (acceptsInput && isAllowed(id)) super.onTextContextMenuItem(id) else false

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            if (revealed && selStart != selEnd) setSelection(selEnd)
        }
    }

    /** Material's eye icons (as on the backup password field), drawn from their 24-unit paths in the tint given. */
    private class IconDrawable(pathData: String, private val sizePx: Int) : Drawable() {
        private val source = PathParser.createPathFromPathData(pathData)
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private var tint: ColorStateList? = null

        override fun getIntrinsicWidth() = sizePx
        override fun getIntrinsicHeight() = sizePx

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            path.reset()
            source.transform(Matrix().apply {
                setScale(bounds.width() / 24f, bounds.height() / 24f)
                postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
            }, path)
        }

        override fun draw(canvas: Canvas) {
            paint.color = tint?.getColorForState(state, tint!!.defaultColor) ?: Color.BLACK
            canvas.drawPath(path, paint)
        }

        override fun setTintList(tint: ColorStateList?) {
            this.tint = tint
            invalidateSelf()
        }

        override fun isStateful() = tint?.isStateful == true
        override fun onStateChange(state: IntArray) = isStateful.also { if (it) invalidateSelf() }
        override fun setAlpha(alpha: Int) = paint.setAlpha(alpha)
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        /** The widest the locked app's name gets beside the field, in the one-row arrangement. */
        const val COMPACT_TITLE_MAX_DP = 200
        /** In one row, the most room kept above and below it, given up first when the room is short. */
        const val COMPACT_ROOM_DP = 12
        const val VISIBILITY = "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z"
        const val VISIBILITY_OFF = "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7zM2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27zM7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2zM11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z"
    }
}
