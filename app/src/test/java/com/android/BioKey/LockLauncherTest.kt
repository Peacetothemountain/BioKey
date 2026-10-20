package com.android.BioKey

import android.app.Application
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
 * every other start only brings the lock screen to the front.
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

        // The app's PendingIntent is reused, with its extras replaced: the next start doesn't ask again.
        assertTrue(LockLauncher.launch(context, "com.example.bank", isSelfProtection = false))
        assertFalse(shadowOf(context).nextStartedActivity.getBooleanExtra(LockScreenActivity.EXTRA_REPROMPT, true))
    }
}
