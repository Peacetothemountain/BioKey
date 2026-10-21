package com.android.BioKey

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
}
