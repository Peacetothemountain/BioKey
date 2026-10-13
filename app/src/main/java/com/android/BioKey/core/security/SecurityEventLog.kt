package com.android.BioKey.core.security

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Append-only, tamper-evident, hardware-encrypted log of security-relevant events.
 * Stored as an AES-GCM blob under the Keystore master key in no-backup storage
 * with an HMAC-SHA256 integrity seal.
 */
object SecurityEventLog {

    private const val TAG = "SecurityEventLog"
    private const val FILE_NAME = "security_events.bin"
    private const val PREFS_NAME = "biokey_event_log_seal"
    private const val KEY_SEAL = "log_hmac_seal"
    private const val KEY_SEALED_COUNT = "log_sealed_count"
    private const val MAX_EVENTS = 200

    enum class Type(val title: String) {
        BIOMETRICS_CHANGED("Enrolled biometrics changed"),
        SETTINGS_TAMPERED("Settings were modified outside BioKey"),
        SELF_PROTECTION_CHALLENGE("Blocked access to BioKey system settings"),
        PROTECTION_PAUSED("Protection paused"),
        PROTECTION_RESUMED("Protection resumed"),
        ACCESSIBILITY_STOPPED("App lock service stopped"),
        UNLOCK_FAILED("Failed unlock attempts"),
        UNLOCK_SUCCESS("Authentication succeeded"),
        INTRUDER_CAPTURED("Intruder photo captured"),
        SETTINGS_MODIFIED("Security settings updated"),
        KEYS_ROTATED("Security keys rotated"),
        ADMIN_REMOVED("Device admin deactivated"),
        LOG_CLEARED("Security log cleared"),
        LOG_TAMPERED("Security log integrity check failed")
    }

    data class Event(val type: Type, val timestamp: Long, val detail: String) {
        fun toIsoString(): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(timestamp))
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "BioKeyEventLog") }
    private val lock = Any()

    @Volatile
    private var inMemoryCache: MutableList<Event>? = null

    private val _version = MutableStateFlow(0)
    /** Increments whenever the log changes, so UI can reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE_NAME)
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(context: Context, type: Type, detail: String = "") {
        val appContext = context.applicationContext
        val event = Event(type, System.currentTimeMillis(), detail.take(200))
        io.execute {
            synchronized(lock) {
                val current = inMemoryCache ?: readLocked(appContext).toMutableList()
                val updated = (current + event).takeLast(MAX_EVENTS)
                inMemoryCache = updated.toMutableList()
                writeLocked(appContext, updated)
            }
            _version.value++
        }
    }

    /** Newest first. Performs disk and keystore I/O: call off the main thread. */
    fun events(context: Context): List<Event> = synchronized(lock) {
        val list = inMemoryCache ?: readLocked(context.applicationContext).also { inMemoryCache = it.toMutableList() }
        list.asReversed()
    }

    /**
     * Clears the log with Step-Up authorization check.
     * Preserves a LOG_CLEARED tombstone record proving the reset was authorized.
     */
    fun clear(context: Context): Boolean {
        if (!LockStateTracker.hasActiveStepUpLease()) {
            Log.w(TAG, "Refused to clear event log: active step-up lease required")
            return false
        }
        val appContext = context.applicationContext
        io.execute {
            synchronized(lock) {
                val tombstone = listOf(
                    Event(Type.LOG_CLEARED, System.currentTimeMillis(), "Authorized log reset by owner")
                )
                inMemoryCache = tombstone.toMutableList()
                writeLocked(appContext, tombstone)
            }
            _version.value++
        }
        return true
    }

    private fun readLocked(context: Context): List<Event> {
        val f = file(context)
        val sp = prefs(context)
        val expectedSeal = sp.getString(KEY_SEAL, null)

        if (!f.exists()) {
            if (expectedSeal != null) {
                Log.e(TAG, "Security log file missing but seal exists: log deletion detected!")
                ProtectionAlerts.logTamperDetected(context)
            }
            return emptyList()
        }

        if (expectedSeal == null && HardwareKeyManager.hasIntegrityKey()) {
            Log.e(TAG, "Security log seal missing but integrity key and log exist: seal deletion detected!")
            ProtectionAlerts.logTamperDetected(context)
            return listOf(Event(Type.LOG_TAMPERED, System.currentTimeMillis(), "Log seal was deleted or wiped"))
        }

        return try {
            val bytes = f.readBytes()
            if (expectedSeal != null) {
                val actualSeal = HardwareKeyManager.integrityMac(context, bytes)
                    ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                if (actualSeal == null || !MessageDigest.isEqual(actualSeal.toByteArray(), expectedSeal.toByteArray())) {
                    Log.e(TAG, "Security log seal mismatch! Tamper detected.")
                    ProtectionAlerts.logTamperDetected(context)
                    return listOf(Event(Type.LOG_TAMPERED, System.currentTimeMillis(), "Tamper or corruption detected on storage"))
                }
            }

            val ivLen = bytes[0].toInt() and 0xFF
            val payload = HardwareKeyManager.EncryptedPayload(
                iv = bytes.copyOfRange(1, 1 + ivLen),
                ciphertext = bytes.copyOfRange(1 + ivLen, bytes.size)
            )
            val plain = HardwareKeyManager.decryptWithMasterKey(payload)
                ?: throw IllegalStateException("Decryption failed: master key or GCM tag invalid")
            val array = JSONArray(String(plain, Charsets.UTF_8))
            HardwareKeyManager.zeroize(plain)
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val type = runCatching { Type.valueOf(o.getString("t")) }.getOrNull() ?: return@mapNotNull null
                Event(type, o.getLong("ts"), o.optString("d"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Event log unreadable or tampered: ${e.message}", e)
            ProtectionAlerts.logTamperDetected(context)
            listOf(Event(Type.LOG_TAMPERED, System.currentTimeMillis(), "Log payload failed verification: ${e.message?.take(50)}"))
        }
    }

    private fun writeLocked(context: Context, events: List<Event>) {
        val array = JSONArray()
        events.forEach { array.put(JSONObject().put("t", it.type.name).put("ts", it.timestamp).put("d", it.detail)) }
        val plain = array.toString().toByteArray(Charsets.UTF_8)
        val payload = HardwareKeyManager.encryptWithMasterKey(plain, context)
        HardwareKeyManager.zeroize(plain)
        if (payload == null) {
            Log.w(TAG, "Master key unavailable; event not persisted")
            return
        }

        val tmp = File(file(context).path + ".tmp")
        tmp.outputStream().use { out ->
            out.write(payload.iv.size)
            out.write(payload.iv)
            out.write(payload.ciphertext)
        }
        val target = file(context)
        if (tmp.renameTo(target)) {
            val writtenBytes = target.readBytes()
            val mac = HardwareKeyManager.integrityMac(context, writtenBytes)
                ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            prefs(context).edit()
                .putString(KEY_SEAL, mac)
                .putInt(KEY_SEALED_COUNT, events.size)
                .commit()
        }
    }

    /**
     * Securely exports the audit trail into an app-private cache file for sharing.
     * Requires an active owner session.
     */
    fun exportAuditLog(context: Context): Uri? {
        if (!LockStateTracker.hasActiveOwnerSession()) {
            Log.w(TAG, "Refused to export audit log: no active owner session")
            return null
        }
        val currentEvents = events(context)
        val exportDir = File(context.cacheDir, "security_exports").apply { mkdirs() }
        val exportFile = File(exportDir, "biokey_audit_${System.currentTimeMillis()}.json")

        val root = JSONObject()
        root.put("exportTime", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
        root.put("eventCount", currentEvents.size)
        val items = JSONArray()
        currentEvents.forEach { ev ->
            items.put(JSONObject().apply {
                put("type", ev.type.name)
                put("title", ev.type.title)
                put("timestamp", ev.timestamp)
                put("isoTime", ev.toIsoString())
                put("detail", ev.detail)
            })
        }
        root.put("events", items)
        exportFile.writeText(root.toString(2), Charsets.UTF_8)

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)
    }
}
