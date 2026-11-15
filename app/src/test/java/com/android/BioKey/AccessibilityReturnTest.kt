package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.AccessibilityReturn
import com.android.BioKey.feature.main.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Once the accessibility service has been switched on in Android's settings, BioKey comes back to
 * the front by itself, when it was BioKey that sent the owner there ([AccessibilityReturn]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityReturnTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var clock = 10_000L

    private fun started() = shadowOf(context as android.app.Application).nextStartedActivity

    @After
    fun tearDown() {
        AccessibilityReturn.take()
        AccessibilityReturn.sinceBoot = android.os.SystemClock::elapsedRealtime
    }

    @Test
    fun sentToTurnItOnTheOwnerIsBroughtBackOnceItIsOn() {
        AccessibilityReturn.sinceBoot = { clock }
        AccessibilityReturn.expect()
        clock += 20_000L
        AccessibilityReturn.comeBack(context)
        assertEquals(MainActivity::class.java.name, started()?.component?.className)
        // Once only: the service coming on again later (a restart) brings nothing forward.
        AccessibilityReturn.comeBack(context)
        assertNull(started())
    }

    @Test
    fun turnedOnWithoutBeingSentBioKeyStaysWhereItIs() {
        AccessibilityReturn.sinceBoot = { clock }
        AccessibilityReturn.comeBack(context)
        assertNull(started())
    }

    @Test
    fun longAfterBeingSentItIsNoLongerTheOwnersAnswer() {
        AccessibilityReturn.sinceBoot = { clock }
        AccessibilityReturn.expect()
        clock += 6 * 60_000L
        assertFalse(AccessibilityReturn.take())
        AccessibilityReturn.expect()
        clock += 4 * 60_000L
        assertTrue(AccessibilityReturn.take())
    }
}
