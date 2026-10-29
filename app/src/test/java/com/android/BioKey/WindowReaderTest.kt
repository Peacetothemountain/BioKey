package com.android.BioKey

import android.util.SparseArray
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.android.BioKey.feature.lock.WindowReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Nothing is read before setup; windows once per event, the phone's own screen first; unreadable ones rested. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowReaderTest {

    private var setUp = true
    private var reads = 0
    private var rootReads = 0
    private var now = 1_000_000L
    private val phone = AccessibilityWindowInfo.obtain()
    private val other = AccessibilityWindowInfo.obtain()
    private val known = HashMap<Int, String>()

    private val reader = WindowReader(
        readWindows = {
            reads++
            SparseArray<List<AccessibilityWindowInfo>>().apply {
                put(2, listOf(other))
                put(Display.DEFAULT_DISPLAY, listOf(phone))
            }
        },
        readActiveRoot = { rootReads++; null },
        setUp = { setUp },
        known = { known[it] },
        learn = { id, pkg -> known[id] = pkg },
        clock = { now }
    )

    @Test
    fun nothingIsReadBeforeSetup() {
        setUp = false
        assertNull(reader.windowList())
        assertNull(reader.activeRoot())
        assertEquals(0, reads)
        assertEquals(0, rootReads)
    }

    @Test
    fun thePhonesOwnScreenComesFirst() {
        val list = reader.windowList()!!
        assertSame(phone, list[0])
        assertSame(other, list[1])
    }

    @Test
    fun windowsAreReadOncePerEvent() {
        reader.duringEvent {
            reader.windowList()
            reader.windowList()
            reader.windowList()
        }
        assertEquals(1, reads)
        // Outside an event, every ask reads: the screen may have changed.
        reader.windowList()
        reader.windowList()
        assertEquals(3, reads)
    }

    @Test
    fun anUnreadableWindowRestsBeforeItsAskedAgain() {
        var asked = 0
        val unreadable = { asked++; null }
        assertNull(reader.packageOf(7, unreadable))
        assertNull(reader.packageOf(7, unreadable))
        assertEquals(1, asked)
        assertTrue(reader.wasUnreadable(7))
        // Its rest over, it's asked again, and once readable it's remembered.
        now += WindowReader.UNREADABLE_RECHECK_MS
        assertEquals("com.example", reader.packageOf(7) { asked++; "com.example" })
        assertEquals(2, asked)
        assertEquals("com.example", reader.packageOf(7) { asked++; null })
        assertEquals(2, asked)
    }

    @Test
    fun goneWindowsAreForgotten() {
        reader.packageOf(7) { null }
        reader.packageOf(8) { null }
        reader.forgetAllBut(setOf(8))
        assertEquals(false, reader.wasUnreadable(7))
        assertEquals(true, reader.wasUnreadable(8))
    }
}
