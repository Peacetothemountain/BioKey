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
import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.scale
import com.android.BioKey.R
import java.lang.ref.WeakReference
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The wordmark's artwork, shared by every logo in the process.
 *
 * The WebPs are 1000 px wide, larger than most places they're drawn (BioKey's lock, the largest,
 * is about 930 px wide on a 3.5x phone), and the always-running protection service draws a logo
 * on its cover. So only copies scaled to the drawn size are kept; each full-size decode (1.8 MB)
 * is dropped as soon as its copy is made. Copies are shared while any logo still draws them, and
 * the last few stay a while longer, so a lock screen reuses the shield's copy and a logo scrolled
 * away and back isn't scaled again.
 *
 * Decorative and best-effort: if the artwork can't be loaded, the logo is left out, nothing
 * throws. Main thread only, apart from the background work behind [prepare].
 *
 * Logos never wait for a decode ([sizedIfReady]), not even on the main thread's first frame: a
 * cover's shows once its copy is ready, and one on BioKey's own screens draws another size's copy
 * in the meantime ([standIn]), if there is one. [sized] decodes on the spot, for tests.
 */
internal object LogoArt {

    /** Copies kept after no logo draws them any more: the two layers at two sizes, and the vines' sway field. */
    private const val RECENT = 5
    /** A [Key] size for artwork kept as it is, unscaled: the sway field, which is sampled, not drawn. */
    private const val AS_IS = 0

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
    /** Logos waiting for a copy being made ([sizedIfReady]): told on the main thread once it is ready, or failed. */
    private val waiting = HashMap<Key, MutableList<() -> Unit>>()
    /** Copies that couldn't be made: a logo that doesn't wait isn't made to try again and again. */
    private val failed = HashSet<Key>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    // One thread, and only while there's work: the service's process keeps no idle thread for a logo.
    private val worker by lazy {
        ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { r -> Thread(r, "biokey-logo").apply { isDaemon = true } }
            .apply { allowCoreThreadTimeOut(true) }
    }

    /** The artwork's own size (read from the WebP header, nothing decoded), or null if unreadable. */
    fun artSize(resources: Resources, @DrawableRes id: Int): IntArray? {
        artSizes[id]?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        runCatching { BitmapFactory.decodeResource(resources, id, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return intArrayOf(bounds.outWidth, bounds.outHeight).also { artSizes[id] = it }
    }

    /** Layer [id] scaled to exactly [width] x [height], or null if it couldn't be made. Waits for it if need be. */
    fun sized(resources: Resources, @DrawableRes id: Int, width: Int, height: Int): Bitmap? {
        val key = Key(id, width, height)
        cached(key)?.let { return it }
        val made = preparing.remove(key)?.let { runCatching { it.get() }.getOrNull() } ?: make(resources, id, width, height)
        return made?.let { keep(key, it) }
    }

    /**
     * [sized]'s copy if it is ready now, never waiting for it: else null, and the copy is made in
     * the background (if it isn't already), [onReady] running on the main thread once it is ready,
     * or failed. For covers: drawn the moment a locked app is covered (or BioKey's settings
     * pages), they mustn't hold that first frame back for a decode. Null, without [onReady] ever
     * running, once the copy has failed.
     */
    fun sizedIfReady(resources: Resources, @DrawableRes id: Int, width: Int, height: Int, onReady: () -> Unit): Bitmap? {
        val key = Key(id, width, height)
        cached(key)?.let { return it }
        if (key in failed) return null
        val future = preparing[key]
        if (future != null && future.isDone) {
            preparing.remove(key)
            val made = runCatching { future.get() }.getOrNull()
            if (made == null) failed += key
            return made?.let { keep(key, it) }
        }
        prepare(resources, id, width, height)
        if (key !in preparing) {
            failed += key // Couldn't even be started.
            return null
        }
        waiting.getOrPut(key, ::ArrayList) += onReady
        return null
    }

    /** Whether no copy is being made, and no logo waits to be told of one: for screenshots of the settled screen. */
    @VisibleForTesting
    fun isIdle(): Boolean = waiting.isEmpty() && preparing.values.all { it.isDone }

    /** Whether [sized]'s copy is being made in the background right now. */
    fun isPreparing(@DrawableRes id: Int, width: Int, height: Int): Boolean = preparing[Key(id, width, height)]?.isDone == false

    /**
     * Starts making [sized]'s copy in the background, for a logo whose size is known before it
     * is first drawn: the shield's cover is drawn the moment a locked app is covered, and must not
     * wait for a decode then.
     */
    fun prepare(resources: Resources, @DrawableRes id: Int, width: Int, height: Int) {
        val key = Key(id, width, height)
        if (key in preparing || cached(key) != null) return
        preparing[key] = runCatching {
            worker.submit(Callable { make(resources, id, width, height).also { main.post { ready(key) } } })
        }.getOrNull() ?: return
    }

    /**
     * BioKey's screens went to the background: the last few copies kept for them are let go.
     * Logos still showing keep theirs (the shield's cover holds its own), so covers never wait
     * for them again.
     */
    fun trim() {
        recent.clear()
        failed.clear()
    }

    private fun ready(key: Key) {
        waiting.remove(key)?.forEach { runCatching(it) }
    }

    private fun keep(key: Key, bitmap: Bitmap): Bitmap {
        live.values.removeAll { ref -> ref.get() == null }
        live[key] = WeakReference(bitmap)
        recent[key] = bitmap
        return bitmap
    }

    /**
     * Another copy of layer [id], ready now, to draw scaled in place of a [width] px wide copy
     * still being made: the one closest in size, or null if there is none. A logo laid out at a
     * new size (BioKey's home header, after its lock) then shows at once rather than a few frames
     * later.
     */
    fun standIn(@DrawableRes id: Int, width: Int): Bitmap? {
        val ready = recent.filterKeys { it.id == id }.values + live.filterKeys { it.id == id }.values.mapNotNull { it.get() }
        return ready.minByOrNull { abs(it.width - width) }
    }

    /**
     * The vines' sway field, as it is: like [sizedIfReady] (null until it is ready, [onReady] then
     * running on the main thread), or decoded on the spot without [onReady]. Kept with the copies,
     * so a moving logo shown again doesn't decode it again.
     */
    fun field(resources: Resources, onReady: (() -> Unit)?): Bitmap? = if (onReady == null) {
        sized(resources, R.drawable.logo_vine_field, AS_IS, AS_IS)
    } else {
        sizedIfReady(resources, R.drawable.logo_vine_field, AS_IS, AS_IS, onReady)
    }

    /** Whether [field] is being decoded in the background right now. */
    fun isFieldPreparing(): Boolean = isPreparing(R.drawable.logo_vine_field, AS_IS, AS_IS)

    /** Starts decoding [field] in the background, ahead of a moving logo's first frame. */
    fun prepareField(resources: Resources) = prepare(resources, R.drawable.logo_vine_field, AS_IS, AS_IS)

    private fun cached(key: Key): Bitmap? =
        recent[key] ?: live[key]?.get()?.also { recent[key] = it }

    private fun make(resources: Resources, id: Int, width: Int, height: Int): Bitmap? = runCatching {
        val full = BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })
            ?: return null
        if (width == AS_IS) return full
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
    /** Its copy is being made in the background ([LogoArt.sizedIfReady]): laid out again once it is ready. */
    private var pending = false
    private var tint = 0

    fun setTint(color: Int) {
        if (color == tint && paint.colorFilter != null) return
        tint = color
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    /**
     * Fits the layer into [width] x [height]; false if there is nothing to draw (yet). Without
     * [onReady] the copy is decoded on the spot if it must be. With it, never: the copy is made in
     * the background and [onReady] runs on the main thread once it is ready, for the caller to
     * lay out (or draw) again. Until then, with [standIn], another size's copy that is ready is
     * drawn scaled in its place ([LogoArt.standIn]); without, nothing is.
     */
    fun layout(width: Float, height: Float, onReady: (() -> Unit)? = null, standIn: Boolean = false): Boolean {
        if (width <= 0f || height <= 0f) return false
        val sameSize = width == lastWidth && height == lastHeight
        if (sameSize && !pending) return content != null
        val art = LogoArt.artSize(resources, id) ?: return false
        val f = LogoArt.fit(art[0], art[1], width, height)
        // Still on its way: [onReady] comes when it is there. Its stand-in, if any, is drawn until then.
        if (sameSize && LogoArt.isPreparing(id, f.pixelWidth, f.pixelHeight)) return content != null
        lastWidth = width
        lastHeight = height
        pending = false
        // The previous copy may still be in a recorded frame: leave it to the GC, never recycle it.
        content = null
        fit = f
        val sized = if (onReady == null) {
            LogoArt.sized(resources, id, f.pixelWidth, f.pixelHeight)
        } else {
            LogoArt.sizedIfReady(resources, id, f.pixelWidth, f.pixelHeight, onReady)
        }
        if (sized == null) {
            // Being made (asked again once ready), or failed (left out, not asked again).
            pending = onReady != null && LogoArt.isPreparing(id, f.pixelWidth, f.pixelHeight)
            if (pending && standIn) content = LogoArt.standIn(id, f.pixelWidth)?.let { placed(it, f) }
            return content != null
        }
        content = placed(sized, f)
        return true
    }

    /** [bitmap] scaled and moved onto [f] exactly, whatever its own size. */
    private fun placed(bitmap: Bitmap, f: LogoArt.Fit) = BitmapShader(bitmap, Shader.TileMode.DECAL, Shader.TileMode.DECAL).apply {
        setLocalMatrix(Matrix().apply {
            setScale(f.width / bitmap.width, f.height / bitmap.height)
            postTranslate(f.left, f.top)
        })
    }

    /**
     * Lets the copy go, by reference only: a recorded frame may still draw it, so it is never
     * recycled. The next layout finds it again, or makes it.
     */
    fun release() {
        content = null
        pending = false
        lastWidth = -1f
        lastHeight = -1f
    }

    /** Draws the layer, still, fitted into [width] x [height]; [onReady] and [standIn]: see [layout]. */
    fun draw(canvas: Canvas, width: Float, height: Float, onReady: (() -> Unit)? = null, standIn: Boolean = false) {
        if (!layout(width, height, onReady, standIn)) return
        paint.shader = content
        canvas.drawRect(fit.left, fit.top, fit.left + fit.width, fit.top + fit.height, paint)
    }
}
