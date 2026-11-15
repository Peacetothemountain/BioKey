package com.android.BioKey

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockProof
import com.android.BioKey.feature.main.PassphraseConfirm
import com.android.BioKey.feature.main.confirmOwner
import java.security.KeyPair
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
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * What each of BioKey's prompts does with a match Android reports, once its challenge has or
 * hasn't been signed ([UnlockProof]): the unlock prompt of a locked app, and the owner check inside
 * BioKey. Android's prompt is stood in for; the "match" is delivered to the prompt's own callback,
 * as Android would deliver it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnlockProofWiringTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = SecurityEngine.INSTANCE
    private val challenge = ByteArray(32) { (it * 3).toByte() }
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private lateinit var asked: (Context) -> Int?
    private lateinit var activity: Activity
    private var prompt: BiometricPrompt.AuthenticationCallback? = null
    private var armedWith: UnlockProof.Armed? = null

    @Before
    fun setUp() {
        asked = SecurityEngine.canAuthenticateStrong
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("biokey_owner_passphrase", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("biokey_unlock_proof", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        OwnerPassphrase.resetForTests()
        LockStateTracker.resetForTests()
        UnlockProof.resetForTests()
        PassphraseConfirm.dismiss()
        OwnerPassphrase.pepper = { bytes, _ ->
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(ByteArray(32) { 5 }, "HmacSHA256"))
                doFinal(bytes)
            }
        }
        OwnerPassphrase.rounds = 100_000
        // A fingerprint that works, so prompts are shown.
        SecurityEngine.canAuthenticateStrong = { BiometricManager.BIOMETRIC_SUCCESS }
        SecurityEngine.promptsForTests = { armed, callback ->
            armedWith = armed
            prompt = callback
        }
        engine.resetAuthenticatingState()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        LockStateTracker.onOwnAppUnlocked()
    }

    @After
    fun tearDown() {
        SecurityEngine.canAuthenticateStrong = asked
        SecurityEngine.promptsForTests = null
        engine.resetAuthenticatingState()
        PassphraseConfirm.dismiss()
        UnlockProof.resetForTests()
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("biokey_owner_passphrase", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("biokey_unlock_proof", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        OwnerPassphrase.resetForTests()
        LockStateTracker.resetForTests()
    }

    /** Every prompt is armed as the phone's hardware would leave it after a real match. */
    private fun hardwareSigns() {
        UnlockProof.armer = {
            UnlockProof.armedForTests(Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private) }, challenge, pair.public)
        }
    }

    /** Every prompt is armed, but nobody was checked: the signature can't be made. */
    private fun hardwareDoesNotSign() {
        UnlockProof.armer = { UnlockProof.armedForTests(Signature.getInstance("SHA256withECDSA"), challenge, pair.public) }
    }

    /** Android says "matched", with a fingerprint. */
    private fun androidReportsAMatch() {
        val result = ReflectionHelpers.callConstructor(
            BiometricPrompt.AuthenticationResult::class.java,
            ClassParameter.from(BiometricPrompt.CryptoObject::class.java, armedWith?.crypto),
            ClassParameter.from(Int::class.javaPrimitiveType, BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC)
        )
        val callback = prompt
        assertNotNull("a prompt was started", callback)
        callback!!.onAuthenticationSucceeded(result)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class Unlock {
        var opened = false
        var error: Int? = null
    }

    private fun unlockPrompt(): Unlock {
        val unlock = Unlock()
        engine.showBiometricPrompt(
            activity, "Notes", "com.example.notes",
            onSuccess = { unlock.opened = true },
            onError = { code, _ -> unlock.error = code }
        )
        return unlock
    }

    @Test
    fun aLockedAppOpensOnAMatchTheHardwareSigned() {
        hardwareSigns()
        val unlock = unlockPrompt()
        assertNotNull("the prompt carries a challenge", armedWith)
        androidReportsAMatch()
        assertTrue(unlock.opened)
        assertNull(unlock.error)
    }

    @Test
    fun aLockedAppStaysShutOnAMatchNothingSigned() {
        hardwareDoesNotSign()
        val unlock = unlockPrompt()
        androidReportsAMatch()
        assertFalse(unlock.opened)
        assertEquals(SecurityEngine.ERROR_UNPROVEN, unlock.error)
        assertFalse("the prompt is over", engine.isAuthenticating())
    }

    @Test
    fun withAPassphraseToGetInWithALockedAppStaysShutHoweverOftenItIsTried() {
        assertTrue(OwnerPassphrase.set(context, "lantern tide 9".toCharArray()))
        SecurityVault.getInstance(context).setBiometricOnly(true)
        hardwareDoesNotSign()
        repeat(4) {
            val unlock = unlockPrompt()
            assertNotNull("still armed on try ${it + 1}: proof is never left off here", armedWith)
            androidReportsAMatch()
            assertFalse(unlock.opened)
            assertEquals(SecurityEngine.ERROR_UNPROVEN, unlock.error)
            armedWith = null
            prompt = null
        }
    }

    @Test
    fun insideBioKeyAMatchNothingSignedConfirmsNothing() {
        hardwareDoesNotSign()
        var ran = false
        var refused: String? = null
        engine.confirmWithBiometric(activity, "Clear security activity", onRefused = { refused = it }) { ran = true }
        androidReportsAMatch()
        assertFalse(ran)
        assertEquals(activity.getString(R.string.prompt_unproven), refused)
    }

    @Test
    fun insideBioKeyAMatchTheHardwareSignedConfirms() {
        hardwareSigns()
        var ran = false
        engine.confirmWithBiometric(activity, "Clear security activity") { ran = true }
        androidReportsAMatch()
        assertTrue(ran)
    }

    @Test
    fun insideBioKeyAnUnprovedMatchAsksForThePassphraseWhereItStandsIn() {
        // The owner must not be stuck: with proof never left off, the passphrase is the way through.
        assertTrue(OwnerPassphrase.set(context, "lantern tide 9".toCharArray()))
        SecurityVault.getInstance(context).setBiometricOnly(true)
        hardwareDoesNotSign()
        var ran = false
        confirmOwner(activity, "Remove your passphrase") { ran = true }
        assertNull("the fingerprint is asked for first", PassphraseConfirm.state.title)
        androidReportsAMatch()
        assertFalse(ran)
        assertEquals("Remove your passphrase", PassphraseConfirm.state.title)
    }
}
