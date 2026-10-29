package com.android.BioKey

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.feature.notification.LockAllSweeps
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * The notification shield after every session ends: whatever a locked app left in the shade
 * (during its session, or a pause) is hidden again, however many sweeps skipped it before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationShieldSweepTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val chat = "com.example.chat"
    private val bank = "com.example.bank"
    private lateinit var vault: SecurityVault
    private lateinit var shield: BioKeyNotificationShieldService

    @Before
    fun start() {
        // As the other test classes do: whatever the last vault was writing lands first.
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        LockStateTracker.lockAll()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        // On, as the owner turns it on (it's off until they agree to notification access).
        vault.setNotificationShield(true)
        // Sessions last until the screen goes off: one can still be going when another app's ends.
        vault.setTimeoutMode(LockTimeoutMode.ON_SCREEN_OFF)
        vault.setAppLock(chat, true)
        vault.setAppLock(bank, true)
        shield = Robolectric.buildService(BioKeyNotificationShieldService::class.java).create().get()
        shield.onListenerConnected()
        idle()
    }

    @After
    fun stop() {
        shield.onListenerDisconnected()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** [pkg] posts a message, and the shield is told, as Android would. */
    private fun post(pkg: String) {
        val message = Notification.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("New message")
            .build()
        shadowOf(shield).addActiveNotification(pkg, 1, message)
        shield.onNotificationPosted(shield.activeNotifications.single { it.packageName == pkg })
        idle()
    }

    /** [pkg]'s "hidden notifications" stand-in, if its notifications are hidden. */
    private fun standIn(pkg: String): Notification? =
        shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification("biokey-shield:$pkg", 1)

    @Test
    fun aMessageLeftShowingDuringASessionHidesWhenThePhoneLocks() {
        LockStateTracker.onAppUnlocked(chat)
        LockStateTracker.onAppVisible(chat)
        idle()
        post(chat)
        assertNull(standIn(chat))
        // Another app's session ends: a sweep, which leaves the chat app's message alone (its session).
        LockStateTracker.onAppUnlocked(bank)
        LockStateTracker.lockPackage(bank)
        idle()
        assertNull(standIn(chat))
        // The phone locks: every session ends, and the message hides.
        LockStateTracker.onScreenTurnedOff()
        idle()
        assertNotNull(standIn(chat))
    }

    @Test
    fun aMessageLeftShowingDuringAPauseHidesWhenProtectionResumes() {
        vault.pauseProtection(30 * 60_000L)
        idle()
        post(chat)
        // The screen goes off during the pause: a sweep with nothing to hide.
        LockStateTracker.onScreenTurnedOff()
        idle()
        assertNull(standIn(chat))
        vault.resumeProtection()
        idle()
        assertNotNull(standIn(chat))
    }

    @Test
    fun aMessageFromAnAppUnlockedBetweenTwoLockAllsHidesAtTheSecond() {
        // The first screen-off sweeps a shade with nothing to hide.
        LockStateTracker.onScreenTurnedOff()
        idle()
        LockStateTracker.onAppUnlocked(chat)
        LockStateTracker.onAppVisible(chat)
        idle()
        post(chat)
        assertNull(standIn(chat))
        // Moments later, the second: an app was unlocked since, so it sweeps too.
        LockStateTracker.onScreenTurnedOff()
        idle()
        assertNotNull(standIn(chat))
    }

    @Test
    fun aLockAllMomentsAfterASweepWithNothingNewSkipsItsOwnButNotForLong() {
        // Android always tells the shield what is posted; one it wasn't told of stands for
        // whatever a skipped sweep would have found.
        val message = Notification.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("New message")
            .build()
        shadowOf(shield).addActiveNotification(chat, 1, message)
        LockStateTracker.onScreenTurnedOff()
        idle()
        assertNull(standIn(chat))
        // A minute on, a lock-all sweeps whatever the bookkeeping says.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        LockStateTracker.onScreenTurnedOff()
        idle()
        assertNotNull(standIn(chat))
    }

    @Test
    fun aLockAllSkipsItsSweepOnlyWhenNothingCouldHaveShownSince() {
        val sweeps = LockAllSweeps(skipWindowMs = 60_000L)
        // Nothing known yet: sweep.
        assertTrue(sweeps.needsSweep(now = 1_000L))
        sweeps.onSwept(leftShowing = false, now = 1_000L)
        assertFalse(sweeps.needsSweep(now = 2_000L))
        // Not for long, nor with a clock that went back.
        assertTrue(sweeps.needsSweep(now = 61_000L))
        assertTrue(sweeps.needsSweep(now = 500L))
        // Something shown since (an unlock, a restore, a message): sweep.
        sweeps.onShowing()
        assertTrue(sweeps.needsSweep(now = 2_000L))
        // A sweep that left a locked app's message showing (its session): the next one sweeps.
        sweeps.onSwept(leftShowing = true, now = 3_000L)
        assertTrue(sweeps.needsSweep(now = 3_500L))
    }

    @Test
    fun aStandInIsOnlyPostedAgainWhenItsCountChangesOrItWasDismissed() {
        post(chat)
        val shown = standIn(chat)
        assertNotNull(shown)
        val manager = context.getSystemService(NotificationManager::class.java)
        // Taken away without the shield being told: the same count posts nothing.
        manager.cancel("biokey-shield:$chat", 1)
        shield.onNotificationPosted(shield.activeNotifications.single { it.packageName == chat })
        idle()
        assertNull(standIn(chat))
        // Dismissed by the owner (the shield is told): the next hidden notification brings it back.
        val standInSbn = StatusBarNotification(
            context.packageName, context.packageName, 1, "biokey-shield:$chat",
            Process.myUid(), 0, 0, shown, Process.myUserHandle(), System.currentTimeMillis()
        )
        shield.onNotificationRemoved(standInSbn, null, NotificationListenerService.REASON_CANCEL)
        shield.onNotificationPosted(shield.activeNotifications.single { it.packageName == chat })
        idle()
        assertNotNull(standIn(chat))
    }
}
