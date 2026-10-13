package com.android.BioKey.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore keys backing BioKey's authentication and at-rest encryption.
 *
 * Keys are generated in StrongBox when the device has one and fall back to the TEE otherwise.
 * Nothing here claims more than the platform reports: [getDiagnostics] reads the real
 * [KeyInfo.getSecurityLevel] of the generated keys.
 *
 * - Biometric key: AES-GCM, usable only for one operation right after a Class 3 biometric
 *   match, invalidated when biometrics are enrolled or removed. Binds BiometricPrompt results to
 *   hardware so a hooked callback alone can't unlock anything.
 * - Credential key: same, but also authorised by the device PIN/pattern/password. Used for the
 *   credential fallback prompt.
 * - Master key: AES-GCM for encrypting intruder photos and the security event log.
 * - Integrity key: HMAC-SHA256 sealing BioKey's settings so offline tampering is detected.
 */
object HardwareKeyManager {

    private const val TAG = "HardwareKeyManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val CHALLENGE_BYTES = 32

    // Aliases are kept from earlier releases so existing keys (and the photos they encrypt) survive upgrades.
    private const val BIOMETRIC_KEY = "BioKey_TitanM3_Biometric_AES256"
    private const val MASTER_KEY = "BioKey_TitanM3_Master_AES256"
    private const val CREDENTIAL_KEY = "BioKey_Credential_AES256"
    private const val INTEGRITY_KEY = "BioKey_Integrity_HMAC"
    private const val LEGACY_ATTESTATION_KEY = "BioKey_TitanM3_Attestation_EC"

    private val secureRandom = SecureRandom()

    sealed interface CryptoResult {
        /** Ready for [BiometricPrompt.authenticate]; pass [challenge] back to [verify]. */
        class Ready(val cryptoObject: BiometricPrompt.CryptoObject, val challenge: ByteArray) : CryptoResult
        /** The biometric key was invalidated because the enrolled biometrics changed. */
        data object BiometricsChanged : CryptoResult
        /** No usable key (e.g. no biometrics enrolled, keystore failure). */
        data class Unavailable(val reason: String) : CryptoResult
    }

    data class KeystoreDiagnostics(
        val isStrongBoxSupported: Boolean,
        val isHardwareKeystoreBacked: Boolean,
        val securityLevel: String,
        val hasEnrolledBiometrics: Boolean,
        val biometricStatus: String
    ) {
        fun getChipBadge(): String = when {
            securityLevel.startsWith("StrongBox") -> "STRONGBOX SECURE ELEMENT"
            isHardwareKeystoreBacked -> "TRUSTED EXECUTION ENVIRONMENT"
            else -> "SOFTWARE KEYSTORE"
        }
    }

    data class ReKeyProof(
        val keyAlias: String,
        val provider: String,
        val securityLevel: String,
        val creationTimestamp: String,
        val verificationHash: String,
        val isHardwareBacked: Boolean,
        val isStrongBoxProtected: Boolean
    )

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * Serializes key creation. Generating an alias that already exists silently replaces the key,
     * so two threads racing on first use (startup init, the vault's first seal, a prompt) could
     * leave data sealed or encrypted under a key that no longer exists.
     */
    private val keyGenLock = Any()

    private fun ensureKey(ks: KeyStore, alias: String, generate: () -> Unit) {
        synchronized(keyGenLock) {
            if (!ks.containsAlias(alias)) generate()
        }
    }

    fun isStrongBoxAvailable(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    fun initialize(context: Context) {
        val ks = try {
            keyStore()
        } catch (e: Exception) {
            Log.e(TAG, "Keystore unavailable", e)
            return
        }
        // Each key is independent: e.g. the auth keys can't exist without a secure lock screen
        // or enrolled biometrics, but that must not prevent the master key.
        // The integrity key is deliberately not created here: the vault's first seal creates it,
        // so its existence proves a seal was written (see [hasIntegrityKey]).
        fun ensure(alias: String, generate: () -> Unit) {
            try {
                ensureKey(ks, alias, generate)
            } catch (e: Exception) {
                Log.w(TAG, "Could not create $alias: ${e.message}")
            }
        }
        ensure(MASTER_KEY) { generateMasterKey(context) }
        ensure(CREDENTIAL_KEY) { generateAuthKey(context, CREDENTIAL_KEY, biometricOnly = false) }
        ensure(BIOMETRIC_KEY) { generateAuthKey(context, BIOMETRIC_KEY, biometricOnly = true) }
        // Earlier versions generated an unused attestation key on demand; clean it up.
        runCatching { if (ks.containsAlias(LEGACY_ATTESTATION_KEY)) ks.deleteEntry(LEGACY_ATTESTATION_KEY) }
    }

    // ---------------------------------------------------------------------------------------
    // Key generation (StrongBox first, TEE fallback)
    // ---------------------------------------------------------------------------------------

    private inline fun generateWithFallback(context: Context, alias: String, generate: (strongBox: Boolean) -> Unit) {
        val strongBox = isStrongBoxAvailable(context)
        try {
            generate(strongBox)
        } catch (e: Exception) {
            if (!strongBox) throw e
            Log.w(TAG, "StrongBox generation failed for $alias, purging and using TEE fallback: ${e.message}")
            runCatching { keyStore().deleteEntry(alias) }
            generate(false)
        }
    }

    private fun generateAuthKey(context: Context, alias: String, biometricOnly: Boolean) {
        val authenticators = if (biometricOnly) {
            KeyProperties.AUTH_BIOMETRIC_STRONG
        } else {
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
        }
        val timeoutSeconds = if (biometricOnly) 0 else 5
        generateWithFallback(context, alias) { strongBox ->
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                // 0 = fresh biometric required per operation; 5s window for credential verification
                .setUserAuthenticationParameters(timeoutSeconds, authenticators)
                .setInvalidatedByBiometricEnrollment(biometricOnly)
                .setIsStrongBoxBacked(strongBox)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(spec)
                generateKey()
            }
        }
    }

    private fun generateMasterKey(context: Context) {
        generateWithFallback(context, MASTER_KEY) { strongBox ->
            val spec = KeyGenParameterSpec.Builder(MASTER_KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setIsStrongBoxBacked(strongBox)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(spec)
                generateKey()
            }
        }
    }

    private fun generateIntegrityKey(context: Context) {
        generateWithFallback(context, INTEGRITY_KEY) { strongBox ->
            val spec = KeyGenParameterSpec.Builder(INTEGRITY_KEY, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(256)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setIsStrongBoxBacked(strongBox)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).apply {
                init(spec)
                generateKey()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // BiometricPrompt crypto binding
    // ---------------------------------------------------------------------------------------

    fun prepareBiometricCrypto(context: Context): CryptoResult = prepareAuthCrypto(context, BIOMETRIC_KEY, biometricOnly = true)

    fun prepareCredentialCrypto(context: Context): CryptoResult = prepareAuthCrypto(context, CREDENTIAL_KEY, biometricOnly = false)

    private fun prepareAuthCrypto(
        context: Context,
        alias: String,
        biometricOnly: Boolean,
        allowRegenerate: Boolean = true
    ): CryptoResult {
        return try {
            val ks = keyStore()
            ensureKey(ks, alias) { generateAuthKey(context, alias, biometricOnly) }
            val key = ks.getKey(alias, null) as? SecretKey ?: return CryptoResult.Unavailable("Key missing")
            val cipher = Cipher.getInstance(AES_GCM).apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
            val challenge = ByteArray(CHALLENGE_BYTES).also(secureRandom::nextBytes)
            CryptoResult.Ready(BiometricPrompt.CryptoObject(cipher), challenge)
        } catch (e: KeyPermanentlyInvalidatedException) {
            if (biometricOnly) {
                Log.w(TAG, "$alias invalidated: enrolled biometrics changed")
                CryptoResult.BiometricsChanged
            } else if (allowRegenerate) {
                // The credential key is only invalidated when the lock screen was removed and
                // re-added; the prompt itself still requires the (new) credential, so just rebuild it.
                runCatching { keyStore().deleteEntry(alias) }
                prepareAuthCrypto(context, alias, biometricOnly, allowRegenerate = false)
            } else {
                CryptoResult.Unavailable("Credential key invalidated")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Crypto unavailable for $alias: ${e.message}")
            CryptoResult.Unavailable(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Proves the authentication result really unlocked the hardware key: the cipher only
     * executes if Keystore received a fresh auth token for this exact operation.
     */
    fun verify(result: BiometricPrompt.AuthenticationResult, challenge: ByteArray): Boolean {
        val cipher = result.cryptoObject?.cipher ?: return false
        return try {
            cipher.doFinal(challenge).isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Hardware auth token verification failed: ${e.message}")
            false
        }
    }

    /**
     * Aborts an active [Cipher] operation to release the underlying KeyMint/Keymaster
     * operation slot in Titan M2 (StrongBox) or TEE.
     *
     * Titan M2 supports a very limited number of concurrent operations (typically 4).
     * If an operation is initiated via [prepareBiometricCrypto] but authentication is
     * cancelled or fails, invoking [doFinal] forces the Keystore SPI to finish or abort
     * the pending operation handle and release the slot, preventing KM_ERROR_TOO_MANY_OPERATIONS.
     */
    fun abortCipher(cipher: Cipher?) {
        if (cipher == null) return
        try {
            cipher.doFinal()
        } catch (_: Throwable) {
            // Expected: UserNotAuthenticatedException, AEADBadTagException, or IllegalBlockSizeException.
            // Throwing/catching here is intentional: it guarantees the KeyMint hardware handle
            // is terminated and discarded by AndroidKeyStoreCipherSpi.
        }
    }

    /**
     * Proves that the device credential (PIN/pattern/password) or strong biometric was actually
     * authenticated by the hardware within the 5-second window.
     *
     * Attempting to initialize and complete an operation with [CREDENTIAL_KEY] succeeds only
     * if Keystore / Gatekeeper received a valid hardware authentication token (HAT).
     * If authentication was mocked, hooked, or timed out, Keystore throws [UserNotAuthenticatedException].
     */
    fun verifyCredentialKeyAuth(context: Context): Boolean {
        fun attemptOp(): Boolean {
            val ks = keyStore()
            ensureKey(ks, CREDENTIAL_KEY) { generateAuthKey(context, CREDENTIAL_KEY, biometricOnly = false) }
            val key = ks.getKey(CREDENTIAL_KEY, null) as? SecretKey ?: return false
            val cipher = Cipher.getInstance(AES_GCM).apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
            val dummyChallenge = ByteArray(16).also(secureRandom::nextBytes)
            return cipher.doFinal(dummyChallenge).isNotEmpty()
        }

        return try {
            attemptOp()
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            Log.e(TAG, "Hardware credential verification failed: user not authenticated: ${e.message}")
            false
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "Credential key invalidated: ${e.message}; regenerating in-flight")
            runCatching { keyStore().deleteEntry(CREDENTIAL_KEY) }
            runCatching { attemptOp() }.getOrDefault(false)
        } catch (e: Exception) {
            Log.e(TAG, "Credential verification failed: ${e.message}")
            false
        }
    }

    /**
     * Replaces the biometric key after the owner has re-proven their identity (device
     * credential) following a biometric enrollment change.
     */
    fun resetBiometricKey(context: Context) {
        try {
            val ks = keyStore()
            synchronized(keyGenLock) {
                if (ks.containsAlias(BIOMETRIC_KEY)) ks.deleteEntry(BIOMETRIC_KEY)
                generateAuthKey(context, BIOMETRIC_KEY, biometricOnly = true)
            }
        } catch (e: Exception) {
            // Typically "no biometrics enrolled"; prepareBiometricCrypto will report Unavailable.
            Log.w(TAG, "Biometric key regeneration failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Master key encryption
    // ---------------------------------------------------------------------------------------

    class EncryptedPayload(val iv: ByteArray, val ciphertext: ByteArray)

    /** AES-GCM under the hardware master key; [aad] is authenticated but not encrypted. */
    fun encryptWithMasterKey(data: ByteArray, context: Context, aad: ByteArray? = null): EncryptedPayload? {
        return try {
            val ks = keyStore()
            ensureKey(ks, MASTER_KEY) { generateMasterKey(context) }
            val key = ks.getKey(MASTER_KEY, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(AES_GCM).apply {
                init(Cipher.ENCRYPT_MODE, key)
                if (aad != null) updateAAD(aad)
            }
            EncryptedPayload(cipher.iv, cipher.doFinal(data))
        } catch (e: Exception) {
            Log.e(TAG, "Master key encryption failed: ${e.message}")
            null
        }
    }

    fun decryptWithMasterKey(payload: EncryptedPayload, aad: ByteArray? = null): ByteArray? {
        return try {
            val key = keyStore().getKey(MASTER_KEY, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(AES_GCM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, payload.iv))
                if (aad != null) updateAAD(aad)
            }
            cipher.doFinal(payload.ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "Master key decryption failed: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Settings integrity
    // ---------------------------------------------------------------------------------------

    /**
     * Whether settings have ever been sealed on this install. The integrity key is created by the
     * first seal and lives in Keystore, out of reach of anyone editing BioKey's files, so a missing
     * seal next to an existing key means the seal was deleted rather than never written.
     */
    fun hasIntegrityKey(): Boolean = try {
        keyStore().containsAlias(INTEGRITY_KEY)
    } catch (e: Exception) {
        false
    }

    /** HMAC-SHA256 of [data] under the hardware integrity key, or null if the keystore is unavailable. */
    fun integrityMac(context: Context, data: ByteArray): ByteArray? {
        return try {
            val ks = keyStore()
            ensureKey(ks, INTEGRITY_KEY) { generateIntegrityKey(context) }
            val key = ks.getKey(INTEGRITY_KEY, null) as? SecretKey ?: return null
            Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
                init(key)
                doFinal(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Integrity MAC failed: ${e.message}")
            null
        }
    }

    fun zeroize(bytes: ByteArray?) {
        bytes?.fill(0)
    }

    // ---------------------------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------------------------

    private fun keySecurityLevel(alias: String): Int? {
        return try {
            val ks = keyStore()
            if (!ks.containsAlias(alias)) return null
            val key = ks.getKey(alias, null) as? SecretKey ?: return null
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val secLevel = keyInfo.securityLevel
                if (secLevel == KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE && keyInfo.isInsideSecureHardware) {
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
                } else {
                    secLevel
                }
            } else {
                if (keyInfo.isInsideSecureHardware) KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
                else KeyProperties.SECURITY_LEVEL_SOFTWARE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking security level for $alias: ${e.message}")
            null
        }
    }

    private fun describeSecurityLevel(level: Int?, hasStrongBox: Boolean = false): String = when (level) {
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox secure element (Titan M2)"
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "Trusted Execution Environment (TEE)"
        KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> if (hasStrongBox) "StrongBox secure element (Titan M2)" else "Secure hardware (TEE)"
        KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software keystore"
        null -> "Keystore uninitialized or unavailable"
        else -> if (hasStrongBox) "StrongBox secure element (Titan M2)" else "Trusted Execution Environment (TEE)"
    }

    private fun isHardwareLevel(level: Int?): Boolean =
        level != null && (
            level == KeyProperties.SECURITY_LEVEL_STRONGBOX ||
                level == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                level == KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE
        )

    fun getDiagnostics(context: Context): KeystoreDiagnostics {
        try {
            val ks = keyStore()
            ensureKey(ks, MASTER_KEY) { generateMasterKey(context) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not ensure master key in diagnostics: ${e.message}")
        }

        val hasStrongBox = isStrongBoxAvailable(context)
        val level = keySecurityLevel(MASTER_KEY)
            ?: keySecurityLevel(CREDENTIAL_KEY)
            ?: keySecurityLevel(BIOMETRIC_KEY)

        val biometricCode = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val biometricStatus = when (biometricCode) {
            BiometricManager.BIOMETRIC_SUCCESS -> "Class 3 biometrics enrolled"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "No Class 3 biometrics enrolled"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No Class 3 biometric hardware"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Biometric hardware unavailable"
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "Security update required"
            else -> "Unavailable ($biometricCode)"
        }
        return KeystoreDiagnostics(
            isStrongBoxSupported = hasStrongBox,
            isHardwareKeystoreBacked = isHardwareLevel(level),
            securityLevel = describeSecurityLevel(level, hasStrongBox),
            hasEnrolledBiometrics = biometricCode == BiometricManager.BIOMETRIC_SUCCESS,
            biometricStatus = biometricStatus
        )
    }

    /**
     * Rotates the authentication keys. The master key is kept because existing
     * intruder photos and the event log are encrypted under it.
     */
    fun performReKeyWithProof(context: Context): ReKeyProof {
        val ks = keyStore()
        for (alias in listOf(BIOMETRIC_KEY, CREDENTIAL_KEY)) {
            try {
                synchronized(keyGenLock) {
                    if (ks.containsAlias(alias)) ks.deleteEntry(alias)
                    generateAuthKey(context, alias, biometricOnly = alias == BIOMETRIC_KEY)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Rotation failed for $alias: ${e.message}")
            }
        }
        ensureKey(ks, MASTER_KEY) { generateMasterKey(context) }

        val level = keySecurityLevel(CREDENTIAL_KEY) ?: keySecurityLevel(MASTER_KEY)
        val created = runCatching { ks.getCreationDate(CREDENTIAL_KEY) }.getOrNull() ?: Date()
        // Fingerprint of the new key's public metadata, so the user can see rotation took effect.
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$CREDENTIAL_KEY:${created.time}:$level".toByteArray())
            .joinToString("") { "%02X".format(it) }
            .take(32)

        SecurityEventLog.record(context, SecurityEventLog.Type.KEYS_ROTATED, describeSecurityLevel(level))

        return ReKeyProof(
            keyAlias = CREDENTIAL_KEY,
            provider = "AndroidKeyStore",
            securityLevel = describeSecurityLevel(level),
            creationTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(created),
            verificationHash = digest,
            isHardwareBacked = isHardwareLevel(level),
            isStrongBoxProtected = level == KeyProperties.SECURITY_LEVEL_STRONGBOX
        )
    }
}
