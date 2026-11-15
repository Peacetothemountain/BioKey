package com.android.BioKey.feature.lock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.withScale
import com.android.BioKey.ui.theme.animationsEnabled
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
        /** How often the breath is redrawn ([onDraw]). */
        private const val BREATH_FRAME_MS = 66L
        /** The ring hugs the icon: a thin bezel, not a gap. */
        private const val RING_GAP_DP = 3.5f
        /** How far the glow reaches, in icon radii. */
        private const val GLOW_REACH = 2.8f
        /** The least it reaches when something below asks it to stop short (the passphrase's field). */
        private const val GLOW_REACH_MIN = 1.4f
    }

    private val density = resources.displayMetrics.density
    /** Re-read whenever the cover shows: the overlay's cover lives as long as its engine. */
    private var animate = animationsEnabled(context)

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
    /** How far the glow reaches from the centre, in pixels, the breath included. */
    private var glowRadius = 0f

    init {
        // A slow breath needs no more than a low frame rate, so the display can drop to one while
        // the prompt waits. Both covers ask the same, so they still draw the same frame.
        if (Build.VERSION.SDK_INT >= 35) setRequestedFrameRate(REQUESTED_FRAME_RATE_CATEGORY_LOW)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animate = animationsEnabled(context)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            animate = animationsEnabled(context)
            invalidate()
        }
    }

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

    /**
     * The icon the glow and ring surround: its centre and [radius] (none if 0). [maxReach]: how
     * far below the centre the glow may reach, for a see-through field under the icon; it fades
     * out by then instead of shining through it, and is never cut off in a line. Nor by the
     * cover's own edges: near one (landscape, the keyboard up, a folded phone) it reaches less far,
     * so it fades out on every side before the edge rather than ending in a straight band.
     * Called after a layout, so the cover's size is known.
     */
    fun setFocus(x: Float, y: Float, radius: Float, maxReach: Float = Float.MAX_VALUE) {
        // The breath grows the glow by up to 3%: it stays inside the reach at its fullest.
        val reach = if (radius <= 0f) 0f
        else minOf(radius * GLOW_REACH, maxReach / 1.03f).coerceAtLeast(radius * GLOW_REACH_MIN)
            .coerceAtMost(edgeRoom(x, y) / 1.03f).coerceAtLeast(radius)
        if (x == focusX && y == focusY && radius == focusRadius && reach == glowRadius) return
        focusX = x
        focusY = y
        focusRadius = radius
        glowRadius = reach
        rebuildGlow()
        invalidate()
    }

    /** How far it is from ([x], [y]) to the nearest edge of the cover; no limit before it's laid out. */
    private fun edgeRoom(x: Float, y: Float): Float =
        if (width <= 0 || height <= 0) Float.MAX_VALUE else minOf(x, y, width - x, height - y)

    private fun rebuildGlow() {
        if (focusRadius <= 0f) return
        glowPaint.shader = RadialGradient(
            focusX, focusY, glowRadius,
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
        canvas.withScale(scale, scale, focusX, focusY) {
            drawCircle(focusX, focusY, glowRadius, glowPaint)
        }

        val ringRadius = focusRadius + RING_GAP_DP * density
        canvas.drawCircle(focusX, focusY, ringRadius, discPaint)
        ringPaint.strokeWidth = 2f * density
        ringPaint.color = withAlpha(accent, 0.38f + 0.14f * breath)
        canvas.drawCircle(focusX, focusY, ringRadius, ringPaint)

        // About 15 frames a second, not one per display refresh (60-120 Hz) for as long as the
        // prompt waits: the breath moves the glow by a few pixels a second, so every frame of a
        // fast display would redraw it for a fraction of a pixel. The frame-rate request above
        // only exists from Android 15.
        // One pending redraw at most, whatever else asked for a frame meanwhile.
        if (animate && isShown) {
            removeCallbacks(breathe)
            postDelayed(breathe, BREATH_FRAME_MS)
        }
    }

    private val breathe = Runnable { invalidate() }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
