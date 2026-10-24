package com.android.BioKey

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.compose.material3.SnackbarDuration
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.ui.brand.LogoArt
import com.android.BioKey.ui.brand.LogoLayer
import com.android.BioKey.ui.brand.breathAt
import com.android.BioKey.ui.theme.BioKeyMessages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
    fun aCoverNeverWaitsForItsLogo() {
        val res = context.resources
        var ready = 0
        // Not there yet: nothing to draw, nothing decoded on the spot, the copy made in the background.
        assertNull(LogoArt.sizedIfReady(res, R.drawable.ic_title_logo_letters, 271, 120) { ready++ })
        val deadline = System.currentTimeMillis() + 10_000L
        while (ready == 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
        // The cover is told, and finds it.
        assertEquals(1, ready)
        val copy = LogoArt.sizedIfReady(res, R.drawable.ic_title_logo_letters, 271, 120) { ready++ }
        assertEquals(271, copy!!.width)
        assertSame(copy, LogoArt.sized(res, R.drawable.ic_title_logo_letters, 271, 120))
    }

    @Test
    fun aLogoAtANewSizeDrawsAnotherCopyUntilItsOwnIsReady() {
        val res = context.resources
        // The lock's copy, ready.
        assertNotNull(LogoArt.sized(res, R.drawable.ic_title_logo_letters, 360, 160))
        var ready = 0
        val layer = LogoLayer(res, R.drawable.ic_title_logo_letters)
        // Home's logo, at a size not made yet: drawn at once from the lock's copy, never decoded on the spot.
        assertTrue(layer.layout(250f, 200f, { ready++ }, standIn = true))
        val standIn = layer.content
        assertNotNull(standIn)
        idleUntil { ready > 0 }
        assertEquals(1, ready)
        // Its own copy is taken in once ready.
        assertTrue(layer.layout(250f, 200f, { ready++ }, standIn = true))
        assertNotSame(standIn, layer.content)
        // A cover's logo doesn't stand in: nothing until its copy is ready.
        val cover = LogoLayer(res, R.drawable.ic_title_logo_letters)
        assertFalse(cover.layout(240f, 200f, { ready++ }))
        idleUntil { ready > 1 }
    }

    @Test
    fun theSwayFieldIsDecodedInTheBackgroundAndKept() {
        val res = context.resources
        var ready = 0
        val first = LogoArt.field(res) { ready++ }
        if (first == null) {
            idleUntil { ready > 0 }
            assertEquals(1, ready)
        }
        val field = LogoArt.field(res) { ready++ }
        assertNotNull(field)
        // Kept: the next moving logo finds it, with no decode.
        assertSame(field, LogoArt.field(res, null))
    }

    private fun idleUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
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
