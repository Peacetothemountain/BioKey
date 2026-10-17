package com.android.BioKey.ui.brand

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import com.android.BioKey.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws the vines of the BioKey logo (the tentacles layer), slithering: waves travel along each
 * vine like a snake, while the artwork itself is untouched.
 *
 * How: `logo_vine_field.png` (made from the artwork by `tools/logo/VineField.cs`) holds, for
 * every pixel on or near a vine, the vine's sideways direction and how far along the vine it
 * is, pre-multiplied into two vectors U and V. A small shader moves each point by
 * A (U cos wt - V sin wt), a wave travelling along the vine; each cross-section moves as one
 * piece, and the vines stay anchored where they wrap round the letters, so the letters never
 * show gaps. The wave is a few multiply-adds per pixel; there is no per-frame allocation besides
 * setting two uniforms.
 *
 * Android 13+ (runtime shaders); earlier versions, and "Remove animations", draw the vines still.
 * Drawn fitted and centred in the given size, like `ContentScale.Fit`, so it lines up with the
 * letters layer drawn the same way. One per view; not thread-safe.
 */
class VineSlither(private val resources: Resources) {

    companion object {
        /** One wave passing a point on a vine. */
        private const val PERIOD_MS = 2_400L
        /** How far a vine sways to either side, in artwork pixels. */
        private const val AMPLITUDE = 18f

        @Volatile private var vinesCache: Bitmap? = null
        @Volatile private var fieldCache: Bitmap? = null

        private fun decode(resources: Resources, id: Int): Bitmap =
            BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })

        private fun vines(resources: Resources) =
            vinesCache ?: decode(resources, R.drawable.ic_title_logo_tentacles).also { vinesCache = it }

        private fun field(resources: Resources) =
            fieldCache ?: decode(resources, R.drawable.logo_vine_field).also { fieldCache = it }

        /** The wave's position on the shared uptime clock, so every logo on screen moves in step. */
        fun phaseAt(uptimeMs: Long): Float = (uptimeMs % PERIOD_MS) / PERIOD_MS.toFloat()

        val canAnimate: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        private const val AGSL = """
uniform shader content;
uniform shader sway;
uniform float2 origin;
uniform float2 artSize;
uniform float2 fieldSize;
uniform float2 wave;
uniform float amp;

half4 main(float2 p) {
    float2 f = clamp((p - origin) / artSize * fieldSize, float2(0.5), fieldSize - 0.5);
    half4 a = sway.eval(f);
    half4 b = sway.eval(f + float2(fieldSize.x, 0.0));
    float2 u = (float2(a.rg) * 255.0 - 128.0) / 127.0;
    float2 v = (float2(b.rg) * 255.0 - 128.0) / 127.0;
    return content.eval(p - amp * (u * wave.x - v * wave.y));
}
"""
    }

    private val vines = vines(resources)
    // The artwork, scaled in [layout] to the size it is drawn at, so its fine ridges don't shimmer as they move.
    private var content = BitmapShader(vines, Shader.TileMode.DECAL, Shader.TileMode.DECAL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    // Built on the first moving frame, so a still logo never compiles the shader.
    private var wave: Wave? = null

    private var lastWidth = -1f
    private var lastHeight = -1f
    private var left = 0f
    private var top = 0f
    private var scale = 1f
    private var tint = 0

    /** Fills the vines with [color] (the theme's tertiary). */
    fun setTint(color: Int) {
        if (color == tint && paint.colorFilter != null) return
        tint = color
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    /** Draws the vines fitted into [width] x [height]; [phase] from [phaseAt], or null for still. */
    fun draw(canvas: Canvas, width: Float, height: Float, phase: Float?) {
        if (width <= 0f || height <= 0f) return
        if (width != lastWidth || height != lastHeight) layout(width, height)
        val artW = vines.width * scale
        val artH = vines.height * scale
        val moving = if (phase != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wave() else null
        if (moving != null && phase != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val reach = AMPLITUDE * scale
            paint.shader = moving.frame(phase)
            canvas.drawRect(left - reach, top - reach, left + artW + reach, top + artH + reach, paint)
        } else {
            paint.shader = content
            canvas.drawRect(left, top, left + artW, top + artH, paint)
        }
    }

    private fun layout(width: Float, height: Float) {
        lastWidth = width
        lastHeight = height
        scale = min(width / vines.width, height / vines.height)
        left = (width - vines.width * scale) / 2f
        top = (height - vines.height * scale) / 2f
        val w = (vines.width * scale).roundToInt().coerceAtLeast(1)
        val h = (vines.height * scale).roundToInt().coerceAtLeast(1)
        // The previous one may still be in a recorded frame: leave it to the GC, never recycle it.
        val sized = downscale(vines, w, h)
        content = BitmapShader(sized, Shader.TileMode.DECAL, Shader.TileMode.DECAL)
        matrix.setScale(vines.width * scale / w, vines.height * scale / h)
        matrix.postTranslate(left, top)
        content.setLocalMatrix(matrix)
        wave?.let(::layoutWave)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun wave(): Wave = wave ?: Wave(field(resources)).also {
        wave = it
        layoutWave(it)
    }

    private fun layoutWave(w: Wave) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            w.layout(content, left, top, vines.width * scale, vines.height * scale, AMPLITUDE * scale)
        }
    }

    /** Halves with filtering until close, then scales the rest of the way: a clean box-like downscale. */
    private fun downscale(src: Bitmap, w: Int, h: Int): Bitmap {
        var b = src
        while (b.width >= w * 2 && b.height >= h * 2) {
            val half = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            if (b !== src) b.recycle()
            b = half
        }
        if (b.width == w && b.height == h) return b
        return Bitmap.createScaledBitmap(b, w, h, true).also { if (b !== src) b.recycle() }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class Wave(field: Bitmap) {
        private val shader = RuntimeShader(AGSL).apply {
            setInputShader("sway", BitmapShader(field, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                filterMode = BitmapShader.FILTER_MODE_LINEAR
            })
            setFloatUniform("fieldSize", field.width / 2f, field.height.toFloat())
        }

        fun layout(content: BitmapShader, left: Float, top: Float, width: Float, height: Float, amp: Float) {
            content.filterMode = BitmapShader.FILTER_MODE_LINEAR
            shader.setInputShader("content", content)
            shader.setFloatUniform("origin", left, top)
            shader.setFloatUniform("artSize", width, height)
            shader.setFloatUniform("amp", amp)
        }

        fun frame(phase: Float): Shader {
            val angle = 2.0 * PI * phase
            shader.setFloatUniform("wave", cos(angle).toFloat(), sin(angle).toFloat())
            return shader
        }
    }
}
