package com.android.BioKey.core.security

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.diagnostics.logDuration
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The owner's own passphrase: an optional second way into locked apps and BioKey when "Face or
 * fingerprint only" is on and the face or fingerprint can't be used (a wet finger, a sensor
 * locked out). It is BioKey's alone, so someone who knows the phone's screen lock doesn't know it.
 *
 * Only a verifier is kept, never the passphrase. Misses are counted where a restart can't reset
 * them, and too many in a row make the next try wait.
 *
 * API CONTRACT (2.0): the signatures below are what Settings and the lock screen are built
 * against. The bodies are filled in separately; change a signature only with the callers.
 */
object OwnerPassphrase {

    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128

    private const val PREFS = "biokey_owner_passphrase"
    private const val PEPPER_ALIAS = "biokey_owner_passphrase_pepper"
    private const val FORMAT_VERSION = 1
    private const val DEFAULT_ROUNDS = 210_000
    private const val MIN_ROUNDS = 100_000
    private const val MAX_ROUNDS = 2_000_000
    private const val SALT_BYTES = 32
    private const val VERIFIER_BYTES = 32
    private const val KEY_BITS = 256
    private const val LONGEST_WAIT_MS = 30 * 60_000L
    /** Far past the tenth, where the wait stops growing: counting on adds nothing. */
    private const val MAX_COUNTED_MISSES = 1_000_000

    private const val KEY_VERSION = "version"
    private const val KEY_ROUNDS = "rounds"
    private const val KEY_SALT = "salt"
    private const val KEY_VERIFIER = "verifier"
    private const val KEY_MISSES = "misses"
    private const val KEY_WAIT_DURATION = "wait_duration"
    private const val KEY_WAIT_START_ELAPSED = "wait_start_elapsed"
    private const val KEY_WAIT_BOOT = "wait_boot"

    /** What [verify] found. */
    sealed interface Outcome {
        /** The passphrase is right; the count of misses is cleared. */
        data object Match : Outcome

        /** Wrong. [waitMs] is above zero when this miss started a wait before the next try. */
        data class Mismatch(val waitMs: Long) : Outcome

        /** Not tried at all: too many misses, and the wait has [waitMs] left. */
        data class Wait(val waitMs: Long) : Outcome

        /** No passphrase is set (or it can't be read yet: the phone hasn't been unlocked since it started). */
        data object NotSet : Outcome
    }

    private val lock = Any()
    @Volatile private var prefs: SharedPreferences? = null
    private val isSetState = MutableStateFlow(false)

    /** Whether a passphrase is set, as last read; changes as one is set or removed. */
    val isSetFlow: StateFlow<Boolean> get() = isSetState

    @VisibleForTesting
    internal var rounds: Int = DEFAULT_ROUNDS

    @VisibleForTesting
    internal var elapsed: () -> Long = SystemClock::elapsedRealtime

    @Volatile private var cachedBoot: Int? = null

    @VisibleForTesting
    internal var bootCount: (Context) -> Int = { context ->
        cachedBoot ?: Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).also { cachedBoot = it }
    }

    /** Computes HMAC-SHA256 under the Keystore pepper key; tests supply a software HMAC. */
    @VisibleForTesting
    internal var pepper: (bytes: ByteArray, createIfMissing: Boolean) -> ByteArray = ::keystorePepper

    /** The hooks above as they are outside tests ([restoreHooksForTests]): taken as BioKey starts, so they are said once. */
    /** Deletes the Keystore pepper key, when the passphrase it belonged to is removed; tests set their own. */
    @VisibleForTesting
    internal var forgetPepper: () -> Unit = ::deleteKeystorePepper

    private val realHooks: () -> Unit = run {
        val rounds = rounds
        val elapsed = elapsed
        val bootCount = bootCount
        val pepper = pepper
        val record = record
        val forgetPepper = forgetPepper
        return@run {
            this.rounds = rounds
            this.elapsed = elapsed
            this.bootCount = bootCount
            this.pepper = pepper
            this.record = record
            this.forgetPepper = forgetPepper
            cachedBoot = null
        }
    }

    /** Puts back every hook a test replaced, so none outlives it into the next test class in the JVM. */
    @VisibleForTesting
    internal fun restoreHooksForTests() = realHooks()

    /** Forgets what was loaded and restores production hooks, for test isolation. */
    @VisibleForTesting
    internal fun resetForTests() {
        synchronized(lock) {
            prefs = null
            isSetState.value = false
            realHooks()
        }
    }

    /** Reads storage once per process; null before the first unlock after a restart. */
    private fun load(context: Context): SharedPreferences? {
        prefs?.let { return it }
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return null
        synchronized(lock) {
            prefs?.let { return it }
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            isSetState.value = p.contains(KEY_VERIFIER)
            prefs = p
            return p
        }
    }

    /** Whether a passphrase is set. Cheap: callable on the main thread. */
    fun isSet(context: Context): Boolean {
        load(context) ?: return false
        return isSetState.value
    }

    /**
     * Sets or replaces the passphrase and clears the count of misses. The caller has already
     * confirmed the owner. Slow (key derivation): never on the main thread. False if [passphrase]
     * is shorter than [MIN_LENGTH] or longer than [MAX_LENGTH], or it couldn't be stored.
     */
    @WorkerThread
    fun set(context: Context, passphrase: CharArray): Boolean = synchronized(lock) {
        if (passphrase.size < MIN_LENGTH || passphrase.size > MAX_LENGTH) return false
        val p = load(context) ?: return false
        val useRounds = rounds
        if (useRounds !in MIN_ROUNDS..MAX_ROUNDS) return false
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        var verifier: ByteArray? = null
        try {
            verifier = try {
                computeVerifier(passphrase, salt, useRounds, createKey = true)
            } catch (_: Exception) {
                return false
            }
            if (verifier.size != VERIFIER_BYTES) return false
            val encoder = Base64.getEncoder()
            val saltText = encoder.encodeToString(salt)
            val verifierText = encoder.encodeToString(verifier)
            val replaces = p.contains(KEY_VERIFIER)
            p.edit(commit = true) {
                putInt(KEY_VERSION, FORMAT_VERSION)
                putInt(KEY_ROUNDS, useRounds)
                putString(KEY_SALT, saltText)
                putString(KEY_VERIFIER, verifierText)
                putInt(KEY_MISSES, 0)
                remove(KEY_WAIT_DURATION)
                remove(KEY_WAIT_START_ELAPSED)
                remove(KEY_WAIT_BOOT)
            }
            isSetState.value = true
            DiagnosticLog.log(if (replaces) "Passphrase changed" else "Passphrase set")
            runCatching { record(context, if (replaces) ProtectionGaps.Change.PASSPHRASE_CHANGED else ProtectionGaps.Change.PASSPHRASE_SET) }
            return true
        } finally {
            salt.fill(0)
            verifier?.fill(0)
        }
    }

    /** Removes the passphrase and its count of misses. The caller has already confirmed the owner. */
    fun clear(context: Context): Unit = synchronized(lock) {
        val p = load(context) ?: return
        val wasSet = p.contains(KEY_VERIFIER)
        p.edit(commit = true) { clear() }
        isSetState.value = false
        // Its key goes with it, so nothing of a removed passphrase is left on the phone. A Keystore
        // that fails here leaves a key that signs nothing anyone keeps; the next passphrase's
        // check value is made with whichever key is there then.
        runCatching { forgetPepper() }
        if (wasSet) {
            DiagnosticLog.log("Passphrase removed")
            runCatching { record(context, ProtectionGaps.Change.PASSPHRASE_REMOVED) }
        }
    }

    /**
     * Writes a change of passphrase into security activity: it is a way into every locked app, so
     * one set, replaced or removed is shown to the owner like any other change of protection.
     * Replaced in tests.
     */
    @VisibleForTesting
    internal var record: (Context, ProtectionGaps.Change) -> Unit = { context, change ->
        ProtectionGaps.recordEvent(context.applicationContext, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, change.encode())
    }

    /**
     * Checks [passphrase]. Counts a miss before it answers, so killing BioKey mid-check can't win
     * a free try. Slow (key derivation): never on the main thread.
     */
    @WorkerThread
    fun verify(context: Context, passphrase: CharArray): Outcome = synchronized(lock) {
        val app = context.applicationContext
        val p = load(context) ?: return Outcome.NotSet
        if (!p.contains(KEY_VERIFIER)) return Outcome.NotSet
        val remaining = activeWaitMs(app, p)
        if (remaining > 0L) return Outcome.Wait(remaining)

        val version = p.getInt(KEY_VERSION, 0)
        val storedRounds = p.getInt(KEY_ROUNDS, 0)
        val salt = decodeBytes(p.getString(KEY_SALT, null), SALT_BYTES)
        val expected = decodeBytes(p.getString(KEY_VERIFIER, null), VERIFIER_BYTES)
        if (version != FORMAT_VERSION || storedRounds !in MIN_ROUNDS..MAX_ROUNDS || salt == null || expected == null) {
            salt?.fill(0)
            expected?.fill(0)
            return Outcome.Mismatch(0L)
        }

        try {
            // Kept within bounds, whatever the file says: a count that wrapped round would start no wait.
            val previousMisses = p.getInt(KEY_MISSES, 0).coerceIn(0, MAX_COUNTED_MISSES)
            val misses = previousMisses + 1
            val waitMs = waitForCount(misses)
            val startElapsed = elapsed()
            val startBoot = bootCount(app)
            p.edit(commit = true) {
                putInt(KEY_MISSES, misses)
                if (waitMs > 0L) {
                    putLong(KEY_WAIT_DURATION, waitMs)
                    putLong(KEY_WAIT_START_ELAPSED, startElapsed)
                    putInt(KEY_WAIT_BOOT, startBoot)
                }
            }

            if (passphrase.isEmpty() || passphrase.size > MAX_LENGTH) {
                logMiss(waitMs)
                return Outcome.Mismatch(waitMs)
            }

            val actual = try {
                computeVerifier(passphrase, salt, storedRounds, createKey = false)
            } catch (_: Exception) {
                // The public Outcome type has no failure case, and answering NotSet would make a
                // set passphrase look removed (including when the Keystore was cleared). Undo the
                // pre-counted miss and return Mismatch(0) without starting a wait.
                p.edit(commit = true) {
                    putInt(KEY_MISSES, previousMisses)
                    remove(KEY_WAIT_DURATION)
                    remove(KEY_WAIT_START_ELAPSED)
                    remove(KEY_WAIT_BOOT)
                }
                DiagnosticLog.log("Passphrase could not be checked")
                return Outcome.Mismatch(0L)
            }

            val matched = try {
                MessageDigest.isEqual(actual, expected)
            } finally {
                actual.fill(0)
            }

            if (matched) {
                p.edit(commit = true) {
                    putInt(KEY_MISSES, 0)
                    remove(KEY_WAIT_DURATION)
                    remove(KEY_WAIT_START_ELAPSED)
                    remove(KEY_WAIT_BOOT)
                }
                return Outcome.Match
            }

            logMiss(waitMs)
            return Outcome.Mismatch(waitMs)
        } finally {
            salt.fill(0)
            expected.fill(0)
        }
    }

    /** How long until the next try is allowed, in milliseconds; zero when one is allowed now. Cheap. */
    fun waitRemainingMs(context: Context): Long = synchronized(lock) {
        val p = load(context) ?: return 0L
        if (!p.contains(KEY_VERIFIER)) return 0L
        return activeWaitMs(context.applicationContext, p)
    }

    /**
     * How many more wrong tries there are before one starts a wait: 2 after three in a row (the
     * fifth starts one), 1 once each starts one. Null when no passphrase is set (or none can be
     * read yet). Only reads: it changes nothing, the count included, and takes no lock, so it never
     * waits on a check running. Cheap: callable on the main thread.
     */
    fun triesBeforeWait(context: Context): Int? {
        val p = load(context) ?: return null
        if (!p.contains(KEY_VERIFIER)) return null
        val misses = p.getInt(KEY_MISSES, 0).coerceIn(0, MAX_COUNTED_MISSES)
        var next = misses + 1
        while (waitForCount(next) <= 0L) next++
        return next - misses
    }

    private fun activeWaitMs(app: Context, p: SharedPreferences): Long {
        // Never longer than the longest wait there is, whatever the file says.
        val duration = p.getLong(KEY_WAIT_DURATION, 0L).coerceAtMost(LONGEST_WAIT_MS)
        if (duration <= 0L) return 0L
        val nowElapsed = elapsed()
        val nowBoot = bootCount(app)
        val startElapsed = p.getLong(KEY_WAIT_START_ELAPSED, 0L)
        val startBoot = p.getInt(KEY_WAIT_BOOT, -1)
        if (nowBoot != startBoot || nowElapsed < startElapsed) {
            p.edit(commit = true) {
                putLong(KEY_WAIT_START_ELAPSED, nowElapsed)
                putInt(KEY_WAIT_BOOT, nowBoot)
            }
            return duration
        }
        val remaining = duration - (nowElapsed - startElapsed)
        if (remaining <= 0L) {
            p.edit(commit = true) {
                remove(KEY_WAIT_DURATION)
                remove(KEY_WAIT_START_ELAPSED)
                remove(KEY_WAIT_BOOT)
            }
            return 0L
        }
        return remaining
    }

    private fun waitForCount(misses: Int): Long = when {
        misses < 5 -> 0L
        misses <= 7 -> 30_000L
        misses <= 9 -> 5 * 60_000L
        else -> 30 * 60_000L
    }

    /**
     * A wait starting is logged. Each wrong try isn't, here: that is a failed unlock, logged with
     * the others and only when the owner has failed unlock alerts on ([UnlockAttempts]).
     */
    private fun logMiss(waitMs: Long) {
        if (waitMs <= 0L) return
        DiagnosticLog.log("Passphrase tries paused for ${logDuration(waitMs)}")
    }

    private fun decodeBytes(encoded: String?, expectedSize: Int): ByteArray? {
        if (encoded == null) return null
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.size != expectedSize) {
            decoded.fill(0)
            return null
        }
        return decoded
    }

    private fun computeVerifier(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        createKey: Boolean
    ): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        var derived: ByteArray? = null
        try {
            val secret = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec)
            try {
                derived = secret.encoded ?: throw IllegalStateException("Key has no bytes")
                return pepper(derived, createKey)
            } finally {
                runCatching { secret.destroy() }
            }
        } finally {
            derived?.fill(0)
            spec.clearPassword()
        }
    }

    private fun deleteKeystorePepper() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(PEPPER_ALIAS)) store.deleteEntry(PEPPER_ALIAS)
    }

    private fun keystorePepper(bytes: ByteArray, createIfMissing: Boolean): ByteArray {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (store.getKey(PEPPER_ALIAS, null) as? SecretKey) ?: if (createIfMissing) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(PEPPER_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setUserAuthenticationRequired(false)
                        .setInvalidatedByBiometricEnrollment(false)
                        .build()
                )
            }.generateKey()
        } else {
            throw KeyStoreException("Pepper key missing")
        }
        return Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
            init(key)
            doFinal(bytes)
        }
    }
}
