package com.android.BioKey.core.security

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * BioKey's backup file: the vault's settings ([SecurityVault.exportState]) sealed with a
 * password, so it can sit in Downloads or a cloud drive without saying which apps are locked.
 *
 * Format: "BIOKEYBK", a version byte, a 16-byte salt, a 12-byte nonce, then AES-256-GCM
 * ciphertext of the settings as JSON. The key comes from the password with PBKDF2-SHA256
 * ([ITERATIONS] rounds). The header is authenticated too, so any change to the file, or a wrong
 * password, is caught rather than read as garbage. Slow on purpose: call off the main thread.
 */
object VaultBackup {

    const val MIN_PASSWORD_LENGTH = 8
    const val FILE_EXTENSION = "biokey"

    private val MAGIC = "BIOKEYBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val ITERATIONS = 310_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    class NotABackupException : Exception("Not a BioKey backup")
    class WrongPasswordException : Exception("Wrong password, or the file was changed")

    fun seal(state: JSONObject, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = MAGIC + VERSION
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
        }
        val sealed = cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
        return ByteBuffer.allocate(header.size + SALT_BYTES + NONCE_BYTES + sealed.size)
            .put(header).put(salt).put(nonce).put(sealed).array()
    }

    fun open(file: ByteArray, password: CharArray): JSONObject {
        val header = MAGIC + VERSION
        if (file.size < header.size + SALT_BYTES + NONCE_BYTES + TAG_BITS / 8 ||
            !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        ) throw NotABackupException()
        if (file[MAGIC.size] != VERSION) throw NotABackupException()
        var at = header.size
        val salt = file.copyOfRange(at, at + SALT_BYTES).also { at += SALT_BYTES }
        val nonce = file.copyOfRange(at, at + NONCE_BYTES).also { at += NONCE_BYTES }
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(header)
                doFinal(file, at, file.size - at)
            }
        } catch (_: GeneralSecurityException) {
            // A wrong key and a changed file fail the same authentication check.
            throw WrongPasswordException()
        }
        return try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            throw NotABackupException()
        }
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
