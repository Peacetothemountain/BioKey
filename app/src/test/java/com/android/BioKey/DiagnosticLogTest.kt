package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.LockStateTracker
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The diagnostic log keeps 3 days of BioKey's own decisions, and names only locked apps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticLogTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file get() = File(context.filesDir, "diagnostics.log")

    @Before
    fun reset() {
        LockStateTracker.onDeviceLockState(false)
        LockStateTracker.lockAll()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < until) { "Gave up waiting for $what" }
            Thread.sleep(20)
        }
    }

    /** Waits for the log's own thread to have loaded [text]. */
    private fun awaitEntry(text: String) = waitUntil("\"$text\"") { DiagnosticLog.entries().any { it.text == text } }

    @Test
    fun entriesOlderThanThreeDaysAreDroppedOnLoad() {
        // The app's own start loaded the log already; let that finish before replacing the file,
        // with the log loaded and on whatever an earlier test in this JVM left it as.
        DiagnosticLog.init(context)
        DiagnosticLog.setEnabled(context, true)
        DiagnosticLog.clear()
        DiagnosticLog.log("settled")
        DiagnosticLog.awaitIoForTests()
        waitUntil("the app's own load") { file.exists() && file.readText().contains("settled") }
        // The log stamps and ages entries on BioKey's trusted clock.
        val now = com.android.BioKey.core.security.TrustedClock.now()
        file.writeText("${now - 4 * 24 * 60 * 60_000L}\tfrom last week\n${now - 60 * 60_000L}\tfrom this morning\n")

        DiagnosticLog.init(context)
        awaitEntry("from this morning")
        assertTrue(DiagnosticLog.entries().none { it.text == "from last week" })
        assertFalse(file.readText().contains("from last week"))
    }

    @Test
    fun linesWithTheirClocksAreReadBackAndAgedToo() {
        DiagnosticLog.clear()
        DiagnosticLog.log("settled")
        waitUntil("the app's own load") { file.exists() && file.readText().contains("settled") }
        val now = com.android.BioKey.core.security.TrustedClock.now()
        // From another boot, on the network's time: aged on that time.
        file.writeText("${now - 4 * 24 * 60 * 60_000L}\t~0,-7,1\told, with clocks\n${now - 60 * 60_000L}\t~0,-7,1\tfresh, with clocks\n")

        DiagnosticLog.init(context)
        awaitEntry("fresh, with clocks")
        assertTrue(DiagnosticLog.entries().none { it.text == "old, with clocks" })
    }

    @Test
    fun everyEntryIsWrittenOnceThroughARewrite() {
        // More than a rewrite's worth, logged faster than the log's own thread writes them: the
        // rewrite already holds the later ones, which mustn't then be appended again.
        DiagnosticLog.clear()
        repeat(600) { DiagnosticLog.log("line $it") }
        waitUntil("the last line on disk") { file.exists() && file.readLines().any { it.endsWith("\tline 599") } }

        val numbers = file.readLines().filter { "\tline " in it }.map { it.substringAfterLast(' ').toInt() }
        assertEquals(599, numbers.last())
        assertEquals(numbers.size, numbers.toSet().size)
        assertEquals(numbers.sorted(), numbers)
    }

    @Test
    fun aHopNamesOnlyTheLockedApp() {
        val chat = "com.example.chat"
        val gallery = "com.vendor.gallery"
        DiagnosticLog.clear()
        LockStateTracker.onAppUnlocked(chat)
        LockStateTracker.onAppLeft(chat, LockTimeoutMode.IMMEDIATE, hopTarget = gallery)
        LockStateTracker.onForegroundChanged("com.example.dating", { LockTimeoutMode.IMMEDIATE })

        val log = DiagnosticLog.entries().joinToString("\n") { it.text }
        assertTrue(log.contains("$chat left the screen · hop to a helper app"))
        assertTrue(log.contains("Hop from $chat ended: another app came to the front"))
        assertFalse(log.contains(gallery))
        assertFalse(log.contains("com.example.dating"))
    }
}
