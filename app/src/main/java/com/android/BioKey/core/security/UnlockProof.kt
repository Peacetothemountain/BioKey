package com.android.BioKey.core.security

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.android.BioKey.core.diagnostics.DiagnosticLog
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

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
 * that fails), prompts run as they did before. If a signature fails twice running after matches
 * Android reported, proof is left off for an hour (or until BioKey next starts) and then tried
 * again, so a phone whose hardware can't sign costs its owner two extra tries, not their apps. Either is written into security activity,
 * once until a match is proved again, so the owner is told rather than left to find it in a log.
 *
 * Except where the owner's passphrase stands in for the screen lock
 * ([SecurityEngine.passphraseStandsIn]): there the owner has another way in, so nothing opens on
 * Android's word alone. Proof is never left off, a match that can't be proved goes on opening
 * nothing, and where there is no proof to ask for at all (the key can't be made or used) the
 * prompt isn't shown: the passphrase is asked for instead.
 */
object UnlockProof {

    private const val ALIAS = "biokey_unlock_proof"
    private const val SIGNATURE = "SHA256withECDSA"
    private const val CHALLENGE_BYTES = 32
    /** Signatures failing this many times running, after matches Android reported, leave proof off. */
    private const val FAILURES_BEFORE_OFF = 2
    /** How long a prompt waits for the key to be loaded, the first time in a run of BioKey. */
    private const val LOAD_WAIT_MS = 300L
    /** Left off, proof is tried again after this long: a fault that has passed mustn't cost it for as long as BioKey runs, which can be weeks. */
    private const val OFF_RETRY_MS = 60 * 60_000L
    private const val PREFS = "biokey_unlock_proof"
    /** Security activity already says matches are going unproved: not said again until one is proved. */
    private const val KEY_SAID_UNPROVED = "said_unproved"

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
    /** When proof was left off, on the clock since boot ([sinceBoot]). */
    @Volatile private var offAt = 0L
    @Volatile private var saidNotReady = false

    /** The clock since boot; tests set their own. */
    @VisibleForTesting
    internal var sinceBoot: () -> Long = android.os.SystemClock::elapsedRealtime
    @Volatile private var saidUnavailable = false
    @Volatile private var loading: Future<*>? = null

    /** Makes the key if there is none, off the main thread: generating one takes a while. */
    fun prepareSoon(context: Context) {
        if (off || keys != null) return
        val app = context.applicationContext
        loading?.takeIf { !it.isDone }?.let { return }
        loading = worker.submit { runCatching { load(app, makeIfMissing = true) } }
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
    fun confirmed(context: Context, armed: Armed?): Boolean {
        if (armed == null) return true
        val app = context.applicationContext
        val proven = runCatching { prover(armed) }.getOrDefault(false)
        if (proven) {
            failures = 0
            provedAgain(app)
            return true
        }
        val count = ++failures
        when {
            count < FAILURES_BEFORE_OFF ->
                DiagnosticLog.log("A match Android reported couldn't be proved by the phone's hardware: not unlocked")
            // The owner has their passphrase to get in with: nothing is opened on Android's word alone.
            runCatching { neverLeftOff(app) }.getOrDefault(false) ->
                DiagnosticLog.log("A match couldn't be proved by the phone's hardware $count times running: not unlocked (the passphrase opens it)")
            else -> {
                off = true
                offAt = sinceBoot()
                DiagnosticLog.log("A match couldn't be proved by the phone's hardware $count times running: unlocking without that proof for an hour, then it is tried again")
                unproved(app)
            }
        }
        return false
    }

    /** Logged once per run of BioKey: the passphrase was asked for because no proof could be. */
    @Volatile private var saidPassphraseInstead = false

    /** No proof could be asked of the phone's hardware where the passphrase stands in, so it was asked for instead. */
    fun saidInstead(context: Context) {
        if (saidPassphraseInstead) return
        saidPassphraseInstead = true
        DiagnosticLog.log("No proof can be asked of the phone's hardware: the passphrase is asked for, not a face, fingerprint or screen lock")
    }

    /**
     * Whether proof stays on however often it fails: where the owner's passphrase opens what a
     * face, fingerprint or screen lock can't be proved for.
     */
    @VisibleForTesting
    internal var neverLeftOff: (Context) -> Boolean = { SecurityEngine.passphraseStandsIn(it) }

    /** Writes into security activity that matches are going unproved; false if it couldn't be (setup not finished). Replaced in tests. */
    @VisibleForTesting
    internal var record: (Context) -> Boolean = { ProtectionGaps.recordEvent(it, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, ProtectionGaps.Change.UNLOCK_PROOF_OFF.encode()) != null }

    /**
     * Matches are being taken on Android's word, on a phone that has a screen lock (without one
     * there is nothing to prove, and that has its own record): said in security activity, once.
     */
    private fun unproved(app: Context) {
        runCatching {
            if (app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) return
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getBoolean(KEY_SAID_UNPROVED, false)) return
            // Marked as said only once it has been: a record refused (setup not finished yet)
            // is made at the next prompt that runs unproved, not lost for good.
            if (record(app)) p.edit { putBoolean(KEY_SAID_UNPROVED, true) }
        }
    }

    /**
     * A prompt is about to run with no proof to ask for. Where the passphrase stands in, no prompt
     * runs (the passphrase is asked for instead), so there is nothing to record.
     */
    private fun unprovedWithoutPassphrase(app: Context) {
        if (!passphraseInstead(app)) unproved(app)
    }

    /** No prompt runs unproved here: with no proof to ask for, the passphrase is asked for. */
    private fun passphraseInstead(app: Context) = runCatching { neverLeftOff(app) }.getOrDefault(false)

    private fun provedAgain(app: Context) {
        runCatching {
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getBoolean(KEY_SAID_UNPROVED, false)) p.edit { remove(KEY_SAID_UNPROVED) }
        }
        // The key is there and working: the next time it isn't is news, in the log too.
        saidNotReady = false
    }

    @VisibleForTesting
    internal var armer: (Context) -> Armed? = ::armWithKeystore

    @VisibleForTesting
    internal var prover: (Armed) -> Boolean = ::proveWithKeystore

    /** The hooks above as they are outside tests ([resetForTests]). */
    private val realHooks: () -> Unit = run {
        val armer = armer
        val prover = prover
        val neverLeftOff = neverLeftOff
        val record = record
        val sinceBoot = sinceBoot
        val signer = signer
        return@run {
            this.signer = signer
            this.sinceBoot = sinceBoot
            this.armer = armer
            this.prover = prover
            this.neverLeftOff = neverLeftOff
            this.record = record
        }
    }

    @VisibleForTesting
    internal fun resetForTests() {
        realHooks()
        keys = null
        failures = 0
        off = false
        saidUnavailable = false
        saidPassphraseInstead = false
        saidNotReady = false
        offAt = 0L
        loading = null
    }

    @VisibleForTesting
    internal val isOffForTests: Boolean get() = off

    /** Readies a signature with the key: the call to the Keystore that fails when the key is gone. Replaced in tests. */
    @VisibleForTesting
    internal var signer: (PrivateKey) -> Signature = { key -> Signature.getInstance(SIGNATURE).apply { initSign(key) } }

    /** As if this key had been loaded from the Keystore, which tests don't have. */
    @VisibleForTesting
    internal fun loadedForTests(private: PrivateKey, public: PublicKey) {
        keys = Keys(private, public)
    }

    /** A [Armed] around [signature], for tests: what [arm] returns, without a Keystore. */
    @VisibleForTesting
    internal fun armedForTests(signature: Signature, challenge: ByteArray, publicKey: PublicKey) =
        Armed(BiometricPrompt.CryptoObject(signature), challenge, publicKey)

    private fun armWithKeystore(app: Context): Armed? {
        // Left off before a passphrase was set: with one standing in, proof is never left off.
        if (off && passphraseInstead(app)) {
            off = false
            failures = 0
        }
        if (off) {
            if (sinceBoot() - offAt < OFF_RETRY_MS) {
                // Recorded when it was left off; again here in case that record couldn't be made then.
                unprovedWithoutPassphrase(app)
                return null
            }
            // An hour on: whatever stopped the hardware signing may have passed. Tried again, with
            // the same two failures allowed before it is left off for another hour.
            off = false
            failures = 0
            DiagnosticLog.log("Proof from the phone's hardware is asked for again")
        }
        val k = keys ?: run {
            // Not loaded yet in this process: loaded (and made, if need be) off the main thread.
            // Loading takes a few hundredths of a second, so this prompt waits that long for it
            // rather than run unproved; making a new key can take longer, and then it does.
            prepareSoon(app)
            runCatching { loading?.get(LOAD_WAIT_MS, TimeUnit.MILLISECONDS) }
            keys ?: run {
                // Still not there (a new key being made takes longer): said, so a prompt that ran
                // without proof can be told from one that had it.
                // Not where the passphrase is asked for instead: no prompt runs unproved there.
                if (!passphraseInstead(app)) {
                    if (!saidNotReady && !saidUnavailable) {
                        saidNotReady = true
                        DiagnosticLog.log("The key that proves a match isn't ready yet: this prompt runs without that proof")
                    }
                    // Said in security activity as the prompt runs (once per outage: [unproved]),
                    // whatever the reason there is no key. Not where there is no Keystore at all
                    // (BioKey's tests): there is nothing a phone's owner could be told.
                    if (Security.getProvider("AndroidKeyStore") != null) unproved(app)
                }
                return null
            }
        }
        val signature = try {
            signer(k.private)
        } catch (_: KeyPermanentlyInvalidatedException) {
            // The screen lock was removed since: Android destroyed the key. A new one next time.
            // Said, so a prompt that ran without proof can be told from one that had it.
            DiagnosticLog.log(
                if (passphraseInstead(app)) "The key that proves a match was destroyed (the screen lock was removed since): a new key is made"
                else "The key that proves a match was destroyed (the screen lock was removed since): this prompt runs without that proof, and a new key is made"
            )
            keys = null
            worker.execute { runCatching { load(app, makeIfMissing = true, replace = true) } }
            unprovedWithoutPassphrase(app)
            return null
        } catch (e: Exception) {
            // Loaded afresh for the next prompt, in case it is the copy held here that has gone stale.
            keys = null
            unavailable("it couldn't be used (${e.javaClass.simpleName})")
            unprovedWithoutPassphrase(app)
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
            // Every phone has a Keystore; where there is none at all (BioKey's tests), there is
            // nothing a phone's owner could be told.
            unavailable("it couldn't be made (${e.javaClass.simpleName})")
        }
    }

    /**
     * Logged, once until a key loads. Security activity is written as a prompt runs without proof
     * ([armWithKeystore]), not here: this can be a background attempt with no prompt behind it.
     */
    private fun unavailable(why: String) {
        if (saidUnavailable) return
        saidUnavailable = true
        DiagnosticLog.log("No proof from the phone's hardware can be asked for: $why")
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
