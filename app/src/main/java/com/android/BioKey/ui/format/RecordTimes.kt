package com.android.BioKey.ui.format

import android.content.Context
import android.text.format.DateUtils
import com.android.BioKey.R

/*
 * BioKey's one way of saying when something happened, for every record, card and alert:
 *
 * - A moment ([attemptTime]): the time alone today ("2:14 PM"), "Yesterday, 2:14 PM", else the
 *   short date and the time ("Oct 27, 2:14 PM"). "Today" is never written: a time alone is today's.
 * - A span ([recordRange]): within one day, that day said as a moment says it, once, before both
 *   times ("Yesterday, 7:46 – 7:47 PM"); across days, each end as a moment
 *   ("Yesterday, 8:10 PM – 1:27 AM", the end today).
 */

/**
 * [text] that never splits across lines where it has a space ("Oct 30", "3:02 PM", "NB Designs"):
 * each space a no-break one. (A narrow no-break space, as some time formats use, is left as it is.)
 */
fun unbroken(text: String): String = text.replace(' ', ' ')

/** A time of day in the phone's own format ("7:15 PM", "19:15"), kept on one line. */
fun clockTime(context: Context, at: Long): String =
    unbroken(android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(at)))

/** A moment: "2:14 PM" today, "Yesterday, 2:14 PM", else "Oct 27, 2:14 PM". */
fun attemptTime(context: Context, at: Long): String =
    withDay(context, at, unbroken(DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME)))

/**
 * A span from [from] to [to]: "3:02 – 3:10 PM" today, "Yesterday, 3:02 – 3:10 PM",
 * "Oct 27, 3:02 – 3:10 PM", and across days each end as [attemptTime] says it.
 */
fun recordRange(context: Context, from: Long, to: Long): String {
    if (!sameDay(from, to)) {
        return context.getString(R.string.record_time_range, attemptTime(context, from), attemptTime(context, to))
    }
    // The times alone, the dash binding both ways: a range never breaks around it.
    val times = DateUtils.formatDateRange(context, from, to, DateUtils.FORMAT_SHOW_TIME)
    return withDay(context, to, unbroken(times.replace(RANGE_DASH, " $1 ")))
}

/** [time] (one time or two) with its day said as a moment says it: nothing for today. */
private fun withDay(context: Context, at: Long, time: String): String = when {
    DateUtils.isToday(at) -> time
    DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.card_time_yesterday, time)
    else -> context.getString(
        R.string.card_time_date,
        unbroken(DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)),
        time
    )
}

private fun sameDay(a: Long, b: Long): Boolean {
    val one = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val two = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return one.get(java.util.Calendar.YEAR) == two.get(java.util.Calendar.YEAR) &&
        one.get(java.util.Calendar.DAY_OF_YEAR) == two.get(java.util.Calendar.DAY_OF_YEAR)
}

/** A range's dash with the spaces round it, thin ones included (ICU uses U+2009). */
private val RANGE_DASH = Regex("""[\s\x{2009}\x{202F}]([\x{2013}-])[\s\x{2009}\x{202F}]""")

/**
 * Words that follow " · " in a record's line ("3:04 PM · protection paused"): they go on from what
 * came before, so they start in lower case, unless they start with a name: BioKey's, Android's,
 * an app's or a profile's ([startsWithName], as the caller knows from the words' own template), or
 * a setting's in quotes. The same words on a line of their own keep their capital.
 */
fun afterSeparator(text: String, startsWithName: Boolean = false): String {
    if (startsWithName || text.isEmpty()) return text
    if (text.first() in "“\"‘'") return text
    if (PROPER_NAMES.any { text.startsWith(it) && text.getOrNull(it.length)?.isLetterOrDigit() != true }) return text
    return text.replaceFirstChar { it.lowercaseChar() }
}

/** Names that keep their capital wherever they are. */
private val PROPER_NAMES = listOf("BioKey", "Android")

/** Whether a template's words start with what's filled in ("%1\$s moved…"): a name, which keeps its capital. */
fun startsWithArgument(template: String): Boolean = template.startsWith("%")
