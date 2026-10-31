package com.android.BioKey

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.feature.lock.AppInfoCache
import com.android.BioKey.feature.lock.LockCoverView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cover takes the locked app's own light or dark scheme, from the background its launch
 * screen's theme gives its window (BioKey's own launch screen stands in for an app here: light in
 * light mode, dark in dark mode), and keeps its design and glow; it follows the phone where that
 * can't be told, and always for BioKey's own lock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverSchemeTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun aLightLaunchScreenIsLight() {
        assertEquals(false, AppInfoCache.launchBackgroundIsDark(context.packageManager, context.packageName))
    }

    @Test
    @Config(qualifiers = "night")
    fun aDarkLaunchScreenIsDark() {
        assertEquals(true, AppInfoCache.launchBackgroundIsDark(context.packageManager, context.packageName))
    }

    @Test
    fun anAppWithoutALaunchScreenCantBeTold() {
        assertNull(AppInfoCache.launchBackgroundIsDark(context.packageManager, "com.example.missing"))
    }

    @Test
    fun theCoverFollowsTheAppAndKeepsItThroughAModeChange() {
        val cover = LockCoverView(context)
        assertFalse(cover.isDark)
        cover.bind("Notes", null, isBioKey = false, accent = null, dark = true)
        assertTrue(cover.isDark)
        cover.refreshPalette()
        assertTrue(cover.isDark)
        // Can't be told: the phone's mode.
        cover.bind("Diary", null, isBioKey = false, accent = null, dark = null)
        assertFalse(cover.isDark)
    }

    @Test
    fun bioKeysOwnLockFollowsThePhone() {
        val cover = LockCoverView(context)
        cover.bind("BioKey", null, isBioKey = true, accent = null, dark = true)
        assertFalse(cover.isDark)
    }
}
