package com.android.BioKey.core.diagnostics

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.android.BioKey.core.security.TrustedClock
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

    /**
     * The kept entries. Their lock is held only to change or copy them, never for the file, which
     * is read and written on [io] outside it: [log] runs on the main thread just before a cover
     * goes up, and must not wait for the disk.
     */
    private val entries = ArrayDeque<Entry>()
    /** How many entries have been logged so far: the newest one's number. Under the entries lock. */
    private var logged = 0L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-diagnostics").apply { isDaemon = true } }
    @Volatile private var file: File? = null
    @Volatile private var enabled = true
    /** Only touched on [io]. */
    private var linesInFile = 0
    /** The number ([logged]) of the newest entry the file holds. Only touched on [io]. */
    private var written = 0L
    /** Time of the oldest line in the file, or [Long.MAX_VALUE] when it has none. Written only on [io]. */
    @Volatile private var oldestInFile = Long.MAX_VALUE

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
            val (kept, through) = synchronized(entries) {
                val early = entries.toList()
                entries.clear()
                if (enabled) {
                    entries.addAll(saved.takeLast(MAX_ENTRIES))
                    entries.addAll(early)
                    while (entries.size > MAX_ENTRIES) entries.removeFirst()
                }
                keptEntries()
            }
            file = f
            write(kept, through)
            _version.update { it + 1 }
        }
    }

    fun isEnabled(): Boolean = enabled

    /** Records [text] now. Cheap, safe from any thread. */
    fun log(text: String) {
        // Whatever the log's own switch: a replay compares what the engines decided.
        EngineTrace.record("o.log", text)
        if (!enabled) return
        val entry = Entry(System.currentTimeMillis(), text.replace('\n', ' '))
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            val number = ++logged
            // Queued under the lock, so entries reach the file in the order they are numbered.
            io.execute {
                // Logged before init() found the log turned off: dropped there, never written.
                val f = file?.takeIf { enabled } ?: return@execute
                // Already in the file: a rewrite since it was logged wrote it.
                if (number <= written) return@execute
                if (oldestInFile < cutoff() || linesInFile >= REWRITE_AT_LINES) {
                    rewrite()
                } else {
                    runCatching { f.appendText("${entry.at}\t${entry.text}\n") }
                    linesInFile++
                    written = number
                    oldestInFile = minOf(oldestInFile, entry.at)
                }
            }
        }
        _version.update { it + 1 }
    }

    fun entries(): List<Entry> {
        val cutoff = cutoff()
        val kept = synchronized(entries) {
            entries.removeAll { it.at < cutoff }
            entries.toList()
        }
        // The file still holds lines that old: written afresh, on the log's own thread.
        if (oldestInFile < cutoff) io.execute { if (oldestInFile < cutoff()) rewrite() }
        return kept
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
        io.execute(::rewrite)
        _version.update { it + 1 }
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, on) }
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

    /** Entries logged before this are older than [MAX_AGE_MS]. */
    /** Aged on [TrustedClock]: changing the phone's date doesn't empty the log. */
    private fun cutoff(): Long = TrustedClock.now() - MAX_AGE_MS

    /**
     * Forgets entries older than [MAX_AGE_MS] and copies the rest, with the number ([logged]) of
     * the newest entry logged so far. Call holding the entries lock.
     */
    private fun keptEntries(): Pair<List<Entry>, Long> {
        val cutoff = cutoff()
        entries.removeAll { it.at < cutoff }
        return entries.toList() to logged
    }

    /** Writes the kept entries over the file. Call on [io]; the lock is held only to copy them. */
    private fun rewrite() {
        val (kept, through) = synchronized(entries) { keptEntries() }
        write(kept, through)
    }

    /** Writes [kept], every entry up to number [through], over the file. On [io], outside the entries lock. */
    private fun write(kept: List<Entry>, through: Long) {
        val f = file ?: return
        runCatching { f.writeText(kept.joinToString("") { "${it.at}\t${it.text}\n" }) }
        linesInFile = kept.size
        written = through
        oldestInFile = kept.minOfOrNull { it.at } ?: Long.MAX_VALUE
    }
}
