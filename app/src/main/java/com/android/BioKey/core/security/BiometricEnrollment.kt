package com.android.BioKey.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Notices when the phone's faces or fingerprints change: someone who knows the PIN can add their
 * own in Android's settings, and then "Face or fingerprint only" lets them in. Android can't be
 * stopped from allowing that, but BioKey can tell the owner it happened.
 *
 * It keeps one Keystore key that Android itself destroys when a strong face or fingerprint is
 * added or removed ([KeyGenParameterSpec.Builder.setInvalidatedByBiometricEnrollment]); the key is
 * never used to encrypt anything, only looked at. When it's gone, or the phone no longer has any
 * strong face or fingerprint where it had one, a record goes into security activity
 * ([ProtectionGaps]) and the owner is told at the next unlock. Nothing about the biometrics
 * themselves is ever seen: Android doesn't tell apps.
 */
object BiometricEnrollment {

    enum class Change { NONE, CHANGED, REMOVED }

    private const val ALIAS = "biokey_biometric_enrollment"
    private const val PREFS = "biokey_biometric_enrollment"
    /** A strong face or fingerprint was set up when last looked. */
    private const val KEY_HAD_BIOMETRIC = "had_biometric"
    /** The key has been made: from then on, its absence means Android destroyed it. */
    private const val KEY_MADE = "key_made"
    /** A change was found and is being recorded: written before the key is replaced, so it isn't lost. */
    private const val KEY_PENDING = "pending"

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-enrollment").apply { isDaemon = true } }

    /** Whether a strong face or fingerprint is set up; tests set their own. */
    @VisibleForTesting
    internal var hasBiometric: (Context) -> Boolean = SecurityEngine::hasEnrolledStrongBiometric

    /** Looks at the key; tests set their own. */
    @VisibleForTesting
    internal var keyState: () -> KeyState = ::checkKey

    enum class KeyState { VALID, INVALIDATED, MISSING }

    @VisibleForTesting
    internal fun resetForTests() {
        hasBiometric = SecurityEngine::hasEnrolledStrongBiometric
        keyState = ::checkKey
    }

    /** Looks off the main thread (the Keystore is slow), and records what changed. */
    fun checkSoon(context: Context) {
        val app = context.applicationContext
        worker.execute { runCatching { check(app) } }
    }

    /** What changed since last time, recorded in security activity. */
    @VisibleForTesting
    internal fun check(context: Context): Change {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val has = hasBiometric(context)
        val had = prefs.getBoolean(KEY_HAD_BIOMETRIC, false)
        val made = prefs.getBoolean(KEY_MADE, false)
        // A change found last time but not yet recorded (the process died in between).
        val pending = prefs.getString(KEY_PENDING, null)?.let { runCatching { Change.valueOf(it) }.getOrNull() }
        val change = pending ?: when {
            had && !has -> Change.REMOVED
            // Set up on a phone that had none, once BioKey was watching: added.
            !had && has && made -> Change.CHANGED
            has -> when (runCatching { keyState() }.getOrNull()) {
                KeyState.INVALIDATED -> Change.CHANGED
                // Gone although made: Android destroyed it (removing the screen lock does that too).
                KeyState.MISSING -> if (made) Change.CHANGED else Change.NONE
                // VALID, or the Keystore failing to answer: nothing to say.
                else -> Change.NONE
            }
            else -> Change.NONE
        }
        // Noted durably before the key is replaced and the record is written.
        if (change != Change.NONE) prefs.edit(commit = true) { putString(KEY_PENDING, change.name) }
        if (has) runCatching { renewKeyIfNeeded() }.onSuccess { prefs.edit { putBoolean(KEY_MADE, true) } }
        prefs.edit { putBoolean(KEY_HAD_BIOMETRIC, has) }
        when (change) {
            Change.CHANGED -> ProtectionGaps.recordEvent(context, ProtectionGaps.Kind.BIOMETRICS_CHANGED)
            Change.REMOVED -> ProtectionGaps.recordEvent(context, ProtectionGaps.Kind.BIOMETRICS_REMOVED)
            Change.NONE -> Unit
        }
        if (change != Change.NONE) prefs.edit { remove(KEY_PENDING) }
        return change
    }

    /** Whether the key is there and usable; Android destroys or invalidates it when biometrics change. */
    private fun checkKey(): KeyState {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(ALIAS, null) as? SecretKey ?: return KeyState.MISSING
        return try {
            // Opening it needs no authentication; Android refuses once the enrolment has changed.
            Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key)
            KeyState.VALID
        } catch (_: KeyPermanentlyInvalidatedException) {
            KeyState.INVALIDATED
        }
    }

    /** A fresh key when there's none or it was invalidated, so the next change is seen too. */
    @VisibleForTesting
    internal var renewKeyIfNeeded: () -> Unit = {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (checkKey() != KeyState.VALID) {
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
            makeKey()
        }
    }

    private fun makeKey() {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            )
        }.generateKey()
    }
}
