package com.android.BioKey

import android.os.SystemClock
import androidx.activity.ComponentDialog
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.android.BioKey.feature.main.PassphraseConfirmDialog
import com.android.BioKey.feature.main.PassphraseConfirmState
import com.android.BioKey.ui.theme.BioKeyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** The passphrase asked for in place of a face or fingerprint before a change: shown on request, hands over exactly what was typed, and keeps nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassphraseConfirmDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private val submitted = mutableListOf<String>()
    private var dismissed = 0

    private fun show(state: PassphraseConfirmState) {
        rule.setContent {
            BioKeyTheme {
                PassphraseConfirmDialog(state, onSubmit = { submitted += String(it) }, onDismiss = { dismissed++ })
            }
        }
        rule.waitForIdle()
    }

    private fun showing() = PassphraseConfirmState().apply { title = "Remove your passphrase" }

    private val isField = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
    private fun field(): SemanticsNodeInteraction = rule.onNode(isField)
    private fun confirm() = rule.onNode(hasText("Confirm") and hasClickAction())
    private fun cancel() = rule.onNode(hasText("Cancel") and hasClickAction())
    private fun typed(): String = field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /** Back, as the dialog's own window gets it. */
    private fun pressBack() {
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        rule.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun noTitleShowsNothing() {
        val state = PassphraseConfirmState()
        show(state)
        rule.onAllNodes(isField).assertCountEquals(0)
        rule.onAllNodes(hasText("Cancel")).assertCountEquals(0)

        state.title = "Remove your passphrase"
        rule.waitForIdle()
        rule.onNodeWithText("Remove your passphrase").assertIsDisplayed()
        field().assertIsDisplayed()

        state.title = null
        rule.waitForIdle()
        rule.onAllNodes(isField).assertCountEquals(0)
    }

    @Test
    fun doneAndConfirmHandOverExactlyWhatWasTypedAndEmptyTheField() {
        show(showing())
        field().performTextInput("correct horse")
        field().performImeAction()
        assertEquals(listOf("correct horse"), submitted)
        assertEquals("", typed())

        field().performTextInput("battery staple")
        confirm().performClick()
        assertEquals(listOf("correct horse", "battery staple"), submitted)
        assertEquals("", typed())
    }

    @Test
    fun nothingTypedCannotBeConfirmed() {
        show(showing())
        confirm().assertIsNotEnabled()
        field().performImeAction()
        assertTrue(submitted.isEmpty())
        field().performTextInput("x")
        confirm().assertIsEnabled()
    }

    @Test
    fun aCheckInProgressHoldsEverythingAndCannotBeDismissed() {
        val state = showing()
        show(state)
        field().performTextInput("abc")
        state.busy = true
        rule.waitForIdle()
        field().assertIsNotEnabled()
        confirm().assertIsNotEnabled()
        cancel().assertIsNotEnabled()
        pressBack()
        assertEquals(0, dismissed)
        field().assertIsDisplayed()

        state.busy = false
        rule.waitForIdle()
        field().assertIsEnabled()
        cancel().assertIsEnabled()
        pressBack()
        assertEquals(1, dismissed)
    }

    @Test
    fun anErrorIsSaidAndEmptiesTheFieldEvenTheSameWordsTwice() {
        val state = showing()
        show(state)
        field().performTextInput("wrong")
        state.error = "Wrong passphrase"
        rule.waitForIdle()
        rule.onNodeWithText("Wrong passphrase").assertIsDisplayed()
        assertEquals("", typed())

        field().performTextInput("wrong again")
        assertEquals("wrong again", typed())
        state.error = "Wrong passphrase"
        rule.waitForIdle()
        assertEquals("", typed())
    }

    @Test
    fun aWaitHoldsTriesUntilItEnds() {
        val state = showing()
        show(state)
        field().performTextInput("abc")
        state.waitUntilElapsed = SystemClock.elapsedRealtime() + 5_000L
        rule.waitForIdle()
        rule.onNodeWithText("Try again in 5 seconds").assertIsDisplayed()
        field().assertIsNotEnabled()
        confirm().assertIsNotEnabled()
        assertEquals("", typed())
        // As on the lock screens: the field keeps focus (the keyboard stays up) and refuses what's typed.
        field().assertIsFocused()
        runCatching { field().performTextInput("abc") }
        assertEquals("", typed())

        ShadowSystemClock.advanceBy(Duration.ofMillis(5_001L))
        rule.mainClock.advanceTimeBy(5_100L)
        rule.waitForIdle()
        field().assertIsEnabled()
        rule.onNodeWithText("You can try again now").assertIsDisplayed()
        field().performTextInput("abc")
        confirm().assertIsEnabled()
        rule.onAllNodes(hasText("You can try again now")).assertCountEquals(0)
    }

    @Test
    fun anErrorAndAWaitAreTwoSentences() {
        val state = showing()
        show(state)
        state.error = "Wrong passphrase"
        state.waitUntilElapsed = SystemClock.elapsedRealtime() + 30_000L
        rule.waitForIdle()
        rule.onNodeWithText("Wrong passphrase. Try again in 30 seconds.").assertIsDisplayed()
    }

    @Test
    fun opensWithTheCursorInTheFieldAndTheTitleCentred() {
        show(showing())
        field().assertIsFocused()
        rule.onNodeWithText("Remove your passphrase").assertIsDisplayed()
        // One eye, its words the app's own for the passphrase.
        rule.onAllNodes(hasContentDescription("Show passphrase")).assertCountEquals(1)
    }

    @Test
    fun aCheckInProgressDoesNotMoveTheButtons() {
        val state = showing()
        show(state)
        // Something typed, so the field's label sits the same whether it has focus or not.
        field().performTextInput("abc")
        val before = confirm().fetchSemanticsNode().boundsInRoot
        state.busy = true
        rule.waitForIdle()
        assertEquals(before, confirm().fetchSemanticsNode().boundsInRoot)
        state.busy = false
        rule.waitForIdle()
        assertEquals(before, confirm().fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun cancelDismisses() {
        show(showing())
        cancel().performClick()
        assertEquals(1, dismissed)
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun theFieldIsNoPlaceForAPasswordManager() {
        show(showing())
        val dataType = field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDataType)
        assertEquals(ContentDataType.None, dataType)
    }
}
