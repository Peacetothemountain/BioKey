package com.android.BioKey.core.diagnostics

/**
 * A length of time as the diagnostic log words it, units spelled out as everywhere else in BioKey:
 * "30 seconds", "1 minute", "5 minutes", "2 hours". Whole units, rounded down, never less than
 * a second. The log is English only (it is read by whoever is sent it).
 */
internal fun logDuration(millis: Long): String {
    val seconds = (millis / 1_000L).coerceAtLeast(1L)
    fun said(n: Long, unit: String) = "$n $unit" + if (n == 1L) "" else "s"
    return when {
        seconds < 60L -> said(seconds, "second")
        seconds < 3_600L -> said(seconds / 60L, "minute")
        else -> said(seconds / 3_600L, "hour")
    }
}
