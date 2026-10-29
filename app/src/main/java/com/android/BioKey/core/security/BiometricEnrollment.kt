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

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-enrollment").apply { isDaemon = true } }

    /** Whether a strong face or fingerprint is set up; tests set their own. */
    @VisibleForTesting
    internal var hasBiometric: (Context) -> Boolean = SecurityEngine::hasStrongBiometric

    /** Looks at the key; tests set their own. Returns whether it was invalidated (and makes a new one). */
    @VisibleForTesting
    internal var keyInvalidated: () -> Boolean = ::checkKey

    @VisibleForTesting
    internal fun resetForTests() {
        hasBiometric = SecurityEngine::hasStrongBiometric
        keyInvalidated = ::checkKey
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
        val change = when {
            had && !has -> Change.REMOVED
            has && runCatching { keyInvalidated() }.getOrDefault(false) -> Change.CHANGED
            else -> Change.NONE
        }
        if (has != had) prefs.edit { putBoolean(KEY_HAD_BIOMETRIC, has) }
        when (change) {
            Change.CHANGED -> ProtectionGaps.recordEvent(context, ProtectionGaps.Kind.BIOMETRICS_CHANGED)
            Change.REMOVED -> ProtectionGaps.recordEvent(context, ProtectionGaps.Kind.BIOMETRICS_REMOVED)
            Change.NONE -> Unit
        }
        return change
    }

    /** True when Android destroyed the key since it was made: a strong face or fingerprint changed. */
    private fun checkKey(): Boolean {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(ALIAS, null) as? SecretKey ?: run {
            makeKey()
            return false
        }
        return try {
            // Opening it needs no authentication; Android refuses once the enrolment has changed.
            Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key)
            false
        } catch (_: KeyPermanentlyInvalidatedException) {
            store.deleteEntry(ALIAS)
            makeKey()
            true
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
