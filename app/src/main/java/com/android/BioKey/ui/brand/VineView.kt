package com.android.BioKey.ui.brand

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.provider.Settings
import android.view.View

/**
 * The logo's slithering vines ([VineSlither]) for plain-View screens (the lock cover), drawn
 * fitted and centred like the letters' ImageView beneath. Moves on the uptime clock while shown,
 * so every copy on screen is in step; still when [animated] is false or with "Remove animations".
 */
@SuppressLint("ViewConstructor")
class VineView(context: Context, animated: Boolean = true) : View(context) {

    private val vines = VineSlither(resources)
    private val animate = animated && VineSlither.canAnimate &&
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setTint(color: Int) {
        vines.setTint(color)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val phase = if (animate) VineSlither.phaseAt(SystemClock.uptimeMillis()) else null
        vines.draw(canvas, width.toFloat(), height.toFloat(), phase)
        if (animate && isShown) postInvalidateOnAnimation()
    }
}
