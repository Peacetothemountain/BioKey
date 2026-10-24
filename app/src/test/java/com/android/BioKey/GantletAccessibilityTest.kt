package com.android.BioKey

import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.feature.lock.InstantShieldOverlay
import com.android.BioKey.feature.lock.LockScreenActivity
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * Gantlet for the accessibility engine: a locked app opened any way, from any home screen, on
 * either screen of a foldable, is covered and stays covered until it is unlocked, and it is never
 * sent Home unless the owner walked away from its lock screen. An app that opens its main screen
 * over its own lock screen as it starts (a launch screen, a sign-in check) is covered again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GantletAccessibilityTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val x = "com.twitter.android"
    private lateinit var vault: SecurityVault
    private lateinit var engine: ServiceController<BioKeyAccessibilityService>

    /** Robolectric's screen: the whole of it. A foldable's other screen, as the window's new bounds. */
    private val whole = Rect(0, 0, 320, 470)
    private val unfolded = Rect(0, 0, 640, 700)

    private val launchers = listOf(
        "com.sec.android.app.launcher" to "com.sec.android.app.launcher.activities.LauncherActivity",
        "com.teslacoilsw.launcher" to "com.teslacoilsw.launcher.NovaLauncher",
        "com.google.android.apps.nexuslauncher" to "com.google.android.apps.nexuslauncher.NexusLauncherActivity"
    )

    @Before
    fun start() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        LockStateTracker.lockAll()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        vault.setAppLock(x, true)
        DiagnosticLog.clear()
        registerHomeScreens()
        engine = Robolectric.buildService(BioKeyAccessibilityService::class.java).create()
        BioKeyAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(engine.get())
        idle()
    }

    @After
    fun stop() {
        vault.setSetupCompletedSync(false)
        engine.destroy()
        lockScreenUp(null)
        ForegroundRules.resetSystemLists()
    }

    /** Nova is a home screen like any other: only Android says so. */
    private fun registerHomeScreens() {
        val pm = shadowOf(context.packageManager)
        for ((pkg, cls) in launchers) {
            val home = android.content.ComponentName(pkg, cls)
            pm.addActivityIfNotPresent(home)
            pm.addIntentFilterForActivity(home, android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
            })
        }
        ForegroundRules.resetSystemLists()
    }

    private fun idle(ms: Long = 500) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun window(id: Int, pkg: String?, bounds: Rect = whole, active: Boolean = true): AccessibilityWindowInfo =
        AccessibilityWindowInfo.obtain().also { w ->
            shadowOf(w).apply {
                setId(id)
                setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                setDisplayId(Display.DEFAULT_DISPLAY)
                setBoundsInScreen(bounds)
                setActive(active)
                setFocused(active)
                if (pkg != null) setRoot(AccessibilityNodeInfo.obtain().apply { packageName = pkg })
            }
        }

    private fun onScreen(vararg windows: AccessibilityWindowInfo) =
        shadowOf(engine.get()).setWindowsOnDisplay(Display.DEFAULT_DISPLAY, windows.toList())

    private fun windowEvent(pkg: String, cls: String = "$pkg.MainActivity") {
        engine.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = pkg
            className = cls
        })
        idle()
    }

    private fun lockScreenUp(pkg: String?) {
        LockScreenActivity::class.java.getDeclaredField("activeLockedTarget").apply { isAccessible = true }.set(null, pkg)
        LockScreenActivity::class.java.getDeclaredField("isLockScreenResumed").apply { isAccessible = true }.setBoolean(null, pkg != null)
    }

    private fun covered(pkg: String) = DiagnosticLog.entries().map { it.text }.filter { it.startsWith("Covered $pkg ") }

    private fun homePresses() = shadowOf(engine.get()).globalActionsPerformed.count { it == GLOBAL_ACTION_HOME }

    private fun shield(): InstantShieldOverlay =
        BioKeyAccessibilityService::class.java.getDeclaredField("shield").apply { isAccessible = true }.get(engine.get()) as InstantShieldOverlay

    /** X comes to the front on [bounds] (one screen of a foldable or the other), its window saying so. */
    private fun xOpens(cls: String = "$x.MainActivity", bounds: Rect = whole) {
        onScreen(window(2, x, bounds))
        windowEvent(x, cls)
    }

    /** X's lock screen comes up over it, its cover drawn. */
    private fun lockScreenComesUp() {
        lockScreenUp(x)
        InstantShieldOverlay.dismiss(x)
        onScreen(window(3, context.packageName, whole))
        windowEvent(context.packageName, LockScreenActivity::class.java.name)
    }

    /** X opens its main screen over its own lock screen, which stops and gives up (LockScreenActivity.onStop). */
    private fun xOpensItsMainScreenOverItsLockScreen() {
        LockStateTracker.markLockDisplaced(x)
        lockScreenUp(null)
        xOpens()
    }

    @Test
    fun anAppThatOpensItsMainScreenOverItsLockScreenIsCoveredAgainNotSentHome() {
        xOpens("$x.SplashActivity")
        lockScreenComesUp()
        xOpensItsMainScreenOverItsLockScreen()
        assertEquals(0, homePresses())
        assertEquals("Covered $x (came back over its lock screen)", covered(x).last())
        assertTrue(shield().isShowing)
        assertEquals(x, shield().targetPackage)
    }

    @Test
    fun anAppThatKeepsComingBackOverItsLockScreenGoesHome() {
        xOpens("$x.SplashActivity")
        repeat(2) {
            lockScreenComesUp()
            xOpensItsMainScreenOverItsLockScreen()
            assertEquals(0, homePresses())
        }
        lockScreenComesUp()
        xOpensItsMainScreenOverItsLockScreen()
        assertEquals(1, homePresses())
    }

    @Test
    fun cancelledThenBackAtOnceGoesHome() {
        xOpens()
        lockScreenComesUp()
        // Cancel, then a quick switch straight back to X.
        LockStateTracker.markLockAbandoned(x)
        lockScreenUp(null)
        xOpens()
        assertEquals(1, homePresses())
        assertEquals(listOf("Covered $x (opened)"), covered(x))
    }

    @Test
    fun fromEveryHomeScreenALockedAppIsCoveredOnceAndLeavingItCoversNothing() {
        for ((launcher, cls) in launchers) {
            DiagnosticLog.clear()
            onScreen(window(1, launcher))
            windowEvent(launcher, cls)
            xOpens()
            assertEquals(launcher, listOf("Covered $x (opened)"), covered(x))
            lockScreenComesUp()
            // Cancel: the lock screen goes Home itself, and the home screen is in front.
            LockStateTracker.markLockAbandoned(x)
            lockScreenUp(null)
            onScreen(window(1, launcher))
            windowEvent(launcher, cls)
            idle(LockStateTracker.ABANDONED_LOCK_COOLDOWN_MS + 100)
            assertEquals(launcher, 1, covered(x).size)
            assertFalse(launcher, shield().isShowing)
            assertEquals(launcher, 0, homePresses())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        }
    }

    @Test
    fun foldingOrUnfoldingAnUnlockedAppKeepsItUnlockedAndUncovered() {
        xOpens()
        LockStateTracker.onAppUnlocked(x)
        lockScreenUp(null)
        xOpens()
        DiagnosticLog.clear()
        // Unfolded, folded, unfolded: X is laid out again on each screen, its window events say so.
        for (bounds in listOf(unfolded, whole, unfolded)) {
            xOpens(bounds = bounds)
            windowEvent(x)
        }
        assertTrue(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
        assertEquals(emptyList<String>(), covered(x))
        assertEquals(0, homePresses())
    }

    @Test
    fun foldingOrUnfoldingUnderTheLockScreenKeepsItCoveredWithoutGoingHome() {
        xOpens()
        lockScreenComesUp()
        // X is laid out again under its lock screen on the other screen: its window shows up again.
        for (bounds in listOf(unfolded, whole)) {
            onScreen(window(3, context.packageName, bounds), window(2, x, bounds, active = false))
            windowEvent(x)
            windowEvent(context.packageName, LockScreenActivity::class.java.name)
        }
        // Its window turning up again may cover it again (the same lock screen, brought forward,
        // keeps its prompt): never anything that leaves it, or sends it Home.
        assertEquals(0, homePresses())
        assertTrue(covered(x).toString(), covered(x).all { it == "Covered $x (opened)" })
        assertFalse(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
        assertFalse(LockStateTracker.isRecentlyAbandoned(x))
    }

    /** Lock screens started since the last look, as Android was asked to start them. */
    private fun lockScreensStarted(): Int {
        var started = 0
        while (true) {
            val intent = shadowOf(context).nextStartedActivity ?: return started
            if (intent.component?.className == LockScreenActivity::class.java.name) started++
        }
    }

    @Test
    fun anAppStillStartingIsCoveredOnceNotOnceForEachOfItsEvents() {
        onScreen(window(2, x))
        lockScreensStarted()
        // A cold start: several window events in a row, before its lock screen has come up.
        repeat(4) {
            engine.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                packageName = x
                className = "$x.MainActivity"
            })
        }
        assertEquals(1, lockScreensStarted())
        assertEquals(listOf("Covered $x (opened)"), covered(x))
        assertTrue(shield().isShowing)
        // Still not up after the shield's first check: started again, whatever else happened.
        idle(100)
        assertTrue(lockScreensStarted() >= 1)
    }

    @Test
    fun aLockScreenPushedAwayAtOnceIsStartedAgain() {
        xOpens()
        lockScreensStarted()
        // Its lock screen came up, then the app opened over it within moments of being started.
        lockScreenUp(x)
        LockScreenActivity::class.java.getDeclaredField("resumedAt").apply { isAccessible = true }
            .setLong(null, android.os.SystemClock.uptimeMillis())
        LockStateTracker.markLockDisplaced(x)
        lockScreenUp(null)
        engine.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = x
            className = "$x.MainActivity"
        })
        assertEquals(1, lockScreensStarted())
    }
}
