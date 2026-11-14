package com.android.BioKey.core.security

import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import com.android.BioKey.core.diagnostics.DiagnosticLog
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executors

/**
 * Proof that Android really matched a face, fingerprint or screen lock, beyond its word for it.
 *
 * A prompt that reports a match is otherwise believed as it stands. Here BioKey keeps a signing
 * key in the phone's secure hardware that can sign once only, and only straight after the phone's
 * owner was checked. Each prompt is handed a fresh random challenge to sign with it
 * ([BiometricPrompt.CryptoObject]); the unlock counts only if the signature comes back and is right.
 * A match reported without one opens nothing.
 *
 * What this does not do: it is no defence on a rooted phone, where BioKey itself can be changed.
 *
 * The key says nothing about whose face or fingerprint it was, and is not the key
 * [BiometricEnrollment] watches for changes with: adding a fingerprint leaves this one working, so
 * unlocking carries on while the change is recorded there. Removing the screen lock destroys it,
 * as Android does with every such key; it is made again once there is a screen lock.
 *
 * Never a way to be locked out: where the key can't be made or used (no screen lock, a Keystore
 * that fails), prompts run as they did before, and that is logged. If a signature fails twice
 * running after matches Android reported, proof is left off until BioKey next starts, so a phone
 * whose hardware can't sign costs its owner two extra tries, not their apps.
 */
object UnlockProof {

    private const val ALIAS = "biokey_unlock_proof"
    private const val SIGNATURE = "SHA256withECDSA"
    private const val CHALLENGE_BYTES = 32
    /** Signatures failing this many times running, after matches Android reported, leave proof off. */
    private const val FAILURES_BEFORE_OFF = 2

    /** A prompt's challenge, and what must sign it. */
    class Armed internal constructor(
        val crypto: BiometricPrompt.CryptoObject,
        internal val challenge: ByteArray,
        internal val publicKey: PublicKey
    )

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-unlock-proof").apply { isDaemon = true } }
    private val random = SecureRandom()

    private class Keys(val private: PrivateKey, val public: PublicKey)

    /** The key as last loaded: asking the Keystore for it is slow, and prompts are armed on the main thread. */
    @Volatile private var keys: Keys? = null
    @Volatile private var failures = 0
    @Volatile private var off = false
    @Volatile private var saidUnavailable = false

    /** Makes the key if there is none, off the main thread: generating one takes a while. */
    fun prepareSoon(context: Context) {
        if (off || keys != null) return
        val app = context.applicationContext
        worker.execute { runCatching { load(app, makeIfMissing = true) } }
    }

    /**
     * A fresh challenge for one prompt, or null when there is no proof to ask for (no key yet, no
     * screen lock, the Keystore failing, or proof left off): the prompt then runs without one.
     * Quick enough for the main thread: one call to the Keystore.
     */
    fun arm(context: Context): Armed? = runCatching { armer(context.applicationContext) }.getOrNull()

    /**
     * Whether the prompt [armed] went with really was passed. True with nothing armed: nothing was
     * asked for. Otherwise the challenge must sign, which the phone's hardware allows only after
     * it has checked its owner, and the signature must be right.
     */
    fun confirmed(armed: Armed?): Boolean {
        if (armed == null) return true
        val proven = runCatching { prover(armed) }.getOrDefault(false)
        if (proven) {
            failures = 0
            return true
        }
        val count = ++failures
        if (count >= FAILURES_BEFORE_OFF) {
            off = true
            DiagnosticLog.log("A match couldn't be proved by the phone's hardware $count times running: unlocking without that proof until BioKey restarts")
        } else {
            DiagnosticLog.log("A match Android reported couldn't be proved by the phone's hardware: not unlocked")
        }
        return false
    }

    @VisibleForTesting
    internal var armer: (Context) -> Armed? = ::armWithKeystore

    @VisibleForTesting
    internal var prover: (Armed) -> Boolean = ::proveWithKeystore

    /** The hooks above as they are outside tests ([resetForTests]). */
    private val realHooks: () -> Unit = run {
        val armer = armer
        val prover = prover
        return@run {
            this.armer = armer
            this.prover = prover
        }
    }

    @VisibleForTesting
    internal fun resetForTests() {
        realHooks()
        keys = null
        failures = 0
        off = false
        saidUnavailable = false
    }

    @VisibleForTesting
    internal val isOffForTests: Boolean get() = off

    /** A [Armed] around [signature], for tests: what [arm] returns, without a Keystore. */
    @VisibleForTesting
    internal fun armedForTests(signature: Signature, challenge: ByteArray, publicKey: PublicKey) =
        Armed(BiometricPrompt.CryptoObject(signature), challenge, publicKey)

    private fun armWithKeystore(app: Context): Armed? {
        if (off) return null
        val k = keys ?: run {
            // Not loaded yet in this process: loaded (and made, if need be) off the main thread,
            // for the next prompt. This one runs as prompts always have.
            prepareSoon(app)
            return null
        }
        val signature = try {
            Signature.getInstance(SIGNATURE).apply { initSign(k.private) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            // The screen lock was removed since: Android destroyed the key. A new one next time.
            keys = null
            worker.execute { runCatching { load(app, makeIfMissing = true, replace = true) } }
            return null
        } catch (e: Exception) {
            // Loaded afresh for the next prompt, in case it is the copy held here that has gone stale.
            keys = null
            unavailable("it couldn't be used (${e.javaClass.simpleName})")
            return null
        }
        return Armed(BiometricPrompt.CryptoObject(signature), ByteArray(CHALLENGE_BYTES).also(random::nextBytes), k.public)
    }

    private fun proveWithKeystore(armed: Armed): Boolean {
        // The object handed to the prompt, not whatever came back with the result: this one is
        // tied to the operation the hardware is holding.
        val signature = armed.crypto.signature ?: return false
        signature.update(armed.challenge)
        val signed = signature.sign()
        return Signature.getInstance(SIGNATURE).run {
            initVerify(armed.publicKey)
            update(armed.challenge)
            verify(signed)
        }
    }

    @Synchronized
    private fun load(app: Context, makeIfMissing: Boolean, replace: Boolean = false) {
        if (keys != null && !replace) return
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (replace && store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
            var private = store.getKey(ALIAS, null) as? PrivateKey
            var public = store.getCertificate(ALIAS)?.publicKey
            if ((private == null || public == null) && makeIfMissing) {
                if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
                public = makeKey()
                private = store.getKey(ALIAS, null) as? PrivateKey
            }
            if (private != null && public != null) {
                keys = Keys(private, public)
                saidUnavailable = false
            }
        } catch (e: Exception) {
            // No screen lock (Android refuses to make such a key without one), or the Keystore failing.
            unavailable("it couldn't be made (${e.javaClass.simpleName})")
        }
    }

    private fun unavailable(why: String) {
        if (saidUnavailable) return
        saidUnavailable = true
        DiagnosticLog.log("Unlocking without proof from the phone's hardware: $why")
    }

    /**
     * Usable once per check of the owner, by a strong face or fingerprint or by the screen lock
     * (a prompt that allows the screen lock needs a key that does). Not destroyed by a new
     * fingerprint: see the class comment.
     */
    private fun makeKey(): PublicKey =
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    .setInvalidatedByBiometricEnrollment(false)
                    .build()
            )
        }.generateKeyPair().public
}
