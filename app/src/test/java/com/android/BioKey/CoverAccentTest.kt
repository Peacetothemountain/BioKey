package com.android.BioKey

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.feature.lock.AppInfoCache
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The cover's glow takes an icon's own vivid colour, and none from a colourless icon. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CoverAccentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aRedIconGlowsRed() {
        val accent = AppInfoCache.accentOf(ColorDrawable(Color.rgb(220, 30, 40)), context.resources)
        assertNotNull(accent)
        val hsv = FloatArray(3)
        Color.colorToHSV(accent!!, hsv)
        assertTrue("hue ${hsv[0]}", hsv[0] < 15f || hsv[0] > 345f)
    }

    @Test
    fun aColourlessIconHasNoGlowOfItsOwn() {
        assertNull(AppInfoCache.accentOf(ColorDrawable(Color.WHITE), context.resources))
        assertNull(AppInfoCache.accentOf(ColorDrawable(Color.DKGRAY), context.resources))
    }
}
