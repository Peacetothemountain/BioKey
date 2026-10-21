package com.android.BioKey

import android.app.Application
import android.content.Intent
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.feature.lock.LockLauncher
import com.android.BioKey.feature.lock.LockScreenActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A tap on a locked app's patch, while its lock screen is already up, asks for the prompt again;
 * every other start only brings the lock screen to the front, never as the user leaving the app below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockLauncherTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun onlyATapAsksForThePromptAgain() {
        assertTrue(LockLauncher.launch(context, "com.example.bank", isSelfProtection = false, reprompt = true))
        val tapped = shadowOf(context).nextStartedActivity
        assertEquals("com.example.bank", tapped.getStringExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE))
        assertTrue(tapped.getBooleanExtra(LockScreenActivity.EXTRA_REPROMPT, false))

        // Each start has its own extras: the next one doesn't ask again.
        assertTrue(LockLauncher.launch(context, "com.example.bank", isSelfProtection = false))
        assertFalse(shadowOf(context).nextStartedActivity.getBooleanExtra(LockScreenActivity.EXTRA_REPROMPT, true))
    }

    @Test
    fun theLockScreenComingUpIsNotTheUserLeavingTheAppBelow() {
        // That app gets no onUserLeaveHint, so it doesn't go into picture-in-picture over its lock screen.
        assertTrue(LockLauncher.launch(context, "com.example.bank", isSelfProtection = false))
        val started = shadowOf(context).nextStartedActivity
        assertTrue(started.flags.toString(), started.flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION != 0)
    }

    @Test
    fun aStartWithoutADisplayIsOnThePhonesOwnScreen() {
        // After a start on another display, one that names none mustn't follow it there.
        assertTrue(LockLauncher.launch(context, "com.example.bank", isSelfProtection = false, displayId = 2))
        shadowOf(context).nextStartedActivityForResult
        assertTrue(LockLauncher.launch(context, "com.example.bank", isSelfProtection = false))
        val options = shadowOf(context).nextStartedActivityForResult.options
        assertEquals(Display.DEFAULT_DISPLAY, options.getInt("android.activity.launchDisplayId", Display.INVALID_DISPLAY))
    }
}
