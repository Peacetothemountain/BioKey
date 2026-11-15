package com.android.BioKey

import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.feature.lock.CoverBackdrop
import com.android.BioKey.feature.lock.LockCoverView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The glow behind a locked app's icon fades out on every side: never cut off in a straight line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverGlowEdgeTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun CoverBackdrop.reach(): Float =
        CoverBackdrop::class.java.getDeclaredField("glowRadius").apply { isAccessible = true }.getFloat(this)

    private fun backdrop(width: Int, height: Int) = CoverBackdrop(context).apply {
        measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        layout(0, 0, width, height)
    }

    @Test
    fun inTheOpenItReachesAsFarAsEver() {
        val glow = backdrop(1000, 2000)
        glow.setFocus(500f, 800f, 100f)
        assertEquals(280f, glow.reach(), 0.01f)
    }

    @Test
    fun nearAnEdgeItFadesOutBeforeIt() {
        // Near the top (the keyboard up), a side (a narrow window) and the bottom alike: at its
        // fullest breath (3% more) it still ends inside the cover.
        for ((x, y) in listOf(500f to 180f, 150f to 800f, 500f to 1850f)) {
            val glow = backdrop(1000, 2000)
            glow.setFocus(x, y, 100f)
            val room = minOf(x, y, 1000 - x, 2000 - y)
            assertTrue("Cut off at ($x, $y)", glow.reach() * 1.03f <= room + 0.01f)
            assertTrue(glow.reach() >= 100f)
        }
    }

    @Test
    fun theCoverDrawsItUnderTheSystemBarsToo() {
        val cover = LockCoverView(context)
        cover.applySystemBarInsets(0, 60, 0, 40)
        assertFalse("The glow is clipped where the padding starts", cover.clipToPadding)
    }
}
