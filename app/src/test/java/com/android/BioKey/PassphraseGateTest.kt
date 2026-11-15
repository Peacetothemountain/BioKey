package com.android.BioKey

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import android.os.SystemClock
import com.android.BioKey.feature.lock.BioKeyLockScreen
import com.android.BioKey.feature.lock.LOCKED_TITLE_TAG
import com.android.BioKey.feature.lock.PassphraseGateState
import com.android.BioKey.feature.lock.hardenEditorInfo
import com.android.BioKey.ui.theme.BioKeyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** BioKey's own lock offers the owner passphrase as the lock cover does, and hands over exactly what was typed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassphraseGateTest {

    @get:Rule
    val rule = createComposeRule()

    private val submitted = mutableListOf<String>()

    private fun show(gate: PassphraseGateState?) {
        rule.setContent {
            BioKeyTheme {
                BioKeyLockScreen(
                    onUnlockClicked = {},
                    onCancelClicked = {},
                    passphrase = gate,
                    onPassphraseSubmit = { submitted += String(it) }
                )
            }
        }
    }

    private fun openGate() = PassphraseGateState().apply {
        offered = true
        open = true
    }

    /** The field: editable text, whether or not it can be typed in now. */
    private val isField = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
    private fun field(): SemanticsNodeInteraction = rule.onNode(isField)
    private fun unlock() = rule.onNode(hasText("Unlock") and !isField)
    private fun typed(): String = field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun withoutAGateTheScreenIsAsBefore() {
        show(null)
        rule.onNodeWithText("Unlock").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onAllNodes(hasText("Use passphrase")).assertCountEquals(0)
        rule.onAllNodes(isField).assertCountEquals(0)
    }

    @Test
    fun notOfferedShowsNoPassphrase() {
        show(PassphraseGateState())
        rule.onAllNodes(hasText("Use passphrase")).assertCountEquals(0)
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun usePassphraseOpensTheEntryAndBackReturnsTheButtons() {
        val gate = PassphraseGateState().apply { offered = true }
        show(gate)
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Use passphrase").assertIsDisplayed().performClick()
        assertTrue(gate.open)
        field().assertIsDisplayed()
        rule.onAllNodes(hasText("Cancel")).assertCountEquals(0)
        rule.onNodeWithText("Back").performClick()
        assertFalse(gate.open)
        rule.onAllNodes(isField).assertCountEquals(0)
        rule.onNodeWithText("Use passphrase").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun theCallerCanOpenAndCloseIt() {
        val gate = PassphraseGateState()
        show(gate)
        gate.open = true
        rule.waitForIdle()
        field().assertIsDisplayed()
        gate.open = false
        rule.waitForIdle()
        rule.onAllNodes(isField).assertCountEquals(0)
    }

    @Test
    fun doneHandsOverExactlyWhatWasTypedAndEmptiesTheField() {
        show(openGate())
        field().performTextInput("correct horse")
        field().performImeAction()
        assertEquals(listOf("correct horse"), submitted)
        assertEquals("", typed())

        field().performTextInput("battery staple")
        unlock().performClick()
        assertEquals(listOf("correct horse", "battery staple"), submitted)
        assertEquals("", typed())
    }

    @Test
    fun nothingTypedCannotBeSubmitted() {
        show(openGate())
        unlock().assertIsNotEnabled()
        field().performImeAction()
        assertTrue(submitted.isEmpty())
        field().performTextInput("x")
        unlock().assertIsEnabled()
    }

    @Test
    fun aCheckInProgressHoldsTheFieldAndButton() {
        val gate = openGate()
        show(gate)
        field().performTextInput("abc")
        gate.busy = true
        rule.waitForIdle()
        field().assertIsNotEnabled()
        // Held, not turned off: it keeps focus (and the keyboard), so focus never lands on Back.
        field().assertIsFocused()
        rule.onNodeWithText("Back").assertIsNotFocused()
        rule.onNode(hasText("Checking…")).assertIsDisplayed().assertIsNotEnabled()
        gate.busy = false
        rule.waitForIdle()
        field().assertIsEnabled()
        field().assertIsFocused()
        unlock().assertIsEnabled()
        assertEquals("abc", typed())
    }

    @Test
    fun anErrorIsSaidAndEmptiesTheField() {
        val gate = openGate()
        show(gate)
        field().performTextInput("wrong")
        gate.error = "Wrong passphrase"
        rule.waitForIdle()
        rule.onNodeWithText("Wrong passphrase").assertIsDisplayed()
        assertEquals("", typed())

        // The same words again still empty it.
        field().performTextInput("wrong again")
        gate.error = "Wrong passphrase"
        rule.waitForIdle()
        assertEquals("", typed())
    }

    @Test
    fun aWaitHoldsTriesUntilItEnds() {
        val gate = openGate()
        show(gate)
        field().performTextInput("abc")
        gate.waitUntilElapsed = SystemClock.elapsedRealtime() + 5_000L
        rule.waitForIdle()
        rule.onNodeWithText("Try again in 5 seconds").assertIsDisplayed()
        field().assertIsNotEnabled()
        unlock().assertIsNotEnabled()
        assertEquals("", typed())
        // The field keeps focus through the wait; nothing else looks focused.
        field().assertIsFocused()
        rule.onNodeWithText("Back").assertIsNotFocused()
        assertTrue("Focus landed on the eye", eye().fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) != true)

        ShadowSystemClock.advanceBy(Duration.ofMillis(5_001L))
        rule.mainClock.advanceTimeBy(5_100L)
        rule.waitForIdle()
        field().assertIsEnabled()
        field().assertIsFocused()
        rule.onNodeWithText("You can try again now").assertIsDisplayed()
        field().performTextInput("abc")
        unlock().assertIsEnabled()
        rule.onAllNodes(hasText("You can try again now")).assertCountEquals(0)
    }

    private fun SemanticsNodeInteraction.top() = fetchSemanticsNode().boundsInRoot.top
    private fun eye() = rule.onNode(hasContentDescription("Show passphrase"))

    @Test
    fun theLineUnderTheFieldKeepsItsRoomSoNothingMoves() {
        val gate = openGate()
        show(gate)
        rule.waitForIdle()
        val field = field().top()
        val unlock = unlock().top()
        fun same(state: String) {
            rule.waitForIdle()
            assertEquals("$state: the field moved", field, field().top())
            assertEquals("$state: Unlock moved", unlock, rule.onNode((hasText("Unlock") or hasText("Checking…")) and !isField).top())
        }
        gate.busy = true
        same("Checking")
        gate.busy = false
        gate.error = "Wrong passphrase"
        same("An error")
        gate.waitUntilElapsed = SystemClock.elapsedRealtime() + 5_000L
        same("A wait")
        ShadowSystemClock.advanceBy(Duration.ofMillis(5_001L))
        rule.mainClock.advanceTimeBy(5_100L)
        rule.onNodeWithText("You can try again now").assertIsDisplayed()
        same("The wait over")
    }

    @Test
    fun duringAWaitTheEyeIsOff() {
        val gate = openGate()
        show(gate)
        eye().assertIsEnabled()
        gate.waitUntilElapsed = SystemClock.elapsedRealtime() + 5_000L
        rule.waitForIdle()
        eye().assertIsNotEnabled()
        ShadowSystemClock.advanceBy(Duration.ofMillis(5_001L))
        rule.mainClock.advanceTimeBy(5_100L)
        rule.waitForIdle()
        eye().assertIsEnabled()
        gate.busy = true
        rule.waitForIdle()
        eye().assertIsNotEnabled()
    }

    @Test
    fun closedTheEntryGoesAtOnceWithWhatWasTyped() {
        val gate = openGate()
        show(gate)
        field().performTextInput("half typed")
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            gate.open = false
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(32)
        // While the room it took is still shrinking, the entry (and what was typed in it) is gone.
        rule.onAllNodes(isField).assertCountEquals(0)
        rule.onNodeWithText("Use passphrase").assertExists()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithText("Use passphrase").assertIsDisplayed()
    }

    @Test
    fun typingDuringAWaitGoesNowhere() {
        val gate = openGate()
        show(gate)
        gate.waitUntilElapsed = SystemClock.elapsedRealtime() + 5_000L
        rule.waitForIdle()
        // As a keyboard would send it, past the semantics' "off".
        val insert = field().fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.InsertTextAtCursor)
        assertTrue("The field gave up on input altogether", insert?.action != null)
        rule.runOnUiThread { insert!!.action!!.invoke(androidx.compose.ui.text.AnnotatedString("x")) }
        rule.waitForIdle()
        assertEquals("", typed())
    }

    /** Where a node sits on the screen. */
    private fun SemanticsNodeInteraction.bounds() = fetchSemanticsNode().boundsInRoot
    private fun title() = rule.onNodeWithTag(LOCKED_TITLE_TAG, useUnmergedTree = true)

    @Test
    @Config(qualifiers = "w915dp-h412dp-land")
    fun onItsSideTheTitleAndButtonsShow() = titleAndButtonsShow()

    @Test
    @Config(qualifiers = "w411dp-h400dp-port")
    fun inHalfASplitScreenTheTitleAndButtonsShow() = titleAndButtonsShow()

    @Test
    @Config(qualifiers = "w915dp-h412dp-land", fontScale = 2f)
    fun onItsSideAtLargeTextTheTitleAndButtonsShow() = titleAndButtonsShow()

    private fun titleAndButtonsShow() {
        show(PassphraseGateState().apply { offered = true })
        rule.waitForIdle()
        val root = rule.onRoot().bounds()
        val title = title().assertIsDisplayed().bounds()
        val unlock = unlock().assertIsDisplayed().bounds()
        assertTrue("The title is cut off: $title in $root", title.top >= root.top && title.bottom <= root.bottom)
        assertTrue("The title runs into Unlock", title.bottom <= unlock.top)
        for (button in listOf("Use passphrase", "Cancel")) {
            val bounds = rule.onNodeWithText(button).assertIsDisplayed().bounds()
            assertTrue("$button is off the screen", bounds.bottom <= root.bottom)
        }
    }

    @Test
    @Config(qualifiers = "w915dp-h300dp-land")
    fun inAVeryShortWindowTheEntryComesFirstAndNothingRunsIntoTheField() {
        show(openGate())
        rule.waitForIdle()
        val field = field().assertIsDisplayed().bounds()
        val root = rule.onRoot().bounds()
        assertTrue("The field is off the screen", field.top >= root.top && field.bottom <= root.bottom)
        val titleNode = title().fetchSemanticsNode()
        if (titleNode.layoutInfo.isPlaced && titleNode.boundsInRoot.height > 0f) {
            assertTrue("The title runs into the field", titleNode.boundsInRoot.bottom <= field.top)
        }
    }

    @Test
    fun theFieldIsNoPlaceForAPasswordManager() {
        show(openGate())
        val dataType = field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDataType)
        assertEquals(ContentDataType.None, dataType)
    }

    @Test
    fun theKeyboardIsToldNotToLearnOrSuggest() {
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        hardenEditorInfo(info)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
        assertTrue(info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
    }

    @Test
    @Config(qualifiers = "w915dp-h150dp-land")
    fun inLandscapeWithTheKeyboardUpItIsOneRowWithEverythingInView() {
        val gate = openGate()
        show(gate)
        gate.error = "Wrong passphrase"
        rule.waitForIdle()
        // What's locked said in the row; the logo and its words stepped aside.
        rule.onNodeWithText("BioKey").assertIsDisplayed()
        field().assertIsDisplayed()
        rule.onNodeWithText("Wrong passphrase").assertIsDisplayed()
        rule.onNodeWithText("Back").assertIsDisplayed()
        unlock().assertIsDisplayed()
        val back = rule.onNodeWithText("Back").fetchSemanticsNode().boundsInRoot
        val unlockAt = unlock().fetchSemanticsNode().boundsInRoot
        assertTrue(back.right <= unlockAt.left)
        assertTrue(kotlin.math.abs(back.center.y - unlockAt.center.y) < 2f)
    }
}
