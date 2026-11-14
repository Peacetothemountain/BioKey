package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.UnlockProof
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A match Android reports counts only with the challenge signed and the signature right
 * ([UnlockProof]). The phone's hardware is stood in for by an ordinary key pair here: what is
 * tested is what BioKey does with a signature, a wrong one and none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnlockProofTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val challenge = ByteArray(32) { it.toByte() }

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** As the hardware leaves it after a real match: ready to sign with [signer]'s key. */
    private fun armed(signer: KeyPair, expected: KeyPair = signer) = UnlockProof.armedForTests(
        Signature.getInstance("SHA256withECDSA").apply { initSign(signer.private) }, challenge, expected.public
    )

    /** As the hardware leaves it with nobody checked: the signature can't be made. */
    private fun armedUnsigned(expected: KeyPair) =
        UnlockProof.armedForTests(Signature.getInstance("SHA256withECDSA"), challenge, expected.public)

    @Before
    @After
    fun reset() = UnlockProof.resetForTests()

    @Test
    fun withNothingAskedForThereIsNothingToProve() {
        assertTrue(UnlockProof.confirmed(null))
    }

    @Test
    fun aRightSignatureProvesTheMatch() {
        assertTrue(UnlockProof.confirmed(armed(keyPair())))
    }

    @Test
    fun aMatchWithNoSignatureIsNotOne() {
        // What a made-up "success" looks like: the callback came, but nothing was ever signed.
        assertFalse(UnlockProof.confirmed(armedUnsigned(keyPair())))
    }

    @Test
    fun aSignatureFromAnotherKeyIsNotOne() {
        assertFalse(UnlockProof.confirmed(armed(signer = keyPair(), expected = keyPair())))
    }

    @Test
    fun twoFailuresRunningLeaveProofOffSoTheOwnerIsNeverLockedOut() {
        val pair = keyPair()
        assertFalse(UnlockProof.confirmed(armedUnsigned(pair)))
        assertFalse(UnlockProof.isOffForTests)
        assertFalse(UnlockProof.confirmed(armedUnsigned(pair)))
        assertTrue(UnlockProof.isOffForTests)
        // Off: nothing is armed, so prompts run as they did before there was any proof.
        assertNull(UnlockProof.arm(context))
    }

    @Test
    fun aProvenMatchBetweenFailuresStartsTheCountAgain() {
        val pair = keyPair()
        assertFalse(UnlockProof.confirmed(armedUnsigned(pair)))
        assertTrue(UnlockProof.confirmed(armed(pair)))
        assertFalse(UnlockProof.confirmed(armedUnsigned(pair)))
        assertFalse(UnlockProof.isOffForTests)
    }

    @Test
    fun withoutAKeystoreNothingIsArmedAndNothingThrows() {
        // Robolectric has no AndroidKeyStore: as on a phone whose Keystore fails.
        assertNull(UnlockProof.arm(context))
        UnlockProof.prepareSoon(context)
        assertNull(UnlockProof.arm(context))
    }
}
