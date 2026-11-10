package com.android.BioKey

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.feature.lock.InstantShieldOverlay
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
        LockStateTracker.resetForTests()
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
    fun aCoverHeldForThePhonesLockIsntHeldForAnotherApp() {
        // Held for x, behind the keyguard; then the camera (another locked app) opens over it and
        // its lock is asked for: judged afresh, not held.
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        val camera = "com.example.camera"
        SecurityVault.getInstance(context).setAppLock(camera, true)
        val controller = Robolectric.buildActivity(
            LockScreenActivity::class.java,
            Intent(context, LockScreenActivity::class.java)
                .putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, x)
                .putExtra(LockScreenActivity.EXTRA_BEHIND_KEYGUARD, true)
        ).setup()
        open += controller
        controller.get().markFirstFrameForTests()
        controller.newIntent(Intent(context, LockScreenActivity::class.java).putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, camera))
        shadowOf(Looper.getMainLooper()).idle()
        keyguard.setKeyguardLocked(false)
        controller.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Still held for the phone's lock after another app's lock", controller.get().isFinishing)
    }

    @Test
    fun thePhonesPinScreenOpeningOverItsPromptIsNotLeavingIt() {
        // Some phones open the PIN screen a prompt hands over to as an activity, which stops the
        // lock screen under it: closing then would cancel the prompt, and the PIN could never be typed.
        val lock = lockScreenFor()
        SecurityEngine.INSTANCE.setPromptForTests(x)
        BioKeyAccessibilityService.setConfirmingDeviceCredentialForTests(true)
        try {
            lock.pause().stop()
            assertFalse(lock.get().isFinishing)
            assertTrue(SecurityEngine.INSTANCE.isAuthenticatingFor(x))
            assertEquals(0, homes())
        } finally {
            BioKeyAccessibilityService.setConfirmingDeviceCredentialForTests(false)
            SecurityEngine.INSTANCE.setPromptForTests(null)
        }
    }

    /** Whether [lock]'s window asks Android to hide other apps' overlays while it shows. */
    private fun hidesOverlays(lock: ActivityController<LockScreenActivity>): Boolean {
        val flags = WindowManager.LayoutParams::class.java.getField("privateFlags").getInt(lock.get().window.attributes)
        // WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS
        return flags and 0x00080000 != 0
    }

    /** As installed: BioKey holds the permission to hide other apps' overlays (Robolectric grants nothing). */
    private fun mayHideOverlays() = shadowOf(context).grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS)

    @Test
    fun otherAppsOverlaysAreHiddenOverTheLockScreen() {
        mayHideOverlays()
        assertTrue(hidesOverlays(lockScreenFor()))
    }

    @Test
    fun aCoverHeldOverTheLockScreenIsntHiddenWithOtherAppsOverlays() {
        // Compatibility mode's cover over a locked app in picture-in-picture, which floats above
        // the lock screen too: an application overlay, which Android through 15 hides along with
        // other apps' whenever a window asks for those to be hidden.
        mayHideOverlays()
        var held = true
        val cover = InstantShieldOverlay(
            context, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, goHome = {}, onTimeout = { _, _ -> false }, holdWhile = { held }
        )
        try {
            assertTrue(cover.show(x) {})
            val lock = lockScreenFor()
            assertFalse(hidesOverlays(lock))
            assertTrue(cover.isShowing)
            // The window gone, the cover goes, and other apps' overlays are hidden again.
            held = false
            cover.hide()
            assertFalse(cover.isShowing)
            assertTrue(hidesOverlays(lock))
            // Held again later, under the same lock screen.
            held = true
            assertTrue(cover.show(x) {})
            assertFalse(hidesOverlays(lock))
        } finally {
            cover.destroy()
        }
    }

    @Test
    fun theAccessibilityEnginesCoverIsNeverAReasonToShowOtherAppsOverlays() {
        // An accessibility overlay isn't hidden with other apps' overlays, held or not.
        mayHideOverlays()
        val cover = InstantShieldOverlay(
            context, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, goHome = {}, onTimeout = { _, _ -> false }, holdWhile = { true }
        )
        try {
            assertTrue(cover.show(x) {})
            assertTrue(hidesOverlays(lockScreenFor()))
        } finally {
            cover.destroy()
        }
    }

    @Test
    fun lockPhoneKeepsTheCoverUpUntilThePhoneHasLocked() {
        // The accessibility engine runs: it can lock the phone, which Android does a moment later.
        val engine = Robolectric.buildService(BioKeyAccessibilityService::class.java).create()
        try {
            BioKeyAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(engine.get())
            shadowOf(Looper.getMainLooper()).idle()
            val lock = lockScreenFor()
            LockScreenActivity::class.java.getDeclaredMethod("lockDeviceOrHome").apply { isAccessible = true }.invoke(lock.get())
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN in shadowOf(engine.get()).globalActionsPerformed)
            // Closed now, the app would show until the keyguard draws.
            assertFalse(lock.get().isFinishing)
            assertEquals(0, homes())
        } finally {
            engine.destroy()
            ForegroundRules.resetSystemLists()
        }
    }

    @Test
    fun lockPhoneGoesHomeWhereThePhoneCantBeLockedFromHere() {
        // Compatibility mode: no accessibility service to lock the phone with.
        val lock = lockScreenFor()
        LockScreenActivity::class.java.getDeclaredMethod("lockDeviceOrHome").apply { isAccessible = true }.invoke(lock.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(lock.get().isFinishing)
        assertEquals(1, homes())
    }

    /** The lock screen for X, stopped under the phone's PIN screen with its prompt running there. */
    private fun stoppedUnderThePinScreen(): ActivityController<LockScreenActivity> {
        val lock = lockScreenFor()
        // Its prompt came up by itself, as it does once the cover is drawn, and went to the PIN screen.
        LockScreenActivity::class.java.getDeclaredField("promptAutoLaunched").apply { isAccessible = true }.setBoolean(lock.get(), true)
        SecurityEngine.INSTANCE.setPromptForTests(x)
        BioKeyAccessibilityService.setConfirmingDeviceCredentialForTests(true)
        lock.pause().stop()
        return lock
    }

    /** That prompt ends without an unlock (cancelled, or the phone locked under it). */
    private fun thePromptEnds(lock: ActivityController<LockScreenActivity>) {
        SecurityEngine.INSTANCE.setPromptForTests(null)
        BioKeyAccessibilityService.setConfirmingDeviceCredentialForTests(false)
        LockScreenActivity::class.java.getDeclaredMethod("promptEnded").apply { isAccessible = true }.invoke(lock.get())
    }

    private fun autoPromptDue(lock: ActivityController<LockScreenActivity>): Boolean =
        !LockScreenActivity::class.java.getDeclaredField("promptAutoLaunched").apply { isAccessible = true }.getBoolean(lock.get())

    @Test
    fun backOnThePhonesPinScreenBringsTheLockScreenBackWithItsButtons() {
        val lock = stoppedUnderThePinScreen()
        thePromptEnds(lock)
        // The PIN screen closes onto this one within the moment it's given.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        lock.start().resume()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))
        assertFalse(lock.get().isFinishing)
        assertEquals(0, homes())
    }

    @Test
    fun leavingFromThePhonesPinScreenIsLeavingTheLockScreen() {
        // Home on the PIN screen: its prompt ends, and this screen doesn't come back.
        val lock = stoppedUnderThePinScreen()
        thePromptEnds(lock)
        assertFalse(lock.get().isFinishing)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))
        // Closed, as a lock screen stopped any other way is: the next one asks by itself.
        assertTrue(lock.get().isFinishing)
    }

    @Test
    fun thePhoneLockingOnItsPinScreenLeavesACoverThatAsksAgain() {
        val lock = stoppedUnderThePinScreen()
        assertFalse(autoPromptDue(lock))
        // Power: the phone locks, which ends the prompt on the PIN screen.
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        thePromptEnds(lock)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))
        // Kept, behind the keyguard, and due to ask again by itself once the phone is unlocked.
        assertFalse(lock.get().isFinishing)
        assertTrue(autoPromptDue(lock))
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
