package com.android.BioKey

import android.app.Application
import android.graphics.Insets
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.widget.TextView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.feature.lock.BioKeyLockScreen
import com.android.BioKey.feature.lock.LockCoverView
import com.android.BioKey.feature.lock.PassphraseGateState
import com.android.BioKey.ui.theme.BioKeyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The passphrase entry on both lock screens, after the round-six design audit: the line under the
 * field shows whole above the keyboard in one row at 200% text, the name beside the field has one
 * style on both, Unlock keeps one width through a check, and the error goes as a new try is typed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassphraseEntryRoundSixTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun LockCoverView.layoutAt(width: Int, height: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        layout(0, 0, width, height)
    }

    private fun LockCoverView.rectOf(view: View): Rect = Rect(0, 0, view.width, view.height).also { offsetDescendantRectToMyCoords(view, it) }

    /** A Pixel 9 on its side with Gboard up, as measured on the emulator: about 107dp left between the status bar and the keyboard. */
    private fun coverWithKeyboard(): Triple<LockCoverView, Int, Int> {
        val cover = LockCoverView(context).apply { bind("Notes", null, isBioKey = false, accent = null) }
        cover.setPassphraseOffered(true)
        cover.setActionsVisible(true)
        cover.showPassphraseEntry({}, {})
        val metrics = cover.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        cover.layoutAt(width, height)
        val status = (53 * metrics.density).toInt()
        val keyboard = height - status - (107 * metrics.density).toInt()
        cover.dispatchApplyWindowInsets(
            WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, status, 0, 0))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboard))
                .build()
        )
        cover.layoutAt(width, height)
        // The entry arranges itself after the size settles (a post).
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        cover.layoutAt(width, height)
        return Triple(cover, width, height - keyboard)
    }

    private fun assertWhollyAbove(cover: LockCoverView, view: View, bottom: Int) {
        val rect = cover.rectOf(view)
        assertTrue("$view has no size", view.width > 0 && view.height > 0)
        assertTrue("$view: ${rect.bottom} runs under the keyboard at $bottom", rect.bottom <= bottom)
        assertTrue("$view: off the top", rect.top >= 0)
        assertTrue("$view: off the side", rect.left >= 0 && rect.right <= cover.width)
    }

    @Test
    @Config(qualifiers = "w923dp-h411dp-land-420dpi", fontScale = 2f)
    fun onTheCoverAtLargeTextTheLineShowsWholeAboveTheKeyboard() {
        val (cover, _, keyboardTop) = coverWithKeyboard()
        val entry = cover.passphraseEntryForTests
        assertTrue(entry.compact)
        // "Wrong passphrase", then the longest it usually says: "Wrong passphrase. Try again in 120 minutes."
        for (wait in listOf(false, true)) {
            cover.setPassphraseError("Wrong passphrase")
            if (wait) cover.setPassphraseWait(SystemClock.elapsedRealtime() + 120 * 60_000L)
            cover.layoutAt(cover.width, cover.height)
            assertEquals("The line wraps", 1, entry.note.lineCount)
            for (view in listOf(cover.passphraseEntryForTests.field, entry.note, entry.backButton, entry.submitButton)) {
                assertWhollyAbove(cover, view, keyboardTop)
            }
        }
    }

    @Test
    @Config(qualifiers = "w923dp-h411dp-land-420dpi")
    fun onTheCoverAtUsualSizesTheLineIsCentredUnderTheField() {
        val (cover, _, keyboardTop) = coverWithKeyboard()
        val entry = cover.passphraseEntryForTests
        cover.setPassphraseError("Wrong passphrase")
        cover.layoutAt(cover.width, cover.height)
        val field = cover.rectOf(entry.field)
        val note = cover.rectOf(entry.note)
        assertEquals("Not centred under the field", field.exactCenterX(), note.exactCenterX(), 2f)
        assertWhollyAbove(cover, entry.note, keyboardTop)
    }

    @Test
    @Config(qualifiers = "w923dp-h411dp-land-420dpi", fontScale = 2f)
    fun onTheCoverUnlockKeepsItsWidthThroughACheck() {
        val (cover, _, _) = coverWithKeyboard()
        val entry = cover.passphraseEntryForTests
        val unlock = cover.rectOf(entry.submitButton)
        val field = cover.rectOf(entry.field)
        cover.setPassphraseBusy(true)
        cover.layoutAt(cover.width, cover.height)
        assertEquals("Checking…", entry.submitButton.text.toString())
        assertEquals("Unlock changed width", unlock, cover.rectOf(entry.submitButton))
        assertEquals("The field changed width", field, cover.rectOf(entry.field))
        cover.setPassphraseBusy(false)
        cover.layoutAt(cover.width, cover.height)
        assertEquals(unlock, cover.rectOf(entry.submitButton))
    }

    @Test
    fun onTheCoverTheErrorGoesWithTheFirstNewCharacterButACountdownStays() {
        val cover = LockCoverView(context).apply { bind("Notes", null, isBioKey = false, accent = null) }
        val palette = LockCoverView.Palette(context)
        cover.showPassphraseEntry({}, {})
        val entry = cover.passphraseEntryForTests
        cover.setPassphraseError("Wrong passphrase")
        assertEquals(palette.error, entry.outlineForTests)
        assertEquals(View.VISIBLE, entry.note.visibility)
        entry.field.text.append("a")
        assertTrue("The line still says it", entry.note.text.isEmpty())
        assertEquals("The line still shows", View.INVISIBLE, entry.note.visibility)
        assertFalse("The outline is still the error's", entry.outlineForTests == palette.error)

        // A countdown isn't an error: nothing can be typed during it, and it stays.
        entry.wipe()
        cover.setPassphraseError("Wrong passphrase")
        cover.setPassphraseWait(SystemClock.elapsedRealtime() + 30_000L)
        entry.field.text.append("a")
        assertEquals("", entry.field.text.toString())
        assertTrue(entry.note.text.toString().startsWith("Wrong passphrase. Try again in"))
        assertEquals(palette.error, entry.outlineForTests)
    }

    @Test
    @Config(qualifiers = "w923dp-h411dp-land-420dpi")
    fun onTheCoverTheNameBesideTheFieldIs20spMedium() {
        val (cover, _, _) = coverWithKeyboard()
        val entry = cover.passphraseEntryForTests
        assertTrue(entry.compact)
        val title = (entry.getChildAt(0) as android.view.ViewGroup).getChildAt(0) as TextView
        assertEquals(20f, title.textSize / title.resources.displayMetrics.scaledDensity, 0.01f)
        assertEquals(Typeface.create("sans-serif-medium", Typeface.NORMAL), title.typeface)
    }

    /** BioKey's own lock names itself beside the field in the cover's style: 20sp, medium. */
    @Test
    @Config(qualifiers = "w915dp-h150dp-land")
    fun onBioKeysOwnLockTheNameBesideTheFieldIsInTheCoversStyle() {
        val gate = PassphraseGateState().apply { offered = true; open = true }
        rule.setContent { BioKeyTheme { BioKeyLockScreen(onUnlockClicked = {}, onCancelClicked = {}, passphrase = gate) } }
        rule.waitForIdle()
        val node = rule.onNodeWithText("BioKey").fetchSemanticsNode()
        val layout = node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.let { get ->
            mutableListOf<androidx.compose.ui.text.TextLayoutResult>().also { get(it) }.first()
        }
        assertEquals(20f, layout.layoutInput.style.fontSize.value, 0.01f)
        assertEquals(FontWeight.Medium, layout.layoutInput.style.fontWeight)
    }

    private val isField = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)

    @Test
    @Config(qualifiers = "w923dp-h160dp-land-420dpi", fontScale = 2f)
    fun onBioKeysOwnLockAtLargeTextTheLineShowsWholeAndUnlockKeepsItsWidth() {
        val gate = PassphraseGateState().apply { offered = true; open = true }
        rule.setContent { BioKeyTheme { BioKeyLockScreen(onUnlockClicked = {}, onCancelClicked = {}, passphrase = gate) } }
        gate.error = context.getString(R.string.lock_passphrase_wrong)
        rule.waitForIdle()
        rule.onNodeWithText("BioKey").assertIsDisplayed()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val line = rule.onNodeWithText("Wrong passphrase").fetchSemanticsNode().boundsInRoot
        assertTrue("The line runs off the bottom: $line in $root", line.bottom <= root.bottom && line.top >= root.top)
        rule.onNode(isField).assertIsDisplayed()
        rule.onNodeWithText("Back").assertIsDisplayed()
        val unlock = rule.onNode(hasText("Unlock") and !isField).fetchSemanticsNode().boundsInRoot
        val field = rule.onNode(isField).fetchSemanticsNode().boundsInRoot
        gate.busy = true
        rule.waitForIdle()
        assertEquals("Unlock changed width", unlock, rule.onNode(hasText("Checking…") and !isField).fetchSemanticsNode().boundsInRoot)
        assertEquals("The field changed width", field, rule.onNode(isField).fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun onBioKeysOwnLockTheErrorGoesWithTheFirstNewCharacter() {
        val gate = PassphraseGateState().apply { offered = true; open = true }
        rule.setContent { BioKeyTheme { BioKeyLockScreen(onUnlockClicked = {}, onCancelClicked = {}, passphrase = gate) } }
        gate.error = "Wrong passphrase"
        rule.waitForIdle()
        rule.onNodeWithText("Wrong passphrase").assertIsDisplayed()
        assertTrue(rule.onNode(isField).fetchSemanticsNode().config.contains(SemanticsProperties.Error))
        rule.onNode(isField).performTextInput("a")
        rule.waitForIdle()
        rule.onAllNodes(hasText("Wrong passphrase")).assertCountEquals(0)
        assertFalse("Screen readers still hear an error", rule.onNode(isField).fetchSemanticsNode().config.contains(SemanticsProperties.Error))
    }
}
