package com.android.BioKey.core.data

/**
 * Cleans untrusted text coming from installed packages, window titles or intents (bidi overrides,
 * control characters, delimiter bytes and oversized strings) before BioKey shows, stores or logs it.
 */
object AppText {

    private const val DEFAULT_MAX_LABEL = 120
    private const val MAX_LOG_LINE = 512

    /**
     * Single-line display text safe to render next to BioKey's own words: strips Unicode
     * bidirectional formatting characters (which could flip surrounding wording such as prompt
     * subtitles or security-activity rows), control characters and line breaks, and caps length.
     */
    fun cleanLabel(raw: CharSequence?, maxLength: Int = DEFAULT_MAX_LABEL): String {
        if (raw.isNullOrEmpty()) return ""
        val out = StringBuilder(minOf(raw.length, maxLength))
        var prevSpace = true
        for (i in 0 until raw.length) {
            val c = raw[i]
            if (isBidiOrFormatControl(c)) continue
            if (c.isWhitespace() || c.code < 0x20 || c == '\u007F') {
                if (!prevSpace && out.length < maxLength) {
                    out.append(' ')
                    prevSpace = true
                }
                continue
            }
            if (out.length >= maxLength) break
            out.append(c)
            prevSpace = false
        }
        if (out.isNotEmpty() && out[out.length - 1] == ' ') out.setLength(out.length - 1)
        return out.toString()
    }

    /** Cleans a single-line log message while keeping spaces intact and stripping tabs/newlines/bidi. */
    fun cleanLog(raw: String, maxLength: Int = MAX_LOG_LINE): String {
        val needsClean = raw.length > maxLength || raw.any {
            it == '\n' || it == '\r' || it == '\t' || it.code < 0x20 || it == '\u007F' || isBidiOrFormatControl(it)
        }
        if (!needsClean) return raw
        val out = StringBuilder(minOf(raw.length, maxLength))
        for (i in 0 until raw.length) {
            if (out.length >= maxLength) break
            val c = raw[i]
            when {
                isBidiOrFormatControl(c) -> Unit
                c == '\n' || c == '\r' || c == '\t' -> out.append(' ')
                c.code < 0x20 || c == '\u007F' -> Unit
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** Unicode bidi isolate/override/mark characters and record-separator bytes. */
    fun isBidiOrFormatControl(c: Char): Boolean = when (c) {
        '\u061C', '\u200E', '\u200F',
        in '\u202A'..'\u202E',
        in '\u2066'..'\u2069',
        '\uFEFF', '\u001F' -> true
        else -> false
    }
}
