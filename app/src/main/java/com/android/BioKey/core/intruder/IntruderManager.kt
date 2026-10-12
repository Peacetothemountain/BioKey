package com.android.BioKey.core.intruder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.ExifInterface
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import com.android.BioKey.core.security.HardwareKeyManager
import com.android.BioKey.core.security.SecurityVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Intruder photos: a silent front-camera capture after repeated failed unlocks.
 *
 * Each photo is envelope-encrypted: a random per-photo AES-256 key (DEK) encrypts the JPEG and a
 * small metadata block (which app, when), and the DEK itself is wrapped by the non-exportable
 * Keystore master key. File names are random, so the directory listing reveals nothing about
 * which apps are locked. Nothing is ever written unencrypted; if the Keystore is unavailable the
 * photo is dropped.
 */
object IntruderManager {

    private const val TAG = "IntruderManager"
    private const val INTRUDER_DIR_NAME = "intruders"
    private const val FILE_EXTENSION = "enc"

    /** Source used for failed device (keyguard) unlocks reported by the device admin. */
    const val DEVICE_LOCK_SOURCE = "biokey:device-lock"

    private const val MAX_STORED_PHOTOS = 100
    private const val CAPTURE_COOLDOWN_MS = 20_000L
    private const val CAPTURE_WATCHDOG_MS = 8_000L
    private const val GCM_TAG_BITS = 128

    private val MAGIC_V2 = "BIOKEY_ENV_V2".toByteArray(Charsets.US_ASCII)
    private val MAGIC_V1 = "BIOKEY_ENV_V1".toByteArray(Charsets.US_ASCII)
    private val MAGIC_LEGACY = "BIOKEY_ENC_V1".toByteArray(Charsets.US_ASCII)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    private val lastCapture = ConcurrentHashMap<String, Long>()
    private val captureInFlight = AtomicBoolean(false)

    data class IntruderRecord(
        val file: File,
        val packageName: String,
        val timestamp: Long,
        val formattedDate: String
    ) {
        val isDeviceLock: Boolean get() = packageName == DEVICE_LOCK_SOURCE
    }

    private fun intrudersDir(context: Context): File =
        File(context.noBackupFilesDir, INTRUDER_DIR_NAME).apply { mkdirs() }

    /** Legacy location (inside filesDir) used by earlier versions; emptied by [migrateLegacyPhotos]. */
    private fun legacyDir(context: Context): File = File(context.filesDir, INTRUDER_DIR_NAME)

    // ---------------------------------------------------------------------------------------
    // Capture
    // ---------------------------------------------------------------------------------------

    /** Capture after a short delay, giving a biometric prompt or face unlock time to release the camera. */
    fun captureIntruderPhoto(context: Context, packageName: String) = scheduleCapture(context, packageName, 500L)

    /** Capture right away; for when the biometric prompt has already been dismissed. */
    fun captureIntruderPhotoNow(context: Context, packageName: String) = scheduleCapture(context, packageName, 150L)

    private fun scheduleCapture(context: Context, packageName: String, delayMs: Long) {
        val appContext = context.applicationContext
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!SecurityVault.getInstance(appContext).isIntruderDetectionEnabled()) return

        // One photo per app per cooldown window; repeated failures don't need a burst of photos.
        val now = SystemClock.elapsedRealtime()
        val previous = lastCapture[packageName]
        if (previous != null && now - previous < CAPTURE_COOLDOWN_MS) return
        lastCapture[packageName] = now

        scope.launch {
            delay(delayMs)
            if (captureInFlight.compareAndSet(false, true)) {
                takeSilentFrontPhoto(appContext, packageName, attempt = 0)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun takeSilentFrontPhoto(context: Context, packageName: String, attempt: Int) {
        val cameraManager = context.getSystemService(CameraManager::class.java)
        if (cameraManager == null) {
            captureInFlight.set(false)
            return
        }

        val thread = HandlerThread("BioKeyIntruderCapture").apply { start() }
        val handler = Handler(thread.looper)
        var imageReader: ImageReader? = null
        var activeCamera: CameraDevice? = null
        var activeSession: CameraCaptureSession? = null
        val cleanedUp = AtomicBoolean(false)
        val openPending = AtomicBoolean(false)
        var retryScheduled = false

        fun cleanup() {
            if (!cleanedUp.compareAndSet(false, true)) return
            runCatching { activeSession?.close() }
            runCatching { activeCamera?.close() }
            runCatching { imageReader?.close() }
            if (openPending.get()) {
                // The camera may still open after the watchdog gave up, and it is delivered on this
                // thread: keep the looper alive so onOpened can close it (bounded backstop).
                handler.postDelayed({ thread.quitSafely() }, CAPTURE_WATCHDOG_MS)
            } else {
                thread.quitSafely()
            }
            if (!retryScheduled) captureInFlight.set(false)
        }

        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                val isBackwardCompatible = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
                val hasJpeg = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)?.isNotEmpty() == true
                facing && isBackwardCompatible && hasJpeg
            } ?: cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
            if (cameraId == null) {
                Log.w(TAG, "No front camera")
                cleanup()
                return
            }
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG).orEmpty()
            val size = sizes.filter { it.width in 640..1920 }.maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height }
                ?: android.util.Size(640, 480)

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            imageReader = reader

            handler.postDelayed({
                if (!cleanedUp.get()) {
                    Log.w(TAG, "Capture timed out")
                    cleanup()
                }
            }, CAPTURE_WATCHDOG_MS)

            reader.setOnImageAvailableListener({ r ->
                try {
                    r.acquireLatestImage()?.use { image ->
                        val buffer = image.planes.firstOrNull()?.buffer ?: return@use
                        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                        saveEncrypted(context, packageName, bytes)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Couldn't read captured frame: ${e.message}")
                } finally {
                    cleanup()
                }
            }, handler)

            openPending.set(true)
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    openPending.set(false)
                    if (cleanedUp.get()) {
                        // Opened after the watchdog fired: release it straight away.
                        camera.close()
                        thread.quitSafely()
                        return
                    }
                    activeCamera = camera
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(context, characteristics))
                        }.build()

                        val sessionCallback = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                activeSession = session
                                try {
                                    session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureFailed(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            failure: CaptureFailure
                                        ) {
                                            Log.w(TAG, "Capture failed: reason=${failure.reason}")
                                            cleanup()
                                        }
                                    }, handler)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Capture request failed: ${e.message}")
                                    cleanup()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.w(TAG, "Capture session configuration failed")
                                cleanup()
                            }
                        }
                        val executor = Executor { handler.post(it) }
                        camera.createCaptureSession(
                            SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                listOf(OutputConfiguration(reader.surface)),
                                executor,
                                sessionCallback
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Couldn't configure capture: ${e.message}")
                        cleanup()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    openPending.set(false)
                    camera.close()
                    cleanup()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    openPending.set(false)
                    camera.close()
                    // Face unlock or the biometric prompt may still hold the camera: back off and retry.
                    val busy = error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                    if (busy && attempt < 3) {
                        retryScheduled = true
                        scope.launch {
                            delay(800L * (attempt + 1))
                            takeSilentFrontPhoto(context, packageName, attempt + 1)
                        }
                    }
                    cleanup()
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Intruder capture failed: ${e.message}")
            openPending.set(false)
            cleanup()
        }
    }

    private fun jpegOrientation(context: Context, characteristics: CameraCharacteristics): Int {
        val rotation = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        val displayDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        return (sensor + displayDegrees) % 360 // front camera: rotations add up (mirrored sensor)
    }

    // ---------------------------------------------------------------------------------------
    // Storage format (V2)
    //
    //   magic "BIOKEY_ENV_V2"
    //   u8  wrappedDekIvLen,  wrappedDekIv     DEK wrapped by the Keystore master key (AAD = magic)
    //   u16 wrappedDekLen,    wrappedDek
    //   u8  metaIvLen,        metaIv           AES-GCM(DEK, AAD = magic|"meta"): u64 timestamp, UTF source
    //   u16 metaLen,          meta
    //   u8  photoIvLen,       photoIv          AES-GCM(DEK, AAD = magic|"photo"): JPEG bytes
    //   ... photo ciphertext (rest of file)
    // ---------------------------------------------------------------------------------------

    /** Returns whether the photo was stored. */
    private fun saveEncrypted(
        context: Context,
        packageName: String,
        jpeg: ByteArray,
        timestamp: Long = System.currentTimeMillis()
    ): Boolean {
        val dek = ByteArray(32).also(random::nextBytes)
        try {
            val wrapped = HardwareKeyManager.encryptWithMasterKey(dek, context, aad = MAGIC_V2)
            if (wrapped == null) {
                Log.e(TAG, "Keystore unavailable; intruder photo discarded rather than stored unencrypted")
                return false
            }
            val meta = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeLong(timestamp)
                    out.writeUTF(packageName.ifBlank { "unknown" })
                }
                bytes.toByteArray()
            }
            val (metaIv, metaCt) = gcmEncrypt(dek, meta, MAGIC_V2 + "meta".toByteArray())
            val (photoIv, photoCt) = gcmEncrypt(dek, jpeg, MAGIC_V2 + "photo".toByteArray())

            val name = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            val dir = intrudersDir(context)
            val file = File(dir, "$name.$FILE_EXTENSION")
            val tmp = File(dir, "$name.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.write(MAGIC_V2)
                out.writeByte(wrapped.iv.size); out.write(wrapped.iv)
                out.writeShort(wrapped.ciphertext.size); out.write(wrapped.ciphertext)
                out.writeByte(metaIv.size); out.write(metaIv)
                out.writeShort(metaCt.size); out.write(metaCt)
                out.writeByte(photoIv.size); out.write(photoIv)
                out.write(photoCt)
            }
            tmp.renameTo(file)
            pruneOldest(context)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't save intruder photo: ${e.message}")
            return false
        } finally {
            HardwareKeyManager.zeroize(dek)
            HardwareKeyManager.zeroize(jpeg)
        }
    }

    private fun gcmEncrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.updateAAD(aad)
        return cipher.iv to cipher.doFinal(plaintext)
    }

    private fun gcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun pruneOldest(context: Context) {
        val files = intrudersDir(context).listFiles().orEmpty()
        if (files.size <= MAX_STORED_PHOTOS) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_STORED_PHOTOS).forEach { it.delete() }
    }

    private class V2Envelope(
        val wrappedIv: ByteArray,
        val wrappedDek: ByteArray,
        val metaIv: ByteArray,
        val meta: ByteArray,
        val photoIv: ByteArray,
        val photoOffset: Int
    )

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size > prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun parseV2(bytes: ByteArray): V2Envelope {
        val input = DataInputStream(ByteArrayInputStream(bytes, MAGIC_V2.size, bytes.size - MAGIC_V2.size))
        fun read(length: Int) = ByteArray(length).also(input::readFully)
        val wrappedIv = read(input.readUnsignedByte())
        val wrappedDek = read(input.readUnsignedShort())
        val metaIv = read(input.readUnsignedByte())
        val meta = read(input.readUnsignedShort())
        val photoIv = read(input.readUnsignedByte())
        return V2Envelope(wrappedIv, wrappedDek, metaIv, meta, photoIv, bytes.size - input.available())
    }

    private fun unwrapDek(envelope: V2Envelope): ByteArray? = HardwareKeyManager.decryptWithMasterKey(
        HardwareKeyManager.EncryptedPayload(envelope.wrappedIv, envelope.wrappedDek), aad = MAGIC_V2
    )

    // ---------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------

    private val legacyMigrated = AtomicBoolean(false)

    /**
     * Moves photos out of the legacy folder into the current format: random file names (the old
     * names exposed the app and time) and envelope encryption. Earlier versions could also leave a
     * plain JPEG there when the Keystore was unavailable; those are encrypted, then deleted.
     * Encrypted files that can't be read right now are retried on the next call; files that are
     * neither are corrupt and dropped. Does Keystore and disk work: call off the main thread.
     */
    fun migrateLegacyPhotos(context: Context) {
        if (!legacyMigrated.compareAndSet(false, true)) return
        var retryLater = false
        for (file in legacyDir(context).listFiles().orEmpty()) {
            if (!file.isFile) continue
            try {
                val bytes = file.readBytes()
                val isEncrypted = bytes.startsWith(MAGIC_V1) || bytes.startsWith(MAGIC_LEGACY)
                val jpeg = when {
                    bytes.isJpeg() -> bytes
                    isEncrypted -> decryptPhoto(bytes)
                    else -> null
                }
                val (timestamp, packageName) = legacyNameParts(file)
                when {
                    jpeg != null && saveEncrypted(context, packageName, jpeg, timestamp) -> file.delete()
                    isEncrypted || jpeg != null -> retryLater = true
                    else -> file.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Legacy intruder photo not migrated: ${e.message}")
                retryLater = true
            }
        }
        if (retryLater) legacyMigrated.set(false)
    }

    private fun ByteArray.isJpeg() =
        size > 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()

    /** V1 and legacy files kept the time and app in the file name. */
    private fun legacyNameParts(file: File): Pair<Long, String> {
        val parts = file.nameWithoutExtension.split("_")
        return if (parts.size >= 3 && parts[0] == "intruder") {
            (parts[1].toLongOrNull() ?: file.lastModified()) to parts.drop(2).joinToString(".")
        } else {
            file.lastModified() to "unknown"
        }
    }

    /** All readable records, newest first. Does Keystore work per file: call off the main thread. */
    fun getIntruderRecords(context: Context): List<IntruderRecord> {
        migrateLegacyPhotos(context)
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val files = intrudersDir(context).listFiles().orEmpty().toList() + legacyDir(context).listFiles().orEmpty().toList()
        return files.filter { it.isFile && it.extension == FILE_EXTENSION }
            .mapNotNull { file -> readMetadata(file)?.let { (ts, pkg) -> IntruderRecord(file, pkg, ts, dateFormat.format(Date(ts))) } }
            .sortedByDescending { it.timestamp }
    }

    private fun readMetadata(file: File): Pair<Long, String>? = try {
        file.inputStream().buffered().use { fis ->
            val input = DataInputStream(fis)
            val magic = ByteArray(MAGIC_V2.size)
            input.readFully(magic)
            if (magic.contentEquals(MAGIC_V2)) {
                fun read(len: Int) = ByteArray(len).also(input::readFully)
                val wrappedIv = read(input.readUnsignedByte())
                val wrappedDek = read(input.readUnsignedShort())
                val metaIv = read(input.readUnsignedByte())
                val metaCt = read(input.readUnsignedShort())
                val envelope = V2Envelope(wrappedIv, wrappedDek, metaIv, metaCt, ByteArray(0), 0)
                val dek = unwrapDek(envelope)
                if (dek == null) null else try {
                    val meta = gcmDecrypt(dek, envelope.metaIv, envelope.meta, MAGIC_V2 + "meta".toByteArray())
                    DataInputStream(ByteArrayInputStream(meta)).use { it.readLong() to it.readUTF() }
                } finally {
                    HardwareKeyManager.zeroize(dek)
                }
            } else if (magic.contentEquals(MAGIC_V1) || magic.contentEquals(MAGIC_LEGACY)) {
                legacyNameParts(file)
            } else {
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unreadable intruder record: ${e.message}")
        null
    }

    /**
     * Decrypts and decodes a photo, downsampled so neither side exceeds [maxDimension] pixels
     * (0 = full size). Does Keystore and decode work: call off the main thread.
     */
    fun loadDecryptedBitmap(file: File, maxDimension: Int = 0): Bitmap? {
        if (!file.exists()) return null
        var jpeg: ByteArray? = null
        return try {
            jpeg = decryptPhoto(file.readBytes()) ?: return null
            decode(jpeg, maxDimension)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't decrypt intruder photo: ${e.message}")
            null
        } finally {
            HardwareKeyManager.zeroize(jpeg)
        }
    }

    private fun decryptPhoto(bytes: ByteArray): ByteArray? = when {
        bytes.startsWith(MAGIC_V2) -> {
            val envelope = parseV2(bytes)
            val dek = unwrapDek(envelope)
            if (dek == null) null else try {
                gcmDecrypt(dek, envelope.photoIv, bytes.copyOfRange(envelope.photoOffset, bytes.size), MAGIC_V2 + "photo".toByteArray())
            } finally {
                HardwareKeyManager.zeroize(dek)
            }
        }
        bytes.startsWith(MAGIC_V1) -> {
            val input = DataInputStream(ByteArrayInputStream(bytes, MAGIC_V1.size, bytes.size - MAGIC_V1.size))
            fun read(length: Int) = ByteArray(length).also(input::readFully)
            val dekIv = read(input.readUnsignedByte())
            val encDek = read(input.readUnsignedShort())
            val payloadIv = read(input.readUnsignedByte())
            val ciphertext = read(input.available())
            val dek = HardwareKeyManager.decryptWithMasterKey(HardwareKeyManager.EncryptedPayload(dekIv, encDek))
            if (dek == null) null else try {
                gcmDecrypt(dek, payloadIv, ciphertext, aad = null)
            } finally {
                HardwareKeyManager.zeroize(dek)
            }
        }
        bytes.startsWith(MAGIC_LEGACY) -> {
            val ivLength = bytes[MAGIC_LEGACY.size].toInt() and 0xFF
            val ivStart = MAGIC_LEGACY.size + 1
            HardwareKeyManager.decryptWithMasterKey(
                HardwareKeyManager.EncryptedPayload(
                    bytes.copyOfRange(ivStart, ivStart + ivLength),
                    bytes.copyOfRange(ivStart + ivLength, bytes.size)
                )
            )
        }
        else -> null
    }

    private fun decode(jpeg: ByteArray, maxDimension: Int): Bitmap? {
        val options = BitmapFactory.Options()
        if (maxDimension > 0) {
            options.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            var sample = 1
            while (options.outWidth / (sample * 2) >= maxDimension || options.outHeight / (sample * 2) >= maxDimension) sample *= 2
            options.inJustDecodeBounds = false
            options.inSampleSize = sample
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null

        val orientation = try {
            ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    // ---------------------------------------------------------------------------------------
    // Deletion
    // ---------------------------------------------------------------------------------------

    fun deleteIntruderRecord(record: IntruderRecord): Boolean = record.file.delete()

    fun clearAllIntruders(context: Context) {
        intrudersDir(context).listFiles()?.forEach { it.delete() }
        legacyDir(context).listFiles()?.forEach { it.delete() }
    }
}
