package com.android.BioKey

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * With "Face or fingerprint only" on and a passphrase set, the passphrase is the other way in, and
 * the screen lock never is: not even when no face or fingerprint can be used at all, which is
 * exactly what someone who knew the screen lock and removed the fingerprints would arrange.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 37])
class PassphraseStandsInTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = SecurityEngine.INSTANCE
    private lateinit var asked: (Context) -> Int?
    private val passphrase get() = "lantern tide 9".toCharArray()

    @Before
    fun setUp() {
        asked = SecurityEngine.canAuthenticateStrong
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("biokey_owner_passphrase", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        OwnerPassphrase.resetForTests()
        // The Keystore's part, which Robolectric hasn't got: an ordinary key in its place.
        OwnerPassphrase.pepper = { bytes, _ ->
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256"))
                doFinal(bytes)
            }
        }
        OwnerPassphrase.rounds = 100_000
        engine.resetAuthenticatingState()
    }

    @After
    fun tearDown() {
        SecurityEngine.canAuthenticateStrong = asked
        engine.resetAuthenticatingState()
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("biokey_owner_passphrase", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        OwnerPassphrase.resetForTests()
    }

    private fun faceOrFingerprintOnly(on: Boolean) = SecurityVault.getInstance(context).setBiometricOnly(on)

    private fun androidAnswers(status: Int) {
        SecurityEngine.canAuthenticateStrong = { status }
    }

    /** The error the prompt for a locked app ends with at once, or null if it didn't (the system prompt is up, or was refused later). */
    private fun promptFor(passphraseEntry: Boolean): Int? {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var error: Int? = null
        engine.showBiometricPrompt(
            activity = activity,
            appName = "Bank",
            packageName = "com.example.bank",
            onSuccess = { throw AssertionError("Nothing was checked, so nothing may open") },
            onError = { code, _ -> error = code },
            passphraseEntry = passphraseEntry
        )
        return error
    }

    @Test
    fun itStandsInOnlyWhileFaceOrFingerprintOnlyIsOn() {
        assertTrue(OwnerPassphrase.set(context, passphrase))
        faceOrFingerprintOnly(false)
        assertFalse(SecurityEngine.passphraseStandsIn(context))
        faceOrFingerprintOnly(true)
        assertTrue(SecurityEngine.passphraseStandsIn(context))
        OwnerPassphrase.clear(context)
        assertFalse(SecurityEngine.passphraseStandsIn(context))
    }

    @Test
    fun withNoFaceOrFingerprintToUseThePassphraseIsAskedForNotTheScreenLock() {
        assertTrue(OwnerPassphrase.set(context, passphrase))
        faceOrFingerprintOnly(true)
        for (status in listOf(
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        )) {
            androidAnswers(status)
            assertEquals("Android answering $status", SecurityEngine.ERROR_USE_PASSPHRASE, promptFor(passphraseEntry = true))
            // No system prompt was started: nothing is left running that a screen lock could answer.
            assertFalse(engine.isAuthenticating())
        }
    }

    @Test
    fun aScreenThatCantAskForThePassphraseIsNeverSentToIt() {
        // BioKey's setup check, say: it has no passphrase entry, so it keeps the prompt it had.
        assertTrue(OwnerPassphrase.set(context, passphrase))
        faceOrFingerprintOnly(true)
        androidAnswers(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
        assertNotEquals(SecurityEngine.ERROR_USE_PASSPHRASE, promptFor(passphraseEntry = false))
    }

    @Test
    fun withoutAPassphraseNothingChanges() {
        faceOrFingerprintOnly(true)
        androidAnswers(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
        assertNotEquals(SecurityEngine.ERROR_USE_PASSPHRASE, promptFor(passphraseEntry = true))
    }

    @Test
    fun withFaceOrFingerprintOnlyOffThePassphraseIsNeverAskedFor() {
        assertTrue(OwnerPassphrase.set(context, passphrase))
        faceOrFingerprintOnly(false)
        androidAnswers(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
        assertNotEquals(SecurityEngine.ERROR_USE_PASSPHRASE, promptFor(passphraseEntry = true))
    }
}
