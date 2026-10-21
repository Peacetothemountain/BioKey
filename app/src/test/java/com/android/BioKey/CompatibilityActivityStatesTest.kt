package com.android.BioKey

import android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
import android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
import android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.feature.lock.ActivityStates
import com.android.BioKey.feature.lock.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compatibility mode's view of the screen from usage events: what is still showing (resumed, or
 * paused and not yet stopped), so a locked app beside or under another one is noticed, and an
 * unlocked one there stays in use. Every poll re-reads the last couple of seconds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompatibilityActivityStatesTest {

    private val chat = "com.example.chat"
    private val bank = "com.example.bank"

    private fun event(type: Int, pkg: String, time: Long, cls: String = "$pkg.MainActivity") = UsageEvent(type, pkg, cls, time)

    @Test
    fun reReadEventsAreNotNews() {
        val states = ActivityStates()
        val opened = event(ACTIVITY_RESUMED, chat, 100)
        assertTrue(states.apply(opened))
        assertFalse(states.apply(opened))
        assertTrue(states.apply(event(ACTIVITY_PAUSED, chat, 200)))
        // The next poll reads both again: nothing changes, the app is still only paused.
        assertFalse(states.apply(opened))
        assertFalse(states.apply(event(ACTIVITY_PAUSED, chat, 200)))
        assertFalse(states.isResumed(chat))
        assertTrue(states.isVisible(chat))
    }

    @Test
    fun splitScreenKeepsBothAppsResumed() {
        val states = ActivityStates()
        states.apply(event(ACTIVITY_RESUMED, bank, 100))
        states.apply(event(ACTIVITY_RESUMED, chat, 101))
        assertTrue(states.isResumed(bank))
        assertTrue(states.isResumed(chat))
    }

    @Test
    fun aPausedAppIsStillShowingUntilItStops() {
        val states = ActivityStates()
        states.apply(event(ACTIVITY_RESUMED, bank, 100))
        states.apply(event(ACTIVITY_PAUSED, bank, 150)) // A permission dialog, picture-in-picture, or on its way out.
        assertTrue(states.isVisible(bank))
        assertFalse(states.isGone(bank))
        states.apply(event(ACTIVITY_STOPPED, bank, 400))
        assertFalse(states.isVisible(bank))
        assertTrue(states.isGone(bank))
        // Re-reading the resume doesn't bring it back.
        assertFalse(states.apply(event(ACTIVITY_RESUMED, bank, 100)))
        assertTrue(states.isGone(bank))
    }

    @Test
    fun aPauseAndStopInTheSameMillisecondStayStoppedWhenReRead() {
        val states = ActivityStates()
        states.apply(event(ACTIVITY_RESUMED, chat, 100))
        states.apply(event(ACTIVITY_PAUSED, chat, 200))
        states.apply(event(ACTIVITY_STOPPED, chat, 200))
        assertFalse(states.apply(event(ACTIVITY_PAUSED, chat, 200)))
        assertTrue(states.isGone(chat))
    }

    @Test
    fun anAppShowsWhileAnyOfItsActivitiesDoes() {
        val states = ActivityStates()
        states.apply(event(ACTIVITY_RESUMED, chat, 100, cls = "$chat.Splash"))
        states.apply(event(ACTIVITY_PAUSED, chat, 150, cls = "$chat.Splash"))
        states.apply(event(ACTIVITY_RESUMED, chat, 160, cls = "$chat.Main"))
        states.apply(event(ACTIVITY_STOPPED, chat, 300, cls = "$chat.Splash"))
        assertTrue(states.isResumed(chat))
        assertFalse(states.isGone(chat))
    }

    @Test
    fun anActivityThatComesBackPausedIsShowingWithoutBeingInFront() {
        // Picture-in-picture after the phone is unlocked: stopped under the keyguard, then paused, never resumed.
        val states = ActivityStates()
        val back = event(ACTIVITY_PAUSED, bank, 300)
        assertNull(states.lastTypeOf(back))
        states.apply(event(ACTIVITY_STOPPED, bank, 200))
        assertEquals(ACTIVITY_STOPPED, states.lastTypeOf(back))
        states.apply(back)
        assertTrue(states.isPaused(back.activity))
        assertEquals(listOf(back.activity), states.pausedActivitiesOf(bank))
        assertFalse(states.isResumed(bank))
        assertTrue(states.isVisible(bank))
        states.apply(event(ACTIVITY_RESUMED, bank, 400))
        assertFalse(states.isPaused(back.activity))
        assertTrue(states.pausedActivitiesOf(bank).isEmpty())
    }

    @Test
    fun aLockedFloatingAppIsAnApp() {
        // Digital Wellbeing floats over apps, unless the user locked it (its icon turned on).
        val wellbeing = "com.google.android.apps.wellbeing"
        assertNull(UsageEvent(ACTIVITY_RESUMED, wellbeing, "$wellbeing.Settings", 100).app)
        assertEquals(wellbeing, UsageEvent(ACTIVITY_RESUMED, wellbeing, "$wellbeing.Settings", 100, lockedOverlay = true).app)
    }

    @Test
    fun nothingKnownIsNeitherShowingNorGone() {
        val states = ActivityStates()
        assertFalse(states.knows(chat))
        assertFalse(states.isVisible(chat))
        assertFalse(states.isGone(chat)) // Not proof that it left.
    }

    @Test
    fun floatingWindowsAreNotApps() {
        val states = ActivityStates()
        val permission = event(ACTIVITY_RESUMED, "com.google.android.permissioncontroller", 100, cls = "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity")
        assertNull(permission.app)
        assertFalse(states.apply(permission))
        val passkey = event(ACTIVITY_RESUMED, PasswordManager.CREDENTIAL_MANAGER, 100, cls = "${PasswordManager.CREDENTIAL_MANAGER}.ui.PasskeyBottomSheetActivity")
        assertNull(passkey.app)
    }

    @Test
    fun googlePasswordManagerInPlayServicesIsThePasswordManager() {
        val states = ActivityStates()
        val gpm = event(ACTIVITY_RESUMED, PasswordManager.GMS, 100, cls = "com.google.android.gms.credential.manager.PasswordManagerActivity")
        assertEquals(PasswordManager.VIRTUAL_PACKAGE, gpm.app)
        states.apply(gpm)
        assertTrue(states.isResumed(PasswordManager.VIRTUAL_PACKAGE))
        assertFalse(states.knows(PasswordManager.GMS))
        // Play services' other screens stay Play services.
        assertEquals(PasswordManager.GMS, event(ACTIVITY_RESUMED, PasswordManager.GMS, 100, cls = "com.google.android.gms.auth.uiflows.addaccount.SignInActivity").app)
    }

    @Test
    fun pruningForgetsOnlyActivitiesThatStoppedLongAgo() {
        val states = ActivityStates()
        states.apply(event(ACTIVITY_RESUMED, chat, 100))
        states.apply(event(ACTIVITY_STOPPED, chat, 200))
        states.apply(event(ACTIVITY_RESUMED, bank, 150))
        states.prune(1_000)
        assertFalse(states.knows(chat))
        assertTrue(states.isResumed(bank))
        states.clear()
        assertFalse(states.knows(bank))
    }

    @Test
    fun aClockChangeKeepsWhatEachActivityDidLast() {
        // A window in picture-in-picture, paused at a time the clock has since gone back past.
        val states = ActivityStates()
        val pip = event(ACTIVITY_PAUSED, bank, 5_000)
        states.apply(pip)
        states.apply(event(ACTIVITY_STOPPED, chat, 5_000))
        states.rebase()
        assertTrue(states.isPaused(pip.activity))
        // Whatever the new clock says applies, earlier as it reads...
        assertTrue(states.apply(event(ACTIVITY_RESUMED, bank, 100)))
        assertTrue(states.isResumed(bank))
        // ...and stopped activities still go at the next prune.
        states.prune(50)
        assertFalse(states.knows(chat))
    }
}
