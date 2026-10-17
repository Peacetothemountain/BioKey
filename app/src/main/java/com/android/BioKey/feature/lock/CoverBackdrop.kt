package com.android.BioKey.feature.lock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import kotlin.math.cos

/**
 * The soft light behind the locked app's icon on the lock cover: a faint glow in the app's own
 * colour that breathes slowly, and a thin ring of the same colour framing the icon.
 *
 * Breathes on the shared uptime clock, so the instant overlay and the lock screen draw the
 * identical frame when one replaces the other. Holds still when "Remove animations" is on.
 */
@SuppressLint("ViewConstructor")
internal class CoverBackdrop(context: Context) : View(context) {

    companion object {
        /** One full breath (in and out): slow, so it reads as light rather than motion. */
        private const val BREATH_MS = 7_000.0
        /** The ring hugs the icon: a thin bezel, not a gap. */
        private const val RING_GAP_DP = 3.5f
        /** How far the glow reaches, in icon radii. */
        private const val GLOW_REACH = 2.8f
    }

    private val density = resources.displayMetrics.density
    private val animate = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var primary = Color.GRAY
    private var accent = Color.GRAY
    private var appColor: Int? = null

    /** Centre and radius of the icon the glow and ring surround (view coordinates); none if radius 0. */
    private var focusX = 0f
    private var focusY = 0f
    private var focusRadius = 0f

    /** [primary] lights apps whose icons have no colour of their own; [disc] sits behind the icon. */
    fun setColors(primary: Int, disc: Int) {
        this.primary = primary
        discPaint.color = disc
        accent = appColor ?: primary
        rebuildGlow()
        invalidate()
    }

    /** The locked app's colour, or null for BioKey's own. */
    fun setAccent(color: Int?) {
        appColor = color
        accent = color ?: primary
        rebuildGlow()
        invalidate()
    }

    fun setFocus(x: Float, y: Float, radius: Float) {
        if (x == focusX && y == focusY && radius == focusRadius) return
        focusX = x
        focusY = y
        focusRadius = radius
        rebuildGlow()
        invalidate()
    }

    private fun rebuildGlow() {
        if (focusRadius <= 0f) return
        glowPaint.shader = RadialGradient(
            focusX, focusY, focusRadius * GLOW_REACH,
            intArrayOf(withAlpha(accent, 0.24f), withAlpha(accent, 0.09f), Color.TRANSPARENT),
            floatArrayOf(0.3f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (focusRadius <= 0f) return
        val breath = if (animate) ((1 - cos(2 * Math.PI * SystemClock.uptimeMillis() / BREATH_MS)) / 2).toFloat() else 0.5f
        val scale = 0.97f + 0.06f * breath
        glowPaint.alpha = (255 * (0.8f + 0.2f * breath)).toInt()
        canvas.save()
        canvas.scale(scale, scale, focusX, focusY)
        canvas.drawCircle(focusX, focusY, focusRadius * GLOW_REACH, glowPaint)
        canvas.restore()

        val ringRadius = focusRadius + RING_GAP_DP * density
        canvas.drawCircle(focusX, focusY, ringRadius, discPaint)
        ringPaint.strokeWidth = 2f * density
        ringPaint.color = withAlpha(accent, 0.38f + 0.14f * breath)
        canvas.drawCircle(focusX, focusY, ringRadius, ringPaint)

        if (animate && isShown) postInvalidateOnAnimation()
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
