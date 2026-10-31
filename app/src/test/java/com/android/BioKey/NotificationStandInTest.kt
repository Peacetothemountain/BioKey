package com.android.BioKey

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService
import com.android.BioKey.feature.notification.QuietRestore
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What a locked app's stand-in says (worded as Android's own App lock words a locked app's
 * notification, never anything of the app's), which notifications are never hidden, and that
 * hidden notifications come back without alerting a second time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationStandInTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val chat = "com.example.chat"
    private val bank = "com.example.bank"
    private lateinit var vault: SecurityVault
    private lateinit var shield: BioKeyNotificationShieldService
    private var nextId = 0

    @Before
    fun start() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        LockStateTracker.lockAll()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        vault.setNotificationShield(true)
        vault.setTimeoutMode(LockTimeoutMode.ON_SCREEN_OFF)
        vault.setAppLock(chat, true)
        vault.setAppLock(bank, true)
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = chat
                applicationInfo = ApplicationInfo().apply {
                    packageName = chat
                    nonLocalizedLabel = "Chat"
                }
            }
        )
        shield = Robolectric.buildService(BioKeyNotificationShieldService::class.java).create().get()
        shield.onListenerConnected()
        idle()
    }

    @After
    fun stop() {
        shield.onListenerDisconnected()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun builder() = Notification.Builder(context, "any").setSmallIcon(android.R.drawable.ic_dialog_email)

    private fun message(text: String = "Are you there?"): Notification = builder()
        .setStyle(
            Notification.MessagingStyle(Person.Builder().setName("Me").build())
                .addMessage(text, 1L, Person.Builder().setName("Ann").build())
        )
        .build()

    private fun plain(): Notification = builder().setContentTitle("Your parcel is on its way").build()

    /** [pkg] posts [notification], and the shield is told, as Android would. Returns its key. */
    private fun post(pkg: String, notification: Notification): String {
        val key = shadowOf(shield).addActiveNotification(pkg, ++nextId, notification)
        shield.onNotificationPosted(shield.activeNotifications.single { it.key == key })
        idle()
        return key
    }

    private fun standIn(pkg: String): Notification? =
        shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification("biokey-shield:$pkg", 1)

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun aHiddenMessageReadsAsAndroidsOwnWithTheAppsName() {
        post(chat, message())
        val standIn = checkNotNull(standIn(chat))
        assertEquals("Chat", standIn.title())
        assertEquals("New message", standIn.text())
        // Where the content is hidden (the phone's lock screen), still no app named.
        val public = checkNotNull(standIn.publicVersion)
        assertEquals("Locked app", public.title())
        assertEquals("Notification hidden by BioKey", public.text())
    }

    @Test
    fun severalAreCountedAndWordedByWhatTheyAll() {
        post(chat, message())
        post(chat, message("Hello?"))
        assertEquals("2 new messages", standIn(chat)?.text())
        assertEquals(2, standIn(chat)?.number)
        // One that isn't a message: they're notifications.
        post(chat, plain())
        assertEquals("3 new notifications", standIn(chat)?.text())
        post(bank, plain())
        assertEquals("New notification", standIn(bank)?.text())
    }

    @Test
    fun aMessageCategoryWithoutTheStyleIsAMessage() {
        post(chat, builder().setCategory(Notification.CATEGORY_MESSAGE).setContentTitle("Ann").build())
        assertEquals("New message", standIn(chat)?.text())
    }

    @Test
    fun aNotificationAndroidsAppLockRedactedIsStillHiddenAndWordedOnce() {
        // As a listener gets a message from an app Android's own App lock locks: the app's name,
        // "New message", no style, the category kept, shown on the lock screen.
        val redacted = builder()
            .setContentTitle("Chat")
            .setContentText("New message")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        post(chat, redacted)
        assertEquals("Chat", standIn(chat)?.title())
        assertEquals("New message", standIn(chat)?.text())
        // Its "New notification" for anything else.
        post(bank, builder().setContentTitle("Bank").setContentText("New notification").build())
        assertEquals("New notification", standIn(bank)?.text())
        // A call it redacted keeps its category, and shows: the stand-in doesn't count it.
        post(chat, builder().setContentTitle("Chat").setContentText("New notification").setCategory(Notification.CATEGORY_CALL).build())
        assertEquals("New message", standIn(chat)?.text())
    }

    @Test
    fun aCallStyledCallAlwaysShowsWhateverItsCategory() {
        val open = PendingIntent.getActivity(context, 0, Intent("answer"), PendingIntent.FLAG_IMMUTABLE)
        val call = builder()
            .setStyle(Notification.CallStyle.forIncomingCall(Person.Builder().setName("Ann").build(), open, open))
            .build()
        // No category: only its style says it's a call.
        assertNull(call.category)
        post(chat, call)
        assertNull(standIn(chat))
    }

    @Test
    fun hiddenNotificationsComeBackWithoutAlertingAgain() {
        val key = post(chat, message())
        assertNotNull(standIn(chat))
        assertEquals(0, shield.currentListenerHints)
        // Unlocked: notification sounds and vibration are held back while they come back...
        LockStateTracker.onAppUnlocked(chat)
        idle()
        assertNull(standIn(chat))
        assertEquals(NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS, shield.currentListenerHints)
        // ...another app's notification meanwhile doesn't end it...
        post(bank, plain())
        assertEquals(NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS, shield.currentListenerHints)
        // ...and once it's back, they aren't.
        shield.onNotificationPosted(shield.activeNotifications.single { it.key == key })
        idle()
        assertEquals(0, shield.currentListenerHints)
        // Back in the shade, not hidden again.
        assertNull(standIn(chat))
    }

    @Test
    fun theHoldIsLiftedWithinSecondsShouldOneNeverComeBack() {
        post(chat, message())
        LockStateTracker.onAppUnlocked(chat)
        idle()
        assertEquals(NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS, shield.currentListenerHints)
        // Withdrawn while hidden: never posted again.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(0, shield.currentListenerHints)
    }

    @Test
    fun quietRestoreAsksOnceAndLiftsWhenAllAreBack() {
        val asked = mutableListOf<Boolean>()
        val quiet = QuietRestore { asked += it }
        quiet.begin(listOf("a", "b"))
        quiet.begin(listOf("c"))
        assertEquals(listOf(true), asked)
        quiet.onPosted("a")
        quiet.onPosted("other")
        quiet.onPosted("b")
        assertTrue(quiet.isQuiet)
        quiet.onPosted("c")
        assertFalse(quiet.isQuiet)
        assertEquals(listOf(true, false), asked)
        // Nothing awaited: nothing to lift again.
        quiet.onPosted("c")
        quiet.end()
        assertEquals(listOf(true, false), asked)
        // Unbound: Android has dropped the hint, so nothing is asked.
        quiet.begin(listOf("d"))
        quiet.forget()
        assertFalse(quiet.isQuiet)
        assertEquals(listOf(true, false, true), asked)
    }
}
