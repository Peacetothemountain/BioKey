package com.android.BioKey

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.android.BioKey.feature.lock.PassphraseEntry
import com.android.BioKey.feature.lock.PassphraseGateState
import com.android.BioKey.ui.theme.BioKeyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BioKey's own passphrase entry switches between upright and one row as the keyboard comes and
 * goes (a phone in landscape): the field is the same field throughout, keeping its focus (and so
 * the keyboard) and what was typed, both ways.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w915dp-h400dp-land")
class PassphraseArrangementTest {

    @get:Rule
    val rule = createComposeRule()

    private val isField = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
    private fun field() = rule.onNode(isField)
    private fun unlock() = rule.onNode(hasText("Unlock") and !isField)
    private fun typed(): String = field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun theFieldKeepsItsFocusAndWhatWasTypedAsTheArrangementChanges() {
        val gate = PassphraseGateState().apply { offered = true; open = true }
        var title by mutableStateOf<String?>(null)
        val submitted = mutableListOf<String>()
        rule.setContent {
            BioKeyTheme { PassphraseEntry(gate, { submitted += String(it) }, Modifier.fillMaxWidth(), compactTitle = title) }
        }
        rule.waitForIdle()
        field().assertIsFocused()
        field().performTextInput("tidepool")

        // The keyboard up in landscape: one row, the same field in it.
        title = "BioKey"
        rule.waitForIdle()
        rule.onNodeWithText("BioKey").assertIsDisplayed()
        field().assertIsFocused()
        assertEquals("tidepool", typed())
        val fieldAt = field().fetchSemanticsNode().boundsInRoot
        val back = rule.onNodeWithText("Back").fetchSemanticsNode().boundsInRoot
        val unlockAt = unlock().fetchSemanticsNode().boundsInRoot
        assertTrue(fieldAt.right <= back.left && back.right <= unlockAt.left)

        // And back upright: still the same field.
        field().performTextInput("-lantern")
        title = null
        rule.waitForIdle()
        rule.onAllNodes(hasText("BioKey")).assertCountEquals(0)
        field().assertIsFocused()
        assertEquals("tidepool-lantern", typed())
        val upright = field().fetchSemanticsNode().boundsInRoot
        val unlockUpright = unlock().fetchSemanticsNode().boundsInRoot
        val backUpright = rule.onNodeWithText("Back").fetchSemanticsNode().boundsInRoot
        assertTrue(upright.bottom <= unlockUpright.top && unlockUpright.bottom <= backUpright.top)

        unlock().performClick()
        assertEquals(listOf("tidepool-lantern"), submitted)
        assertEquals("", typed())
    }
}
