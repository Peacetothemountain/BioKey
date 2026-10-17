package com.android.BioKey.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap

/**
 * App icons, drawn the same everywhere in BioKey (lists, sheets, the lock cover).
 *
 * - Adaptive icons: their layers drawn full-bleed through a circle, as a circle-shaped launcher
 *   would, whatever shape the device's own mask is.
 * - Legacy (non-adaptive) icons: drawn exactly as the app ships them.
 */
object AppIcons {

    /** Adaptive layers are 108dp with a 72dp visible centre: 1/4 extra on each side. */
    private const val ADAPTIVE_EXTRA = 0.25f

    fun render(drawable: Drawable, sizePx: Int): Bitmap {
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
        drawable.draw(canvas)
        return out
    }
}
