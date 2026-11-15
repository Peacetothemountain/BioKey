package com.android.BioKey

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.PauseNotice
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * While protection is paused a notification says so, with a Resume button ([PauseNotice]): a
 * pause "until I turn it back on" mustn't be forgotten. Resume only ever puts protection back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PauseNoticeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val posted get() = shadowOf(manager).getNotification(PauseNotice.NOTIFICATION_ID_FOR_TESTS)
    private lateinit var vault: SecurityVault

    @Before
    fun setUp() {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        ProtectionEngine.overrideModeForTests = ProtectionEngine.Mode.ACCESSIBILITY
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        ProtectionEngine.overrideModeForTests = null
        manager.cancelAll()
        vault.awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
    }

    @Test
    fun nothingIsShownWhileProtectionIsOn() {
        PauseNotice.update(context)
        assertNull(posted)
    }

    @Test
    fun aPauseIsSaidForAsLongAsItLastsWithAWayToResume() {
        vault.pauseProtection(null)
        PauseNotice.update(context)
        val notification = posted
        assertNotNull(notification)
        val shown = shadowOf(notification)
        assertEquals(context.getString(R.string.compat_notification_title_paused), shown.contentTitle.toString())
        assertEquals(context.getString(R.string.home_paused_body), shown.contentText.toString())
        assertTrue("it stays until protection is back", notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("kept off the lock screen", android.app.Notification.VISIBILITY_SECRET, notification.visibility)
        assertEquals(context.getString(R.string.pause_notice_resume), notification.actions.single().title.toString())
    }

    @Test
    fun aTimedPauseSaysWhenItEnds() {
        vault.pauseProtection(15 * 60_000L)
        PauseNotice.update(context)
        val text = shadowOf(posted).contentText.toString()
        assertTrue(text, text.contains(" until "))
        assertTrue(text, text.contains("or until you resume"))
    }

    @Test
    fun resumeTakesItDown() {
        vault.pauseProtection(null)
        PauseNotice.update(context)
        assertNotNull(posted)
        vault.resumeProtection()
        PauseNotice.update(context)
        assertNull(posted)
    }

    @Test
    fun theResumeButtonPutsProtectionBack() {
        vault.pauseProtection(null)
        PauseNotice.update(context)
        PauseNotice.ResumeReceiver().onReceive(context, Intent(PauseNotice.ACTION_RESUME))
        assertTrue(vault.isProtectionActive())
        assertNull(posted)
    }

    @Test
    fun theResumeButtonCanOnlyEverResume() {
        // Sent while protection is on (a stale button), or with another action: nothing changes.
        PauseNotice.ResumeReceiver().onReceive(context, Intent(PauseNotice.ACTION_RESUME))
        assertTrue(vault.isProtectionActive())
        vault.pauseProtection(null)
        PauseNotice.ResumeReceiver().onReceive(context, Intent("something.else"))
        assertFalse(vault.isProtectionActive())
    }

    @Test
    fun compatibilityModeSaysItItself() {
        // Its own notification, which Android requires while it runs, already reads "Protection paused".
        ProtectionEngine.overrideModeForTests = ProtectionEngine.Mode.COMPATIBILITY
        vault.pauseProtection(null)
        PauseNotice.update(context)
        assertNull(posted)
    }

    @Test
    fun beforeSetupNothingIsShown() {
        vault.pauseProtection(null)
        vault.setSetupCompletedSync(false)
        PauseNotice.update(context)
        assertNull(posted)
    }

    @Test
    fun withNotificationsOffNothingIsPostedAndNothingThrows() {
        shadowOf(manager).setNotificationsEnabled(false)
        vault.pauseProtection(null)
        PauseNotice.update(context)
        assertNull(posted)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    @Test
    fun theReceiverIsNotOpenToOtherApps() {
        val info = context.packageManager.getReceiverInfo(
            android.content.ComponentName(context as Application, PauseNotice.ResumeReceiver::class.java), 0
        )
        assertFalse(info.exported)
    }
}
