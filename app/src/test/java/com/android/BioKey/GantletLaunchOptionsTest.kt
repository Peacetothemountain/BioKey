package com.android.BioKey

import android.app.ActivityOptions
import android.app.Application
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.feature.lock.LockLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

/**
 * Gantlet: how the lock screen is asked for, on every kind of screen. On the phone's own screen it
 * opens as any app does: launch bounds there open it in a pop-up window on Samsung's phones and
 * foldables, which can't be made full screen, so it went Home every time (4.6, a Galaxy Z Fold7).
 * Only another display where windows are freeform (a monitor, DeX) gets the whole display asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], shadows = [InstallTimePermissions::class])
class GantletLaunchOptionsTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val bank = "com.example.bank"

    private fun on(screen: GantletScreen) {
        RuntimeEnvironment.setQualifiers(screen.qualifiers)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT, screen.freeform)
    }

    /** The options the last start was sent with. */
    private fun lastStartOptions(): Bundle {
        val started = shadowOf(context).nextStartedActivityForResult
        assertNotNull("Nothing was started", started)
        return started.options ?: Bundle.EMPTY
    }

    private fun Bundle.launchBounds(): Rect? = keySet().firstOrNull { it.contains("launchBounds") }?.let {
        @Suppress("DEPRECATION")
        get(it) as Rect?
    }

    private fun Bundle.launchDisplay(): Int = getInt("android.activity.launchDisplayId", Display.INVALID_DISPLAY)

    @Test
    fun onThePhonesOwnScreenTheLockScreenNeverAsksForBounds() {
        for (screen in GantletScreens.ALL) {
            on(screen)
            assertNull(screen.name, LockLauncher.wholeDisplay(context, Display.DEFAULT_DISPLAY))
            for (displayId in listOf(null, Display.DEFAULT_DISPLAY)) {
                assertTrue(screen.name, LockLauncher.launch(context, bank, isSelfProtection = false, displayId = displayId))
                val options = lastStartOptions()
                assertNull("${screen.name}: ${options.keySet()}", options.launchBounds())
                assertEquals(screen.name, Display.DEFAULT_DISPLAY, options.launchDisplay())
            }
            // BioKey's own lock (a settings page that could switch it off) is asked for the same way.
            assertTrue(screen.name, LockLauncher.launch(context, "biokey_self_protection", isSelfProtection = true))
            assertNull(screen.name, lastStartOptions().launchBounds())
        }
    }

    @Test
    fun onAMonitorWithFreeformWindowsTheLockScreenAsksForTheWholeOfIt() {
        on(GantletScreens.FOLD7_INNER)
        val monitor = ShadowDisplayManager.addDisplay("w1920dp-h1080dp-land-mdpi")
        assertEquals(Rect(0, 0, 1920, 1080), LockLauncher.wholeDisplay(context, monitor))
        assertTrue(LockLauncher.launch(context, bank, isSelfProtection = false, displayId = monitor))
        val options = lastStartOptions()
        assertEquals(options.keySet().toString(), Rect(0, 0, 1920, 1080), options.launchBounds())
        assertEquals(monitor, options.launchDisplay())
    }

    @Test
    fun onAMonitorWithoutFreeformWindowsTheLockScreenOpensAsAnyAppDoes() {
        on(GantletScreens.PHONE)
        val monitor = ShadowDisplayManager.addDisplay("w1920dp-h1080dp-land-mdpi")
        assertNull(LockLauncher.wholeDisplay(context, monitor))
        assertTrue(LockLauncher.launch(context, bank, isSelfProtection = false, displayId = monitor))
        val options = lastStartOptions()
        assertNull(options.keySet().toString(), options.launchBounds())
        assertEquals(monitor, options.launchDisplay())
    }

    /** The options the lock screen's PendingIntent was made with (its creator's), for the start just made. */
    private fun creatorOptions(started: android.content.Intent): Bundle {
        val record = PendingIntent.getActivity(context, bank.hashCode(), started, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNotNull("No PendingIntent was made", record)
        return shadowOf(record).options ?: Bundle.EMPTY
    }

    /** [options] read back as Android reads them (ActivityOptions.fromBundle isn't public). */
    private fun read(options: Bundle): ActivityOptions =
        ActivityOptions::class.java.getMethod("fromBundle", Bundle::class.java).invoke(null, options) as ActivityOptions

    /**
     * Every lock screen from the background rests on this grant on Android 14 and later: without
     * it Android refuses the start without an error, and the locked app stays in front. Both the
     * PendingIntent's maker and its sender grant it, as each version asks: ALLOWED on 14 and 15,
     * ALLOW_ALWAYS (ALLOWED's replacement) from 16. Before 14 there is nothing to grant.
     */
    @Test
    @Config(sdk = [31, 34, 35, 36, 37])
    fun theLockScreenIsGrantedItsStartFromTheBackground() {
        assertTrue(LockLauncher.launch(context, bank, isSelfProtection = false))
        val started = shadowOf(context).nextStartedActivityForResult
        assertNotNull("Nothing was started", started)
        val sent = started.options ?: Bundle.EMPTY
        val made = creatorOptions(started.intent)
        val sdk = Build.VERSION.SDK_INT
        if (sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertFalse(made.keySet().toString(), made.keySet().any { it.contains("BackgroundActivityStartMode") })
            return
        }
        @Suppress("DEPRECATION")
        val expected = if (sdk >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        assertEquals("sent, on $sdk", expected, read(sent).pendingIntentBackgroundActivityStartMode)
        assertEquals("made, on $sdk", expected, read(made).pendingIntentCreatorBackgroundActivityStartMode)
    }

    @Test
    fun aDisplayThatIsGoneAsksForNoBounds() {
        on(GantletScreens.TABLET)
        assertNull(LockLauncher.wholeDisplay(context, 4242))
    }
}
