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
 * Format: "BIOKEYBK", a version byte, the PBKDF2 round count (4 bytes, big-endian; version 1
 * files have none and used [V1_ITERATIONS]), a 16-byte salt, a 12-byte nonce, then AES-256-GCM
 * ciphertext of the settings as JSON. The key comes from the password with PBKDF2-SHA256
 * ([ITERATIONS] rounds for new files; the count travels with the file so it can go up without
 * another format change). The header is authenticated too, so any change to the file, or a wrong
 * password, is caught rather than read as garbage. Slow on purpose: call off the main thread.
 */
object VaultBackup {

    const val MIN_PASSWORD_LENGTH = 8
    const val FILE_EXTENSION = "biokey"

    private val MAGIC = "BIOKEYBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 2
    private const val V1_ITERATIONS = 310_000
    private const val ITERATIONS = 600_000
    /** A file asking for fewer or more rounds than this was not made by BioKey. */
    private val ACCEPTED_ITERATIONS = 100_000..10_000_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    class NotABackupException : Exception("Not a BioKey backup")
    class UnsupportedVersionException : Exception("Made by a newer version of BioKey")
    class WrongPasswordException : Exception("Wrong password, or the file was changed")

    fun seal(state: JSONObject, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(MAGIC.size + 1 + 4).put(MAGIC).put(VERSION.toByte()).putInt(ITERATIONS).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
        }
        val sealed = cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
        return ByteBuffer.allocate(header.size + SALT_BYTES + NONCE_BYTES + sealed.size)
            .put(header).put(salt).put(nonce).put(sealed).array()
    }

    fun open(file: ByteArray, password: CharArray): JSONObject {
        if (file.size <= MAGIC.size || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw NotABackupException()
        val version = file[MAGIC.size].toInt() and 0xFF
        val headerSize: Int
        val iterations: Int
        when (version) {
            1 -> {
                headerSize = MAGIC.size + 1
                iterations = V1_ITERATIONS
            }
            2 -> {
                headerSize = MAGIC.size + 1 + 4
                if (file.size < headerSize) throw NotABackupException()
                iterations = ByteBuffer.wrap(file, MAGIC.size + 1, 4).int
                if (iterations !in ACCEPTED_ITERATIONS) throw NotABackupException()
            }
            in VERSION + 1..0xFF -> throw UnsupportedVersionException()
            else -> throw NotABackupException()
        }
        if (file.size < headerSize + SALT_BYTES + NONCE_BYTES + TAG_BITS / 8) throw NotABackupException()
        val header = file.copyOfRange(0, headerSize)
        var at = headerSize
        val salt = file.copyOfRange(at, at + SALT_BYTES).also { at += SALT_BYTES }
        val nonce = file.copyOfRange(at, at + NONCE_BYTES).also { at += NONCE_BYTES }
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
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

    /** The AES key for [password]; every copy of the key and password made on the way is wiped. */
    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val secret = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
            val bytes = secret.encoded
            try {
                return SecretKeySpec(bytes, "AES") // keeps its own copy
            } finally {
                bytes.fill(0)
                runCatching { secret.destroy() }
            }
        } finally {
            spec.clearPassword()
        }
    }
}
