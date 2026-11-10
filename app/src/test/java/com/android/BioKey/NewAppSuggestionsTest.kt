package com.android.BioKey

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.NewAppSuggestions
import com.android.BioKey.core.security.SecurityVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** An app just installed that nothing locked is offered once, and its Lock button locks it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NewAppSuggestionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = "com.example.newbank"
    private lateinit var vault: SecurityVault
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before
    fun reset() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        NewAppSuggestions.setEnabled(context, true)
        NewAppSuggestions.worker = java.util.concurrent.Executor { it.run() }
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // A launchable app.
        val pm = shadowOf(context.packageManager)
        val activity = ComponentName(app, "$app.Main")
        pm.addActivityIfNotPresent(activity)
        pm.addIntentFilterForActivity(activity, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    @Test
    fun anAppNothingLockedIsOffered() {
        NewAppSuggestions.onInstalled(context, app, "New Bank")
        val posted = notifications.allNotifications.single()
        assertEquals("Lock New Bank?", posted.extras.getCharSequence("android.title").toString())
        assertEquals("Lock", posted.actions.single().title.toString())
    }

    @Test
    fun twoAppsOfferedAtOnceNeverShareANotification() {
        // Their package names' hashes agree in the 15 bits the id was once made of.
        val pair = listOf("com.example.app1000", "com.example.app2331")
        assertEquals(pair[0].hashCode() and 0x7fff, pair[1].hashCode() and 0x7fff)
        for (pkg in pair) {
            val activity = ComponentName(pkg, "$pkg.Main")
            shadowOf(context.packageManager).addActivityIfNotPresent(activity)
            shadowOf(context.packageManager).addIntentFilterForActivity(activity, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
        NewAppSuggestions.onInstalled(context, pair[0], "First")
        NewAppSuggestions.onInstalled(context, pair[1], "Second")
        val titles = notifications.allNotifications.map { it.extras.getCharSequence("android.title").toString() }
        assertTrue(titles.toString(), "Lock First?" in titles && "Lock Second?" in titles)
        // Each Lock button locks its own app and takes down its own offer.
        NewAppSuggestions.LockReceiver().onReceive(
            context,
            Intent("com.android.BioKey.action.LOCK_NEW_APP").setData(Uri.fromParts("package", pair[0], null))
        )
        assertTrue(pair[0] in vault.selectedAppsFlow.value)
        assertTrue(pair[1] !in vault.selectedAppsFlow.value)
        val left = notifications.allNotifications.map { it.extras.getCharSequence("android.title").toString() }
        assertTrue(left.toString(), "Lock Second?" in left && "Lock First?" !in left)
    }

    @Test
    fun anAppAlreadyLockedIsNotOffered() {
        vault.setAppLock(app, true)
        NewAppSuggestions.onInstalled(context, app, "New Bank")
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test
    fun switchedOffNothingIsOffered() {
        NewAppSuggestions.setEnabled(context, false)
        NewAppSuggestions.onInstalled(context, app, "New Bank")
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test
    fun theLockButtonLocksItAndTheOfferGoes() {
        NewAppSuggestions.onInstalled(context, app, "New Bank")
        NewAppSuggestions.LockReceiver().onReceive(
            context,
            Intent("com.android.BioKey.action.LOCK_NEW_APP").setData(Uri.fromParts("package", app, null))
        )
        assertTrue(app in vault.selectedAppsFlow.value)
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test
    fun anOfferSwipedAwayIsNoLongerCounted() {
        val others = listOf("com.example.newchat", "com.example.newmail")
        for (pkg in others) {
            val activity = ComponentName(pkg, "$pkg.Main")
            shadowOf(context.packageManager).addActivityIfNotPresent(activity)
            shadowOf(context.packageManager).addIntentFilterForActivity(activity, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
        NewAppSuggestions.onInstalled(context, app, "New Bank")
        NewAppSuggestions.onInstalled(context, others[0], "New Chat")
        NewAppSuggestions.onInstalled(context, others[1], "New Mail")
        assertEquals("3 new apps to lock?", summaryTitle())
        // Swiped away: Android removes it and sends its delete intent.
        val manager = context.getSystemService(NotificationManager::class.java)
        val bank = manager.activeNotifications.single { it.notification.extras.getCharSequence("android.title").toString() == "Lock New Bank?" }
        manager.cancel(bank.tag, bank.id)
        NewAppSuggestions.LockReceiver().onReceive(
            context,
            Intent("com.android.BioKey.action.NEW_APP_DISMISSED").setData(Uri.fromParts("package", app, null))
        )
        assertEquals("2 new apps to lock?", summaryTitle())
        assertTrue(app !in vault.selectedAppsFlow.value)
    }

    private fun summaryTitle() = context.getSystemService(NotificationManager::class.java).activeNotifications
        .single { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0 }
        .notification.extras.getCharSequence("android.title").toString()
}
