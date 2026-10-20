package com.android.BioKey

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.security.LockStateTracker
import java.io.File
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
        // The app's own start loaded the log already; let that finish before replacing the file.
        DiagnosticLog.clear()
        DiagnosticLog.log("settled")
        waitUntil("the app's own load") { file.exists() && file.readText().contains("settled") }
        val now = System.currentTimeMillis()
        file.writeText("${now - 4 * 24 * 60 * 60_000L}\tfrom last week\n${now - 60 * 60_000L}\tfrom this morning\n")

        DiagnosticLog.init(context)
        awaitEntry("from this morning")
        assertTrue(DiagnosticLog.entries().none { it.text == "from last week" })
        assertFalse(file.readText().contains("from last week"))
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
