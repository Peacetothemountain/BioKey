package com.android.BioKey

import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.android.BioKey.core.security.AlsoLockedByAndroid
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.feature.lock.InstantShieldOverlay
import com.android.BioKey.feature.lock.LockScreenActivity
import com.android.BioKey.feature.lock.SystemAppLock
import com.android.BioKey.feature.lock.SystemAppLockPrompt
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

/**
 * Living alongside Android's own App lock (Android 17 QPR3): its cover is recognised, never covered
 * and never taken for the app it's for; BioKey's cover from the tap is lowered so Android's prompt
 * shows; and an app locked by both asks once, unless the owner wants BioKey's own say. An app only
 * Android locks is left alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemAppLockTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val x = "com.example.notes"
    private val other = "com.example.diary"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val coverWindowId = 7
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
        AlsoLockedByAndroid.clear(context)
        UnlockAttempts.load(context)
        UnlockAttempts.setEnabled(context, false)
        install(x, "Notes")
        install(other, "Diary")
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        vault.setAppLock(x, true)
        DiagnosticLog.clear()
        val home = ComponentName(launcher, "$launcher.NexusLauncherActivity")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(home)
            addIntentFilterForActivity(home, IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            })
        }
        ForegroundRules.resetSystemLists()
        engine = Robolectric.buildService(BioKeyAccessibilityService::class.java).create()
        BioKeyAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(engine.get())
        idle()
        homeInFront()
    }

    @After
    fun stop() {
        vault.setSetupCompletedSync(false)
        engine.destroy()
        LockScreenActivity.setStateForTests(null)
        ForegroundRules.resetSystemLists()
        UnlockAttempts.setEnabled(context, false)
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

    private fun window(id: Int, pkg: String?, active: Boolean = true): AccessibilityWindowInfo =
        AccessibilityWindowInfo.obtain().also { w ->
            shadowOf(w).apply {
                setId(id)
                setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                setDisplayId(Display.DEFAULT_DISPLAY)
                setBoundsInScreen(whole)
                setActive(active)
                setFocused(active)
                if (pkg != null) setRoot(AccessibilityNodeInfo.obtain().apply { packageName = pkg })
            }
        }

    private fun onScreen(vararg windows: AccessibilityWindowInfo) =
        shadowOf(engine.get()).setWindowsOnDisplay(Display.DEFAULT_DISPLAY, windows.toList())

    private fun send(type: Int, pkg: String, cls: String, text: String? = null, windowId: Int = -1, wait: Long = 500) {
        engine.get().onAccessibilityEvent(AccessibilityEvent(type).apply {
            packageName = pkg
            className = cls
            if (text != null) this.text.add(text)
            if (windowId >= 0) {
                android.view.accessibility.AccessibilityRecord::class.java.getDeclaredMethod("setWindowId", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(this, windowId)
            }
        })
        idle(wait)
    }

    private fun homeInFront() {
        onScreen(window(1, launcher))
        send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, launcher, "$launcher.NexusLauncherActivity", windowId = 1)
    }

    /** The owner taps [label]'s icon on the home screen. */
    private fun tapIcon(label: String) =
        send(AccessibilityEvent.TYPE_VIEW_CLICKED, launcher, "android.widget.TextView", label, wait = 100)

    /** Android's App lock intercepts the launch: its see-through cover over the home screen, its prompt over that. */
    private fun androidCoverComesUp() {
        onScreen(window(coverWindowId, SystemAppLock.PACKAGE), window(1, launcher, active = false))
        send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, SystemAppLock.PACKAGE, SystemAppLock.COVER_CLASS, windowId = coverWindowId, wait = 100)
    }

    /** Android's prompt passed: its cover goes, and Android starts [pkg] as it was opened. */
    private fun androidLetsThrough(pkg: String) {
        onScreen(window(2, pkg))
        send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, pkg, "$pkg.MainActivity", windowId = 2)
    }

    private fun shield(): InstantShieldOverlay =
        BioKeyAccessibilityService::class.java.getDeclaredField("shield").apply { isAccessible = true }.get(engine.get()) as InstantShieldOverlay

    private fun covered(pkg: String) = DiagnosticLog.entries().map { it.text }.filter { it.startsWith("Covered $pkg ") }

    private fun homePresses() = shadowOf(engine.get()).globalActionsPerformed.count { it == GLOBAL_ACTION_HOME }

    private fun lockScreensStarted(): Int {
        var started = 0
        while (true) {
            val intent = shadowOf(context).nextStartedActivity ?: return started
            if (intent.component?.className == LockScreenActivity::class.java.name) started++
        }
    }

    @Test
    fun theCoverFromTheTapIsLoweredSoAndroidsPromptShows() {
        tapIcon("Notes")
        assertTrue(shield().isShowing)
        androidCoverComesUp()
        assertFalse(shield().isShowing)
        assertEquals(0, lockScreensStarted())
        assertEquals(emptyList<String>(), covered(x))
        assertEquals(0, homePresses())
        assertTrue(DiagnosticLog.entries().any { it.text == "Android's App lock is asking for $x" })
    }

    @Test
    fun anAppLockedByBothAsksOnce() {
        tapIcon("Notes")
        androidCoverComesUp()
        androidLetsThrough(x)
        assertTrue(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
        assertEquals(emptyList<String>(), covered(x))
        assertEquals(0, lockScreensStarted())
        assertFalse(shield().isShowing)
        assertTrue(DiagnosticLog.entries().any { it.text == "$x opened through Android's App lock: not asked again" })
        // Left for Home, it locks again as usual.
        homeInFront()
        idle(1_000)
        assertFalse(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun anAppLockedByBothIsNotedAsAlsoLockedByAndroid() {
        tapIcon("Notes")
        androidCoverComesUp()
        assertEquals(setOf(x), AlsoLockedByAndroid.apps.value)
    }

    @Test
    fun faceOrFingerprintOnlyStillAsksAfterAndroid() {
        vault.setBiometricOnly(true)
        tapIcon("Notes")
        androidCoverComesUp()
        androidLetsThrough(x)
        assertFalse(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
        assertEquals(listOf("Covered $x (opened)"), covered(x))
        assertTrue(DiagnosticLog.entries().any { it.text == "$x opened through Android's App lock; asking again (face or fingerprint only)" })
    }

    @Test
    fun notingOpeningsWithTheScreenLockStillAsksAfterAndroid() {
        UnlockAttempts.setEnabled(context, true)
        UnlockAttempts.setScreenLockUnlocks(context, true)
        tapIcon("Notes")
        androidCoverComesUp()
        androidLetsThrough(x)
        assertFalse(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
        assertEquals(listOf("Covered $x (opened)"), covered(x))
    }

    @Test
    fun cancelledOnAndroidsPromptTheAppIsCoveredIfItOpensAfterwards() {
        tapIcon("Notes")
        androidCoverComesUp()
        // Cancelled: the cover goes, the home screen is in front again.
        homeInFront()
        androidLetsThrough(x)
        assertFalse(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
        assertEquals(listOf("Covered $x (opened)"), covered(x))
    }

    @Test
    fun aCoverBioKeyCantPlaceChangesNothing() {
        // Opened from a notification, say: no tap told BioKey which app.
        androidCoverComesUp()
        assertTrue(DiagnosticLog.entries().any { it.text == "Android's App lock is asking for an app" })
        androidLetsThrough(x)
        assertFalse(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
        assertEquals(listOf("Covered $x (opened)"), covered(x))
    }

    @Test
    fun anotherAppAfterTheCoverIsNoUnlockOfIt() {
        vault.setAppLock(other, true)
        tapIcon("Notes")
        androidCoverComesUp()
        // Something else came to the front instead: it isn't the app Android was asked about.
        androidLetsThrough(other)
        assertFalse(LockStateTracker.isAppUnlocked(other, LockTimeoutMode.IMMEDIATE))
        assertEquals(listOf("Covered $other (opened)"), covered(other))
        // Nor is the app it was for, opening later.
        LockScreenActivity.setStateForTests(null)
        homeInFront()
        androidLetsThrough(x)
        assertFalse(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun anAppOnlyAndroidLocksIsLeftAlone() {
        // The owner is in Notes, unlocked; Notes opens Diary, which only Android locks.
        LockStateTracker.onAppUnlocked(x)
        onScreen(window(2, x))
        send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, x, "$x.MainActivity", windowId = 2)
        onScreen(window(coverWindowId, SystemAppLock.PACKAGE), window(2, x, active = false))
        send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, SystemAppLock.PACKAGE, SystemAppLock.COVER_CLASS, windowId = coverWindowId)
        // Its prompt over everything: no app's window is listed meanwhile.
        onScreen()
        idle(2_000)
        assertEquals(0, lockScreensStarted())
        assertEquals(0, homePresses())
        assertTrue(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
        assertEquals(emptySet<String>(), AlsoLockedByAndroid.apps.value)
        // Cancelled: back in Notes, still unlocked.
        onScreen(window(2, x))
        idle(1_000)
        assertTrue(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
        // Passed: Diary opens; Notes is left as usual.
        androidLetsThrough(other)
        idle(1_000)
        assertFalse(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
        assertEquals(emptyList<String>(), covered(other))
    }

    @Test
    fun anAppAlreadyOpenComesBackFromBehindItsCoverInItsOwnWindow() {
        // Notes was open before (its window known), and Android covered it in its task meanwhile.
        onScreen(window(2, x))
        send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, x, "$x.MainActivity", windowId = 2)
        LockStateTracker.onAppUnlocked(x)
        LockStateTracker.lockPackage(x)
        LockScreenActivity.setStateForTests(null)
        homeInFront()
        DiagnosticLog.clear()
        lockScreensStarted()
        // Opened from Recents: Android's cover is in front, in Notes' own task.
        tapIcon("Notes")
        androidCoverComesUp()
        // Passed: the cover goes, and Notes' own window comes back without an event of its own.
        onScreen(window(2, x))
        engine.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOWS_CHANGED).apply {
            android.view.accessibility.AccessibilityRecord::class.java.getDeclaredMethod("setWindowId", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(this, 2)
            AccessibilityEvent::class.java.getDeclaredMethod("setWindowChanges", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(this, AccessibilityEvent.WINDOWS_CHANGE_ACTIVE)
        })
        idle()
        assertTrue(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
        assertEquals(emptyList<String>(), covered(x))
        assertEquals(0, lockScreensStarted())
    }

    /** SystemUI's prompt over Android's cover, its words [texts] (in English, as on the owner's phone). */
    private fun androidPromptSays(vararg texts: String) {
        engine.get().systemPromptWords = SystemAppLockPrompt(
            "This app is protected with app lock",
            "%1\$s and %2\$s are protected with app lock",
            "%1\$s, %2\$s, and %3\$s are protected with app lock"
        )
        val systemUi = "com.android.systemui"
        val root = AccessibilityNodeInfo.obtain().apply { packageName = systemUi }
        for (t in texts) shadowOf(root).addChild(AccessibilityNodeInfo.obtain().apply { packageName = systemUi; text = t })
        val prompt = AccessibilityWindowInfo.obtain().also { w ->
            shadowOf(w).apply {
                setId(9)
                setType(AccessibilityWindowInfo.TYPE_SYSTEM)
                setDisplayId(Display.DEFAULT_DISPLAY)
                setBoundsInScreen(whole)
                setActive(true)
                setFocused(true)
                setRoot(root)
            }
        }
        onScreen(prompt)
        send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, systemUi, "android.widget.FrameLayout", wait = 100)
    }

    @Test
    fun aLaunchWithoutATapIsPlacedByAndroidsPrompt() {
        androidCoverComesUp()
        androidPromptSays("Verify it's you", "Notes", "This app is protected with app lock", "Use PIN")
        assertTrue(DiagnosticLog.entries().any { it.text == "Android's App lock prompt names $x" })
        androidLetsThrough(x)
        assertTrue(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
        assertEquals(emptyList<String>(), covered(x))
    }

    @Test
    fun androidsPromptStandsOverATapGuess() {
        tapIcon("Notes")
        androidCoverComesUp()
        // Its prompt is for Diary, which BioKey doesn't lock: not Notes, whatever was tapped.
        androidPromptSays("Verify it's you", "Diary", "This app is protected with app lock")
        androidLetsThrough(x)
        assertFalse(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
        assertEquals(listOf("Covered $x (opened)"), covered(x))
    }

    @Test
    fun oneOfAndroidsPromptsForSeveralAppsLetsEachThrough() {
        vault.setAppLock(other, true)
        androidCoverComesUp()
        androidPromptSays("Verify it's you", "Notes and Diary are protected with app lock")
        androidLetsThrough(x)
        androidLetsThrough(other)
        assertTrue(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.GRACE_5_MINUTES))
        assertTrue(LockStateTracker.isAppUnlocked(other, LockTimeoutMode.GRACE_5_MINUTES))
    }

    @Test
    fun androidsSentenceIsReadInAnyOrderALanguagePutsItsNamesIn() {
        val words = SystemAppLockPrompt("Diese App ist gesperrt", "%2\$s und %1\$s sind gesperrt")
        assertEquals(listOf("Notes", "Diary"), words.namesIn(listOf("Diary und Notes sind gesperrt")))
        assertEquals(listOf("Titel", "Notes"), words.namesIn(listOf("Titel", "Diese App ist gesperrt", "Notes")))
        assertEquals(null, words.namesIn(listOf("Notes", "Diary")))
    }

    @Test
    fun anAppNotSeenLockedByAndroidForAMonthIsForgotten() {
        val start = System.currentTimeMillis()
        try {
            AlsoLockedByAndroid.clock = { start }
            AlsoLockedByAndroid.note(context, x)
            AlsoLockedByAndroid.clock = { start + AlsoLockedByAndroid.FORGET_AFTER_MS + 1 }
            AlsoLockedByAndroid.note(context, other)
            assertEquals(setOf(other), AlsoLockedByAndroid.apps.value)
        } finally {
            AlsoLockedByAndroid.clock = System::currentTimeMillis
        }
    }

    @Test
    fun theScreenGoingOffEndsTheWait() {
        tapIcon("Notes")
        androidCoverComesUp()
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        idle()
        androidLetsThrough(x)
        assertFalse(LockStateTracker.isAppUnlocked(x, LockTimeoutMode.IMMEDIATE))
    }
}
