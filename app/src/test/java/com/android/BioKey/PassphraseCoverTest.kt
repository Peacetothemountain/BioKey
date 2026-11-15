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
        assertFalse(cover.field.acceptsInput)
        assertFalse(cover.submit.isEnabled)
        // Held, not turned off: it refuses what's typed, and keeps what's there for the check.
        cover.type("more")
        assertEquals("something", cover.field.text.toString())
        cover.setPassphraseBusy(false)
        assertTrue(cover.field.acceptsInput)
        assertTrue(cover.submit.isEnabled)
    }

    @Test
    fun aPauseCountsDownAndEndsByItself() {
        val cover = cover()
        cover.showPassphraseEntry({}, {})
        cover.setPassphraseWait(SystemClock.elapsedRealtime() + 28_000)
        val note = cover.passphraseEntryForTests.note
        assertFalse(cover.field.acceptsInput)
        assertFalse(cover.submit.isEnabled)
        assertEquals("Try again in 28 seconds", note.text.toString())
        cover.type("x")
        assertEquals("Typing during a wait goes nowhere", "", cover.field.text.toString())
        idle(Duration.ofSeconds(10))
        assertEquals("Try again in 18 seconds", note.text.toString())
        assertFalse(cover.field.acceptsInput)
        idle(Duration.ofSeconds(19))
        assertTrue(cover.field.acceptsInput)
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
        assertEquals("Try again in 4 minutes", cover.passphraseEntryForTests.note.text.toString())
        cover.setPassphraseWait(0)
        assertTrue(cover.field.acceptsInput)
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

    /** Where [view] sits on the cover. */
    private fun LockCoverView.rectOf(view: View): Rect = Rect(0, 0, view.width, view.height).also { offsetDescendantRectToMyCoords(view, it) }

    @Test
    fun theLineUnderTheFieldKeepsItsRoomSoNothingMoves() {
        val cover = cover()
        cover.setPassphraseOffered(true)
        cover.setActionsVisible(true)
        cover.showPassphraseEntry({}, {})
        cover.layoutOnScreen()
        val field = cover.rectOf(cover.field)
        val unlock = cover.rectOf(cover.submit)
        val entryHeight = cover.passphraseEntryForTests.height
        fun same(state: String) {
            cover.layoutOnScreen()
            assertEquals("$state: the field moved", field, cover.rectOf(cover.field))
            assertEquals("$state: Unlock moved", unlock, cover.rectOf(cover.submit))
            assertEquals("$state: the entry changed height", entryHeight, cover.passphraseEntryForTests.height)
        }
        cover.setPassphraseBusy(true)
        same("Checking")
        cover.setPassphraseBusy(false)
        cover.setPassphraseError("Wrong passphrase")
        same("An error")
        cover.setPassphraseWait(SystemClock.elapsedRealtime() + 28_000)
        same("A wait")
        idle(Duration.ofSeconds(29))
        assertEquals("You can try again now", cover.passphraseEntryForTests.note.text.toString())
        same("The wait over")
        cover.type("a")
        same("Typing again")
    }

    @Test
    fun duringAWaitTheEyeIsOffAndTheOutlineSaysWhatTheLineDoes() {
        val cover = cover()
        val palette = LockCoverView.Palette(context)
        cover.showPassphraseEntry({}, {})
        val entry = cover.passphraseEntryForTests
        val eye = entry.eyeForTests
        assertTrue(eye.isEnabled)
        assertTrue(eye.isFocusable)

        cover.setPassphraseError("Wrong passphrase")
        assertEquals("An error: the outline is the line's colour", palette.error, entry.outlineForTests)
        assertEquals(palette.error, entry.note.currentTextColor)
        assertTrue("Only a wait or a check turns the eye off", eye.isEnabled)

        cover.setPassphraseWait(SystemClock.elapsedRealtime() + 28_000)
        assertFalse("The eye is off while the field is", eye.isEnabled)
        assertFalse("Nothing can be left focused on the eye", eye.isFocusable)
        assertFalse(eye.isFocused)
        assertEquals(palette.error, entry.outlineForTests)

        // A wait alone (the entry opened while one runs): the same.
        cover.setPassphraseError(null)
        assertEquals(palette.error, entry.outlineForTests)
        assertEquals(palette.error, entry.note.currentTextColor)

        idle(Duration.ofSeconds(29))
        assertTrue(eye.isEnabled)
        assertTrue(eye.isFocusable)
        assertTrue("You can try again now isn't an error", entry.outlineForTests != palette.error)
        assertTrue(entry.note.currentTextColor != palette.error)

        // A check running: the eye waits with the field, and nothing is said to be wrong.
        cover.type("abc")
        cover.setPassphraseBusy(true)
        assertFalse(eye.isEnabled)
        assertTrue(entry.outlineForTests != palette.error)
        cover.setPassphraseBusy(false)
        assertTrue(eye.isEnabled)
    }

    @Test
    fun itsWordsComeFromTheSharedStrings() {
        val cover = cover()
        assertEquals(context.getString(R.string.lock_cover_app_locked, "Notes"), cover.accessibilityPaneTitle)
        assertEquals("Notes is locked", cover.accessibilityPaneTitle)
        cover.setLabel("Maps")
        assertEquals("Maps is locked", cover.accessibilityPaneTitle)
        cover.bind("BioKey", null, isBioKey = true, accent = null)
        assertEquals(context.getString(R.string.lock_screen_locked), cover.accessibilityPaneTitle)
        cover.setActionsVisible(true)
        cover.layoutOnScreen()
        assertEquals(listOf(context.getString(R.string.lock_screen_unlock), context.getString(R.string.prompt_cancel)), cover.actionWords())
        cover.showPassphraseEntry({}, {})
        assertEquals(context.getString(R.string.lock_screen_unlock), cover.submit.text.toString())
    }

    /** The fingerprint glyph beside "Unlock", as drawn. */
    private fun LockCoverView.unlockGlyphHeight(): Int {
        val text = unlockButtonForTests.text as android.text.Spanned
        val span = text.getSpans(0, text.length, android.text.style.ImageSpan::class.java).single()
        return span.drawable.bounds.height()
    }

    @Test
    fun theUnlockGlyphGrowsWithTheText() {
        val normal = cover().unlockGlyphHeight()
        RuntimeEnvironment.setFontScale(2f)
        val large = cover().unlockGlyphHeight()
        assertTrue("At twice the text size the glyph is $large px against $normal", large >= normal * 3 / 2)
    }

    @Test
    fun aVeryLongNameWrapsAndNeverPushesTheButtonsOff() {
        val name = "The Extraordinarily Long Name Of A Banking App For Personal And Business Accounts Worldwide"
        for (screen in GantletScreens.ALL) {
            RuntimeEnvironment.setQualifiers(screen.qualifiers)
            RuntimeEnvironment.setFontScale(2f)
            val cover = LockCoverView(context).apply { bind(name, null, isBioKey = false, accent = null) }
            cover.setPassphraseOffered(true)
            cover.setActionsVisible(true)
            val metrics = cover.resources.displayMetrics
            cover.layoutAt(metrics.widthPixels, metrics.heightPixels)
            for (view in listOf(cover.unlockButtonForTests, cover.usePassphraseActionForTests)) {
                assertTrue("${screen.name}: a button is off the cover", within(cover, view, metrics.heightPixels))
            }
            val label = cover.nameForTests
            assertTrue("${screen.name}: the name runs to ${label.lineCount} lines", label.lineCount in 1..2)
            assertTrue("${screen.name}: the name is wider than the cover", cover.rectOf(label).let { it.left >= 0 && it.right <= cover.width })
        }
    }

    /** The cover in an activity's window, so focus is real. */
    private fun coverInWindow(): Pair<ComponentActivity, LockCoverView> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val cover = cover()
        activity.setContentView(cover)
        cover.setPassphraseOffered(true)
        cover.setActionsVisible(true)
        idle()
        return activity to cover
    }

    @Test
    fun aCheckAndAWaitKeepTheFieldFocusedAndNothingElseLooksFocused() {
        val (_, cover) = coverInWindow()
        val entry = cover.passphraseEntryForTests
        cover.showPassphraseEntry({}, {})
        idle()
        assertTrue(cover.field.hasFocus())

        // The fifth wrong try, as the lock screen reports it: a check, its end, a message, a wait.
        cover.type("wrong")
        cover.setPassphraseBusy(true)
        assertTrue("A check took the field's focus", cover.field.hasFocus())
        cover.setPassphraseBusy(false)
        cover.setPassphraseError("Wrong passphrase")
        cover.setPassphraseWait(SystemClock.elapsedRealtime() + 28_000)
        idle()
        assertTrue("A wait took the field's focus", cover.field.hasFocus())
        assertFalse("Focus landed on Back", entry.backButton.isFocused)
        assertFalse("Focus landed on the eye", entry.eyeForTests.isFocused)
        assertFalse(entry.submitButton.isFocused)

        idle(Duration.ofSeconds(29))
        assertTrue("The wait is over and the field isn't focused", cover.field.hasFocus())
        assertTrue(cover.field.acceptsInput)
        cover.type("a")
        assertEquals("a", cover.field.text.toString())
    }

    @Test
    fun aWaitLeavesTheKeyboardUpAndItsEndBringsItBack() {
        val (_, cover) = coverInWindow()
        val entry = cover.passphraseEntryForTests
        cover.showPassphraseEntry({}, {})
        idle()
        assertTrue("The entry opened without the keyboard", entry.keyboardForTests)
        cover.setPassphraseBusy(true)
        cover.setPassphraseBusy(false)
        cover.setPassphraseError("Wrong passphrase")
        cover.setPassphraseWait(SystemClock.elapsedRealtime() + 28_000)
        idle()
        assertTrue("A wait put the keyboard away", entry.keyboardForTests)
        // However the owner left it during the wait, its end asks for the keyboard again.
        entry.keyboardForTests = false
        idle(Duration.ofSeconds(29))
        assertTrue("The wait's end left the keyboard down", entry.keyboardForTests)
        assertTrue(cover.field.hasFocus())
    }

    private val LockCoverView.icon get() = (nameForTests.parent as ViewGroup).getChildAt(1)

    /** Short windows: a phone on its side, half of a split screen, the Flip's cover screen. */
    private val shortWindows = listOf(
        "w915dp-h412dp-land-420dpi", "w411dp-h400dp-port-420dpi", "w800dp-h360dp-land-420dpi", "w361dp-h399dp-port-420dpi"
    )

    @Test
    fun inAShortWindowTheNameStaysInSightAndNothingRunsIntoTheField() {
        for (qualifiers in shortWindows) for (fontScale in listOf(1f, 2f)) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            RuntimeEnvironment.setFontScale(fontScale)
            val case = "$qualifiers at $fontScale"
            val cover = cover()
            cover.setPassphraseOffered(true)
            cover.setActionsVisible(true)
            val metrics = cover.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val status = (24 * metrics.density).toInt()
            val nav = (48 * metrics.density).toInt()

            // The buttons: the name above Unlock, whole.
            cover.layoutAt(width, height)
            val name = cover.nameForTests
            assertTrue("$case: the name isn't shown", cover.shows(name))
            assertTrue("$case: the name runs into Unlock", cover.rectOf(name).bottom <= cover.rectOf(cover.unlockButtonForTests).top)
            assertTrue("$case: the name is off the top", cover.rectOf(name).top >= 0)

            // The entry, with and without the keyboard.
            cover.showPassphraseEntry({}, {})
            for (keyboard in listOf(0, height * 11 / 20)) {
                cover.dispatchApplyWindowInsets(
                    WindowInsets.Builder()
                        .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, status, 0, 0))
                        .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, nav))
                        .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, maxOf(keyboard, nav)))
                        .build()
                )
                cover.layoutAt(width, height)
                val state = "$case, keyboard ${if (keyboard > 0) "up" else "down"}"
                val field = cover.rectOf(cover.field)
                assertTrue("$state: the field is off the cover", field.top >= 0 && field.bottom <= height - maxOf(keyboard, nav))
                for (view in listOf(name, cover.icon)) {
                    if (!cover.shows(view) || view.width == 0 || view.height == 0) continue
                    val rect = cover.rectOf(view)
                    assertTrue("$state: ${if (view === name) "the name" else "the icon"} runs into the field", rect.bottom <= field.top)
                    assertTrue("$state: ${if (view === name) "the name" else "the icon"} is under the status bar", rect.top >= status)
                }
                if (keyboard == 0) {
                    assertTrue("$state: the name isn't shown", cover.shows(name) && name.height > 0)
                    // Not scrolled out of sight, either.
                    assertEquals("$state: the name is scrolled", 0, (name.parent.parent as View).scrollY)
                }
            }
            cover.hidePassphraseEntry()
        }
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun besideACameraCutoutEverythingStaysInTheMiddleAndTheGlowFillsTheScreen() {
        RuntimeEnvironment.setQualifiers("w915dp-h412dp-land-420dpi")
        val cover = cover()
        cover.setPassphraseOffered(true)
        cover.setActionsVisible(true)
        val metrics = cover.resources.displayMetrics
        val cutout = (53 * metrics.density).toInt()
        val status = (24 * metrics.density).toInt()
        cover.dispatchApplyWindowInsets(
            WindowInsets.Builder()
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(cutout, 0, 0, 0))
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, status, 0, 0))
                .build()
        )
        cover.layoutAt(metrics.widthPixels, metrics.heightPixels)
        for (view in listOf(cover.unlockButtonForTests, cover.nameForTests)) {
            assertEquals("Off the middle of the screen", metrics.widthPixels / 2f, cover.rectOf(view).exactCenterX(), 2f)
        }
        val backdrop = cover.getChildAt(0)
        assertEquals("The glow is cut off", Rect(0, 0, metrics.widthPixels, metrics.heightPixels), cover.rectOf(backdrop))
    }

    @Test
    fun inLandscapeWithTheKeyboardUpTheNameFieldLineBackAndUnlockShowTogether() {
        RuntimeEnvironment.setQualifiers("w915dp-h412dp-land-420dpi")
        val cover = cover()
        cover.setPassphraseOffered(true)
        cover.setActionsVisible(true)
        cover.showPassphraseEntry({}, {})
        val metrics = cover.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        cover.layoutAt(width, height)
        // Room to spare without the keyboard: upright, under the app's icon and name.
        assertFalse(cover.passphraseEntryForTests.compact)
        // The keyboard as Gboard's in landscape: all but about 135dp under the status bar.
        val status = (24 * metrics.density).toInt()
        val keyboard = height - status - (135 * metrics.density).toInt()
        cover.dispatchApplyWindowInsets(
            WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, status, 0, 0))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboard))
                .build()
        )
        cover.layoutAt(width, height)
        cover.setPassphraseError("Wrong passphrase")
        cover.layoutAt(width, height)
        val entry = cover.passphraseEntryForTests
        assertTrue(entry.compact)
        val title = (entry.getChildAt(0) as ViewGroup).getChildAt(0) as TextView
        assertEquals("Notes", title.text.toString())
        for (view in listOf(title, cover.field, entry.note, entry.backButton, cover.submit)) {
            assertTrue("$view: not in view above the keyboard", within(cover, view, height - keyboard))
            assertTrue("$view: hidden", cover.shows(view))
        }
        // Back and Unlock side by side, Unlock at the end.
        assertTrue(cover.rectOf(entry.backButton).right <= cover.rectOf(cover.submit).left)
        // Put away, upright again for the next time.
        cover.dispatchApplyWindowInsets(WindowInsets.Builder().build())
        cover.layoutAt(width, height)
        assertFalse(entry.compact)
    }

    /** Whether [view] lies wholly within the cover, above [bottom]. */
    private fun within(cover: LockCoverView, view: View, bottom: Int): Boolean {
        val rect = Rect(0, 0, view.width, view.height)
        cover.offsetDescendantRectToMyCoords(view, rect)
        return view.width > 0 && view.height > 0 && rect.left >= 0 && rect.top >= 0 && rect.right <= cover.width && rect.bottom <= bottom
    }
}
