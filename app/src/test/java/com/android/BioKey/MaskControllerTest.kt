package com.android.BioKey

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.InstantShieldOverlay
import com.android.BioKey.feature.lock.MaskController
import com.android.BioKey.feature.lock.Patches
import com.android.BioKey.feature.lock.WindowMask
import java.lang.ref.WeakReference
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
import org.robolectric.annotation.Config

/**
 * Which windows get a patch, and what happens when one can't go up. A locked app's
 * picture-in-picture window is patched (and counted masked, so no full-screen cover goes over it
 * too); an unlocked app's, or a full-screen window (the shield's), isn't; paused, none; with the
 * phone locked they wait hidden; with a prompt up they step aside only on its display. And it
 * fails closed: a patch that can't go up covers the app the usual way, a picture-in-picture app
 * that won't come out is locked under its patch, and a check that throws locks the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaskControllerTest {

    class Service : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    /** Patches that go up, or (with [failing]) refuse to; and what was asked of them. */
    private class FakePatches(val onTapped: (WindowMask.Target) -> Unit) : Patches {
        var failing = false
        val up = LinkedHashMap<Int, WindowMask.Target>()
        var hiddenEverywhere = false
        var hiddenDisplays = emptySet<Int>()
        override val isEmpty get() = up.isEmpty()
        override fun isMasking(windowId: Int) = windowId in up
        override fun show(targets: Collection<WindowMask.Target>, keepOn: Set<Int>): List<WindowMask.Target> {
            up.values.removeAll { it.displayId !in keepOn && targets.none { t -> t.windowId == it.windowId } }
            return add(targets)
        }
        override fun add(targets: Collection<WindowMask.Target>): List<WindowMask.Target> {
            if (failing) return targets.toList()
            targets.forEach { up[it.windowId] = it }
            return emptyList()
        }
        override fun setHidden(hidden: Boolean) { hiddenEverywhere = hidden; hiddenDisplays = emptySet() }
        override fun setHiddenOn(displays: Set<Int>) { hiddenDisplays = displays }
        override fun forget(pkg: String) { up.values.removeAll { it.pkg == pkg } }
        override fun clear() = up.clear()
        override fun refreshPalette() = Unit
    }

    private val service = Robolectric.setupService(Service::class.java)
    private val vault get() = SecurityVault.getInstance(service)
    private var keyguardLocked = false
    private var windows: List<AccessibilityWindowInfo>? = null
    private var needsCover: (String) -> Boolean = { true }
    private val packages = HashMap<Int, String>()
    /** What was locked the usual way: app, why, display. */
    private val locked = mutableListOf<Triple<String, String, Int?>>()
    private val broughtForward = mutableListOf<String>()

    private val host = object : MaskController.Host {
        override fun windowList() = windows
        override fun knownPackage(windowId: Int) = packages[windowId]
        override fun packageOf(window: AccessibilityWindowInfo) = packages[window.id]
        override fun isCoverable(pkg: String) = pkg != service.packageName
        override fun isOwnWindow(window: AccessibilityWindowInfo) = false
        override fun isInteractive() = true
        override fun isKeyguardLocked() = keyguardLocked
        override fun needsCover(pkg: String) = needsCover.invoke(pkg)
        override fun isOwnScreenInFront(front: String?) = false
        override fun mayBe(target: String, pkg: String?) = target == pkg
        override fun lock(pkg: String, why: String, displayId: Int?) { locked += Triple(pkg, why, displayId) }
        override fun bringLockScreenForward(pkg: String, displayId: Int) { broughtForward += pkg }
        override fun guarded(what: String, block: () -> Unit) = block()
        override fun describe(t: Throwable) = t.toString()
    }

    private val shield by lazy {
        InstantShieldOverlay(service, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, goHome = {}, onTimeout = { _, _ -> false })
    }
    private lateinit var patches: FakePatches
    private val controller by lazy {
        MaskController(service, vault, shield, Handler(Looper.getMainLooper()), host) { onTapped -> FakePatches(onTapped).also { patches = it } }
    }

    @Before
    fun reset() {
        vault.awaitPendingWrites()
        service.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        vault.setSetupCompletedSync(true)
        vault.setGlobalLockEnabled(true)
        vault.setAppLock("com.example.bank", true)
        controller.isEmpty
    }

    @After
    fun tearDown() {
        setPrompt(false)
        controller.clear()
        controller.stop()
        shield.hide()
    }

    private fun window(id: Int, pkg: String, bounds: Rect, pip: Boolean = false, display: Int = Display.DEFAULT_DISPLAY): AccessibilityWindowInfo {
        packages[id] = pkg
        return AccessibilityWindowInfo.obtain().also { w ->
            shadowOf(w).apply {
                setId(id)
                setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                setDisplayId(display)
                setPictureInPicture(pip)
                setLayer(1)
                setBoundsInScreen(bounds)
            }
        }
    }

    private val small = Rect(0, 0, 300, 200)

    /** A fingerprint prompt up (with no window of its own to say which display: the phone's). */
    private fun setPrompt(up: Boolean) {
        val active = SecurityEngine::class.java.getDeclaredField("active").apply { isAccessible = true }
        if (!up) return active.set(SecurityEngine.INSTANCE, null)
        val attempt = Class.forName("${SecurityEngine::class.java.name}\$Attempt").declaredConstructors.single().apply { isAccessible = true }
        active.set(SecurityEngine.INSTANCE, attempt.newInstance("com.example.bank", WeakReference<Activity>(null), CancellationSignal()))
    }

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
        assertFalse(controller.isEmpty)
        assertTrue(patches.hiddenEverywhere)
        assertTrue(locked.isEmpty())
    }

    @Test
    fun aPromptHidesPatchesOnlyOnItsDisplay() {
        setPrompt(true)
        val onPhone = window(1, "com.example.bank", small, pip = true)
        val elsewhere = window(2, "com.example.bank", small, display = 2)
        controller.update(listOf(onPhone, elsewhere), full = true)
        // Stepped aside on the phone, where the prompt is; still up, and taking taps, on the other display.
        assertEquals(setOf(Display.DEFAULT_DISPLAY), patches.hiddenDisplays)
        assertTrue(patches.isMasking(2))
        assertFalse(patches.hiddenEverywhere)
    }

    @Test
    fun aPatchThatCantGoUpCoversTheAppTheUsualWay() {
        controller.isEmpty
        patches.failing = true
        controller.update(listOf(window(2, "com.example.bank", small, display = 2)), full = true)
        assertEquals(listOf(Triple("com.example.bank", "its window couldn't be masked", 2)), locked)
    }

    @Test
    fun aPictureInPicturePatchThatCantGoUpBringsTheAppOutToCoverIt() {
        controller.isEmpty
        patches.failing = true
        val pip = window(1, "com.example.bank", small, pip = true)
        windows = listOf(pip)
        controller.update(listOf(pip), full = false)
        // Covered at once while it's brought out of picture-in-picture.
        assertTrue(shield.isShowing)
        // It wouldn't come out: locked, under its patch.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals("com.example.bank", locked.single().first)
        assertEquals("in picture-in-picture without a session", locked.single().second)
    }

    @Test
    fun aCheckThatThrowsLocksTheApp() {
        controller.isEmpty
        patches.failing = true
        val pip = window(1, "com.example.bank", small, pip = true)
        windows = listOf(pip)
        controller.update(listOf(pip), full = false)
        needsCover = { throw IllegalStateException("window gone") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(listOf(Triple("com.example.bank", "in picture-in-picture", null as Int?)), locked)
    }

    @Test
    fun aTappedPatchAsksForItsApp() {
        controller.update(listOf(window(2, "com.example.bank", small, display = 2)), full = true)
        patches.onTapped(patches.up.getValue(2))
        assertEquals(listOf(Triple("com.example.bank", "its window was tapped", 2)), locked)
    }

    @Test
    fun armingPutsUpHiddenPatchesForPictureInPicture() {
        windows = listOf(window(1, "com.example.bank", small, pip = true), window(3, "com.example.chat", small, pip = true))
        controller.arm()
        assertTrue(patches.hiddenEverywhere)
        assertEquals(listOf("com.example.bank"), patches.up.values.map { it.pkg })
        assertTrue(patches.up.values.single().inPip)
    }
}
