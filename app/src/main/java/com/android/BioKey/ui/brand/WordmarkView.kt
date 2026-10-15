package com.android.BioKey.ui.brand

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.provider.Settings
import android.view.View

/**
 * The [Wordmark] for plain-View screens (the lock cover). Animates on the uptime clock while
 * shown, and holds still when "Remove animations" is on.
 */
@SuppressLint("ViewConstructor")
class WordmarkView(context: Context) : View(context) {

    private val painter = WordmarkPainter()
    private val animate = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    private var letters = Color.GRAY
    private var vine = Color.GRAY
    private var ground = Color.BLACK

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setColors(letters: Int, vine: Int, ground: Int) {
        this.letters = letters
        this.vine = vine
        this.ground = ground
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val phase = if (animate) Wordmark.phaseAt(SystemClock.uptimeMillis()) else Wordmark.STILL_PHASE
        painter.draw(canvas, width.toFloat(), height.toFloat(), phase, letters, vine, ground)
        if (animate && isShown) postInvalidateOnAnimation()
    }
}
