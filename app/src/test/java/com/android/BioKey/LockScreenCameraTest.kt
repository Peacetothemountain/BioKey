package com.android.BioKey

import android.app.Application
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
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
 * The phone's lock-screen camera (and emergency dialer), locked in BioKey, with the accessibility
 * engine: by default it opens over the keyguard uncovered, as Android's own App lock lets it, and
 * is covered the moment the phone is unlocked with it still in front. With the setting off, or for
 * an app started behind the keyguard rather than over it, it's covered as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockScreenCameraTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val camera = "com.example.camera"
    private val secureScreen = "$camera.SecureCameraActivity"
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
        vault.setAppLock(camera, true)
        DiagnosticLog.clear()
        val secure = ComponentName(camera, secureScreen)
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(secure)
            addIntentFilterForActivity(secure, IntentFilter(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        }
        ForegroundRules.resetSystemLists()
        engine = Robolectric.buildService(BioKeyAccessibilityService::class.java).create()
        BioKeyAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(engine.get())
        idle()
        // The screen on, the phone locked: the keyguard is up.
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        keyguard(locked = true)
        // Seen locked before anything opens: nothing was on screen as it locked.
        LockStateTracker.onDeviceLockState(true)
        idle()
    }

    @After
    fun stop() {
        keyguard(locked = false)
        vault.setSetupCompletedSync(false)
        engine.destroy()
        LockScreenActivity.setStateForTests(null)
        ForegroundRules.resetSystemLists()
    }

    private fun keyguard(locked: Boolean) {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).apply {
            setKeyguardLocked(locked)
            setIsDeviceLocked(locked)
        }
    }

    private fun idle(ms: Long = 500) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun appWindow(id: Int, pkg: String) = AccessibilityWindowInfo.obtain().also { w ->
        shadowOf(w).apply {
            setId(id)
            setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            setDisplayId(Display.DEFAULT_DISPLAY)
            setBoundsInScreen(whole)
            setActive(true)
            setFocused(true)
            setRoot(AccessibilityNodeInfo.obtain().apply { packageName = pkg })
        }
    }

    /** The keyguard's own window: full screen, a system one. */
    private fun keyguardWindow() = AccessibilityWindowInfo.obtain().also { w ->
        shadowOf(w).apply {
            setId(50)
            setType(AccessibilityWindowInfo.TYPE_SYSTEM)
            setDisplayId(Display.DEFAULT_DISPLAY)
            setBoundsInScreen(whole)
            setRoot(AccessibilityNodeInfo.obtain().apply { packageName = "com.android.systemui" })
        }
    }

    private fun onScreen(vararg windows: AccessibilityWindowInfo) =
        shadowOf(engine.get()).setWindowsOnDisplay(Display.DEFAULT_DISPLAY, windows.toList())

    private fun windowEvent(pkg: String, cls: String, wait: Long = 500) {
        engine.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = pkg
            className = cls
            android.view.accessibility.AccessibilityRecord::class.java.getDeclaredMethod("setWindowId", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(this, 2)
        })
        idle(wait)
    }

    /** The camera opens from the lock screen (a double press of the power button): over the keyguard. */
    private fun cameraOpensOverTheKeyguard() {
        onScreen(appWindow(2, camera), keyguardWindow())
        windowEvent(camera, secureScreen, wait = 1_500)
    }

    private fun covered() = DiagnosticLog.entries().map { it.text }.filter { it.startsWith("Covered $camera ") }

    private fun lockScreensStarted(): List<Intent> = buildList {
        while (true) {
            val intent = shadowOf(context).nextStartedActivity ?: break
            if (intent.component?.className == LockScreenActivity::class.java.name) add(intent)
        }
    }

    @Test
    fun theLockScreenCameraOpensUncoveredAndIsCoveredOnceThePhoneIsUnlocked() {
        cameraOpensOverTheKeyguard()
        assertEquals(emptyList<String>(), covered())
        assertTrue(lockScreensStarted().isEmpty())
        // Unlocked from inside the camera: the keyguard goes, the camera is still in front.
        keyguard(locked = false)
        onScreen(appWindow(2, camera))
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        idle()
        assertTrue(covered().toString(), covered().isNotEmpty())
        assertTrue(lockScreensStarted().isNotEmpty())
    }

    @Test
    fun withTheSettingOffTheLockScreenCameraIsCoveredOverTheKeyguard() {
        vault.setKeyguardCameraAllowed(false)
        cameraOpensOverTheKeyguard()
        assertEquals(listOf("Covered $camera (opened)"), covered())
        val starts = lockScreensStarted()
        assertTrue(starts.isNotEmpty())
        assertTrue(starts.none { it.getBooleanExtra(LockScreenActivity.EXTRA_BEHIND_KEYGUARD, false) })
    }

    @Test
    fun aLockScreenCameraThatCrashedIsntCoveredAsTheScreenGoesOff() {
        cameraOpensOverTheKeyguard()
        // It crashed: its window has gone, the keyguard is all there is.
        onScreen(keyguardWindow())
        idle(1_000)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        idle()
        assertEquals(emptyList<String>(), covered())
        assertTrue(lockScreensStarted().isEmpty())
    }

    @Test
    fun anotherScreenOfTheCameraAppOverTheKeyguardIsCovered() {
        onScreen(appWindow(2, camera), keyguardWindow())
        windowEvent(camera, "$camera.SettingsActivity", wait = 1_500)
        assertTrue(covered().toString(), covered().isNotEmpty())
    }

    @Test
    fun theCamerasStartingWindowWaitsForItsScreenToNameItself() {
        onScreen(appWindow(2, camera), keyguardWindow())
        // Its starting window comes first, naming no screen; then the lock-screen camera's own.
        windowEvent(camera, "android.widget.FrameLayout", wait = 100)
        windowEvent(camera, secureScreen, wait = 1_500)
        assertEquals(emptyList<String>(), covered())
    }

    @Test
    fun aScreenThatNeverNamesItselfIsCovered() {
        onScreen(appWindow(2, camera), keyguardWindow())
        windowEvent(camera, "android.widget.FrameLayout", wait = 1_500)
        assertTrue(covered().toString(), covered().isNotEmpty())
    }

    @Test
    fun aPhoneSetUpBeforeTheSettingCameKeepsTheCameraCovered() {
        // Set up already, from before the setting existed: it isn't stored.
        val prefs = context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE)
        vault.awaitPendingWrites()
        prefs.edit().remove("keyguard_camera_allowed").commit()
        SecurityVault.resetForTests()
        assertEquals(false, SecurityVault.getInstance(context).isKeyguardCameraAllowed())
        // A new setup: on, as Android's own App lock has it.
        prefs.edit().clear().commit()
        SecurityVault.resetForTests()
        assertEquals(true, SecurityVault.getInstance(context).isKeyguardCameraAllowed())
        SecurityVault.resetForTests()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
    }

    @Test
    fun theCameraStartedBehindTheKeyguardIsStillCoveredBehindIt() {
        // From a notification's action, say: it starts behind the keyguard, under it, not over it.
        onScreen(keyguardWindow(), appWindow(2, camera))
        windowEvent(camera, "$camera.MainActivity", wait = 1_500)
        assertEquals(listOf("Covered $camera (opened behind the keyguard)"), covered())
        assertTrue(lockScreensStarted().all { it.getBooleanExtra(LockScreenActivity.EXTRA_BEHIND_KEYGUARD, false) })
    }
}
