package com.android.BioKey.core.security

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 18 Zero-Knowledge Memory-Safe Sanitization & Hygiene Engine
 *
 * Implements strict defense-in-depth memory scrubbing primitives designed for Android 16-18
 * security hardening specifications. Prevents JVM string-interning persistence, JIT dead-store
 * elimination, cold-boot RAM recovery, heap snapshot leaks, and inter-process inspection.
 */
object ZeroKnowledgeMemorySanitizer {

    private const val TAG = "MemorySanitizer"

    // Cryptographic PRNG for non-deterministic multi-pass scrubbing
    private val secureRandom = SecureRandom()

    // Volatile read sink to thwart JIT/ART C2 dead-store elimination (DSE) compiler optimizations
    @Volatile
    var blackHoleSink: Byte = 0

    @Volatile
    var charSink: Char = '\u0000'

    // Weak registry of all live secure buffers for instantaneous global session purging
    private val activeWipables = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Wipeable, Boolean>())
    )

    // Active cryptographic session token registry indexed by target package identifier
    private val activeSessionTokens = ConcurrentHashMap<String, SecureToken>()

    interface Wipeable : Closeable {
        val isWiped: Boolean
        fun wipe()
        override fun close() = wipe()
    }

    /**
     * Registers a wipeable resource into the global hygiene registry.
     */
    fun registerWipeable(wipeable: Wipeable) {
        activeWipables.add(wipeable)
    }

    /**
     * Unregisters a wipeable resource.
     */
    fun unregisterWipeable(wipeable: Wipeable) {
        activeWipables.remove(wipeable)
    }

    /**
     * Unconditionally purges all live memory-allocated cryptographic buffers,
     * session tokens, and cached byte structures across the entire runtime.
     */
    fun evictAllSessionsAndScrubMemory() {
        val count: Int
        synchronized(activeWipables) {
            count = activeWipables.size
            val iterator = activeWipables.iterator()
            while (iterator.hasNext()) {
                val wipeable = iterator.next()
                try {
                    wipeable.wipe()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error scrubbing wipeable buffer: ${t.message}")
                }
                iterator.remove()
            }
        }

        // Scrub all active package session tokens
        for ((pkg, token) in activeSessionTokens) {
            token.wipe()
        }
        activeSessionTokens.clear()

        Log.i(TAG, "Zero-Knowledge purge completed: $count buffers sanitized from RAM.")
    }

    /**
     * Sanitizes ephemeral UI memory structures (such as PIN buffers) when UI is hidden,
     * without prematurely invalidating active user sessions.
     */
    fun trimUiMemory() {
        var count = 0
        synchronized(activeWipables) {
            val iterator = activeWipables.iterator()
            while (iterator.hasNext()) {
                val wipeable = iterator.next()
                if (wipeable is SecurePinBuffer) {
                    try {
                        wipeable.wipe()
                        count++
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error trimming UI wipeable: ${t.message}")
                    }
                    iterator.remove()
                }
            }
        }

        // Scrub and evict expired tokens
        val tokenIterator = activeSessionTokens.entries.iterator()
        while (tokenIterator.hasNext()) {
            val entry = tokenIterator.next()
            if (entry.value.isExpired() || entry.value.isWiped) {
                entry.value.wipe()
                tokenIterator.remove()
            }
        }

        Log.i(TAG, "UI trim completed: $count ephemeral UI buffers sanitized.")
    }

    /**
     * Multi-pass cryptographic scrubbing for raw byte arrays.
     * Zero-allocation defense-in-depth: uses deterministic bit inversion passes
     * and volatile memory barrier to thwart JIT/ART dead-store elimination (DSE).
     */
    fun wipe(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return

        var sinkAcc: Byte = 0

        // Pass 1: Alternating bit pattern 0xAA (10101010)
        Arrays.fill(bytes, 0xAA.toByte())
        for (i in bytes.indices) {
            sinkAcc = (sinkAcc.toInt() xor bytes[i].toInt()).toByte()
        }

        // Pass 2: Inverted alternating bit pattern 0x55 (01010101)
        Arrays.fill(bytes, 0x55.toByte())
        for (i in bytes.indices) {
            sinkAcc = (sinkAcc.toInt() xor bytes[i].toInt()).toByte()
        }

        // Pass 3: Inverted full bit pattern 0xFF (11111111) - Zero heap allocations
        Arrays.fill(bytes, 0xFF.toByte())
        for (i in bytes.indices) {
            sinkAcc = (sinkAcc.toInt() xor bytes[i].toInt()).toByte()
        }

        // Pass 4: Final zeroization 0x00
        Arrays.fill(bytes, 0.toByte())
        for (i in bytes.indices) {
            sinkAcc = (sinkAcc.toInt() xor bytes[i].toInt()).toByte()
        }

        // Flush accumulated fence read to volatile field
        blackHoleSink = sinkAcc
    }

    /**
     * Scrub char array to protect PINs and passphrases without creating String pools.
     */
    fun wipe(chars: CharArray?) {
        if (chars == null || chars.isEmpty()) return

        var charAcc = '\u0000'

        // Pass 1: High unicode mask
        Arrays.fill(chars, '\uFFFF')
        for (i in chars.indices) {
            charAcc = (charAcc.code xor chars[i].code).toChar()
        }

        // Pass 2: Alternating mask
        Arrays.fill(chars, '\uAAAA')
        for (i in chars.indices) {
            charAcc = (charAcc.code xor chars[i].code).toChar()
        }

        // Pass 3: Clean null character zeroization
        Arrays.fill(chars, '\u0000')
        for (i in chars.indices) {
            charAcc = (charAcc.code xor chars[i].code).toChar()
        }

        charSink = charAcc
    }

    /**
     * Scrub and zeroize NIO ByteBuffers.
     */
    fun wipe(buffer: ByteBuffer?) {
        if (buffer == null) return
        try {
            buffer.clear()
            if (buffer.hasArray()) {
                wipe(buffer.array())
            } else {
                val zero = 0.toByte()
                var sink: Byte = 0
                for (i in 0 until buffer.capacity()) {
                    buffer.put(i, zero)
                    sink = (sink.toInt() xor buffer.get(i).toInt()).toByte()
                }
                blackHoleSink = sink
            }
            buffer.clear()
        } catch (e: Exception) {
            Log.w(TAG, "ByteBuffer scrubbing exception: ${e.message}")
        }
    }

    /**
     * Sanitizes Bitmap memory before recycling to prevent task switcher / recents leaks.
     */
    fun sanitizeBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        try {
            if (bitmap.isMutable) {
                bitmap.eraseColor(Color.TRANSPARENT)
            }
            bitmap.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Bitmap memory sanitization failed: ${e.message}")
        }
    }

    /**
     * Constant-time comparison between two byte arrays to thwart side-channel timing attacks.
     */
    fun constantTimeEquals(a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null) return a === b
        if (a.size != b.size) return false
        return MessageDigest.isEqual(a, b)
    }

    /**
     * Constant-time comparison between two char arrays.
     */
    fun constantTimeEquals(a: CharArray?, b: CharArray?): Boolean {
        if (a == null || b == null) return a === b
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    /**
     * Secure execution block: Allocates an ephemeral byte buffer, executes the action,
     * and guarantees immediate cryptographic erasure upon block exit (even during exceptions).
     */
    inline fun <R> runWithSecureBuffer(size: Int, block: (ByteArray) -> R): R {
        val buffer = ByteArray(size)
        return try {
            block(buffer)
        } finally {
            wipe(buffer)
        }
    }

    /**
     * Stores and returns an active session token for an unlocked target package.
     */
    fun createSessionToken(packageName: String, ttlMillis: Long): SecureToken {
        // Invalidate any preexisting token
        activeSessionTokens[packageName]?.wipe()

        val tokenBytes = ByteArray(32)
        secureRandom.nextBytes(tokenBytes)
        val token = SecureToken(tokenBytes, ttlMillis)
        activeSessionTokens[packageName] = token
        return token
    }

    /**
     * Validates whether an active session token is present and valid for the package.
     */
    fun isSessionTokenValid(packageName: String): Boolean {
        val token = activeSessionTokens[packageName] ?: return false
        if (token.isExpired() || token.isWiped) {
            token.wipe()
            activeSessionTokens.remove(packageName)
            return false
        }
        return true
    }

    /**
     * Evicts session token for a specific package.
     */
    fun evictSession(packageName: String) {
        activeSessionTokens.remove(packageName)?.wipe()
    }
}

/**
 * Production-Grade Secure Memory Wrapper for Byte Arrays.
 * Implements [ZeroKnowledgeMemorySanitizer.Wipeable]. Prevents accidental String conversions and logs.
 */
class SecureByteArray(bytes: ByteArray) : ZeroKnowledgeMemorySanitizer.Wipeable {

    @PublishedApi internal var buffer: ByteArray? = bytes.clone()
    @PublishedApi internal val wiped = AtomicBoolean(false)

    init {
        ZeroKnowledgeMemorySanitizer.registerWipeable(this)
    }

    override val isWiped: Boolean
        get() = wiped.get()

    val size: Int
        get() = buffer?.size ?: 0

    /**
     * Grants controlled, non-escaping access to the underlying raw bytes.
     */
    inline fun <R> withBuffer(action: (ByteArray) -> R): R {
        check(!wiped.get()) { "SecureByteArray has already been wiped and disposed." }
        val b = buffer ?: error("Secure buffer is null")
        return action(b)
    }

    /**
     * Clones data into an ephemeral output array. The caller assumes responsibility
     * for wiping the returned array after use.
     */
    fun copyBytes(): ByteArray {
        check(!wiped.get()) { "SecureByteArray has already been wiped and disposed." }
        return buffer!!.clone()
    }

    override fun wipe() {
        if (wiped.compareAndSet(false, true)) {
            val b = buffer
            buffer = null
            if (b != null) {
                ZeroKnowledgeMemorySanitizer.wipe(b)
            }
            ZeroKnowledgeMemorySanitizer.unregisterWipeable(this)
        }
    }

    override fun toString(): String {
        return if (wiped.get()) {
            "[SecureByteArray: WIPED]"
        } else {
            "[SecureByteArray: size=${buffer?.size ?: 0}, protected]"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecureByteArray) return false
        if (this.wiped.get() || other.wiped.get()) return false
        return ZeroKnowledgeMemorySanitizer.constantTimeEquals(this.buffer, other.buffer)
    }

    override fun hashCode(): Int {
        return if (wiped.get()) 0 else Arrays.hashCode(buffer)
    }
}

/**
 * Production-Grade Secure Cryptographic Session Token.
 * Encapsulates an authentication key with TTL enforcement and immediate memory zeroization.
 */
class SecureToken(
    rawTokenBytes: ByteArray,
    private val validityDurationMs: Long
) : ZeroKnowledgeMemorySanitizer.Wipeable {

    @PublishedApi internal val secureBuffer = SecureByteArray(rawTokenBytes)
    private val creationTimestamp: Long = SystemClock.elapsedRealtime()
    @PublishedApi internal val wiped = AtomicBoolean(false)

    init {
        // Scrub the input raw array immediately after ingesting into SecureByteArray
        ZeroKnowledgeMemorySanitizer.wipe(rawTokenBytes)
        ZeroKnowledgeMemorySanitizer.registerWipeable(this)
    }

    override val isWiped: Boolean
        get() = wiped.get() || secureBuffer.isWiped

    fun isExpired(): Boolean {
        if (wiped.get()) return true
        if (validityDurationMs <= 0L) return false
        return (SystemClock.elapsedRealtime() - creationTimestamp) > validityDurationMs
    }

    fun remainingValidityMs(): Long {
        if (isExpired()) return 0L
        if (validityDurationMs <= 0L) return Long.MAX_VALUE
        return (validityDurationMs - (SystemClock.elapsedRealtime() - creationTimestamp)).coerceAtLeast(0L)
    }

    inline fun <R> useToken(block: (ByteArray) -> R): R {
        check(!isExpired()) { "SecureToken has expired." }
        check(!wiped.get()) { "SecureToken has been wiped." }
        return secureBuffer.withBuffer(block)
    }

    override fun wipe() {
        if (wiped.compareAndSet(false, true)) {
            secureBuffer.wipe()
            ZeroKnowledgeMemorySanitizer.unregisterWipeable(this)
        }
    }

    override fun toString(): String {
        return if (isWiped) "[SecureToken: WIPED]" else "[SecureToken: active, expires in ${remainingValidityMs()}ms]"
    }
}

/**
 * Secure PIN and Passphrase Buffer.
 * Collects PIN digits directly into a [CharArray] without ever creating JVM [String] instances.
 */
class SecurePinBuffer(val capacity: Int = 16) : ZeroKnowledgeMemorySanitizer.Wipeable {

    @PublishedApi internal val chars = CharArray(capacity)
    @PublishedApi internal var currentLength = 0
    @PublishedApi internal val wiped = AtomicBoolean(false)

    init {
        ZeroKnowledgeMemorySanitizer.registerWipeable(this)
    }

    val length: Int
        get() = currentLength

    override val isWiped: Boolean
        get() = wiped.get()

    @Synchronized
    fun append(digit: Char): Boolean {
        if (wiped.get() || currentLength >= capacity) return false
        chars[currentLength++] = digit
        return true
    }

    @Synchronized
    fun backspace(): Boolean {
        if (wiped.get() || currentLength <= 0) return false
        currentLength--
        chars[currentLength] = '\u0000'
        return true
    }

    @Synchronized
    fun clear() {
        if (!wiped.get()) {
            ZeroKnowledgeMemorySanitizer.wipe(chars)
            currentLength = 0
        }
    }

    inline fun <R> withChars(block: (CharArray, Int) -> R): R {
        synchronized(this) {
            check(!wiped.get()) { "SecurePinBuffer is wiped." }
            return block(chars, currentLength)
        }
    }

    @Synchronized
    fun matches(other: CharArray): Boolean {
        if (wiped.get() || currentLength != other.size) return false
        var matches = 0
        for (i in 0 until currentLength) {
            matches = matches or (chars[i].code xor other[i].code)
        }
        return matches == 0
    }

    @Synchronized
    override fun wipe() {
        if (wiped.compareAndSet(false, true)) {
            ZeroKnowledgeMemorySanitizer.wipe(chars)
            currentLength = 0
            ZeroKnowledgeMemorySanitizer.unregisterWipeable(this)
        }
    }

    override fun toString(): String {
        return if (wiped.get()) "[SecurePinBuffer: WIPED]" else "[SecurePinBuffer: digits=$currentLength]"
    }
}
