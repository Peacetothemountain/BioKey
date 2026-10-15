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
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Background of the lock cover: BioKey's constellation, faint, fading toward the bottom, and a
 * breathing glow and ring around the locked app's icon, tinted with the app's own colour.
 *
 * The glow breathes on the shared uptime clock, and the constellation is seeded by the locked
 * package, so the instant overlay and the lock screen draw the identical frame at the moment one
 * replaces the other. Holds still when "Remove animations" is on.
 */
@SuppressLint("ViewConstructor")
internal class CoverBackdrop(context: Context) : View(context) {

    companion object {
        /** One full breath (in and out), matching the logo aura on BioKey's home screen. */
        private const val BREATH_MS = 5_600.0
        private const val STAR_COUNT = 26
        private const val LINK_DIST_DP = 120f
    }

    private val density = resources.displayMetrics.density
    private val animate = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.2f * density }

    private var primary = Color.GRAY
    private var tertiary = Color.GRAY
    private var accent = Color.GRAY
    private var disc = Color.DKGRAY

    /** Centre and radius of the icon the glow and ring surround (view coordinates); none if radius 0. */
    private var focusX = 0f
    private var focusY = 0f
    private var focusRadius = 0f

    private var seed = 0
    private var starsX = FloatArray(0)
    private var starsY = FloatArray(0)
    private var starsR = FloatArray(0)

    fun setColors(primary: Int, tertiary: Int, disc: Int) {
        this.primary = primary
        this.tertiary = tertiary
        this.disc = disc
        if (accent == Color.GRAY) accent = primary
        rebuildAura()
        invalidate()
    }

    /** Tint for the glow: the locked app's colour, or null for BioKey's own. */
    fun setAccent(appColor: Int?) {
        accent = appColor?.let { blend(it, primary, 0.45f) } ?: primary
        rebuildAura()
        invalidate()
    }

    /** Same package, same constellation, on both covers. */
    fun setSeed(value: Int) {
        if (value == seed && starsX.isNotEmpty()) return
        seed = value
        layoutStars()
        invalidate()
    }

    fun setFocus(x: Float, y: Float, radius: Float) {
        if (x == focusX && y == focusY && radius == focusRadius) return
        focusX = x
        focusY = y
        focusRadius = radius
        rebuildAura()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutStars()
        rebuildAura()
    }

    private fun layoutStars() {
        if (width == 0 || height == 0) return
        val rnd = Random(seed)
        starsX = FloatArray(STAR_COUNT) { rnd.nextFloat() * width }
        // Mostly in the top half, thinning out lower down.
        starsY = FloatArray(STAR_COUNT) { (rnd.nextFloat() * rnd.nextFloat()) * height * 0.85f }
        starsR = FloatArray(STAR_COUNT) { (1.4f + rnd.nextFloat() * 1.8f) * density }
    }

    private fun rebuildAura() {
        if (focusRadius <= 0f) return
        val r = focusRadius * 3.2f
        auraPaint.shader = RadialGradient(
            focusX, focusY, r,
            intArrayOf(withAlpha(accent, 0.42f), withAlpha(accent, 0.14f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        // Constellation.
        val linkDist = LINK_DIST_DP * density
        for (i in starsX.indices) {
            val fadeI = fade(starsY[i], h)
            for (j in i + 1 until starsX.size) {
                val dx = starsX[i] - starsX[j]
                val dy = starsY[i] - starsY[j]
                val d = sqrt(dx * dx + dy * dy)
                if (d >= linkDist) continue
                linkPaint.color = withAlpha(primary, (1f - d / linkDist) * 0.22f * fadeI)
                canvas.drawLine(starsX[i], starsY[i], starsX[j], starsY[j], linkPaint)
            }
            starPaint.color = withAlpha(if (i % 3 == 0) tertiary else primary, 0.55f * fadeI)
            canvas.drawCircle(starsX[i], starsY[i], starsR[i], starPaint)
        }

        if (focusRadius <= 0f) return
        // Breathing glow, then a quiet disc and ring framing the icon.
        val breath = if (animate) ((1 - cos(2 * Math.PI * SystemClock.uptimeMillis() / BREATH_MS)) / 2).toFloat() else 0.5f
        val scale = 0.92f + 0.16f * breath
        auraPaint.alpha = (255 * (0.7f + 0.3f * breath)).toInt()
        canvas.save()
        canvas.scale(scale, scale, focusX, focusY)
        canvas.drawCircle(focusX, focusY, focusRadius * 3.2f, auraPaint)
        canvas.restore()

        discPaint.color = disc
        canvas.drawCircle(focusX, focusY, focusRadius + 10 * density, discPaint)
        ringPaint.strokeWidth = 2 * density
        ringPaint.color = withAlpha(accent, 0.55f + 0.25f * breath)
        canvas.drawCircle(focusX, focusY, focusRadius + 10 * density, ringPaint)

        if (animate && isShown) postInvalidateOnAnimation()
    }

    private fun fade(y: Float, h: Float): Float {
        val p = (y / h).coerceIn(0f, 1f)
        return 1f - p * p
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
    )
}
