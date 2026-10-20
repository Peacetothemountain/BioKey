package com.android.BioKey

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.feature.lock.LockScreenActivity
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService.CachedPendingIntent
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Which hidden notification a stand-in tap opens once its app is unlocked. Only the app's own
 * link to one of its screens is sent; anything else opens the app itself, so the new session
 * always reaches the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationDeepLinkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val chat = "com.example.chat"
    private var requestCode = 0

    private fun activityLink(creator: String = chat): PendingIntent =
        PendingIntent.getActivity(context, requestCode++, Intent("open"), PendingIntent.FLAG_IMMUTABLE)
            .also { shadowOf(it).setCreatorPackage(creator) }

    private fun broadcastLink(creator: String = chat): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode++, Intent("open"), PendingIntent.FLAG_IMMUTABLE)
            .also { shadowOf(it).setCreatorPackage(creator) }

    private fun cache(vararg links: CachedPendingIntent) = links.withIndex().associate { (i, link) -> "key$i" to link }

    @Test
    fun opensTheNewestNotificationOfTheAppItself() {
        val older = activityLink()
        val newer = activityLink()
        val summary = activityLink()
        val links = cache(CachedPendingIntent(older, 1L, false), CachedPendingIntent(newer, 2L, false), CachedPendingIntent(summary, 3L, true))
        assertSame(newer, LockScreenActivity.deepLinkFor(chat, links))
    }

    @Test
    fun aLinkThatOpensNoScreenOpensTheAppInstead() {
        // A broadcast may show nothing at all, and the session must still reach the screen.
        assertNull(LockScreenActivity.deepLinkFor(chat, cache(CachedPendingIntent(broadcastLink(), 1L, false))))
    }

    @Test
    fun anotherAppsLinkIsNeverSent() {
        val foreign = activityLink(creator = "com.example.other")
        assertNull(LockScreenActivity.deepLinkFor(chat, cache(CachedPendingIntent(foreign, 1L, false))))
    }

    @Test
    fun passwordManagerLinksMayComeFromPlayServices() {
        val fromGms = activityLink(creator = PasswordManager.GMS)
        val fromCredentialManager = activityLink(creator = PasswordManager.CREDENTIAL_MANAGER)
        assertSame(fromGms, LockScreenActivity.deepLinkFor(PasswordManager.VIRTUAL_PACKAGE, cache(CachedPendingIntent(fromGms, 1L, false))))
        assertSame(
            fromCredentialManager,
            LockScreenActivity.deepLinkFor(PasswordManager.VIRTUAL_PACKAGE, cache(CachedPendingIntent(fromCredentialManager, 1L, false)))
        )
        // Play services in its own right is a different app.
        assertNull(LockScreenActivity.deepLinkFor(chat, cache(CachedPendingIntent(fromGms, 1L, false))))
    }

    @Test
    fun nothingCachedOpensTheApp() {
        assertNull(LockScreenActivity.deepLinkFor(chat, null))
        assertNull(LockScreenActivity.deepLinkFor(chat, emptyMap()))
    }
}
