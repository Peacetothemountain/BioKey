package com.android.BioKey

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.LockScreenActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Gantlet: BioKey's lock screen, once up, stays up until the owner unlocks or leaves it, whatever
 * the screen does under it: folding, unfolding, turning, becoming a window as big as the display.
 * It goes Home only when it must (Cancel, Back, a window the locked app can show beside). Being
 * covered by something it didn't choose (the locked app opening its main screen over it) is
 * neither: the engines cover the app again rather than sending it Home.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], shadows = [InstallTimePermissions::class])
class GantletLockScreenTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val x = "com.twitter.android"
    private val open = mutableListOf<ActivityController<LockScreenActivity>>()

    @Before
    fun start() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        LockStateTracker.lockAll()
        SecurityVault.getInstance(context).apply {
            setSetupCompletedSync(true)
            setAppLock(x, true)
        }
        homes()
    }

    @After
    fun stop() {
        open.forEach { runCatching { it.pause().stop().destroy() } }
    }

    private fun on(screen: GantletScreen) {
        RuntimeEnvironment.setQualifiers(screen.qualifiers)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT, screen.freeform)
    }

    /** The lock screen for [pkg], up, its cover drawn (as its first frame commit says). */
    private fun lockScreenFor(pkg: String = x): ActivityController<LockScreenActivity> {
        val controller = Robolectric.buildActivity(
            LockScreenActivity::class.java,
            Intent(context, LockScreenActivity::class.java).putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, pkg)
        ).setup()
        open += controller
        controller.get().markFirstFrameForTests()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("The lock screen closed at once", controller.get().isFinishing)
        homes()
        return controller
    }

    /** Home screens started since the last call (the lock screen failing closed, or a Cancel). */
    private fun homes(): Int = generateSequence { shadowOf(context).nextStartedActivity }
        .count { it.hasCategory(Intent.CATEGORY_HOME) }

    /** The screen changes under the lock screen, as folding, unfolding or turning the phone does. */
    private fun moveTo(controller: ActivityController<LockScreenActivity>, screen: GantletScreen) {
        on(screen)
        controller.configurationChange(RuntimeEnvironment.getApplication().resources.configuration)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aCoverStartedAsThePhoneLockedStaysWhenSomethingComesOverIt() {
        // Started at a screen-off or a screensaver, behind the keyguard.
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        val controller = Robolectric.buildActivity(
            LockScreenActivity::class.java,
            Intent(context, LockScreenActivity::class.java)
                .putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, x)
                .putExtra(LockScreenActivity.EXTRA_BEHIND_KEYGUARD, true)
        ).setup()
        open += controller
        controller.get().markFirstFrameForTests()
        shadowOf(Looper.getMainLooper()).idle()
        // A screensaver stops it before the keyguard's own lock delay is up: the keyguard isn't
        // locked yet and the screen is on, which would read as something displacing it.
        keyguard.setKeyguardLocked(false)
        controller.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("A cover held for the phone's lock closed", controller.get().isFinishing)
        assertEquals(0, homes())
        // Seen with the phone unlocked, it's an ordinary cover again: another screen over it closes it.
        controller.restart().resume()
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Still held once the phone was unlocked", controller.get().isFinishing)
    }

    @Test
    fun anOrdinaryCoverStoppedByAnotherScreenCloses() {
        val lockScreen = lockScreenFor()
        lockScreen.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(lockScreen.get().isFinishing)
    }

    @Test
    fun foldingAndUnfoldingNeverClosesTheLockScreen() {
        for (sequence in GantletScreens.FOLD_SEQUENCES) {
            on(sequence.first())
            val lockScreen = lockScreenFor()
            for (screen in sequence.drop(1)) {
                moveTo(lockScreen, screen)
                assertFalse("Closed on the ${screen.name}", lockScreen.get().isFinishing)
                assertEquals("Went Home on the ${screen.name}", 0, homes())
            }
            assertEquals(x, LockScreenActivity.activeLockedTarget)
            assertFalse(LockStateTracker.isRecentlyAbandoned(x))
            lockScreen.pause().stop().destroy()
            open -= lockScreen
            LockStateTracker.lockAll()
        }
    }

    @Test
    fun onEveryScreenTheLockScreenOpensAndStays() {
        for (screen in GantletScreens.ALL) {
            on(screen)
            val lockScreen = lockScreenFor()
            assertEquals(screen.name, 0, homes())
            assertTrue(screen.name, LockScreenActivity.isLockScreenResumed)
            lockScreen.pause().stop().destroy()
            open -= lockScreen
            LockStateTracker.lockAll()
        }
    }

    @Test
    fun aWindowAsBigAsTheDisplayIsTheWholeDisplay() {
        // In multi-window (a Samsung pop-up the size of the screen, say), but nothing beside it.
        on(GantletScreens.FOLD7_COVER)
        val lockScreen = lockScreenFor()
        shadowOf(lockScreen.get()).setInMultiWindowMode(true)
        lockScreen.get().onMultiWindowModeChanged(true, lockScreen.get().resources.configuration)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(lockScreen.get().isFinishing)
        assertEquals(0, homes())
    }

    @Test
    fun whetherALockScreenWindowHidesTheWholeDisplay() {
        val cover = Rect(0, 0, 1080, 2520)
        val inner = Rect(0, 0, 1968, 2184)
        // Full screen, whatever its bounds say mid-fold.
        assertTrue(LockScreenActivity.coversDisplay(false, cover, inner))
        assertTrue(LockScreenActivity.coversDisplay(false, Rect(), cover))
        // A window exactly as big, or reaching past the edges.
        assertTrue(LockScreenActivity.coversDisplay(true, Rect(cover), cover))
        assertTrue(LockScreenActivity.coversDisplay(true, Rect(-10, -10, 1090, 2530), cover))
        // Smaller: split screen, a pop-up window, one inset by a frame.
        assertFalse(LockScreenActivity.coversDisplay(true, Rect(0, 0, 1080, 1260), cover))
        assertFalse(LockScreenActivity.coversDisplay(true, Rect(0, 0, 984, 2184), inner))
        assertFalse(LockScreenActivity.coversDisplay(true, Rect(0, 80, 1080, 2520), cover))
        // The cover screen's size is no longer the whole display once unfolded.
        assertFalse(LockScreenActivity.coversDisplay(true, cover, inner))
    }

    @Test
    fun coveredByTheLockedAppItselfIsNotTheOwnerLeaving() {
        val lockScreen = lockScreenFor()
        // X opens its main screen over its lock screen: the lock screen stops, and gives up.
        lockScreen.pause().stop()
        assertTrue(lockScreen.get().isFinishing)
        assertEquals(0, homes())
        assertTrue(LockStateTracker.isRecentlyAbandoned(x))
        // Back in front during the cooldown, X is covered again, not sent Home.
        assertTrue(LockStateTracker.coverAgainAfterDisplaced(x))
    }

    @Test
    fun backIsTheOwnerLeaving() {
        val lockScreen = lockScreenFor()
        lockScreen.get().onBackPressedDispatcher.onBackPressed()
        assertTrue(lockScreen.get().isFinishing)
        assertEquals(1, homes())
        assertTrue(LockStateTracker.isRecentlyAbandoned(x))
        assertFalse(LockStateTracker.coverAgainAfterDisplaced(x))
    }

    @Test
    fun theScreenGoingOffKeepsTheCover() {
        val lockScreen = lockScreenFor()
        shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(false)
        try {
            lockScreen.pause().stop()
            assertFalse(lockScreen.get().isFinishing)
            assertFalse(LockStateTracker.isRecentlyAbandoned(x))
            assertEquals(0, homes())
        } finally {
            shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(true)
        }
    }

    @Test
    fun anAppUnlockedMeanwhileClosesTheLockScreenWithoutGoingHome() {
        val lockScreen = lockScreenFor()
        LockStateTracker.onAppUnlocked(x)
        lockScreen.pause().resume()
        assertTrue(lockScreen.get().isFinishing)
        assertEquals(0, homes())
    }
}
