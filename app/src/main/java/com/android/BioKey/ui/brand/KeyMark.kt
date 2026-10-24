package com.android.BioKey.ui.brand

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.withTranslation
import kotlin.math.min

/**
 * BioKey's mark: the key of its launcher icon, a fingerprint whose middle ridge becomes the key's
 * blade, drawn in vector at any size and in the phone's own colours (Material You) instead of the
 * icon's fixed blue-to-orange.
 *
 * Its geometry is the icon's own, measured from the launcher artwork on its 108-unit grid, so the
 * mark inside BioKey and the icon on the home screen are the same key: five concentric ridges,
 * each with legs of its own length as a finger's ridges have, the blade with two teeth, and three
 * sparkles. Still: it never moves.
 *
 * Shared by BioKey's Compose screens ([BioKeyLogo]) and the plain-View lock cover ([WordmarkView]).
 * The paths are made once, here; a draw only scales them.
 */
internal object KeyMark {

    /** The mark's bounds on the icon's 108-unit grid: the ridges' outer edge to the sparkles. */
    private val BOUNDS = RectF(32.5f, 23.5f, 74f, 70.5f)

    /** Width over height: how much room the mark takes beside its name. */
    val ASPECT = BOUNDS.width() / BOUNDS.height()

    /** The icon's stroke: every ridge, the blade and its teeth. */
    private const val STROKE = 1.75f

    /** Where the ridges are centred. */
    private const val CX = 53.875f
    private const val CY = 37.65f

    /**
     * Each ridge: its radius, and how far down its left and right legs reach (to the centre of
     * their round ends), as the icon has them.
     */
    private val RIDGES = listOf(
        Triple(12.875f, 46.875f, 45.625f),
        Triple(10.375f, 50.625f, 53.125f),
        Triple(7.625f, 54.375f, 48.125f),
        Triple(5.125f, 51.875f, 50.625f),
        Triple(2.625f, 49.375f, 46.875f),
    )

    /** The fingerprint's ridges, as one path. */
    private val ridges = Path().apply {
        for ((r, leftEnd, rightEnd) in RIDGES) {
            moveTo(CX - r, leftEnd)
            lineTo(CX - r, CY)
            arcTo(RectF(CX - r, CY - r, CX + r, CY + r), 180f, 180f)
            lineTo(CX + r, rightEnd)
        }
    }

    /** The blade, down the middle ridge, and its two teeth. */
    private val key = Path().apply {
        moveTo(CX, 40.375f)
        lineTo(CX, 68.625f)
        moveTo(CX, 60.75f)
        lineTo(58.375f, 60.75f)
        moveTo(CX, 66f)
        lineTo(56.625f, 66f)
    }

    /** A four-pointed sparkle at [cx], [cy], [r] from its centre to each point. */
    private fun sparkle(cx: Float, cy: Float, r: Float) = Path().apply {
        moveTo(cx, cy - r)
        quadTo(cx, cy, cx + r, cy)
        quadTo(cx, cy, cx, cy + r)
        quadTo(cx, cy, cx - r, cy)
        quadTo(cx, cy, cx, cy - r)
        close()
    }

    private val sparkleLarge = sparkle(36f, 34.5f, 3.2f)
    private val sparkleRight = sparkle(71.1f, 40.9f, 2.15f)
    private val sparkleSmall = sparkle(38.9f, 59f, 1.2f)

    /** Draws with these, one set per caller's thread: the main thread, everywhere BioKey draws it. */
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shaderFor: Pair<Int, Int>? = null

    /**
     * Draws the mark fitted into [width] x [height] at [left], [top] (centred, its proportions
     * kept): the ridges and key in a gradient from [primary] (top left) to [tertiary] (the blade's
     * end), as the icon runs from blue to orange; the sparkles in [primary], [tertiary] and
     * [secondary], as the icon's are blue, orange and blue.
     */
    fun draw(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, primary: Int, secondary: Int, tertiary: Int) {
        if (width <= 0f || height <= 0f) return
        val scale = min(width / BOUNDS.width(), height / BOUNDS.height())
        val dx = left + (width - BOUNDS.width() * scale) / 2f - BOUNDS.left * scale
        val dy = top + (height - BOUNDS.height() * scale) / 2f - BOUNDS.top * scale
        if (shaderFor != primary to tertiary) {
            // In the mark's own units: the gradient scales with it.
            stroke.shader = LinearGradient(40f, 24f, 60f, 70f, primary, tertiary, Shader.TileMode.CLAMP)
            shaderFor = primary to tertiary
        }
        canvas.withTranslation(dx, dy) {
            scale(scale, scale)
            drawPath(ridges, stroke)
            drawPath(key, stroke)
            fill.color = primary
            drawPath(sparkleLarge, fill)
            fill.color = tertiary
            drawPath(sparkleRight, fill)
            fill.color = withAlpha(secondary, 0.8f)
            drawPath(sparkleSmall, fill)
        }
    }

    private fun withAlpha(color: Int, alpha: Float) =
        Color.argb((Color.alpha(color) * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
