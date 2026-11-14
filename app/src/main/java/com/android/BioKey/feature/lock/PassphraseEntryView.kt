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
import androidx.core.view.isVisible
import androidx.core.view.size
import com.android.BioKey.R
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.ui.security.WindowProtection
import kotlin.math.ceil

/**
 * The owner passphrase's entry on the lock cover ([LockCoverView.showPassphraseEntry]): in place of
 * the cover's buttons while it's open, and built like them. A pill-shaped field with an eye (as in
 * BioKey's backup password dialog), a line under it for what went wrong or how long to wait,
 * "Unlock" as the cover's own Unlock is drawn, and "Back" as its Cancel is.
 *
 * A scroll view, as a last resort: on a short window with the keyboard up (a foldable's cover
 * screen, a phone in landscape) the field stays in view and the rest scrolls.
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
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        filters = arrayOf(InputFilter.LengthFilter(OwnerPassphrase.MAX_LENGTH))
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
        setPadding(dp(24), 0, dp(52), 0)
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
        setOnClickListener { setRevealed(!revealed) }
    }

    /** The pill the field and eye sit in: its outline thickens and takes the scheme's colour with focus, or an error. */
    private val fieldBox = FrameLayout(context).apply {
        addView(field, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(eye, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(4) })
    }

    /** What went wrong and how long to wait, as seen: the countdown changes every second, so screen readers don't hear it. */
    internal val note = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
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
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        isScreenReaderFocusable = true
        accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.text = note.text
            }
        }
    }

    private val noteBox = FrameLayout(context).apply {
        visibility = GONE
        addView(note, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(spoken, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    internal val submitButton = TextView(context).apply {
        text = context.getString(R.string.lock_passphrase_unlock)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(fieldBox, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        addView(noteBox, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            leftMargin = dp(16)
            rightMargin = dp(16)
        })
        addView(submitButton, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(12) })
        addView(backButton, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(8) })
    }

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
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (ended && !s.isNullOrEmpty()) {
                    ended = false
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
        field.setHintTextColor(p.onSurfaceVariant)
        field.highlightColor = withAlpha(p.primary, 0.3f)
        field.textCursorDrawable = GradientDrawable().apply {
            setColor(p.primary)
            setSize(dp(2), 0)
        }
        field.textSelectHandle?.setTint(p.primary)
        field.textSelectHandleLeft?.setTint(p.primary)
        field.textSelectHandleRight?.setTint(p.primary)
        eye.imageTintList = ColorStateList.valueOf(p.onSurfaceVariant)
        eye.background = RippleDrawable(ColorStateList.valueOf(withAlpha(p.onSurface, 0.16f)), null, GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        })
        note.setTextColor(if (message != null) p.error else p.onSurfaceVariant)
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
        submitButton.text = context.getString(if (value) R.string.lock_passphrase_checking else R.string.lock_passphrase_unlock)
        updateEnabled()
        if (!value && isAttachedToWindow && isShown && !isWaiting()) focusField(screenReader = false)
    }

    fun setError(text: String?) {
        if (text != null) wipe()
        setMessage(text)
    }

    fun setWait(untilElapsedRealtime: Long) {
        val starting = untilElapsedRealtime > SystemClock.elapsedRealtime() && untilElapsedRealtime != waitUntil
        waitUntil = untilElapsedRealtime
        if (starting) {
            wipe()
            hideKeyboard()
        }
        refresh(announceEnd = false)
        if (starting) spoken.text = noteText()
    }

    /** Overwrites what was typed in the field's own buffer, then empties it (and its undo history). */
    fun wipe() {
        val text = field.text ?: return
        val length = text.length
        if (length > 0) text.replace(0, length, String(CharArray(length)))
        text.clear()
        text.clearSpans()
        field.setText("")
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
        note.setTextColor(if (message != null) palette.error else palette.onSurfaceVariant)
        noteBox.isVisible = text.isNotEmpty()
        if (text.isEmpty()) spoken.text = ""
        if (justEnded) spoken.text = text
        updateOutline()
        updateEnabled()
        if (justEnded && isAttachedToWindow && isShown && !busy) field.requestFocus()
    }

    private fun noteText(): String {
        val remaining = waitUntil - SystemClock.elapsedRealtime()
        val countdown = if (remaining > 0) waitText(remaining) else null
        val over = if (ended && message == null && countdown == null) context.getString(R.string.lock_passphrase_wait_over) else null
        // A message and a countdown are two sentences: "Wrong passphrase. Try again in 28 s".
        val said = message
        if (said != null && countdown != null) return context.getString(R.string.lock_passphrase_then, said, countdown)
        return listOfNotNull(said, countdown, over).joinToString(" ")
    }

    /** "Try again in 28 s", "in 4 min", "in 2 h": always rounded up, so it never says 0. */
    private fun waitText(remainingMs: Long): String = when {
        remainingMs <= 60_000L -> context.getString(R.string.lock_passphrase_wait_seconds, ceil(remainingMs / 1_000.0).toInt())
        remainingMs <= 120 * 60_000L -> context.getString(R.string.lock_passphrase_wait_minutes, ceil(remainingMs / 60_000.0).toInt())
        else -> context.getString(R.string.lock_passphrase_wait_hours, ceil(remainingMs / 3_600_000.0).toInt())
    }

    private fun updateEnabled() {
        val open = !busy && !isWaiting()
        field.isEnabled = open
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
        field.alpha = if (open) 1f else 0.6f
    }

    private fun updateOutline() {
        val p = palette
        val (width, color) = when {
            message != null -> dp(2) to p.error
            field.isFocused -> dp(2) to p.primary
            else -> dp(1) to p.outline
        }
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
        eye.contentDescription = context.getString(if (show) R.string.lock_passphrase_hide else R.string.lock_passphrase_show)
    }

    @SuppressLint("AccessibilityFocus") // Asked for: the entry opens in place of the button the owner was on.
    private fun focusField(screenReader: Boolean) {
        if (!field.isEnabled) return
        field.requestFocus()
        // Screen readers land on the field too as it opens; not after each check, which would move them.
        if (screenReader) field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        field.post { if (field.hasFocus()) field.windowInsetsController?.show(WindowInsets.Type.ime()) }
    }

    private fun hideKeyboard() {
        if (field.hasFocus()) field.windowInsetsController?.hide(WindowInsets.Type.ime())
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

        override fun onTextContextMenuItem(id: Int): Boolean = if (isAllowed(id)) super.onTextContextMenuItem(id) else false

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
        const val VISIBILITY = "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z"
        const val VISIBILITY_OFF = "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7zM2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27zM7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2zM11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z"
    }
}
