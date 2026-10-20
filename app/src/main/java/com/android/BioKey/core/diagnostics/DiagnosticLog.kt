package com.android.BioKey.core.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A short on-phone log of BioKey's own lock decisions: which locked apps it locked or unlocked,
 * and why ("Covered com.chase…", "… left the screen · hop to a helper app", "All sessions ended
 * (phone locked)"), so an unexpected lock, or a missing one, can be traced. It names locked apps
 * only: where a hop went is "a helper app" or "another app", never which.
 *
 * It stays on the phone, in BioKey's private storage, and leaves it only if the user shares it
 * from Settings. It keeps the last [MAX_ENTRIES] entries of the last 3 days ([MAX_AGE_MS]),
 * checked whenever it is loaded, written or read; turning it off in Settings deletes it. Release
 * builds strip Android's own logging, so this is the only trace.
 */
object DiagnosticLog {

    private const val MAX_ENTRIES = 500
    private const val MAX_AGE_MS = 3 * 24 * 60 * 60_000L
    /** Appending is cheap; once the file holds this many lines, it is written afresh. */
    private const val REWRITE_AT_LINES = MAX_ENTRIES + 50
    private const val FILE_NAME = "diagnostics.log"
    private const val PREFS = "biokey_diagnostics"
    private const val KEY_ENABLED = "enabled"

    data class Entry(val at: Long, val text: String)

    private val entries = ArrayDeque<Entry>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-diagnostics").apply { isDaemon = true } }
    @Volatile private var file: File? = null
    @Volatile private var enabled = true
    private var linesInFile = 0
    /** Time of the oldest line in the file, or [Long.MAX_VALUE] when it has none. */
    private var oldestInFile = Long.MAX_VALUE

    private val _version = MutableStateFlow(0)
    /** Changes whenever the log does, for screens showing it. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /**
     * Loads the saved log; call once the user's storage is unlocked. Before that, and before the
     * on/off setting is read (on the log's own thread), entries stay in memory only.
     */
    fun init(context: Context) {
        val f = File(context.filesDir, FILE_NAME)
        io.execute {
            enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
            val saved = runCatching {
                if (!f.exists()) emptyList() else f.readLines().mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) null else line.substring(0, tab).toLongOrNull()?.let { Entry(it, line.substring(tab + 1)) }
                }
            }.getOrDefault(emptyList())
            synchronized(entries) {
                val early = entries.toList()
                entries.clear()
                if (enabled) {
                    entries.addAll(saved.takeLast(MAX_ENTRIES))
                    entries.addAll(early)
                    while (entries.size > MAX_ENTRIES) entries.removeFirst()
                    pruneOld()
                }
                file = f
                rewrite()
            }
            _version.update { it + 1 }
        }
    }

    fun isEnabled(): Boolean = enabled

    /** Records [text] now. Cheap, safe from any thread. */
    fun log(text: String) {
        if (!enabled) return
        val entry = Entry(System.currentTimeMillis(), text.replace('\n', ' '))
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        io.execute {
            // Logged before init() found the log turned off: dropped there, never written.
            val f = file?.takeIf { enabled } ?: return@execute
            synchronized(entries) {
                if (pruneOld() || linesInFile >= REWRITE_AT_LINES) {
                    rewrite()
                } else {
                    runCatching { f.appendText("${entry.at}\t${entry.text}\n") }
                    linesInFile++
                    oldestInFile = minOf(oldestInFile, entry.at)
                }
            }
        }
        _version.update { it + 1 }
    }

    fun entries(): List<Entry> = synchronized(entries) {
        if (pruneOld()) io.execute { synchronized(entries) { rewrite() } }
        entries.toList()
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
        io.execute { synchronized(entries) { rewrite() } }
        _version.update { it + 1 }
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) log("Diagnostic log turned on") else clear()
    }

    /** The whole log as text for sharing, newest last, with what's needed to read it. */
    fun report(context: Context, engine: String): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" }
        }.getOrDefault("?")
        return buildString {
            appendLine("BioKey diagnostic log")
            appendLine("BioKey $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Engine: $engine")
            appendLine()
            for (e in entries()) appendLine("${time.format(Date(e.at))}  ${e.text}")
        }
    }

    /**
     * Forgets entries older than [MAX_AGE_MS]. Returns whether the file still holds some, and so
     * must be written afresh. Call holding the entries lock.
     */
    private fun pruneOld(): Boolean {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        entries.removeAll { it.at < cutoff }
        return oldestInFile < cutoff
    }

    /** Writes the kept entries over the file. Call holding the entries lock, on [io]. */
    private fun rewrite() {
        val f = file ?: return
        runCatching { f.writeText(entries.joinToString("") { "${it.at}\t${it.text}\n" }) }
        linesInFile = entries.size
        oldestInFile = entries.minOfOrNull { it.at } ?: Long.MAX_VALUE
    }
}
