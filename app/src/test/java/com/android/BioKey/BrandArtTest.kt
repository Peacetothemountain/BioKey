package com.android.BioKey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import androidx.compose.material3.SnackbarDuration
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.ui.brand.KeyMark
import com.android.BioKey.ui.brand.WordmarkView
import com.android.BioKey.ui.brand.at
import com.android.BioKey.ui.theme.BioKeyMessages
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BioKey's mark is the launcher icon's key, drawn in the phone's colours, fitted and centred in
 * whatever box it is given, the header's swirling smoothly; the small icon loads. Messages that waited too long for a screen are dropped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BrandArtTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val primary = Color.rgb(200, 30, 30)
    private val secondary = Color.rgb(30, 160, 30)
    private val tertiary = Color.rgb(30, 30, 200)

    /** The box (left, top, right, bottom) of what was drawn on [bitmap], or null if nothing was. */
    private fun drawnBounds(bitmap: Bitmap): IntArray? {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 40) {
                l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); b = maxOf(b, y)
            }
        }
        return if (r < 0) null else intArrayOf(l, t, r, b)
    }

    @Test
    fun theMarkFitsItsBoxCentredInThePhonesColours() {
        // A wide box: the mark takes its height, centred across.
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        KeyMark.draw(Canvas(bitmap), 0f, 0f, 400f, 200f, primary, secondary, tertiary)
        val (l, t, r, b) = drawnBounds(bitmap) ?: error("nothing drawn")
        val expectedWidth = 200f * KeyMark.ASPECT
        assertTrue("fills the height: $t..$b", t <= 12 && b >= 188)
        assertEquals("centred across", 200f, (l + r) / 2f, 8f)
        assertTrue("no wider than it should be: ${r - l}", r - l <= expectedWidth + 2)
        // The gradient runs from primary (the ridges' top left) to tertiary (the blade's end).
        var reddish = 0; var bluish = 0
        for (y in 0 until 200) for (x in 0 until 400) {
            val c = bitmap.getPixel(x, y)
            if (Color.alpha(c) < 200) continue
            if (Color.red(c) > Color.blue(c) + 60) reddish++
            if (Color.blue(c) > Color.red(c) + 60) bluish++
        }
        assertTrue("primary in it: $reddish", reddish > 50)
        assertTrue("tertiary in it: $bluish", bluish > 50)
    }

    @Test
    fun theMarkDrawsNothingInAnEmptyBox() {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        KeyMark.draw(Canvas(bitmap), 0f, 0f, 0f, 20f, primary, secondary, tertiary)
        assertEquals(null, drawnBounds(bitmap))
    }

    @Test
    fun theCoversLogoIsTheMarkCentred() {
        val view = WordmarkView(context).apply { setColors(primary, secondary, tertiary) }
        view.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 200, 120)
        val bitmap = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val (l, t, r, b) = drawnBounds(bitmap) ?: error("nothing drawn")
        assertEquals("centred across", 100f, (l + r) / 2f, 6f)
        assertTrue("fills the height: $t..$b", t <= 8 && b >= 112)
    }

    @Test
    fun theHeadersRingsGoRoundSmoothlyEachAtItsOwnPace() {
        val a = KeyMark.Motion().apply { at(1.234f) }
        val b = KeyMark.Motion().apply { at(1.234f + 0.016f) }
        // One frame later: a small step round for every ring and every sparkle, never a jump.
        for (i in a.ridgeAngle.indices) {
            val step = (b.ridgeAngle[i] - a.ridgeAngle[i] + 360f) % 360f
            assertTrue("ring $i stepped $step", step > 0f && step < 2f)
        }
        for (k in a.sparkleScale.indices) assertTrue("sparkle $k", abs(a.sparkleScale[k] - b.sparkleScale[k]) < 0.05f)
        // Every ring at its own pace: their gaps never stay lined up.
        val later = KeyMark.Motion().apply { at(5f) }
        val steps = later.ridgeAngle.indices.map { (later.ridgeAngle[it] - KeyMark.Motion().apply { at(0f) }.ridgeAngle[it] + 360f) % 360f }
        assertEquals(steps.size, steps.map { it.toInt() }.toSet().size)
    }

    @Test
    fun theSmallIconLoads() {
        val icon = context.getDrawable(R.drawable.ic_biokey_mark)
        assertNotNull(icon)
        assertEquals((24 * context.resources.displayMetrics.density).toInt(), icon!!.intrinsicWidth)
    }

    @Test
    fun messagesExpireWhileWaitingAndAreFreshWhenResent() {
        val message = BioKeyMessages.Message("Chase unlocked", null, "Undo", null, SnackbarDuration.Long, postedAt = 1_000L)
        assertFalse(message.isStale(1_000L + BioKeyMessages.MAX_WAIT_MS))
        assertTrue(message.isStale(1_001L + BioKeyMessages.MAX_WAIT_MS))
        // Carried across a rotation: raised again now, so not dropped as stale.
        val now = SystemClock.uptimeMillis()
        val waited = BioKeyMessages.Message("Chase unlocked", null, "Undo", null, SnackbarDuration.Long, postedAt = now - 60_000L)
        assertTrue(waited.isStale(now))
        val again = waited.again()
        assertEquals("Undo", again.actionLabel)
        assertFalse(again.isStale(now))
    }
}
