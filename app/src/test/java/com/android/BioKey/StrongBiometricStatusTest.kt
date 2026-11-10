package com.android.BioKey

import android.content.Context
import android.hardware.biometrics.BiometricManager
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.SecurityEngine
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Android's answer about a strong face or fingerprint means to BioKey: whether one can be
 * used now (prompts, "Face or fingerprint only") and whether one is set up at all (the records of
 * faces and fingerprints added or removed). A sensor that can't be used for a while still has its
 * faces and fingerprints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 37])
class StrongBiometricStatusTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var asked: (Context) -> Int?

    @Before
    fun keep() {
        asked = SecurityEngine.canAuthenticateStrong
    }

    @After
    fun restore() {
        SecurityEngine.canAuthenticateStrong = asked
    }

    private fun androidAnswers(status: Int) {
        SecurityEngine.canAuthenticateStrong = { status }
    }

    @Test
    fun oneThatWorksIsSetUpAndUsable() {
        androidAnswers(BiometricManager.BIOMETRIC_SUCCESS)
        assertTrue(SecurityEngine.hasStrongBiometric(context))
        assertTrue(SecurityEngine.hasEnrolledStrongBiometric(context))
    }

    @Test
    fun noneSetUpIsNeither() {
        androidAnswers(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
        assertFalse(SecurityEngine.hasStrongBiometric(context))
        assertFalse(SecurityEngine.hasEnrolledStrongBiometric(context))
    }

    @Test
    fun aSensorThatCantBeUsedForNowStillHasItsFingerprints() {
        for (status in listOf(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE, BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED)) {
            androidAnswers(status)
            // The screen lock is let in meanwhile, or the owner would be locked out of everything.
            assertFalse(SecurityEngine.hasStrongBiometric(context))
            assertTrue(SecurityEngine.hasEnrolledStrongBiometric(context))
        }
    }

    @Test
    fun tooManyMissesIsNoRemovalAndNoWayInForTheScreenLock() {
        // BiometricManager.BIOMETRIC_ERROR_LOCKOUT: hidden, but among canAuthenticate's answers.
        androidAnswers(7)
        assertTrue(SecurityEngine.hasEnrolledStrongBiometric(context))
        assertTrue(SecurityEngine.hasStrongBiometric(context))
        assertTrue(SecurityEngine.lastKnownStrongBiometric() == true)
    }
}
