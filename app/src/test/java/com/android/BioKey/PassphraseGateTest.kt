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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import android.os.SystemClock
import com.android.BioKey.feature.lock.BioKeyLockScreen
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
        rule.onNode(hasText("Checking…")).assertIsDisplayed().assertIsNotEnabled()
        gate.busy = false
        rule.waitForIdle()
        field().assertIsEnabled()
        unlock().assertIsEnabled()
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
        rule.onNodeWithText("Try again in 5 s").assertIsDisplayed()
        field().assertIsNotEnabled()
        unlock().assertIsNotEnabled()
        assertEquals("", typed())

        ShadowSystemClock.advanceBy(Duration.ofMillis(5_001L))
        rule.mainClock.advanceTimeBy(5_100L)
        rule.waitForIdle()
        field().assertIsEnabled()
        rule.onNodeWithText("You can try again now").assertIsDisplayed()
        field().performTextInput("abc")
        unlock().assertIsEnabled()
        rule.onAllNodes(hasText("You can try again now")).assertCountEquals(0)
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
}
