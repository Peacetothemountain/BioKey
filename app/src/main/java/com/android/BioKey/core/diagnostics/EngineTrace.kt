package com.android.BioKey.core.diagnostics

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.android.BioKey.BuildConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * Debug builds only: everything the protection engines were told, and what they did, in order,
 * so a scenario seen on a phone can be replayed on the JVM (the replay tests in app/src/test) and
 * a fix checked against it there, without the phone.
 *
 * One line per record, `<uptimeMs>\t<kind>\t<field>\t...`, fields escaped ([escape]). Inputs:
 * - `env`: the phone as the engines see it, at the start of a trace (locked apps and their re-lock
 *   delays, names, home screens, helper apps, keyboards; see [environment]).
 * - `a.ev` an accessibility event, `a.win` a window list as read ([windows]).
 * - `c.poll` a compatibility-mode poll that read usage events for the first time: each, and how
 *   long before the read it happened.
 * - `d.*` what the engines asked of the phone (screen on, phone locked, keyguard up) whenever the
 *   answer changed, and `d.bc` a broadcast they received.
 * - `l.*` BioKey's lock screen resuming or leaving, `p.*` its prompt, `u.*` what the owner did
 *   there (unlocked, cancelled, or the lock screen was pushed away).
 * Outputs, which a replay must reproduce: `o.log` (the diagnostic log), `o.launch` (a lock screen
 * started), `o.home` (sent Home).
 *
 * Kept in memory (the last [MAX_LINES]) and written to `files/engine-trace.txt` a moment after
 * each change, where `adb shell run-as com.android.BioKey cat files/engine-trace.txt` reads it.
 * Release builds record nothing: these lines name locked apps and what was on screen.
 */
object EngineTrace {

    private const val MAX_LINES = 20_000
    private const val FILE_NAME = "engine-trace.txt"
    private const val WRITE_DELAY_MS = 2_000L
    const val HEADER = "# BioKey engine trace v1"

    /** Recording at all: debug builds only. A constant, so R8 drops every record from a release build. */
    val enabled: Boolean get() = BuildConfig.DEBUG

    private val lines = ArrayDeque<String>()
    private val io by lazy { Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "biokey-engine-trace").apply { isDaemon = true } } }
    @Volatile private var file: File? = null
    @Volatile private var writeQueued = false
    /** Lines recorded since the trace started, and how many of them are in the file (under [lines]' lock). */
    private var appended = 0L
    private var flushed = 0L
    /** Lines in the file: once it holds twice what is kept, it is written afresh. */
    private var linesInFile = 0
    private var rewriteNext = true
    /** The last answer recorded for each `d.*` question, so only changes are recorded. */
    private val lastAnswers = HashMap<String, Boolean>()
    @Volatile private var environment: List<String> = emptyList()

    /**
     * Starts a trace for an engine that has just started: forgets the last one, and records [env]
     * (`env` records from [environment]) first. Where the trace is written: [context]'s files.
     */
    fun start(context: Context, env: List<String>) {
        if (!enabled) return
        file = File(context.applicationContext.filesDir, FILE_NAME)
        synchronized(lines) {
            lines.clear()
            lastAnswers.clear()
            appended = 0L
            flushed = 0L
            rewriteNext = true
        }
        environment = env
        for (line in env) append(line)
    }

    /** Records `<now>\t<kind>\t<fields>`. */
    fun record(kind: String, vararg fields: Any?) {
        if (!enabled) return
        append(line(kind, *fields))
    }

    /** One record, as [record] writes it, for [start]'s environment. */
    fun line(kind: String, vararg fields: Any?): String = buildString {
        append(SystemClock.uptimeMillis()).append('\t').append(kind)
        for (f in fields) append('\t').append(escape(f?.toString() ?: ""))
    }

    /** Records `d.<question>` with [answer], only when it differs from the last one recorded. */
    fun answer(question: String, answer: Boolean) {
        if (!enabled) return
        val changed = synchronized(lines) {
            if (lastAnswers[question] == answer) false else {
                lastAnswers[question] = answer
                true
            }
        }
        if (changed) record("d.$question", if (answer) 1 else 0)
    }

    /**
     * An accessibility event, as the accessibility engine received it: its type, package, class,
     * window, display, window and content changes, description ([NONE] for none) and texts, each
     * text a field of its own.
     */
    fun event(e: AccessibilityEvent) {
        if (!enabled) return
        val display = if (android.os.Build.VERSION.SDK_INT >= 33) e.displayId else -1
        val description = e.contentDescription?.toString() ?: NONE
        val texts = e.text.map { it?.toString().orEmpty() }
        record("a.ev", e.eventType, e.packageName, e.className, e.windowId, display, e.windowChanges, e.contentChangeTypes, description, *texts.toTypedArray())
    }

    /** A field that has no value, unlike an empty one (a character no text has). */
    const val NONE = "\u0000"

    /**
     * A window list as the accessibility engine read it: for each window its id, type, display,
     * state (`a` active, `f` focused, `p` picture-in-picture), layer, bounds, title, and whose it
     * reads as ([rootPackage], empty when it can't be read).
     */
    fun windows(list: List<AccessibilityWindowInfo>, rootPackage: (AccessibilityWindowInfo) -> String?) {
        if (!enabled) return
        val bounds = android.graphics.Rect()
        val fields = list.map { w ->
            w.getBoundsInScreen(bounds)
            val state = buildString {
                if (w.isActive) append('a')
                if (w.isFocused) append('f')
                if (w.isInPictureInPictureMode) append('p')
            }
            listOf(
                w.id, w.type, w.displayId, state, w.layer,
                "${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}",
                w.title?.toString().orEmpty(), rootPackage(w).orEmpty()
            ).joinToString(",") { escape(it.toString()) }
        }
        record("a.win", *fields.toTypedArray())
    }

    /** Every line recorded, the environment first (it is kept even once older lines go). */
    fun lines(): List<String> = synchronized(lines) {
        val kept = lines.toList()
        listOf(HEADER) + (if (kept.firstOrNull()?.contains("\tenv\t") == true) emptyList() else environment) + kept
    }

    fun clear() = synchronized(lines) {
        lines.clear()
        lastAnswers.clear()
        environment = emptyList()
        appended = 0L
        flushed = 0L
        rewriteNext = true
    }

    private fun append(line: String) {
        synchronized(lines) {
            lines.addLast(line)
            appended++
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        scheduleWrite()
    }

    private fun scheduleWrite() {
        if (file == null || writeQueued) return
        writeQueued = true
        runCatching {
            io.schedule({
                writeQueued = false
                write()
            }, WRITE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.onFailure { writeQueued = false }
    }

    /**
     * Adds what was recorded since the last write to the file, or writes it afresh: when a trace
     * starts, when lines went from memory before they were written, and once the file holds twice
     * what memory keeps.
     */
    private fun write() {
        val f = file ?: return
        val (text, afresh) = synchronized(lines) {
            val firstKept = appended - lines.size
            val afresh = rewriteNext || flushed < firstKept || linesInFile + (appended - flushed) > 2 * MAX_LINES
            val text = if (afresh) {
                linesInFile = lines.size + 1 + environment.size
                lines().joinToString("\n", postfix = "\n")
            } else {
                val new = lines.toList().takeLast((appended - flushed).toInt())
                linesInFile += new.size
                new.joinToString("\n", postfix = "\n")
            }
            rewriteNext = false
            flushed = appended
            text to afresh
        }
        runCatching { if (afresh) f.writeText(text) else f.appendText(text) }
    }

    /** Tabs, line breaks, commas, [NONE] and the escape itself, as `%XX`, so fields and lists stay apart. */
    fun escape(s: String): String {
        if (s.none { it == '\t' || it == '\n' || it == '\r' || it == ',' || it == '%' || it == '\u0000' }) return s
        return buildString {
            for (c in s) when (c) {
                '\t', '\n', '\r', ',', '%', '\u0000' -> append('%').append("%02X".format(c.code))
                else -> append(c)
            }
        }
    }

    fun unescape(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                out.append(s.substring(i + 1, i + 3).toInt(16).toChar())
                i += 3
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
