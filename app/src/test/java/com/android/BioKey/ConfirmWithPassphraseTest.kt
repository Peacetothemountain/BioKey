package com.android.BioKey

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockProof
import com.android.BioKey.feature.main.PassphraseConfirm
import com.android.BioKey.feature.main.confirmOwner
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Inside BioKey, what removes protection asks who it is first. Where the passphrase stands in for
 * a face or fingerprint ("Face or fingerprint only" on, one set) and none can be used, that check
 * is the passphrase and never the screen lock: someone who knew the screen lock and removed the
 * fingerprints must not be able to switch the passphrase, or "Face or fingerprint only", off with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfirmWithPassphraseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = SecurityEngine.INSTANCE
    private val right = "lantern tide 9"
    private lateinit var asked: (Context) -> Int?
    private lateinit var activity: Activity
    private var ran = false

    @Before
    fun setUp() {
        asked = SecurityEngine.canAuthenticateStrong
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("biokey_owner_passphrase", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        OwnerPassphrase.resetForTests()
        LockStateTracker.resetForTests()
        PassphraseConfirm.dismiss()
        OwnerPassphrase.pepper = { bytes, _ ->
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(ByteArray(32) { 9 }, "HmacSHA256"))
                doFinal(bytes)
            }
        }
        OwnerPassphrase.rounds = 100_000
        engine.resetAuthenticatingState()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // BioKey is open and unlocked: these checks happen inside it.
        LockStateTracker.onOwnAppUnlocked()
        ran = false
    }

    @After
    fun tearDown() {
        SecurityEngine.canAuthenticateStrong = asked
        SecurityEngine.promptsForTests = null
        UnlockProof.resetForTests()
        engine.resetAuthenticatingState()
        PassphraseConfirm.dismiss()
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("biokey_owner_passphrase", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        OwnerPassphrase.resetForTests()
        LockStateTracker.resetForTests()
    }

    private fun passphraseStandsInWithNoFingerprintToUse() {
        assertTrue(OwnerPassphrase.set(context, right.toCharArray()))
        SecurityVault.getInstance(context).setBiometricOnly(true)
        SecurityEngine.canAuthenticateStrong = { BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Long enough for the check itself to finish, without letting its answer be handled yet. */
    private fun whileTheCheckRuns() = Thread.sleep(2_500)

    /** Lets answers be handled until [done], or a few seconds have passed. */
    private fun settle(done: () -> Boolean) {
        repeat(120) {
            idle()
            if (done()) return
            Thread.sleep(50)
        }
    }

    private fun askToRemove() = confirmOwner(activity, "Remove your passphrase") { ran = true }

    @Test
    fun thePassphraseIsAskedForNotTheScreenLock() {
        passphraseStandsInWithNoFingerprintToUse()
        askToRemove()
        assertEquals("Remove your passphrase", PassphraseConfirm.state.title)
        // No system prompt was started: nothing is up that a screen lock could answer.
        assertFalse(engine.isAuthenticating())
        assertFalse(ran)
    }

    @Test
    fun aWrongPassphraseConfirmsNothing() {
        passphraseStandsInWithNoFingerprintToUse()
        askToRemove()
        PassphraseConfirm.submit(context, "not the passphrase".toCharArray())
        settle { PassphraseConfirm.state.error != null }
        assertNotNull(PassphraseConfirm.state.error)
        assertFalse(PassphraseConfirm.state.busy)
        assertNotNull("still asking", PassphraseConfirm.state.title)
        assertFalse(ran)
    }

    @Test
    fun theRightPassphraseLetsTheChangeThrough() {
        passphraseStandsInWithNoFingerprintToUse()
        askToRemove()
        PassphraseConfirm.submit(context, right.toCharArray())
        settle { ran }
        assertTrue(ran)
        assertNull(PassphraseConfirm.state.title)
    }

    @Test
    fun backedOutOfWhileItsCheckedTheChangeIsntMade() {
        passphraseStandsInWithNoFingerprintToUse()
        askToRemove()
        PassphraseConfirm.submit(context, right.toCharArray())
        whileTheCheckRuns()
        PassphraseConfirm.dismiss()
        settle { false }
        assertFalse(ran)
    }

    @Test
    fun anAnswerForAnEarlierQuestionDoesntConfirmALaterOne() {
        passphraseStandsInWithNoFingerprintToUse()
        var first = false
        confirmOwner(activity, "Allow your screen lock again") { first = true }
        PassphraseConfirm.submit(context, right.toCharArray())
        whileTheCheckRuns()
        // Asked something else before the first answer is handled.
        askToRemove()
        settle { false }
        assertFalse(first)
        assertFalse(ran)
        assertEquals("Remove your passphrase", PassphraseConfirm.state.title)
    }

    @Test
    fun withBioKeyLockedWhileItsCheckedTheChangeIsntMade() {
        passphraseStandsInWithNoFingerprintToUse()
        askToRemove()
        PassphraseConfirm.submit(context, right.toCharArray())
        whileTheCheckRuns()
        LockStateTracker.lockOwnApp()
        settle { false }
        assertFalse(ran)
    }

    @Test
    fun theSystemCheckItselfNeverFallsBackToTheScreenLockThere() {
        // Asked directly, past the passphrase's dialog: refused, not answered with the screen lock.
        passphraseStandsInWithNoFingerprintToUse()
        var refused: String? = null
        engine.confirmWithBiometric(activity, "Remove your passphrase", onRefused = { refused = it }) { ran = true }
        assertNotNull(refused)
        assertFalse(engine.isAuthenticating())
        assertFalse(ran)
    }

    @Test
    fun withoutAPassphraseTheCheckIsWhatItWas() {
        SecurityVault.getInstance(context).setBiometricOnly(true)
        SecurityEngine.canAuthenticateStrong = { BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED }
        askToRemove()
        assertNull(PassphraseConfirm.state.title)
    }

    @Test
    fun withFaceOrFingerprintOnlyOffThePassphraseIsntAskedFor() {
        assertTrue(OwnerPassphrase.set(context, right.toCharArray()))
        SecurityVault.getInstance(context).setBiometricOnly(false)
        SecurityEngine.canAuthenticateStrong = { BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED }
        askToRemove()
        assertNull(PassphraseConfirm.state.title)
    }

    @Test
    fun withAFaceOrFingerprintToUseThatIsWhatIsAskedFor() {
        assertTrue(OwnerPassphrase.set(context, right.toCharArray()))
        SecurityVault.getInstance(context).setBiometricOnly(true)
        SecurityEngine.canAuthenticateStrong = { BiometricManager.BIOMETRIC_SUCCESS }
        // A phone whose hardware can prove the match, as phones do.
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        UnlockProof.armer = {
            UnlockProof.armedForTests(Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private) }, ByteArray(32), pair.public)
        }
        SecurityEngine.promptsForTests = { _, _ -> }
        askToRemove()
        assertNull(PassphraseConfirm.state.title)
        assertTrue("the fingerprint prompt is up", engine.isAuthenticating())
    }

    @Test
    fun withNoProofToAskTheHardwareForThePassphraseIsAskedForInstead() {
        // The key can't be made or used (here: no Keystore at all). Where the passphrase stands
        // in, a match is never taken on Android's word alone, so the fingerprint isn't asked for.
        assertTrue(OwnerPassphrase.set(context, right.toCharArray()))
        SecurityVault.getInstance(context).setBiometricOnly(true)
        SecurityEngine.canAuthenticateStrong = { BiometricManager.BIOMETRIC_SUCCESS }
        askToRemove()
        assertEquals("Remove your passphrase", PassphraseConfirm.state.title)
        assertFalse(engine.isAuthenticating())
        assertFalse(ran)
    }
}
