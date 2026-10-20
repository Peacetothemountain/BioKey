package com.android.BioKey

import android.content.Context
import android.os.SystemClock
import androidx.compose.material3.SnackbarDuration
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.feature.main.breathAt
import com.android.BioKey.ui.brand.LogoArt
import com.android.BioKey.ui.theme.BioKeyMessages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The logo is drawn from copies sized to where it's drawn, shared between logos (the shield's
 * cover and every lock screen), and its aura breathes on the shared clock like the transition it
 * replaced. Messages that waited too long for a screen are dropped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BrandArtTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun fitsLikeContentScaleFitAndCentres() {
        // Wider box than the artwork: height-bound, centred horizontally.
        val wide = LogoArt.fit(1600, 709, 400f, 100f)
        assertEquals(100f / 709, wide.scale, 1e-6f)
        assertEquals(100f, wide.height, 1e-3f)
        assertEquals((400f - wide.width) / 2f, wide.left, 1e-3f)
        assertEquals(0f, wide.top, 1e-3f)
        assertEquals(226, wide.pixelWidth)
        assertEquals(100, wide.pixelHeight)
        // Taller box: width-bound, centred vertically.
        val tall = LogoArt.fit(1600, 709, 320f, 400f)
        assertEquals(320f, tall.width, 1e-3f)
        assertEquals((400f - tall.height) / 2f, tall.top, 1e-3f)
        assertEquals(0f, tall.left, 1e-3f)
        // Never a zero-sized copy.
        assertEquals(1, LogoArt.fit(1600, 709, 0.2f, 0.2f).pixelHeight)
    }

    @Test
    fun copiesAreSizedAndShared() {
        val res = context.resources
        assertArrayEquals(intArrayOf(1600, 709), LogoArt.artSize(res, R.drawable.ic_title_logo_letters))
        val first = LogoArt.sized(res, R.drawable.ic_title_logo_letters, 338, 150)
        assertNotNull(first)
        assertEquals(338, first!!.width)
        assertEquals(150, first.height)
        // A second logo of the same size (the lock screen after the shield) reuses the copy.
        assertSame(first, LogoArt.sized(res, R.drawable.ic_title_logo_letters, 338, 150))
    }

    @Test
    fun preparedCopyIsTheOneDrawn() {
        val res = context.resources
        LogoArt.prepare(res, R.drawable.ic_title_logo_tentacles, 300, 133)
        val sized = LogoArt.sized(res, R.drawable.ic_title_logo_tentacles, 300, 133)
        assertEquals(300, sized!!.width)
        assertEquals(133, sized.height)
        assertSame(sized, LogoArt.sized(res, R.drawable.ic_title_logo_tentacles, 300, 133))
    }

    @Test
    fun auraBreathesInAndOutOverFivePointSixSeconds() {
        assertEquals(0f, breathAt(0L), 1e-6f)
        assertEquals(1f, breathAt(2_800L), 1e-6f)
        assertEquals(0f, breathAt(5_600L), 1e-6f)
        assertEquals(0.5f, breathAt(1_400L), 1e-3f)
        // Out is the way in, reversed, like the RepeatMode.Reverse transition it replaces.
        assertEquals(breathAt(1_000L), breathAt(4_600L), 1e-5f)
        assertTrue(breathAt(700L) < 0.5f)
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
