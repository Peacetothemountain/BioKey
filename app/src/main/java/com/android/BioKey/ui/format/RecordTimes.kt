package com.android.BioKey.ui.format

import android.content.Context
import android.text.format.DateUtils
import com.android.BioKey.R

/** "2:14 PM" today, "Yesterday, 2:14 PM", else "Oct 27, 2:14 PM". */
fun attemptTime(context: Context, at: Long): String {
    // "Oct 30" and "3:02 PM" each stay on one line.
    fun unbroken(text: String) = text.replace(' ', ' ')
    val time = unbroken(DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME))
    return when {
        DateUtils.isToday(at) -> time
        DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.card_time_yesterday, time)
        else -> context.getString(
            R.string.card_time_date,
            unbroken(DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)),
            time
        )
    }
}
