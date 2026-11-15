package com.android.BioKey

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.UnlockProof
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    private var recorded = 0

    @Before
    @After
    fun reset() {
        UnlockProof.resetForTests()
        context.getSharedPreferences("biokey_unlock_proof", Context.MODE_PRIVATE).edit().clear().commit()
        recorded = 0
    }

    private fun screenLock(set: Boolean) =
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(set)

    /** BioKey starting again: what it held in memory is gone, what it saved is not. */
    private fun restart() {
        UnlockProof.resetForTests()
        UnlockProof.record = { recorded++; true }
    }

    @Test
    fun withNothingAskedForThereIsNothingToProve() {
        assertTrue(UnlockProof.confirmed(context, null))
    }

    @Test
    fun aRightSignatureProvesTheMatch() {
        assertTrue(UnlockProof.confirmed(context, armed(keyPair())))
    }

    @Test
    fun aMatchWithNoSignatureIsNotOne() {
        // What a made-up "success" looks like: the callback came, but nothing was ever signed.
        assertFalse(UnlockProof.confirmed(context, armedUnsigned(keyPair())))
    }

    @Test
    fun aSignatureFromAnotherKeyIsNotOne() {
        assertFalse(UnlockProof.confirmed(context, armed(signer = keyPair(), expected = keyPair())))
    }

    @Test
    fun twoFailuresRunningLeaveProofOffSoTheOwnerIsNeverLockedOut() {
        val pair = keyPair()
        assertFalse(UnlockProof.confirmed(context, armedUnsigned(pair)))
        assertFalse(UnlockProof.isOffForTests)
        assertFalse(UnlockProof.confirmed(context, armedUnsigned(pair)))
        assertTrue(UnlockProof.isOffForTests)
        // Off: nothing is armed, so prompts run as they did before there was any proof.
        assertNull(UnlockProof.arm(context))
    }

    @Test
    fun leftOffItIsTriedAgainAfterAnHour() {
        var clock = 5_000L
        UnlockProof.sinceBoot = { clock }
        val pair = keyPair()
        repeat(2) { assertFalse(UnlockProof.confirmed(context, armedUnsigned(pair))) }
        assertTrue(UnlockProof.isOffForTests)
        // Within the hour nothing is armed (there is no Keystore here to arm with either way).
        clock += 59 * 60_000L
        assertNull(UnlockProof.arm(context))
        assertTrue(UnlockProof.isOffForTests)
        // An hour on, the next prompt asks for proof again.
        clock += 2 * 60_000L
        UnlockProof.arm(context)
        assertFalse(UnlockProof.isOffForTests)
    }

    @Test
    fun aProvenMatchBetweenFailuresStartsTheCountAgain() {
        val pair = keyPair()
        assertFalse(UnlockProof.confirmed(context, armedUnsigned(pair)))
        assertTrue(UnlockProof.confirmed(context, armed(pair)))
        assertFalse(UnlockProof.confirmed(context, armedUnsigned(pair)))
        assertFalse(UnlockProof.isOffForTests)
    }

    @Test
    fun whereThePassphraseOpensThingsProofIsNeverLeftOff() {
        // The owner has another way in, so nothing is opened on Android's word however often it fails.
        UnlockProof.neverLeftOff = { true }
        UnlockProof.record = { recorded++; true }
        screenLock(true)
        val pair = keyPair()
        repeat(5) { assertFalse(UnlockProof.confirmed(context, armedUnsigned(pair))) }
        assertFalse(UnlockProof.isOffForTests)
        assertEquals(0, recorded)
        // And a real match still counts.
        assertTrue(UnlockProof.confirmed(context, armed(pair)))
    }

    @Test
    fun proofLeftOffIsWrittenIntoSecurityActivityOnceUntilAMatchIsProvedAgain() {
        screenLock(true)
        restart()
        val pair = keyPair()
        assertFalse(UnlockProof.confirmed(context, armedUnsigned(pair)))
        assertEquals("one failure opens nothing and isn't yet a state to record", 0, recorded)
        assertFalse(UnlockProof.confirmed(context, armedUnsigned(pair)))
        assertTrue(UnlockProof.isOffForTests)
        assertEquals(1, recorded)

        // BioKey restarts and the hardware still can't sign: already said, not said again.
        restart()
        repeat(2) { assertFalse(UnlockProof.confirmed(context, armedUnsigned(pair))) }
        assertTrue(UnlockProof.isOffForTests)
        assertEquals("still the one record", 1, recorded)

        // It works again, and later fails again: that is news.
        restart()
        assertTrue(UnlockProof.confirmed(context, armed(pair)))
        repeat(2) { assertFalse(UnlockProof.confirmed(context, armedUnsigned(pair))) }
        assertEquals(2, recorded)
    }

    @Test
    fun withNoScreenLockThereIsNothingToProveAndNothingIsRecorded() {
        // That state has a record of its own (no screen lock), and no key can exist without one.
        screenLock(false)
        restart()
        val pair = keyPair()
        repeat(2) { assertFalse(UnlockProof.confirmed(context, armedUnsigned(pair))) }
        assertTrue(UnlockProof.isOffForTests)
        assertEquals(0, recorded)
    }

    @Test
    fun aKeyAndroidDestroyedIsRecordedAsAPromptWithoutProof() {
        // The prompt that finds its key gone runs without proof: said in security activity, once.
        screenLock(true)
        restart()
        val pair = keyPair()
        UnlockProof.signer = { throw android.security.keystore.KeyPermanentlyInvalidatedException() }
        UnlockProof.loadedForTests(pair.private, pair.public)
        assertNull(UnlockProof.arm(context))
        assertEquals(1, recorded)
        UnlockProof.loadedForTests(pair.private, pair.public)
        assertNull(UnlockProof.arm(context))
        assertEquals("already said", 1, recorded)
    }

    @Test
    fun aRecordThatCouldntBeMadeIsMadeAtTheNextPromptWithoutProof() {
        // Before setup is finished security activity takes no records: that mustn't count as said.
        screenLock(true)
        restart()
        var taken = false
        UnlockProof.record = { recorded++; taken }
        val pair = keyPair()
        UnlockProof.signer = { throw android.security.keystore.KeyPermanentlyInvalidatedException() }
        UnlockProof.loadedForTests(pair.private, pair.public)
        assertNull(UnlockProof.arm(context))
        assertEquals("tried", 1, recorded)
        taken = true
        UnlockProof.loadedForTests(pair.private, pair.public)
        assertNull(UnlockProof.arm(context))
        assertEquals("tried again, and taken", 2, recorded)
        UnlockProof.loadedForTests(pair.private, pair.public)
        assertNull(UnlockProof.arm(context))
        assertEquals("said: not again", 2, recorded)
    }

    @Test
    fun aKeyAndroidDestroyedIsNotRecordedWhereThePassphraseIsAskedForInstead() {
        // No prompt runs on Android's word there, so there is nothing to say was taken on it.
        screenLock(true)
        restart()
        UnlockProof.neverLeftOff = { true }
        val pair = keyPair()
        UnlockProof.signer = { throw android.security.keystore.KeyPermanentlyInvalidatedException() }
        UnlockProof.loadedForTests(pair.private, pair.public)
        assertNull(UnlockProof.arm(context))
        assertEquals(0, recorded)
    }

    @Test
    fun withoutAKeystoreNothingIsArmedAndNothingThrows() {
        // Robolectric has no AndroidKeyStore: as on a phone whose Keystore fails.
        assertNull(UnlockProof.arm(context))
        UnlockProof.prepareSoon(context)
        assertNull(UnlockProof.arm(context))
    }
}
