package com.android.BioKey

import android.app.Application
import android.graphics.Insets
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.feature.lock.LockCoverView
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The owner passphrase on the lock cover: offered among its buttons only when asked for (the
 * cover is otherwise exactly as it was), entered in their place, and handed over, emptied,
 * paused and put away as the lock screen says. On every screen the gantlet knows, the field and
 * its Unlock fit on the cover, with the keyboard up too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassphraseCoverTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun cover() = LockCoverView(context).apply {
        bind("Notes", null, isBioKey = false, accent = null)
    }

    private fun LockCoverView.layoutAt(width: Int, height: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        layout(0, 0, width, height)
    }

    private fun LockCoverView.layoutOnScreen() = resources.displayMetrics.let { layoutAt(it.widthPixels, it.heightPixels) }

    /** Visible on the cover: it and everything between it and the cover are. */
    private fun LockCoverView.shows(view: View): Boolean {
        var current: View? = view
        while (current != null && current !== this) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return current === this
    }

    /** The words on the buttons that show in the cover's action row. */
    private fun LockCoverView.actionWords(): List<String> {
        val words = mutableListOf<String>()
        fun walk(view: View) {
            if (!shows(view)) return
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            else if (view is TextView) words += view.text.toString().replace(Typography.nbsp, ' ').trim()
        }
        walk(actionsForTests)
        return words
    }

    private val LockCoverView.field get() = passphraseEntryForTests.field
    private val LockCoverView.submit get() = passphraseEntryForTests.submitButton

    private fun LockCoverView.type(text: String) {
        field.text.append(text)
    }

    private fun idle(duration: Duration = Duration.ZERO) = shadowOf(Looper.getMainLooper()).idleFor(duration)

    @Test
    fun notOfferedTheActionsAreAsTheyWere() {
        val cover = cover()
        cover.setActionsVisible(true)
        cover.layoutOnScreen()
        assertEquals(listOf("Unlock", "Cancel"), cover.actionWords())
        assertFalse(cover.shows(cover.usePassphraseActionForTests))
        // Unlock (56), the gap (8) and Cancel (48), as before.
        assertEquals((112 * context.resources.displayMetrics.density).toInt(), cover.actionsForTests.measuredHeight)
        assertFalse(cover.isPassphraseEntryShowing)
    }

    @Test
    fun offeredItShowsWithTheActionsAndAsksForThePassphrase() {
        val cover = cover()
        var asked = 0
        cover.onUsePassphrase = { asked++ }
        cover.setPassphraseOffered(true)
        cover.setActionsVisible(true)
        cover.layoutOnScreen()
        assertEquals(listOf("Unlock", "Use passphrase", "Cancel"), cover.actionWords())
        assertTrue(cover.usePassphraseActionForTests.performClick())
        assertEquals(1, asked)
    }

    @Test
    fun offeredItStaysHiddenWhileTheActionsAre() {
        val cover = cover()
        cover.setPassphraseOffered(true)
        cover.setActionsVisible(false)
        assertFalse(cover.shows(cover.usePassphraseActionForTests))
        // Taken back: gone with the actions up too.
        cover.setActionsVisible(true)
        cover.setPassphraseOffered(false)
        assertFalse(cover.shows(cover.usePassphraseActionForTests))
    }

    @Test
    fun doneAndUnlockHandOverExactlyWhatWasTypedAndEmptyTheField() {
        val cover = cover()
        val got = mutableListOf<CharArray>()
        cover.setPassphraseOffered(true)
        cover.setActionsVisible(true)
        cover.showPassphraseEntry(onSubmit = { got += it.copyOf() }, onDismiss = {})
        assertTrue(cover.isPassphraseEntryShowing)
        assertTrue(cover.shows(cover.field))
        assertFalse("The buttons stay up under the entry", cover.shows(cover.usePassphraseActionForTests))

        assertFalse("Unlock with nothing typed", cover.submit.isEnabled)
        cover.field.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertTrue("Done with nothing typed handed something over", got.isEmpty())

        cover.type("correct horse ✓")
        assertTrue(cover.submit.isEnabled)
        cover.field.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertEquals(1, got.size)
        assertArrayEquals("correct horse ✓".toCharArray(), got[0])
        assertEquals("", cover.field.text.toString())
        assertFalse(cover.submit.isEnabled)

        cover.type("x")
        assertTrue(cover.submit.performClick())
        assertEquals(2, got.size)
        assertArrayEquals(charArrayOf('x'), got[1])
        assertEquals("", cover.field.text.toString())
    }

    @Test
    fun anErrorIsSaidUnderTheFieldAndEmptiesIt() {
        val cover = cover()
        cover.showPassphraseEntry({}, {})
        cover.type("wrong")
        cover.setPassphraseError("That's not it")
        val note = cover.passphraseEntryForTests.note
        assertTrue(cover.shows(note))
        assertEquals("That's not it", note.text.toString())
        assertEquals("", cover.field.text.toString())
        cover.setPassphraseError(null)
        assertFalse(cover.shows(note))
    }

    @Test
    fun aCheckMakesTheFieldAndUnlockWait() {
        val cover = cover()
        cover.showPassphraseEntry({}, {})
        cover.type("something")
        cover.setPassphraseBusy(true)
        assertFalse(cover.field.isEnabled)
        assertFalse(cover.submit.isEnabled)
        cover.setPassphraseBusy(false)
        assertTrue(cover.field.isEnabled)
        assertTrue(cover.submit.isEnabled)
    }

    @Test
    fun aPauseCountsDownAndEndsByItself() {
        val cover = cover()
        cover.showPassphraseEntry({}, {})
        cover.setPassphraseWait(SystemClock.elapsedRealtime() + 28_000)
        val note = cover.passphraseEntryForTests.note
        assertFalse(cover.field.isEnabled)
        assertFalse(cover.submit.isEnabled)
        assertEquals("Try again in 28 s", note.text.toString())
        idle(Duration.ofSeconds(10))
        assertEquals("Try again in 18 s", note.text.toString())
        assertFalse(cover.field.isEnabled)
        idle(Duration.ofSeconds(19))
        assertTrue(cover.field.isEnabled)
        assertEquals("You can try again now", note.text.toString())
        cover.type("a")
        assertTrue(cover.submit.isEnabled)
        assertFalse("The wait's end is still shown once typing starts", cover.shows(note))
    }

    @Test
    fun aLongPauseCountsInMinutesAndAPastTimeEndsIt() {
        val cover = cover()
        cover.showPassphraseEntry({}, {})
        cover.setPassphraseWait(SystemClock.elapsedRealtime() + 4 * 60_000 - 500)
        assertEquals("Try again in 4 min", cover.passphraseEntryForTests.note.text.toString())
        cover.setPassphraseWait(0)
        assertTrue(cover.field.isEnabled)
        assertFalse(cover.shows(cover.passphraseEntryForTests.note))
    }

    @Test
    fun backingOutBringsTheActionsBackAndKeepsNothing() {
        val cover = cover()
        var dismissed = 0
        cover.setPassphraseOffered(true)
        cover.setActionsVisible(true)
        cover.showPassphraseEntry({}, { dismissed++ })
        cover.type("half typed")
        cover.passphraseEntryForTests.backButton.performClick()
        assertEquals(1, dismissed)
        assertFalse(cover.isPassphraseEntryShowing)
        assertTrue(cover.shows(cover.usePassphraseActionForTests))
        assertEquals("", cover.field.text.toString())

        // Put away by the lock screen: the actions come back, nothing typed survives, and
        // reopening it starts empty.
        cover.showPassphraseEntry({}, { dismissed++ })
        cover.type("again")
        cover.hidePassphraseEntry()
        assertEquals("Hidden by the lock screen isn't the owner backing out", 1, dismissed)
        assertTrue(cover.shows(cover.usePassphraseActionForTests))
        assertEquals("", cover.field.text.toString())
        cover.showPassphraseEntry({}, {})
        assertEquals("", cover.field.text.toString())
    }

    @Test
    fun theActionsStayHiddenAfterTheEntryIfThePromptIsUp() {
        val cover = cover()
        cover.setPassphraseOffered(true)
        cover.showPassphraseEntry({}, {})
        cover.setActionsVisible(true)
        assertFalse(cover.shows(cover.actionsForTests))
        cover.setActionsVisible(false)
        cover.hidePassphraseEntry()
        assertFalse(cover.shows(cover.actionsForTests))
    }

    @Test
    fun theBackGestureLeavesTheEntryNotTheLockScreen() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var screenBack = 0
        activity.onBackPressedDispatcher.addCallback(activity, object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() { screenBack++ } })
        val cover = cover()
        activity.setContentView(cover)
        var dismissed = 0
        cover.showPassphraseEntry({}, { dismissed++ })
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, dismissed)
        assertEquals(0, screenBack)
        assertFalse(cover.isPassphraseEntryShowing)
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, screenBack)
    }

    @Test
    fun aTapAroundTheEntryDoesntAskAgain() {
        val cover = cover()
        var taps = 0
        cover.setOnClickListener { taps++ }
        cover.showPassphraseEntry({}, {})
        cover.performClick()
        assertEquals(0, taps)
        cover.hidePassphraseEntry()
        cover.performClick()
        assertEquals(1, taps)
    }

    @Test
    fun whatIsTypedSurvivesARotationButNotTheCoverGoing() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val cover = cover()
        activity.setContentView(cover)
        cover.showPassphraseEntry({}, {})
        cover.type("kept")
        cover.refreshPalette()
        cover.layoutOnScreen()
        assertEquals("kept", cover.field.text.toString())
        (cover.parent as ViewGroup).removeView(cover)
        assertEquals("", cover.field.text.toString())
    }

    @Test
    fun theFieldIsHardened() {
        val field = cover().field
        assertFalse(field.isImportantForAutofill)
        // None of its own: Android 14 and later report "passwordAuto" for any password field without hints.
        assertTrue(field.autofillHints.orEmpty().all { it == "passwordAuto" })
        assertFalse(field.isSaveEnabled)
        assertEquals(InputType.TYPE_CLASS_TEXT, field.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, field.inputType and InputType.TYPE_MASK_VARIATION)
        assertTrue(field.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertTrue(field.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        // Nothing comes back out.
        field.text.append("secret")
        field.selectAll()
        assertFalse(field.onTextContextMenuItem(android.R.id.copy))
        assertFalse(field.onTextContextMenuItem(android.R.id.cut))
        assertEquals("secret", field.text.toString())
        assertNotNull(field.filters.firstOrNull { it is android.text.InputFilter.LengthFilter })
    }

    @Test
    fun theEntryFitsOnEveryScreenWithAndWithoutTheKeyboard() {
        for (screen in GantletScreens.ALL) {
            RuntimeEnvironment.setQualifiers(screen.qualifiers)
            val cover = cover()
            cover.setPassphraseOffered(true)
            cover.setStatus("Too many attempts. Try again later.", permanentLockout = false)
            cover.setActionsVisible(true)
            cover.showPassphraseEntry({}, {})
            cover.setPassphraseError("That's not it")
            cover.setPassphraseWait(SystemClock.elapsedRealtime() + 5 * 60_000)
            val metrics = cover.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            cover.layoutAt(width, height)
            for (view in listOf(cover.field, cover.submit)) {
                assertTrue("${screen.name}: not inside the cover", within(cover, view, height))
            }

            // A keyboard taking two fifths of the screen, over a 48dp navigation bar.
            val nav = (48 * metrics.density).toInt()
            val keyboard = height * 2 / 5
            cover.dispatchApplyWindowInsets(
                WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, nav))
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboard))
                    .build()
            )
            cover.layoutAt(width, height)
            for (view in listOf(cover.field, cover.submit)) {
                assertTrue("${screen.name}: under the keyboard", within(cover, view, height - keyboard))
            }
            cover.hidePassphraseEntry()
        }
    }

    /** Whether [view] lies wholly within the cover, above [bottom]. */
    private fun within(cover: LockCoverView, view: View, bottom: Int): Boolean {
        val rect = Rect(0, 0, view.width, view.height)
        cover.offsetDescendantRectToMyCoords(view, rect)
        return view.width > 0 && view.height > 0 && rect.left >= 0 && rect.top >= 0 && rect.right <= cover.width && rect.bottom <= bottom
    }
}
