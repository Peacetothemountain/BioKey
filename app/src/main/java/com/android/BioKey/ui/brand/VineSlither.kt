package com.android.BioKey.ui.brand

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import com.android.BioKey.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the vines of the BioKey logo (the tentacles layer), slithering: waves travel along each
 * vine like a snake, while the artwork itself is untouched.
 *
 * How: `logo_vine_field.webp` (made from the artwork by `tools/logo/VineField.cs`) holds, for
 * every pixel on or near a vine, the vine's sideways direction and how far along the vine it
 * is, pre-multiplied into two vectors U and V. A small shader moves each point by
 * A (U cos wt - V sin wt), a wave travelling along the vine; each cross-section moves as one
 * piece, and the vines stay anchored where they wrap round the letters, so the letters never
 * show gaps. The wave is a few multiply-adds per pixel; there is no per-frame allocation besides
 * setting two uniforms.
 *
 * Android 13+ (runtime shaders); earlier versions, and "Remove animations", draw the vines still.
 * Drawn fitted and centred in the given size, like `ContentScale.Fit`, from a copy sized to fit
 * ([LogoLayer]), so it lines up with the letters layer drawn the same way. One per view; not
 * thread-safe.
 */
class VineSlither(private val resources: Resources) {

    companion object {
        /** One wave passing a point on a vine. */
        private const val PERIOD_MS = 2_400L
        /** How far a vine sways to either side, in artwork pixels. */
        private const val AMPLITUDE = 18f

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

    private val vines = LogoLayer(resources, R.drawable.ic_title_logo_tentacles)
    // Built on the first moving frame, so a still logo never compiles the shader.
    private var wave: Wave? = null
    private var waveFailed = false
    // The artwork the wave was last pointed at; a new size brings a new one.
    private var waveContent: BitmapShader? = null

    /** Fills the vines with [color] (the theme's tertiary). */
    fun setTint(color: Int) = vines.setTint(color)

    /** Starts preparing the artwork for a [width] x [height] drawing ahead of its first frame. */
    fun prepare(width: Int, height: Int) = vines.prepare(width, height)

    /** Draws the vines fitted into [width] x [height]; [phase] from [phaseAt], or null for still. */
    fun draw(canvas: Canvas, width: Float, height: Float, phase: Float?) {
        if (!vines.layout(width, height)) return
        if (phase != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val moving = wave()
            if (moving != null) {
                val fit = vines.fit
                val reach = AMPLITUDE * fit.scale
                vines.paint.shader = moving.frame(phase)
                canvas.drawRect(fit.left - reach, fit.top - reach, fit.left + fit.width + reach, fit.top + fit.height + reach, vines.paint)
                return
            }
        }
        vines.draw(canvas, width, height)
    }

    /** The wave, laid out over the current artwork; null (draw still) if it can't be built. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun wave(): Wave? {
        val content = vines.content ?: return null
        val w = wave ?: run {
            if (waveFailed) return null
            // Decorative: without the field or the shader, the vines just hold still.
            runCatching { LogoArt.field(resources)?.let(::Wave) }.getOrNull()
                .also { wave = it; waveFailed = it == null } ?: return null
        }
        if (content !== waveContent) {
            waveContent = content
            val fit = vines.fit
            w.layout(content, fit.left, fit.top, fit.width, fit.height, AMPLITUDE * fit.scale)
        }
        return w
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
