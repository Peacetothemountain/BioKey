package com.android.BioKey

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.InstantShieldOverlay
import com.android.BioKey.feature.lock.MaskController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Which windows get a patch: a locked app's picture-in-picture window does (and the app counts as
 * masked, so no full-screen cover is put over it too); an unlocked app's, or a full-screen window
 * (the shield's to cover), doesn't; with protection paused, none; with the phone locked, they wait hidden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaskControllerTest {

    class Service : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    private val service = Robolectric.setupService(Service::class.java)
    private val vault get() = SecurityVault.getInstance(service)
    private var keyguardLocked = false
    private val packages = HashMap<Int, String>()
    private val locked = mutableListOf<String>()

    private val host = object : MaskController.Host {
        override fun windowList(): List<AccessibilityWindowInfo>? = null
        override fun knownPackage(windowId: Int) = packages[windowId]
        override fun packageOf(window: AccessibilityWindowInfo) = packages[window.id]
        override fun isCoverable(pkg: String) = pkg != service.packageName
        override fun isOwnWindow(window: AccessibilityWindowInfo) = false
        override fun isInteractive() = true
        override fun isKeyguardLocked() = keyguardLocked
        override fun needsCover(pkg: String) = true
        override fun isOwnScreenInFront(front: String?) = false
        override fun mayBe(target: String, pkg: String?) = target == pkg
        override fun lock(pkg: String, why: String, displayId: Int?) { locked += pkg }
        override fun bringLockScreenForward(pkg: String, displayId: Int) = Unit
        override fun guarded(what: String, block: () -> Unit) = block()
        override fun describe(t: Throwable) = t.toString()
    }

    private val shield by lazy {
        InstantShieldOverlay(service, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, goHome = {}, onTimeout = { _, _ -> false })
    }
    private val controller by lazy { MaskController(service, vault, shield, Handler(Looper.getMainLooper()), host) }

    @Before
    fun reset() {
        vault.awaitPendingWrites()
        service.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        vault.setSetupCompletedSync(true)
        vault.setGlobalLockEnabled(true)
        vault.setAppLock("com.example.bank", true)
    }

    @After
    fun tearDown() {
        controller.clear()
        controller.stop()
    }

    private fun window(id: Int, pkg: String, bounds: Rect, pip: Boolean = false): AccessibilityWindowInfo {
        packages[id] = pkg
        return AccessibilityWindowInfo.obtain().also { w ->
            shadowOf(w).apply {
                setId(id)
                setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                setDisplayId(Display.DEFAULT_DISPLAY)
                setPictureInPicture(pip)
                setLayer(1)
                setBoundsInScreen(bounds)
            }
        }
    }

    private val small = Rect(0, 0, 300, 200)

    @Test
    fun aLockedAppInPictureInPictureIsPatched() {
        controller.update(listOf(window(1, "com.example.bank", small, pip = true)), full = false)
        assertEquals(setOf("com.example.bank"), controller.maskedApps)
        assertTrue(locked.isEmpty())
    }

    @Test
    fun anUnlockedAppIsLeftAlone() {
        controller.update(listOf(window(1, "com.example.chat", small, pip = true)), full = false)
        assertTrue(controller.maskedApps.isEmpty())
        assertTrue(controller.isEmpty)
    }

    @Test
    fun aFullScreenWindowIsTheShieldsToCover() {
        val screen = service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        controller.update(listOf(window(1, "com.example.bank", Rect(screen))), full = true)
        assertTrue(controller.isEmpty)
    }

    @Test
    fun pausedProtectionTakesEveryPatchDown() {
        controller.update(listOf(window(1, "com.example.bank", small, pip = true)), full = false)
        vault.pauseProtection(60_000L)
        controller.update(listOf(window(1, "com.example.bank", small, pip = true)), full = false)
        assertTrue(controller.isEmpty)
        assertTrue(controller.maskedApps.isEmpty())
    }

    @Test
    fun withThePhoneLockedPatchesWaitHidden() {
        keyguardLocked = true
        controller.update(listOf(window(1, "com.example.bank", small, pip = true)), full = false)
        // Kept, to show once the phone is unlocked; nothing covered the usual way meanwhile.
        assertTrue(!controller.isEmpty)
        assertTrue(locked.isEmpty())
    }
}
