package com.android.BioKey.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap

/**
 * App icons as true circles, the same everywhere in BioKey (lists, sheets, the lock cover).
 *
 * - Adaptive icons: their layers drawn full-bleed through a circle, as a circle-shaped launcher
 *   would, whatever shape the device's own mask is.
 * - Legacy icons that fill their square: cropped to the circle, rather than shrunk onto a plate.
 * - Legacy icons that are already round (or another cut-out shape): kept as drawn.
 */
object AppIcons {

    /** Adaptive layers are 108dp with a 72dp visible centre: 1/4 extra on each side. */
    private const val ADAPTIVE_EXTRA = 0.25f

    fun circle(drawable: Drawable, sizePx: Int): Bitmap {
        val size = sizePx.coerceAtLeast(1)
        val out = createBitmap(size, size)
        val canvas = Canvas(out)
        val clip = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
        if (drawable is AdaptiveIconDrawable) {
            val extra = (size * ADAPTIVE_EXTRA).toInt()
            canvas.save()
            canvas.clipPath(clip)
            for (layer in listOf(drawable.background, drawable.foreground)) {
                layer ?: continue
                layer.setBounds(-extra, -extra, size + extra, size + extra)
                layer.draw(canvas)
            }
            canvas.restore()
            return out
        }
        drawable.setBounds(0, 0, size, size)
        if (fillsSquare(drawable)) {
            canvas.save()
            canvas.clipPath(clip)
            drawable.draw(canvas)
            canvas.restore()
        } else {
            drawable.draw(canvas)
        }
        return out
    }

    /** Whether a legacy icon is a full square: its corners are opaque. */
    private fun fillsSquare(drawable: Drawable): Boolean {
        val probe = createBitmap(24, 24)
        drawable.setBounds(0, 0, 24, 24)
        drawable.draw(Canvas(probe))
        val corners = listOf(1 to 1, 22 to 1, 1 to 22, 22 to 22)
        val opaque = corners.count { (x, y) -> Color.alpha(probe.getPixel(x, y)) > 200 }
        probe.recycle()
        return opaque >= 3
    }
}
