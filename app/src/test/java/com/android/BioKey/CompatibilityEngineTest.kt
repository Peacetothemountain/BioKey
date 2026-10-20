package com.android.BioKey

import android.app.Application
import android.app.NotificationManager
import android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
import android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
import android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.feature.lock.InstantShieldOverlay
import com.android.BioKey.feature.lock.UsageWatchService
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
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowUsageStatsManager

/**
 * Compatibility mode end to end, from usage events to what it covers: every app that comes to the
 * screen counts, not only the newest; an unlocked app stays in use while it shows; nothing floating
 * over an unlocked app asks for authentication.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompatibilityEngineTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val bank = "com.example.bank"
    private val chat = "com.example.chat"
    private lateinit var vault: SecurityVault
    private lateinit var engine: ServiceController<UsageWatchService>
    private lateinit var worker: Looper

    @Before
    fun start() {
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        ShadowSettings.setCanDrawOverlays(true)
        LockStateTracker.lockAll()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        vault.setAppLock(bank, true)
        DiagnosticLog.clear()
        engine = Robolectric.buildService(UsageWatchService::class.java).create()
        worker = (UsageWatchService::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(engine.get()) as HandlerThread).looper
        step(200)
    }

    @After
    fun stop() {
        engine.destroy()
        // The engine asked which apps are launchers and pickers: leave no cached answer to other tests.
        for (name in listOf("launchersCheckedAt", "pickersCheckedAt")) {
            ForegroundRules::class.java.getDeclaredField(name).apply { isAccessible = true }.setLong(null, 0L)
        }
    }

    /** Lets [ms] pass: polls run, and what they read is acted on. */
    private fun step(ms: Long = 200) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        shadowOf(worker).idle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun event(type: Int, pkg: String, cls: String = "$pkg.MainActivity") {
        val usage = context.getSystemService(UsageStatsManager::class.java)
        shadowOf(usage).addEvent(
            ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setPackage(pkg).setClass(cls).setEventType(type).setTimeStamp(System.currentTimeMillis())
                .build()
        )
        // Usage access stamps events with the wall clock (not the test clock): keep them apart.
        Thread.sleep(2)
    }

    private fun covered(pkg: String) = DiagnosticLog.entries().map { it.text }.filter { it.startsWith("Covered $pkg ") }

    @Test
    fun aLockedAppIsCovered() {
        event(ACTIVITY_RESUMED, bank)
        step()
        assertEquals(listOf("Covered $bank (opened)"), covered(bank))
    }

    @Test
    fun splitScreenCoversTheLockedHalfEvenWhenTheOtherOpenedLast() {
        event(ACTIVITY_RESUMED, bank)
        event(ACTIVITY_RESUMED, chat)
        step()
        assertEquals(listOf("Covered $bank (on screen beside another app)"), covered(bank))
    }

    @Test
    fun aDialogOverALockedAppAtLaunchStillCoversIt() {
        event(ACTIVITY_RESUMED, bank)
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, "com.google.android.permissioncontroller", "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity")
        step()
        assertEquals(listOf("Covered $bank (opened under a dialog)"), covered(bank))
    }

    @Test
    fun anAppThatHandsStraightOverToAnotherIsNotCovered() {
        event(ACTIVITY_RESUMED, bank)
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, chat)
        step()
        event(ACTIVITY_STOPPED, bank)
        repeat(8) { step() }
        assertTrue(covered(bank).isEmpty())
    }

    @Test
    fun anAppLeftShowingUnderASeeThroughOneIsCovered() {
        event(ACTIVITY_RESUMED, bank)
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_RESUMED, chat)
        repeat(8) { step() }
        assertEquals(listOf("Covered $bank (still showing under another app)"), covered(bank))
    }

    @Test
    fun googlePasswordManagerInsidePlayServicesIsCovered() {
        vault.setAppLock(PasswordManager.VIRTUAL_PACKAGE, true)
        event(ACTIVITY_RESUMED, PasswordManager.GMS, "com.google.android.gms.credential.manager.PasswordManagerActivity")
        step()
        assertEquals(listOf("Covered ${PasswordManager.VIRTUAL_PACKAGE} (opened)"), covered(PasswordManager.VIRTUAL_PACKAGE))
    }

    @Test
    fun aPasskeySheetOverAnUnlockedAppAsksForNothing() {
        vault.setAppLock(PasswordManager.VIRTUAL_PACKAGE, true)
        event(ACTIVITY_RESUMED, chat)
        step()
        event(ACTIVITY_PAUSED, chat)
        event(ACTIVITY_RESUMED, PasswordManager.CREDENTIAL_MANAGER, "${PasswordManager.CREDENTIAL_MANAGER}.ui.PasskeyBottomSheetActivity")
        repeat(8) { step() }
        assertTrue(DiagnosticLog.entries().none { it.text.startsWith("Covered") })
    }

    @Test
    fun anUnlockedAppBesideAnotherStaysUnlockedUntilItStops() {
        event(ACTIVITY_RESUMED, bank)
        step()
        LockStateTracker.onAppUnlocked(bank)
        event(ACTIVITY_RESUMED, bank) // Back from the lock screen.
        step()
        event(ACTIVITY_RESUMED, chat) // Split screen: the bank stays resumed beside it.
        repeat(3) { step() }
        assertTrue(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
        event(ACTIVITY_PAUSED, bank)
        event(ACTIVITY_STOPPED, bank)
        step()
        assertFalse(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.IMMEDIATE))
    }

    @Test
    fun aLockedAppLeftUncoveredInFrontIsCoveredAgain() {
        event(ACTIVITY_RESUMED, bank)
        step()
        // A lock screen on its way out takes the cover down and gives up, the app still in front.
        InstantShieldOverlay.dismiss(bank)
        LockStateTracker.markLockAbandoned(bank)
        step(LockStateTracker.ABANDONED_LOCK_COOLDOWN_MS + 100)
        repeat(3) { step() }
        assertEquals(listOf("Covered $bank (opened)", "Covered $bank (still in front, uncovered)"), covered(bank))
    }

    @Test
    fun nothingIsCoveredOverAScreensaver() {
        event(ACTIVITY_RESUMED, bank)
        step()
        LockStateTracker.onAppUnlocked(bank)
        event(ACTIVITY_RESUMED, bank)
        step()
        context.sendBroadcast(Intent(Intent.ACTION_DREAMING_STARTED))
        repeat(3) { step() }
        assertEquals(1, covered(bank).size)
        assertFalse(LockStateTracker.isAppUnlocked(bank, LockTimeoutMode.GRACE_5_MINUTES))
    }

    @Test
    fun losingDisplayOverOtherAppsStopsTheEngineWithAnAlert() {
        LockStateTracker.onAppUnlocked(chat)
        ShadowSettings.setCanDrawOverlays(false)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        step()
        assertTrue(shadowOf(engine.get()).isStoppedBySelf)
        assertFalse(LockStateTracker.isAppUnlocked(chat, LockTimeoutMode.ON_SCREEN_OFF))
        val alert = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
            .map { it.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString() }
        assertTrue(alert.toString(), alert.any { "lost display over other apps" in it })
    }
}
