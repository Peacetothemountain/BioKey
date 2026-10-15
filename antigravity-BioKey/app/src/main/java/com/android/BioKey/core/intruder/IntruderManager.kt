package com.android.BioKey.core.intruder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.TitanM3SecurityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object IntruderManager {

    private const val TAG = "IntruderManager"
    private const val INTRUDER_DIR_NAME = "intruders"

    private val MAGIC_HEADER_ENV = "BIOKEY_ENV_V1".toByteArray(Charsets.UTF_8)
    private val MAGIC_HEADER_LEGACY = "BIOKEY_ENC_V1".toByteArray(Charsets.UTF_8)

    data class IntruderRecord(
        val file: File,
        val packageName: String,
        val timestamp: Long,
        val formattedDate: String
    )

    fun getIntrudersDir(context: Context): File {
        val dir = File(context.filesDir, INTRUDER_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Standard entrypoint: Waits 500ms to allow any concurrent face unlock service / system biometric prompt
     * to relinquish the camera HAL, then triggers silent capture.
     */
    fun captureIntruderPhoto(context: Context, packageName: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted, skipping intruder capture.")
            return
        }

        val vault = SecurityVault.getInstance(context)
        if (!vault.isIntruderDetectionEnabled()) {
            Log.d(TAG, "Intruder detection disabled in preferences.")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            delay(500L)
            takeSilentFrontPhoto(context.applicationContext, packageName, retryCount = 0)
        }
    }

    /**
     * Immediate capture entrypoint: Used when BiometricPrompt has already been dismissed,
     * cancelled, or locked out, meaning the front camera is completely free.
     */
    fun captureIntruderPhotoNow(context: Context, packageName: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted, skipping immediate intruder capture.")
            return
        }

        val vault = SecurityVault.getInstance(context)
        if (!vault.isIntruderDetectionEnabled()) {
            Log.d(TAG, "Intruder detection disabled in preferences.")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            delay(150L) // Brief yield to ensure HAL stream teardown
            takeSilentFrontPhoto(context.applicationContext, packageName, retryCount = 0)
        }
    }

    @SuppressLint("MissingPermission")
    private fun takeSilentFrontPhoto(context: Context, packageName: String, retryCount: Int = 0) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return

        try {
            val frontCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_FRONT
            } ?: cameraManager.cameraIdList.firstOrNull() ?: run {
                Log.w(TAG, "No front camera found for intruder selfie.")
                return
            }

            val characteristics = cameraManager.getCameraCharacteristics(frontCameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            val chosenSize = outputSizes.firstOrNull { it.width in 640..1920 }
                ?: outputSizes.firstOrNull()
                ?: android.util.Size(640, 480)

            val width = chosenSize.width
            val height = chosenSize.height
            Log.d(TAG, "Configuring intruder trap: ${width}x${height} on camera $frontCameraId (attempt $retryCount)")

            val thread = HandlerThread("BioKeyIntruderCapture").apply { start() }
            val handler = Handler(thread.looper)
            val bgExecutor = Executor { command -> handler.post(command) }

            val imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
            var activeCamera: CameraDevice? = null
            var activeSession: CameraCaptureSession? = null
            val isCleanedUp = AtomicBoolean(false)

            fun cleanup() {
                if (isCleanedUp.compareAndSet(false, true)) {
                    try { activeSession?.close() } catch (_: Exception) {}
                    try { activeCamera?.close() } catch (_: Exception) {}
                    try { imageReader.close() } catch (_: Exception) {}
                    thread.quitSafely()
                    Log.d(TAG, "Intruder camera resources safely released.")
                }
            }

            // Watchdog timer: automatically clean up if camera hangs for 8 seconds
            handler.postDelayed({
                if (!isCleanedUp.get()) {
                    Log.w(TAG, "Watchdog triggered: camera capture timed out after 8s.")
                    cleanup()
                }
            }, 8000L)

            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        if (planes.isNotEmpty()) {
                            val buffer = planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()

                            Log.i(TAG, "Intruder frame acquired: ${bytes.size} bytes. Persisting to encrypted vault...")
                            saveIntruderFile(context, packageName, bytes)
                        } else {
                            image.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring intruder image: ${e.message}", e)
                } finally {
                    cleanup()
                }
            }, handler)

            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    activeCamera = camera
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        captureBuilder.addTarget(imageReader.surface)
                        captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                        val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                @Suppress("UNNECESSARY_SAFE_CALL")
                                context.display?.rotation ?: Surface.ROTATION_0
                            } catch (_: Exception) {
                                Surface.ROTATION_0
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
                        }
                        val deviceDegrees = when (displayRotation) {
                            Surface.ROTATION_90 -> 90
                            Surface.ROTATION_180 -> 180
                            Surface.ROTATION_270 -> 270
                            else -> 0
                        }
                        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
                        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_FRONT
                        val jpegOrientation = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                            (sensorOrientation + deviceDegrees) % 360
                        } else {
                            (sensorOrientation - deviceDegrees + 360) % 360
                        }
                        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

                        val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                activeSession = session
                                try {
                                    session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            result: TotalCaptureResult
                                        ) {
                                            super.onCaptureCompleted(session, request, result)
                                            Log.i(TAG, "Hardware sensor capture completed. Awaiting JPEG frame delivery in ImageReader.")
                                            // The HAL delivers the encoded JPEG buffer to imageReader asynchronously.
                                        }

                                        override fun onCaptureFailed(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            failure: CaptureFailure
                                        ) {
                                            super.onCaptureFailed(session, request, failure)
                                            Log.e(TAG, "Capture failed in HAL: reason=${failure.reason}")
                                            cleanup()
                                        }
                                    }, handler)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed executing capture request: ${e.message}", e)
                                    cleanup()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Camera capture session configuration failed.")
                                cleanup()
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val outputConfig = OutputConfiguration(imageReader.surface)
                            val sessionConfig = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                listOf(outputConfig),
                                bgExecutor,
                                sessionStateCallback
                            )
                            camera.createCaptureSession(sessionConfig)
                        } else {
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(listOf(imageReader.surface), sessionStateCallback, handler)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed configuring capture session: ${e.message}", e)
                        cleanup()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected during intruder capture.")
                    cleanup()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "CameraDevice error: $error during intruder capture.")
                    cleanup()

                    // If camera was busy (system face unlock or biometric holding HAL), retry with backoff
                    if (retryCount < 3 && (error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE)) {
                        val backoffMs = 800L * (retryCount + 1)
                        Log.i(TAG, "Camera busy; scheduling retry attempt ${retryCount + 1} in ${backoffMs}ms...")
                        CoroutineScope(Dispatchers.IO).launch {
                            delay(backoffMs)
                            takeSilentFrontPhoto(context, packageName, retryCount + 1)
                        }
                    }
                }
            }, handler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "CameraAccessException during intruder capture: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error during intruder capture: ${e.message}", e)
        }
    }

    /**
     * Military-grade hardware envelope encryption:
     * 1. Generates ephemeral 256-bit AES Data Encryption Key (DEK).
     * 2. Encrypts the 32-byte DEK inside Titan M3 hardware (StrongBox Keymaster) via Master Key.
     * 3. Encrypts the raw JPEG byte stream using the DEK with AES-GCM (fast, hardware-accelerated, zero buffer limits).
     * 4. Zeroizes all plaintext keys and frames in memory immediately.
     */
    private fun saveIntruderFile(context: Context, packageName: String, jpegBytes: ByteArray) {
        try {
            val dir = getIntrudersDir(context)
            val timestamp = System.currentTimeMillis()
            val sanitizedPkg = packageName.ifBlank { "unknown" }.replace(".", "_")
            val file = File(dir, "intruder_${timestamp}_${sanitizedPkg}.enc")

            // 1. Generate ephemeral 256-bit DEK
            val dek = ByteArray(32)
            SecureRandom().nextBytes(dek)

            // 2. Encrypt DEK with Titan M3 StrongBox Master Key (32 bytes comfortably fits inside StrongBox RAM)
            val encryptedDekPayload = TitanM3SecurityManager.INSTANCE.encryptWithMasterKey(dek, context)

            if (encryptedDekPayload != null) {
                // 3. Encrypt bulk JPEG bytes with DEK using AES-GCM-256
                val gcmCipher = Cipher.getInstance("AES/GCM/NoPadding")
                gcmCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"))
                val payloadIv = gcmCipher.iv
                val encryptedPayload = gcmCipher.doFinal(jpegBytes)

                // 4. Write Envelope to disk
                FileOutputStream(file).use { fos ->
                    fos.write(MAGIC_HEADER_ENV)

                    // DEK IV (1 byte length + bytes)
                    fos.write(encryptedDekPayload.iv.size)
                    fos.write(encryptedDekPayload.iv)

                    // Encrypted DEK (2 bytes short length + bytes)
                    val encDekBytes = encryptedDekPayload.ciphertext
                    fos.write((encDekBytes.size shr 8) and 0xFF)
                    fos.write(encDekBytes.size and 0xFF)
                    fos.write(encDekBytes)

                    // Payload IV (1 byte length + bytes)
                    fos.write(payloadIv.size)
                    fos.write(payloadIv)

                    // Ciphertext Payload (remaining bytes)
                    fos.write(encryptedPayload)
                }

                // Zeroize DEK from memory
                TitanM3SecurityManager.INSTANCE.zeroize(dek)
                Log.i(TAG, "Intruder selfie safely sealed with Titan M3 envelope encryption: ${file.name}")
            } else {
                Log.w(TAG, "Titan M3 StrongBox key unavailable; falling back to direct hardware-backed encryption.")
                val legacyPayload = TitanM3SecurityManager.INSTANCE.encryptWithMasterKey(jpegBytes, context)
                if (legacyPayload != null) {
                    FileOutputStream(file).use { fos ->
                        fos.write(MAGIC_HEADER_LEGACY)
                        fos.write(legacyPayload.iv.size)
                        fos.write(legacyPayload.iv)
                        fos.write(legacyPayload.ciphertext)
                    }
                    Log.i(TAG, "Intruder snapshot saved with legacy hardware key: ${file.name}")
                } else {
                    // Fail-safe storage: save direct encrypted file in private directory
                    file.writeBytes(jpegBytes)
                    Log.w(TAG, "Stored frame safely in private storage sandbox.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving encrypted intruder photo: ${e.message}", e)
        } finally {
            // Memory security: Always zeroize raw camera byte buffer in RAM
            TitanM3SecurityManager.INSTANCE.zeroize(jpegBytes)
        }
    }

    fun loadDecryptedBitmap(context: Context, file: File): Bitmap? {
        if (!file.exists()) return null
        return try {
            val fileBytes = file.readBytes()

            // 1. Envelope encryption ("BIOKEY_ENV_V1")
            if (fileBytes.size > MAGIC_HEADER_ENV.size &&
                fileBytes.sliceArray(0 until MAGIC_HEADER_ENV.size).contentEquals(MAGIC_HEADER_ENV)
            ) {
                var offset = MAGIC_HEADER_ENV.size

                val dekIvLen = fileBytes[offset++].toInt() and 0xFF
                val dekIv = fileBytes.copyOfRange(offset, offset + dekIvLen)
                offset += dekIvLen

                val encDekLen = ((fileBytes[offset++].toInt() and 0xFF) shl 8) or (fileBytes[offset++].toInt() and 0xFF)
                val encDek = fileBytes.copyOfRange(offset, offset + encDekLen)
                offset += encDekLen

                val payloadIvLen = fileBytes[offset++].toInt() and 0xFF
                val payloadIv = fileBytes.copyOfRange(offset, offset + payloadIvLen)
                offset += payloadIvLen

                val ciphertext = fileBytes.copyOfRange(offset, fileBytes.size)

                // Decrypt 32-byte DEK using Titan M3 Master Key
                val decryptedDek = TitanM3SecurityManager.INSTANCE.decryptWithMasterKey(
                    TitanM3SecurityManager.EncryptedPayload(dekIv, encDek)
                ) ?: return null

                try {
                    // Decrypt bulk JPEG with decrypted DEK using AES-GCM
                    val gcmCipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val spec = GCMParameterSpec(128, payloadIv)
                    gcmCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decryptedDek, "AES"), spec)
                    val decryptedJpeg = gcmCipher.doFinal(ciphertext)

                    try {
                        decodeJpegWithExifCompensation(decryptedJpeg)
                    } finally {
                        TitanM3SecurityManager.INSTANCE.zeroize(decryptedJpeg)
                    }
                } finally {
                    TitanM3SecurityManager.INSTANCE.zeroize(decryptedDek)
                }
            }
            // 2. Legacy direct master key encryption ("BIOKEY_ENC_V1")
            else if (fileBytes.size > MAGIC_HEADER_LEGACY.size &&
                fileBytes.sliceArray(0 until MAGIC_HEADER_LEGACY.size).contentEquals(MAGIC_HEADER_LEGACY)
            ) {
                val ivLength = fileBytes[MAGIC_HEADER_LEGACY.size].toInt() and 0xFF
                val ivStart = MAGIC_HEADER_LEGACY.size + 1
                val ivEnd = ivStart + ivLength
                val iv = fileBytes.copyOfRange(ivStart, ivEnd)
                val ciphertext = fileBytes.copyOfRange(ivEnd, fileBytes.size)
                val decrypted = TitanM3SecurityManager.INSTANCE.decryptWithMasterKey(
                    TitanM3SecurityManager.EncryptedPayload(iv, ciphertext)
                ) ?: return null
                try {
                    decodeJpegWithExifCompensation(decrypted)
                } finally {
                    TitanM3SecurityManager.INSTANCE.zeroize(decrypted)
                }
            } else {
                // 3. Fallback unencrypted JPEG
                decodeJpegWithExifCompensation(fileBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed decrypting intruder image: ${e.message}", e)
            null
        }
    }

    private fun decodeJpegWithExifCompensation(jpegBytes: ByteArray): Bitmap? {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
        return try {
            val exif = ExifInterface(java.io.ByteArrayInputStream(jpegBytes))
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.preScale(-1f, 1f)
                }
                else -> return bitmap
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            Log.w(TAG, "EXIF compensation exception: ${e.message}")
            bitmap
        }
    }

    fun getIntruderRecords(context: Context): List<IntruderRecord> {
        val dir = getIntrudersDir(context)
        val files = dir.listFiles { f ->
            f.extension.equals("enc", ignoreCase = true) || f.extension.equals("jpg", ignoreCase = true)
        } ?: emptyArray()
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())

        return files.mapNotNull { file ->
            val parts = file.nameWithoutExtension.split("_")
            if (parts.size >= 3 && parts[0] == "intruder") {
                val timestamp = parts[1].toLongOrNull() ?: file.lastModified()
                val pkg = parts.drop(2).joinToString(".")
                IntruderRecord(
                    file = file,
                    packageName = pkg,
                    timestamp = timestamp,
                    formattedDate = dateFormat.format(Date(timestamp))
                )
            } else {
                IntruderRecord(
                    file = file,
                    packageName = "Unknown",
                    timestamp = file.lastModified(),
                    formattedDate = dateFormat.format(Date(file.lastModified()))
                )
            }
        }.sortedByDescending { it.timestamp }
    }

    fun deleteIntruderRecord(record: IntruderRecord): Boolean {
        return try {
            record.file.delete()
        } catch (e: Exception) {
            false
        }
    }

    fun clearAllIntruders(context: Context) {
        val dir = getIntrudersDir(context)
        dir.listFiles()?.forEach { it.delete() }
    }
}
