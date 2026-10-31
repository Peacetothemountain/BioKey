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
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
    fun missedCallsAreNotificationsEvenStyledAsMessages() {
        // Google's Phone app posts missed calls in a messaging style.
        val missed = { builder().setCategory(Notification.CATEGORY_MISSED_CALL).setStyle(
            Notification.MessagingStyle(Person.Builder().setName("Me").build())
                .addMessage("Missed call", 1L, Person.Builder().setName("Ann").build())
        ).build() }
        post(chat, missed())
        assertEquals("New notification", standIn(chat)?.text())
        post(chat, missed())
        assertEquals("2 new notifications", standIn(chat)?.text())
    }

    /** A notification [pkg] has posted in [group], whose posting the shield never hears of. */
    private fun unseen(pkg: String, group: String, summary: Boolean = false): StatusBarNotification {
        val notification = builder().setGroup(group).setGroupSummary(summary).setContentTitle("Missed call").build()
        val key = shadowOf(shield).addActiveNotification(pkg, ++nextId, notification)
        return shield.activeNotifications.single { it.key == key }
    }

    @Test
    fun notificationsAndroidSnoozesWithAHiddenSummaryAreCountedAndComeBackWithIt() {
        // The summary is hidden: Android snoozes the group's children with it, and only says
        // they were snoozed.
        val summaryKey = post(chat, builder().setGroup("missed").setGroupSummary(true).setContentTitle("2 missed calls").build())
        assertEquals("New notification", standIn(chat)?.text())
        val first = unseen(chat, "missed")
        val second = unseen(chat, "missed")
        shield.onNotificationRemoved(first, null, NotificationListenerService.REASON_SNOOZED)
        shield.onNotificationRemoved(second, null, NotificationListenerService.REASON_SNOOZED)
        assertEquals("2 new notifications", standIn(chat)?.text())
        // The owner's own snooze, of another group (shown while the app was unlocked, say): not the shield's.
        shield.onNotificationRemoved(unseen(chat, "other"), null, NotificationListenerService.REASON_SNOOZED)
        assertEquals("2 new notifications", standIn(chat)?.text())
        // Unlocked: all three are brought back, and the hold on sounds lasts until all three are.
        LockStateTracker.onAppUnlocked(chat)
        idle()
        assertNull(standIn(chat))
        for (key in listOf(summaryKey, first.key)) {
            shield.onNotificationPosted(shield.activeNotifications.single { it.key == key })
            assertEquals(NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS, shield.currentListenerHints)
        }
        shield.onNotificationPosted(second)
        assertEquals(0, shield.currentListenerHints)
    }

    @Test
    fun whatIsStillSnoozedOnAnAppsTrailComesBack() {
        val user = Process.myUserHandle()
        fun sbn(pkg: String, id: Int, group: String?) = StatusBarNotification(
            pkg, pkg, id, null, Process.myUid(), 0, 0,
            builder().setGroup(group).build(), user, 0L
        )
        val known = sbn(chat, 1, "missed")
        val snoozedWithIt = sbn(chat, 2, "missed")
        val postedAgain = sbn(chat, 3, null)
        val ownersOwn = sbn(chat, 4, "other")
        val anotherApp = sbn(bank, 5, "missed")
        val found = BioKeyNotificationShieldService.stillSnoozedOnTrail(
            chat,
            trailKeys = setOf(known.key, postedAgain.key),
            trailGroups = setOf(known.groupKey),
            known = listOf(known.key),
            snoozed = listOf(known, snoozedWithIt, postedAgain, ownersOwn, anotherApp)
        )
        assertEquals(listOf(snoozedWithIt.key, postedAgain.key), found)
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
