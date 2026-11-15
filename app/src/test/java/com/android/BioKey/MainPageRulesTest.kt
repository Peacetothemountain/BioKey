package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionGaps.Change
import com.android.BioKey.core.security.ProtectionGaps.Gap
import com.android.BioKey.core.security.ProtectionGaps.Kind
import com.android.BioKey.feature.main.RecordRun
import com.android.BioKey.feature.main.cardSettle
import com.android.BioKey.feature.main.gapLine
import com.android.BioKey.feature.main.runLine
import com.android.BioKey.ui.format.afterSeparator
import com.android.BioKey.ui.format.attemptTime
import com.android.BioKey.ui.format.recordRange
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The main page's rules for records: one way of saying a moment and a span, the same day said the
 * same way everywhere; words after " · " in lower case unless they start with a name; and a
 * profile's card never left cut by the pinned controls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class MainPageRulesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Today at [hour]:[minute], [daysAgo] days back. */
    private fun at(daysAgo: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun plain(s: String) = s.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')

    /** What a moment says before its time: "", "Yesterday, " or "Nov 14, ". */
    private fun dayPart(at: Long): String {
        val time = plain(android.text.format.DateUtils.formatDateTime(context, at, android.text.format.DateUtils.FORMAT_SHOW_TIME))
        return plain(attemptTime(context, at)).removeSuffix(time)
    }

    @Test
    fun aDayIsSaidTheSameWayInAMomentAndInASpan() {
        for (daysAgo in listOf(0, 1, 5)) {
            val from = at(daysAgo, 0, 5)
            val to = at(daysAgo, 0, 6)
            val span = plain(recordRange(context, from, to))
            assertTrue("$span / ${dayPart(to)}", span.startsWith(dayPart(to)) && "–" in span)
            // "Today" is never written: a time alone is today's.
            assertFalse(span.contains("Today"))
            assertFalse(plain(attemptTime(context, to)).contains("Today"))
        }
        // Older than yesterday: a short date, never the platform's "at" form.
        assertTrue(dayPart(at(5, 0, 5)).endsWith(", "))
        assertFalse(plain(recordRange(context, at(5, 0, 5), at(5, 0, 6))).contains(" at "))
    }

    @Test
    fun aSpanAcrossDaysSaysEachEndAsAMomentIs() {
        val from = at(2, 20, 10)
        val to = at(1, 1, 27)
        val span = plain(recordRange(context, from, to))
        assertEquals(plain(attemptTime(context, from)) + " – " + plain(attemptTime(context, to)), span)
        // Not the platform's "Nov 14 at 8:10 PM" form.
        assertFalse(span, " at " in span)
    }

    @Test
    fun aRunOfRecordsUsesTheSameSpanAsATimeOff() {
        val from = at(1, 19, 46)
        val to = at(1, 19, 47)
        val change = Gap(to, to, Kind.CHANGED_WITH_SCREEN_LOCK, 2L, Change.PAUSED.encode())
        val line = plain(runLine(context, RecordRun(change, count = 2, oldestAt = from)))
        assertTrue(line, line.startsWith(plain(recordRange(context, from, to))))
        val off = plain(gapLine(context, Gap(from, to, Kind.SWITCHED_OFF, 3L)))
        assertTrue(off, off.startsWith(plain(recordRange(context, from, to))))
    }

    @Test
    fun wordsAfterTheDotStartInLowerCaseUnlessTheyStartWithAName() {
        assertEquals("protection paused", afterSeparator("Protection paused"))
        assertEquals("lock switched off: Notes", afterSeparator("Lock switched off: Notes"))
        assertEquals("BioKey was force-stopped", afterSeparator("BioKey was force-stopped"))
        assertEquals("Android’s settings changed", afterSeparator("Android’s settings changed"))
        assertEquals("“Face or fingerprint only” switched off", afterSeparator("“Face or fingerprint only” switched off"))
        assertEquals("Work profile switched off", afterSeparator("Work profile switched off", startsWithName = true))
        // A word that only starts like a name isn't one.
        assertEquals("androids", afterSeparator("Androids"))
    }

    @Test
    fun aRecordsLineAppliesTheRuleWhereItIsPutTogether() {
        fun line(change: Change, vararg args: String) =
            plain(gapLine(context, Gap(at(0, 0, 1), at(0, 0, 1), Kind.CHANGED_WITH_SCREEN_LOCK, 1L, change.encode(*args)), grouped = true))
        assertTrue(line(Change.PAUSED), line(Change.PAUSED).endsWith(" · protection paused"))
        assertTrue(line(Change.LOCK_OFF, "Notes").endsWith(" · lock switched off: Notes"))
        // A profile's name first keeps its capital, as does a quoted setting.
        assertTrue(line(Change.PROFILE_OFF, "Work").endsWith(" · Work profile switched off"))
        assertTrue(line(Change.BIOMETRIC_ONLY_OFF).endsWith(" · “Face or fingerprint only” switched off"))
        // The same words on a line of their own keep their capital.
        assertEquals("Protection paused", context.getString(R.string.change_paused))
    }

    @Test
    fun aProfileCardNeverRestsCutByThePinnedControls() {
        // Clear of them, or wholly under them: left alone.
        assertEquals(0, cardSettle(offset = 300, size = 200, under = 250, canGoOn = true))
        assertEquals(0, cardSettle(offset = 0, size = 200, under = 250, canGoOn = true))
        // A little under: back until it's whole.
        assertEquals(-50, cardSettle(offset = 200, size = 200, under = 250, canGoOn = true))
        // Mostly under: on until it's gone, where the list can go on; else back.
        assertEquals(50, cardSettle(offset = 100, size = 200, under = 250, canGoOn = true))
        assertEquals(-150, cardSettle(offset = 100, size = 200, under = 250, canGoOn = false))
    }
}
