package com.android.BioKey

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.os.PowerManager
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * "Lock apps when the phone locks": sessions go on through a screen-off Android doesn't follow
 * with a lock, end once it's seen locked (the app left on screen covered behind the keyguard), and
 * end whenever BioKey can't tell. Off (the default), the screen turning off ends them, as always.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneLockRelockTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val x = "com.example.notes"
    private val mode = LockTimeoutMode.GRACE_5_MINUTES
    private lateinit var vault: SecurityVault

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
        LockStateTracker.onDeviceLockState(false)
    }

    @After
    fun stop() {
        vault.setRelockAtPhoneLock(false)
        vault.setSetupCompletedSync(false)
        LockScreenActivity.setStateForTests(null)
    }

    @Test
    fun byDefaultTheScreenTurningOffLocksEverything() {
        LockStateTracker.onAppUnlocked(x)
        LockStateTracker.onScreenTurnedOff(phoneLocked = false)
        assertFalse(LockStateTracker.isAppUnlocked(x, mode))
    }

    @Test
    fun aScreenOffWithoutALockKeepsSessionsUntilThePhoneLocks() {
        vault.setRelockAtPhoneLock(true)
        LockStateTracker.onAppUnlocked(x)
        LockStateTracker.onScreenTurnedOff(phoneLocked = false)
        assertTrue(LockStateTracker.isAppUnlocked(x, mode))
        assertTrue(LockStateTracker.awaitingPhoneLock)
        // BioKey itself still locks with the screen.
        assertFalse(LockStateTracker.isOwnAppUnlocked())
        LockStateTracker.onDeviceLockState(true)
        assertFalse(LockStateTracker.isAppUnlocked(x, mode))
        assertFalse(LockStateTracker.awaitingPhoneLock)
    }

    @Test
    fun aPhoneAlreadyLockedOrOneThatCantBeAskedLocksEverything() {
        vault.setRelockAtPhoneLock(true)
        LockStateTracker.onAppUnlocked(x)
        LockStateTracker.onScreenTurnedOff(phoneLocked = true)
        assertFalse(LockStateTracker.isAppUnlocked(x, mode))
        LockStateTracker.onDeviceLockState(false)
        LockStateTracker.onAppUnlocked(x)
        LockStateTracker.onScreenTurnedOff(phoneLocked = null)
        assertFalse(LockStateTracker.isAppUnlocked(x, mode))
    }

    @Test
    fun aScreensaverStillLocksEverything() {
        vault.setRelockAtPhoneLock(true)
        LockStateTracker.onAppUnlocked(x)
        LockStateTracker.onScreenTurnedOff()
        assertFalse(LockStateTracker.isAppUnlocked(x, mode))
    }

    @Test
    fun backOnBeforeThePhoneLockedTheSessionGoesOn() {
        vault.setRelockAtPhoneLock(true)
        LockStateTracker.onAppUnlocked(x)
        LockStateTracker.onScreenTurnedOff(phoneLocked = false)
        LockStateTracker.onScreenOn(phoneLocked = false)
        assertTrue(LockStateTracker.isAppUnlocked(x, mode))
    }

    @Test
    fun unlockedWithoutBeingSeenNotLockedFailsTowardLocking() {
        // A fingerprint on the dark screen: the phone locked and was unlocked again, unseen.
        vault.setRelockAtPhoneLock(true)
        LockStateTracker.onAppUnlocked(x)
        LockStateTracker.onScreenTurnedOff(phoneLocked = false)
        LockStateTracker.onScreenOn(phoneLocked = false)
        LockStateTracker.onUserPresent()
        assertFalse(LockStateTracker.isAppUnlocked(x, mode))
    }

    @Test
    fun smartLockKeepingThePhoneUnlockedUnderItsKeyguardKeepsTheSession() {
        vault.setRelockAtPhoneLock(true)
        LockStateTracker.onAppUnlocked(x)
        LockStateTracker.onScreenTurnedOff(phoneLocked = false)
        // In the dark: the keyguard up, the phone not locked under it.
        LockStateTracker.onDeviceLockState(false) { true }
        LockStateTracker.onScreenOn(phoneLocked = false)
        LockStateTracker.onUserPresent()
        assertTrue(LockStateTracker.isAppUnlocked(x, mode))
    }

    @Test
    fun theAccessibilityEngineCoversTheAppLeftOnScreenOnceThePhoneLocksInTheDark() {
        vault.setRelockAtPhoneLock(true)
        ForegroundRules.resetSystemLists()
        val engine: ServiceController<BioKeyAccessibilityService> = Robolectric.buildService(BioKeyAccessibilityService::class.java).create()
        try {
            BioKeyAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(engine.get())
            idle()
            val power = shadowOf(context.getSystemService(PowerManager::class.java))
            val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
            power.setIsInteractive(true)
            keyguard.setKeyguardLocked(false)
            keyguard.setIsDeviceLocked(false)
            // Notes is open and unlocked.
            LockStateTracker.onAppUnlocked(x)
            shadowOf(engine.get()).setWindowsOnDisplay(Display.DEFAULT_DISPLAY, listOf(AccessibilityWindowInfo.obtain().also { w ->
                shadowOf(w).apply {
                    setId(2)
                    setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                    setDisplayId(Display.DEFAULT_DISPLAY)
                    setBoundsInScreen(Rect(0, 0, 320, 470))
                    setActive(true)
                    setFocused(true)
                    setRoot(AccessibilityNodeInfo.obtain().apply { packageName = x })
                }
            }))
            engine.get().onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                packageName = x
                className = "$x.MainActivity"
            })
            idle()
            while (shadowOf(context).nextStartedActivity != null) Unit
            // The screen times out; Android's own lock delay hasn't run out yet.
            power.setIsInteractive(false)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            idle(3_000)
            assertTrue(LockStateTracker.isAppUnlocked(x, mode))
            // Then Android locks the phone, the screen still off.
            keyguard.setKeyguardLocked(true)
            keyguard.setIsDeviceLocked(true)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            idle(3_000)
            assertFalse(LockStateTracker.isAppUnlocked(x, mode))
            assertTrue(DiagnosticLog.entries().any { it.text == "Covered $x (on screen as the phone locked)" })
            val started = generateSequence { shadowOf(context).nextStartedActivity }.toList()
            assertTrue(started.any {
                it.component?.className == LockScreenActivity::class.java.name && it.getBooleanExtra(LockScreenActivity.EXTRA_BEHIND_KEYGUARD, false)
            })
        } finally {
            engine.destroy()
            ForegroundRules.resetSystemLists()
        }
    }

    private fun idle(ms: Long = 500) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}
