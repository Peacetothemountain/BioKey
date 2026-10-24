package com.android.BioKey

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
import com.android.BioKey.feature.main.MainActivity
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
 * The accessibility engine and BioKey's own session: a locked app's lock screen it brings up over
 * BioKey's own screen, beside it in split screen, is a trip BioKey starts itself; an app that
 * comes to the front by itself is the owner leaving BioKey.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityOwnTripTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val bank = "com.example.bank"
    private lateinit var vault: SecurityVault
    private lateinit var engine: ServiceController<BioKeyAccessibilityService>

    /** Robolectric's screen is 320 by 470: the two halves of a split screen, and the whole of it. */
    private val top = Rect(0, 0, 320, 230)
    private val bottom = Rect(0, 240, 320, 470)
    private val whole = Rect(0, 0, 320, 470)

    @Before
    fun start() {
        // Robolectric's clock starts again for each test; a lock screen "resumed" in an earlier one
        // in this JVM would otherwise look newer than anything started here.
        LockScreenActivity::class.java.getDeclaredField("resumedAt").apply { isAccessible = true }.setLong(null, 0L)
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        LockStateTracker.lockAll()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        vault.setAppLock(bank, true)
        DiagnosticLog.clear()
        engine = Robolectric.buildService(BioKeyAccessibilityService::class.java).create()
        BioKeyAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(engine.get())
        idle()
    }

    @After
    fun stop() {
        // Not set up any more: stopping hands nothing over to compatibility mode.
        vault.setSetupCompletedSync(false)
        engine.destroy()
        lockScreenUp(null)
        ForegroundRules.resetSystemLists()
    }

    private fun idle(ms: Long = 500) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** An app's window: [pkg] is read from it, or (null) it can't be read, as BioKey's own can't. */
    private fun window(id: Int, pkg: String?, bounds: Rect, active: Boolean): AccessibilityWindowInfo =
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

    /** [pkg]'s screen [cls] came to the front, as its window event says. */
    private fun windowEvent(pkg: String, cls: String = "$pkg.MainActivity") {
        engine.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = pkg
            className = cls
        })
        idle()
    }

    /** The app BioKey's lock screen is up and in front for (null: none), as the real activity reports it. */
    private fun lockScreenUp(pkg: String?) {
        LockScreenActivity::class.java.getDeclaredField("activeLockedTarget").apply { isAccessible = true }.set(null, pkg)
        LockScreenActivity::class.java.getDeclaredField("isLockScreenResumed").apply { isAccessible = true }.setBoolean(null, pkg != null)
    }

    /** BioKey's screen stops, as MainActivity.onStop decides. */
    private fun bioKeysScreenStops() {
        if (LockStateTracker.isOwnTripActive()) LockStateTracker.onOwnAppStopped() else LockStateTracker.lockOwnApp()
    }

    /** BioKey's screen comes back, as MainActivity.onResume decides. */
    private fun bioKeysScreenResumes() {
        LockStateTracker.isOwnLockScreenHopActive()
        if (LockStateTracker.isOwnAppUnlocked()) LockStateTracker.onOwnAppResumed()
    }

    private fun covered(pkg: String) = DiagnosticLog.entries().map { it.text }.filter { it.startsWith("Covered $pkg ") }

    @Test
    fun aLockedAppBesideBioKeyCoveredAsProtectionResumesLeavesBioKeyUnlocked() {
        // BioKey, unlocked, beside the bank, unlocked too, in split screen; the owner is in BioKey.
        LockStateTracker.onOwnAppUnlocked()
        LockStateTracker.onAppUnlocked(bank)
        onScreen(window(1, null, top, active = true), window(2, bank, bottom, active = false))
        windowEvent(context.packageName, MainActivity::class.java.name)
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
        // Resume protection, tapped in BioKey: every session but BioKey's ends, and the bank is covered.
        LockStateTracker.lockAll(includeOwnApp = false)
        idle()
        assertEquals(listOf("Covered $bank (on screen without a session)"), covered(bank))
        // Its lock screen comes up over both, and BioKey's screen stops under it.
        lockScreenUp(bank)
        InstantShieldOverlay.dismiss(bank)
        onScreen(window(3, null, whole, active = true))
        bioKeysScreenStops()
        idle()
        assertTrue(LockStateTracker.isOwnAppUnlocked())
        // Unlocked: the pair is back, the bank's window saying so before BioKey's screen has resumed.
        LockStateTracker.onAppUnlocked(bank)
        lockScreenUp(null)
        onScreen(window(1, null, top, active = true), window(2, bank, bottom, active = false))
        windowEvent(bank)
        bioKeysScreenResumes()
        assertTrue(LockStateTracker.hasActiveOwnerSession())
        assertFalse(LockStateTracker.isOwnTripActive())
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun aLockedAppThatComesToTheFrontOverBioKeyIsTheOwnerLeavingIt() {
        LockStateTracker.onOwnAppUnlocked()
        onScreen(window(1, null, whole, active = true))
        windowEvent(context.packageName, MainActivity::class.java.name)
        // A locked app's notification is tapped: it opens over BioKey, whose screen hasn't stopped yet.
        onScreen(window(2, bank, whole, active = true))
        windowEvent(bank)
        assertEquals(listOf("Covered $bank (opened)"), covered(bank))
        assertFalse(LockStateTracker.isOwnTripActive())
        bioKeysScreenStops()
        assertFalse(LockStateTracker.isOwnAppUnlocked())
    }
}
