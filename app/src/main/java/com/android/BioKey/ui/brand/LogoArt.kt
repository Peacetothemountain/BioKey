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
import android.graphics.Shader
import androidx.annotation.DrawableRes
import androidx.core.graphics.scale
import com.android.BioKey.R
import java.lang.ref.WeakReference
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The wordmark's artwork, shared by every logo in the process.
 *
 * The PNGs are 1600 px wide, several times larger than anywhere they're drawn, and the
 * always-running protection service draws a logo on its cover. So only copies scaled to the
 * drawn size are kept; each full-size decode (4.5 MB) is dropped as soon as its copy is made.
 * Copies are shared while any logo still draws them, and the last few stay a while longer, so a
 * lock screen reuses the shield's copy and a logo scrolled away and back isn't scaled again.
 *
 * Decorative and best-effort: if the artwork can't be loaded, the logo is left out, nothing
 * throws. Main thread only, apart from the background work behind [prepare].
 */
internal object LogoArt {

    /** Copies kept after no logo draws them any more: the two layers at two sizes. */
    private const val RECENT = 4

    /** Where a layer lands when fitted into a size, like `ContentScale.Fit`. */
    data class Fit(
        /** Drawn size over the artwork's own: artwork pixels to drawn pixels. */
        val scale: Float,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    ) {
        /** Size of the scaled copy: the drawn size in whole pixels. */
        val pixelWidth get() = width.roundToInt().coerceAtLeast(1)
        val pixelHeight get() = height.roundToInt().coerceAtLeast(1)
    }

    /** [artWidth] x [artHeight] fitted and centred in [width] x [height]. */
    fun fit(artWidth: Int, artHeight: Int, width: Float, height: Float): Fit {
        val scale = min(width / artWidth, height / artHeight)
        val w = artWidth * scale
        val h = artHeight * scale
        return Fit(scale, (width - w) / 2f, (height - h) / 2f, w, h)
    }

    private data class Key(val id: Int, val width: Int, val height: Int)

    private val artSizes = HashMap<Int, IntArray>()
    private val live = HashMap<Key, WeakReference<Bitmap>>()
    private val recent = object : LinkedHashMap<Key, Bitmap>(RECENT * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>?) = size > RECENT
    }
    private val preparing = HashMap<Key, Future<Bitmap?>>()
    // One thread, and only while there's work: the service's process keeps no idle thread for a logo.
    private val worker by lazy {
        ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { r -> Thread(r, "biokey-logo").apply { isDaemon = true } }
            .apply { allowCoreThreadTimeOut(true) }
    }
    private var sway: WeakReference<Bitmap>? = null

    /** The artwork's own size (read from the PNG header, nothing decoded), or null if unreadable. */
    fun artSize(resources: Resources, @DrawableRes id: Int): IntArray? {
        artSizes[id]?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        runCatching { BitmapFactory.decodeResource(resources, id, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return intArrayOf(bounds.outWidth, bounds.outHeight).also { artSizes[id] = it }
    }

    /** Layer [id] scaled to exactly [width] x [height], or null if it couldn't be made. */
    fun sized(resources: Resources, @DrawableRes id: Int, width: Int, height: Int): Bitmap? {
        val key = Key(id, width, height)
        cached(key)?.let { return it }
        val made = preparing.remove(key)?.let { runCatching { it.get() }.getOrNull() } ?: make(resources, id, width, height)
        return made?.also {
            live.values.removeAll { ref -> ref.get() == null }
            live[key] = WeakReference(it)
            recent[key] = it
        }
    }

    /**
     * Starts making [sized]'s copy in the background, for a logo whose size is known before it
     * is first drawn: the shield's cover is drawn the moment a locked app is covered, and must not
     * wait for a decode then.
     */
    fun prepare(resources: Resources, @DrawableRes id: Int, width: Int, height: Int) {
        val key = Key(id, width, height)
        if (key in preparing || cached(key) != null) return
        preparing[key] = runCatching { worker.submit(Callable { make(resources, id, width, height) }) }.getOrNull() ?: return
    }

    /** The vines' sway field, for as long as a moving logo uses it; null if it couldn't be loaded. */
    fun field(resources: Resources): Bitmap? = sway?.get() ?: runCatching {
        BitmapFactory.decodeResource(resources, R.drawable.logo_vine_field, BitmapFactory.Options().apply { inScaled = false })
    }.getOrNull()?.also { sway = WeakReference(it) }

    private fun cached(key: Key): Bitmap? =
        recent[key] ?: live[key]?.get()?.also { recent[key] = it }

    private fun make(resources: Resources, id: Int, width: Int, height: Int): Bitmap? = runCatching {
        val full = BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })
            ?: return null
        downscale(full, width, height).also { if (it !== full) full.recycle() }
    }.getOrNull()

    /**
     * Halves with filtering until close, then scales the rest of the way: a clean box-like
     * downscale, so the vines' fine ridges don't shimmer. (Decoding with `inSampleSize` would be
     * cheaper, but for PNGs it skips pixels rather than averaging them.)
     */
    private fun downscale(src: Bitmap, w: Int, h: Int): Bitmap {
        var b = src
        while (b.width >= w * 2 && b.height >= h * 2) {
            val half = b.scale(b.width / 2, b.height / 2)
            if (b !== src) b.recycle()
            b = half
        }
        if (b.width == w && b.height == h) return b
        return b.scale(w, h).also { if (b !== src) b.recycle() }
    }
}

/**
 * One layer of the BioKey wordmark (letters or vines: white artwork with two-tone detail in its
 * alpha), tinted, and drawn fitted and centred like `ContentScale.Fit` from a copy sized to fit
 * ([LogoArt]), so both layers line up. One per view; main thread only.
 */
internal class LogoLayer(private val resources: Resources, @DrawableRes private val id: Int) {

    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** The sized artwork, placed where it's drawn; null until laid out, or if it couldn't be loaded. */
    var content: BitmapShader? = null
        private set
    var fit = LogoArt.Fit(1f, 0f, 0f, 0f, 0f)
        private set

    private var lastWidth = -1f
    private var lastHeight = -1f
    private var tint = 0

    fun setTint(color: Int) {
        if (color == tint && paint.colorFilter != null) return
        tint = color
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    /** Starts making the copy for a [width] x [height] drawing ahead of its first frame. */
    fun prepare(width: Int, height: Int) {
        val art = LogoArt.artSize(resources, id) ?: return
        val f = LogoArt.fit(art[0], art[1], width.toFloat(), height.toFloat())
        LogoArt.prepare(resources, id, f.pixelWidth, f.pixelHeight)
    }

    /** Fits the layer into [width] x [height]; false if there is nothing to draw. */
    fun layout(width: Float, height: Float): Boolean {
        if (width <= 0f || height <= 0f) return false
        if (width == lastWidth && height == lastHeight) return content != null
        lastWidth = width
        lastHeight = height
        // The previous copy may still be in a recorded frame: leave it to the GC, never recycle it.
        content = null
        val art = LogoArt.artSize(resources, id) ?: return false
        val f = LogoArt.fit(art[0], art[1], width, height)
        fit = f
        val sized = LogoArt.sized(resources, id, f.pixelWidth, f.pixelHeight) ?: return false
        content = BitmapShader(sized, Shader.TileMode.DECAL, Shader.TileMode.DECAL).apply {
            setLocalMatrix(Matrix().apply {
                setScale(f.width / sized.width, f.height / sized.height)
                postTranslate(f.left, f.top)
            })
        }
        return true
    }

    /** Draws the layer, still, fitted into [width] x [height]. */
    fun draw(canvas: Canvas, width: Float, height: Float) {
        if (!layout(width, height)) return
        paint.shader = content
        canvas.drawRect(fit.left, fit.top, fit.left + fit.width, fit.top + fit.height, paint)
    }
}
