package com.android.BioKey

import android.graphics.Bitmap
import android.graphics.Canvas
import com.android.BioKey.ui.brand.Wordmark
import com.android.BioKey.ui.brand.WordmarkPainter
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the wordmark large, at several points of the vine's loop, for design review
 * (app/build/screenshots/wordmark_*.png).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WordmarkRenderTest {

    @Test
    fun frames() {
        val themes = listOf(
            Triple("dark", intArrayOf(0xFFA8C7FA.toInt(), 0xFFD7BAFF.toInt()), 0xFF121318.toInt()),
            Triple("light", intArrayOf(0xFF3B5BA9.toInt(), 0xFF7A4E9C.toInt()), 0xFFFAF8FF.toInt())
        )
        val dir = File("build/screenshots").apply { mkdirs() }
        val h = 600
        val w = (h * Wordmark.ASPECT).toInt()
        for ((name, fills, ground) in themes) {
            for (phase in listOf(Wordmark.STILL_PHASE, 0.1f, 0.6f, 0.85f)) {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(ground)
                WordmarkPainter().draw(canvas, w.toFloat(), h.toFloat(), phase, fills[0], fills[1], ground)
                File(dir, "wordmark_${name}_${(phase * 100).toInt()}.png").outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
