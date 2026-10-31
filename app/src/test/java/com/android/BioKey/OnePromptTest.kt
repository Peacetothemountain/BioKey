package com.android.BioKey

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * One prompt for every locked app on screen: with two locked apps side by side in split screen (or
 * one on another display), the lock screen's prompt names them all and its one success unlocks
 * them all, rather than one prompt after the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [InstallTimePermissions::class])
class OnePromptTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val x = "com.example.notes"
    private val y = "com.example.diary"
    private lateinit var vault: SecurityVault
    private lateinit var engine: ServiceController<BioKeyAccessibilityService>
    private val top = Rect(0, 0, 320, 235)
    private val bottom = Rect(0, 235, 320, 470)
    private val whole = Rect(0, 0, 320, 470)

    @Before
    fun start() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        LockStateTracker.lockAll()
        LockScreenActivity.forgetResumeForTests()
        install(x, "Notes")
        install(y, "Diary")
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        vault.setAppLock(x, true)
        vault.setAppLock(y, true)
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

    private fun install(pkg: String, label: String) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                nonLocalizedLabel = label
            }
        })
    }

    private fun idle(ms: Long = 500) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun window(id: Int, pkg: String?, bounds: Rect, active: Boolean) = AccessibilityWindowInfo.obtain().also { w ->
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

    /** Notes and Diary open side by side (a pair from Recents); Notes' event comes first. */
    private fun splitScreenOpens() {
        onScreen(window(2, x, top, active = true), window(3, y, bottom, active = false))
        engine.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = x
            className = "$x.MainActivity"
        })
        idle()
    }

    /** Notes' lock screen comes up over the whole display: neither app's window is listed under it. */
    private fun lockScreenUpFor(pkg: String) {
        LockScreenActivity.setStateForTests(pkg)
        onScreen(window(4, null, whole, active = true))
        idle()
    }

    @Test
    fun aPromptNamesUpToThreeAppsAndCountsTheRest() {
        val res = context.resources
        assertEquals("Notes", LockScreenActivity.appsNamed(res, listOf("Notes")))
        assertEquals("Notes and Diary", LockScreenActivity.appsNamed(res, listOf("Notes", "Diary")))
        assertEquals("Notes, Diary and Bank", LockScreenActivity.appsNamed(res, listOf("Notes", "Diary", "Bank")))
        assertEquals("Notes, Diary, Bank and 1 other app", LockScreenActivity.appsNamed(res, listOf("Notes", "Diary", "Bank", "Chat")))
        assertEquals("Notes, Diary, Bank and 2 other apps", LockScreenActivity.appsNamed(res, listOf("Notes", "Diary", "Bank", "Chat", "Mail")))
    }

    @Test
    fun theOtherHalfOfASplitScreenIsAskedForWithIt() {
        splitScreenOpens()
        lockScreenUpFor(x)
        assertEquals(listOf(y), BioKeyAccessibilityService.lockedAppsOnScreenWith(x))
    }

    @Test
    fun aSplitFormedUnderTheCoverFromRecentsIsAskedForInOnePrompt() {
        // Recents: Notes chosen for split screen, then Diary's card tapped. The cover goes up at the
        // tap, before either half has a window, so no window list ever shows them: only their events.
        val launcher = "com.google.android.apps.nexuslauncher"
        onScreen(window(1, launcher, whole, active = true))
        engine.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = launcher
            className = "android.widget.TextView"
            text.add("Diary")
        })
        idle(100)
        for (pkg in listOf(x, y)) {
            engine.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                packageName = pkg
                className = "$pkg.MainActivity"
            })
            idle(50)
        }
        lockScreenUpFor(y)
        assertEquals(listOf(x), BioKeyAccessibilityService.lockedAppsOnScreenWith(y))
    }

    @Test
    fun anAppLeftFromItsCoverIsntAskedForWithTheNext() {
        engine.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = x
            className = "$x.MainActivity"
        })
        idle(50)
        // Its lock screen cancelled: Home.
        LockStateTracker.markLockAbandoned(x)
        engine.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = y
            className = "$y.MainActivity"
        })
        idle(50)
        lockScreenUpFor(y)
        assertEquals(emptyList<String>(), BioKeyAccessibilityService.lockedAppsOnScreenWith(y))
    }

    @Test
    fun anUnlockedAppBesideItIsntAskedFor() {
        LockStateTracker.onAppUnlocked(y)
        splitScreenOpens()
        lockScreenUpFor(x)
        assertEquals(emptyList<String>(), BioKeyAccessibilityService.lockedAppsOnScreenWith(x))
    }

    @Test
    fun anAppNotOnScreenIsntAskedFor() {
        onScreen(window(2, x, whole, active = true))
        engine.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = x
            className = "$x.MainActivity"
        })
        idle()
        lockScreenUpFor(x)
        assertEquals(emptyList<String>(), BioKeyAccessibilityService.lockedAppsOnScreenWith(x))
    }

    @Test
    fun oneSuccessUnlocksEveryAppItNamed() {
        splitScreenOpens()
        lockScreenUpFor(x)
        val screen: ActivityController<LockScreenActivity> = Robolectric.buildActivity(
            LockScreenActivity::class.java,
            Intent(context, LockScreenActivity::class.java).putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, x)
        ).setup()
        try {
            // The prompt it showed named both; its success comes back.
            LockScreenActivity::class.java.getDeclaredMethod(
                "onAuthenticated", String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, List::class.java
            ).apply { isAccessible = true }.invoke(screen.get(), x, false, LockStateTracker.lockGeneration(), listOf(y))
            assertTrue(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
            assertTrue(LockStateTracker.isAppUnlocked(y, LockTimeoutMode.IMMEDIATE))
            assertTrue(DiagnosticLog.entries().any { it.text == "$y unlocked with $x, by the same prompt" })
        } finally {
            runCatching { screen.pause().stop().destroy() }
        }
    }

    @Test
    fun aPhoneLockedMidPromptUnlocksNoneOfThem() {
        splitScreenOpens()
        lockScreenUpFor(x)
        val generation = LockStateTracker.lockGeneration()
        val screen: ActivityController<LockScreenActivity> = Robolectric.buildActivity(
            LockScreenActivity::class.java,
            Intent(context, LockScreenActivity::class.java).putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, x)
        ).setup()
        try {
            LockStateTracker.lockAll(reason = "phone locked")
            LockScreenActivity::class.java.getDeclaredMethod(
                "onAuthenticated", String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, List::class.java
            ).apply { isAccessible = true }.invoke(screen.get(), x, false, generation, listOf(y))
            assertFalse(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
            assertFalse(LockStateTracker.isAppUnlocked(y, LockTimeoutMode.IMMEDIATE))
        } finally {
            runCatching { screen.pause().stop().destroy() }
        }
    }
}
