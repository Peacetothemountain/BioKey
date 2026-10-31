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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Failing closed in the accessibility engine when the cover itself can't go up: nothing else
 * checks the lock screen then, and Android can refuse one started from the background without a
 * word. Not up within a second, with the app still on screen: Home.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [InstallTimePermissions::class])
class FailClosedTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val x = "com.example.notes"
    private lateinit var vault: SecurityVault
    private lateinit var engine: ServiceController<BioKeyAccessibilityService>
    private val whole = Rect(0, 0, 320, 470)

    @Before
    fun start() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        LockStateTracker.lockAll()
        LockScreenActivity.forgetResumeForTests()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        vault.setAppLock(x, true)
        DiagnosticLog.clear()
        ForegroundRules.resetSystemLists()
        engine = Robolectric.buildService(BioKeyAccessibilityService::class.java).create()
        BioKeyAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(engine.get())
        idle()
    }

    @After
    fun stop() {
        vault.setSetupCompletedSync(false)
        engine.destroy()
        LockScreenActivity.setStateForTests(null)
        ForegroundRules.resetSystemLists()
    }

    private fun idle(ms: Long = 500) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun shield(): InstantShieldOverlay =
        BioKeyAccessibilityService::class.java.getDeclaredField("shield").apply { isAccessible = true }.get(engine.get()) as InstantShieldOverlay

    private fun homePresses() = shadowOf(engine.get()).globalActionsPerformed.count { it == GLOBAL_ACTION_HOME }

    private fun xOpens() {
        shadowOf(engine.get()).setWindowsOnDisplay(Display.DEFAULT_DISPLAY, listOf(AccessibilityWindowInfo.obtain().also { w ->
            shadowOf(w).apply {
                setId(2)
                setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                setDisplayId(Display.DEFAULT_DISPLAY)
                setBoundsInScreen(whole)
                setActive(true)
                setFocused(true)
                setRoot(AccessibilityNodeInfo.obtain().apply { packageName = x })
            }
        }))
        engine.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = x
            className = "$x.MainActivity"
        })
    }

    @Test
    fun noCoverAndNoLockScreenGoesHome() {
        // The cover's window can't be shown (Android took it away, say).
        shield().destroy()
        idle()
        xOpens()
        idle(200)
        assertEquals(0, homePresses())
        // Android refused the lock screen without a word: it never came up.
        idle(1_000)
        assertEquals(1, homePresses())
        assertTrue(DiagnosticLog.entries().any { it.text == "Lock screen for $x didn't appear; went Home" })
    }

    @Test
    fun aTapOnTheLockScreenWithNoPromptUpAsksAgain() {
        // A lock screen whose prompt never came (its app gone behind the keyguard, say): a tap anywhere asks.
        val screen = Robolectric.buildActivity(
            LockScreenActivity::class.java,
            android.content.Intent(context, LockScreenActivity::class.java).putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, x)
        ).setup()
        try {
            screen.get().markFirstFrameForTests()
            val cover = screen.get().findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            com.android.BioKey.core.diagnostics.EngineTrace.lines()
            cover.performClick()
            assertTrue(com.android.BioKey.core.diagnostics.EngineTrace.lines().any { it.endsWith("\tp.up\t$x") })
        } finally {
            runCatching { screen.pause().stop().destroy() }
            com.android.BioKey.core.security.SecurityEngine.INSTANCE.resetAuthenticatingState()
        }
    }

    @Test
    fun noCoverButTheLockScreenCameUpStays() {
        shield().destroy()
        idle()
        xOpens()
        LockScreenActivity.setStateForTests(x)
        idle(1_500)
        assertEquals(0, homePresses())
    }
}
