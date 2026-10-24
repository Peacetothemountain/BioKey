package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.AdvancedProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Advanced Protection decides BioKey's engine: on Android 17, with it on, Android switches the
 * accessibility service off and BioKey has to run in compatibility mode. Android 16 has the mode
 * but leaves BioKey alone; older versions don't have it. Robolectric runs Android 16 and 17 only on
 * Java 21, so these run the decisions on any version, with Android's answer given.
 */
class AdvancedProtectionTest {

    private var asked = 0

    private fun answer(on: Boolean): () -> Boolean = { asked++; on }

    @Test
    fun beforeAndroid16ThereIsNothingToAskAbout() {
        assertFalse(AdvancedProtection.isEnabled(31, answer(true)))
        assertFalse(AdvancedProtection.isEnabled(35, answer(true)))
        assertEquals("Android 15 and older have no AdvancedProtectionManager to ask", 0, asked)
    }

    @Test
    fun fromAndroid16ItIsWhatAndroidSays() {
        assertTrue(AdvancedProtection.isEnabled(36, answer(true)))
        assertFalse(AdvancedProtection.isEnabled(36, answer(false)))
        assertTrue(AdvancedProtection.isEnabled(37, answer(true)))
        assertFalse(AdvancedProtection.isEnabled(37, answer(false)))
    }

    @Test
    fun aQuestionThatFailsCountsAsOff() {
        assertFalse(AdvancedProtection.isEnabled(37) { throw SecurityException("QUERY_ADVANCED_PROTECTION_MODE") })
        assertFalse(AdvancedProtection.isEnabled(37) { throw IllegalStateException("No service") })
    }

    @Test
    fun onAndroid17ItBlocksBioKeyWhileItIsOn() {
        assertTrue(AdvancedProtection.blocksBioKey(37, answer(true)))
        assertFalse(AdvancedProtection.blocksBioKey(37, answer(false)))
        assertTrue("Later versions keep the restriction", AdvancedProtection.blocksBioKey(38, answer(true)))
    }

    @Test
    fun onAndroid16AndOlderItNeverBlocksBioKey() {
        assertFalse(AdvancedProtection.blocksBioKey(36, answer(true)))
        assertFalse(AdvancedProtection.blocksBioKey(35, answer(true)))
        assertFalse(AdvancedProtection.blocksBioKey(31, answer(true)))
        assertEquals("Whether it's on doesn't matter there", 0, asked)
    }
}

/**
 * On a real Android 12 and 15 (in Robolectric): off, and asking doesn't reach for
 * AdvancedProtectionManager, a class those versions don't have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 35], shadows = [InstallTimePermissions::class])
class AdvancedProtectionBeforeAndroid16Test {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun itIsOffAndBlocksNothing() {
        assertFalse(AdvancedProtection.isEnabled(context))
        assertFalse(AdvancedProtection.blocksBioKey(context))
    }
}
