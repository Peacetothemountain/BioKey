package com.android.BioKey

import android.content.Context
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.OwnerPassphrase
import com.android.BioKey.feature.main.PassphraseEntryDialog
import com.android.BioKey.feature.main.PassphraseRowState
import com.android.BioKey.feature.main.passphraseCanBeSet
import com.android.BioKey.feature.main.passphraseEntryReady
import com.android.BioKey.feature.main.passphraseLengthOk
import com.android.BioKey.feature.main.passphraseMismatch
import com.android.BioKey.feature.main.passphraseRowState
import com.android.BioKey.ui.theme.BioKeyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Settings' passphrase row says what's true now: shown only where a passphrase can be asked for,
 * or where one is set (never hidden while kept), and never "On" when nothing would ask for it. Its
 * set / change dialog takes the passphrase twice, so a typo is never what's saved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassphraseSettingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun notSetShowsOnlyWhereItWouldBeAskedFor() {
        assertEquals(PassphraseRowState.NOT_SET, passphraseRowState(isSet = false, biometricOnly = true, strongBiometric = true))
        assertEquals(PassphraseRowState.HIDDEN, passphraseRowState(isSet = false, biometricOnly = false, strongBiometric = true))
        assertEquals(PassphraseRowState.HIDDEN, passphraseRowState(isSet = false, biometricOnly = true, strongBiometric = false))
        assertEquals(PassphraseRowState.HIDDEN, passphraseRowState(isSet = false, biometricOnly = false, strongBiometric = false))
    }

    @Test
    fun aSetPassphraseIsAlwaysShownAndOnOnlyWhileTheSwitchIs() {
        assertEquals(PassphraseRowState.ON, passphraseRowState(isSet = true, biometricOnly = true, strongBiometric = true))
        // With no face or fingerprint to use, the passphrase is what's asked for, not the screen lock.
        assertEquals(PassphraseRowState.ON_NO_BIOMETRIC, passphraseRowState(isSet = true, biometricOnly = true, strongBiometric = false))
        assertEquals(PassphraseRowState.UNUSED_SWITCH_OFF, passphraseRowState(isSet = true, biometricOnly = false, strongBiometric = true))
        assertEquals(PassphraseRowState.UNUSED_SWITCH_OFF, passphraseRowState(isSet = true, biometricOnly = false, strongBiometric = false))
        for (only in listOf(true, false)) for (strong in listOf(true, false)) {
            val state = passphraseRowState(isSet = true, biometricOnly = only, strongBiometric = strong)
            assertTrue(state.shown)
            assertTrue(state.isSet)
            // In use exactly while "Face or fingerprint only" is on.
            assertEquals(only, state.inUse)
        }
    }

    @Test
    fun withoutAFaceOrFingerprintItCanOnlyBeRemoved() {
        // The owner check would be the screen lock then: not enough to choose what stands in for it.
        for (state in PassphraseRowState.entries) assertFalse(passphraseCanBeSet(state, strongBiometric = false))
        assertFalse(passphraseCanBeSet(PassphraseRowState.ON_NO_BIOMETRIC, strongBiometric = true))
        assertTrue(passphraseCanBeSet(PassphraseRowState.NOT_SET, strongBiometric = true))
        assertTrue(passphraseCanBeSet(PassphraseRowState.ON, strongBiometric = true))
        assertTrue(passphraseCanBeSet(PassphraseRowState.UNUSED_SWITCH_OFF, strongBiometric = true))
    }

    @Test
    fun eachShownStateHasItsOwnSubtitle() {
        assertNull(PassphraseRowState.HIDDEN.subtitle)
        val shown = PassphraseRowState.entries.filter { it.shown }
        val subtitles = shown.map { context.getString(it.subtitle!!) }
        assertEquals(shown.size, subtitles.toSet().size)
        // Only the states where it's really asked for say "On".
        assertEquals(shown.filter { it.inUse }, shown.filter { context.getString(it.subtitle!!).startsWith("On") })
        // A glance, not an explanation: the state first, short enough for one line (two on a narrow phone).
        for (subtitle in subtitles) {
            assertTrue(subtitle, subtitle.length <= 60)
            // The app's separator: a space (a no-break one, so " ·" never starts a line) either side.
            assertTrue(subtitle, Regex("[  ]· ").containsMatchIn(subtitle))
            assertFalse(subtitle, subtitle.endsWith("."))
        }
    }

    @Test
    fun lengthRule() {
        assertFalse(passphraseLengthOk(0))
        assertFalse(passphraseLengthOk(OwnerPassphrase.MIN_LENGTH - 1))
        assertTrue(passphraseLengthOk(OwnerPassphrase.MIN_LENGTH))
        assertTrue(passphraseLengthOk(OwnerPassphrase.MAX_LENGTH))
        assertFalse(passphraseLengthOk(OwnerPassphrase.MAX_LENGTH + 1))
        assertEquals(8, OwnerPassphrase.MIN_LENGTH)
    }

    @Test
    fun savedOnlyWhenLongEnoughAndTypedTheSameTwice() {
        assertFalse(passphraseEntryReady("", ""))
        assertFalse(passphraseEntryReady("short", "short"))
        assertFalse(passphraseEntryReady("correct horse", ""))
        assertFalse(passphraseEntryReady("correct horse", "correct hors"))
        assertFalse(passphraseEntryReady("correct horse", "Correct horse"))
        assertTrue(passphraseEntryReady("correct horse", "correct horse"))
        val longest = "x".repeat(OwnerPassphrase.MAX_LENGTH)
        assertTrue(passphraseEntryReady(longest, longest))
        assertFalse(passphraseEntryReady(longest + "x", longest + "x"))
    }

    @Test
    fun aMismatchIsSaidOnlyOnceTheSecondCantMatch() {
        // Nothing typed again, or the same: nothing to say.
        assertFalse(passphraseMismatch("correct horse", "", finished = true))
        assertFalse(passphraseMismatch("correct horse", "correct horse", finished = true))
        // Still being typed and on its way to matching: not said on every keystroke.
        assertFalse(passphraseMismatch("correct horse", "correct", finished = false))
        // Finished with (Done, or left): said.
        assertTrue(passphraseMismatch("correct horse", "correct", finished = true))
        // Already can't match: a wrong character, as long and different, or longer.
        assertTrue(passphraseMismatch("correct horse", "corx", finished = false))
        assertTrue(passphraseMismatch("correct horse", "correct horsx", finished = false))
        assertTrue(passphraseMismatch("correct horse", "correct horse!", finished = false))
        // The first changed after the second was typed.
        assertTrue(passphraseMismatch("correct horses", "correct horsx", finished = false))
    }

    // The set / change dialog itself.

    private val stored = mutableListOf<String>()
    private var saved = 0
    private var removed = 0
    private var dismissed = 0
    private var storeWorks = true

    private fun showEntry(changing: Boolean = false) {
        rule.setContent {
            BioKeyTheme {
                PassphraseEntryDialog(
                    changing = changing,
                    onSaved = { saved++ },
                    onRemove = { removed++ },
                    onDismiss = { dismissed++ },
                    store = { _, chars -> stored += String(chars); storeWorks }
                )
            }
        }
        rule.waitForIdle()
    }

    private val isField = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
    private fun first(): SemanticsNodeInteraction = rule.onAllNodes(isField)[0]
    private fun again(): SemanticsNodeInteraction = rule.onAllNodes(isField)[1]
    private fun save() = rule.onNode(hasText("Save") and hasClickAction())
    private fun errorOf(field: SemanticsNodeInteraction): String? = field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error)
    private val noMatch get() = context.getString(R.string.passphrase_no_match)

    @Test
    fun opensWithTheCursorInTheFirstOfTwoFields() {
        showEntry()
        rule.onAllNodes(isField).assertCountEquals(2)
        first().assertIsFocused()
        rule.onNode(hasText("Set a passphrase")).assertIsDisplayed()
        rule.onNode(hasText("Type it again")).assertExists()
        // One eye, for both fields.
        rule.onAllNodes(hasContentDescription("Show passphrase")).assertCountEquals(1)
        rule.onNode(hasContentDescription("Show passphrase")).performClick()
        rule.onAllNodes(hasContentDescription("Hide passphrase")).assertCountEquals(1)
        // Setting one isn't where it's removed.
        rule.onAllNodes(hasText("Remove passphrase")).assertCountEquals(0)
    }

    @Test
    fun saveWaitsForTheSamePassphraseTwice() {
        showEntry()
        save().assertIsNotEnabled()
        first().performTextInput("correct horse")
        save().assertIsNotEnabled()
        again().performTextInput("correct horse")
        save().assertIsEnabled()
        save().performClick()
        rule.waitUntil(5_000L) { ShadowLooper.idleMainLooper(); saved == 1 }
        assertEquals(listOf("correct horse"), stored)
    }

    @Test
    fun doneOnTheSecondFieldSavesWhenTheyMatch() {
        showEntry()
        first().performTextInput("battery staple")
        again().performTextInput("battery staple")
        again().performImeAction()
        rule.waitUntil(5_000L) { ShadowLooper.idleMainLooper(); saved == 1 }
        assertEquals(listOf("battery staple"), stored)
    }

    @Test
    fun aMismatchIsSaidUnderTheSecondFieldAndNothingIsSaved() {
        showEntry()
        first().performTextInput("correct horse")
        again().performTextInput("correct")
        // On its way to matching: nothing said yet.
        rule.onAllNodes(hasText(noMatch)).assertCountEquals(0)
        assertNull(errorOf(again()))
        again().performTextInput("x")
        rule.onNode(hasText(noMatch)).assertExists()
        // The field says it too, for a screen reader landing on it.
        assertEquals(noMatch, errorOf(again()))
        save().assertIsNotEnabled()

        // Emptied and typed again: the same patience as the first time.
        again().performTextClearance()
        rule.onAllNodes(hasText(noMatch)).assertCountEquals(0)
        again().performTextInput("correct")
        rule.onAllNodes(hasText(noMatch)).assertCountEquals(0)

        // Done on a second that's only the start of the first: said, not saved.
        again().performImeAction()
        rule.onNode(hasText(noMatch)).assertExists()
        rule.waitForIdle()
        assertTrue(stored.isEmpty())
        assertEquals(0, saved)
    }

    @Test
    fun nextMovesOnOnlyOnceTheFirstIsLongEnough() {
        showEntry()
        first().performTextInput("short")
        first().performImeAction()
        first().assertIsFocused()
        assertNotNull(errorOf(first()))
        first().performTextInput(" enough")
        // Long enough: the rule is help again, not an error.
        assertNull(errorOf(first()))
        first().performImeAction()
        again().assertIsFocused()
    }

    @Test
    fun aFailedSaveIsSaidAndGoesOnceEdited() {
        storeWorks = false
        showEntry()
        first().performTextInput("correct horse")
        again().performTextInput("correct horse")
        save().performClick()
        val notSaved = context.getString(R.string.passphrase_not_saved)
        rule.waitUntil(5_000L) { ShadowLooper.idleMainLooper(); rule.onAllNodes(hasText(notSaved)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, saved)
        // Still there to try again.
        save().assertIsEnabled()
        again().performTextInput("!")
        rule.onAllNodes(hasText(notSaved)).assertCountEquals(0)
    }

    @Test
    fun changingOffersRemovingWithTheButtonsAtTheirStart() {
        showEntry(changing = true)
        rule.onNode(hasText("Change your passphrase")).assertIsDisplayed()
        rule.onNode(hasText("New passphrase")).assertExists()
        // With Cancel and Save, at their start (not in what scrolls), as "Delete profile" is in its dialog.
        val remove = rule.onNode(hasText("Remove passphrase") and hasClickAction())
        val cancelAt = rule.onNode(hasText("Cancel") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue(remove.fetchSemanticsNode().boundsInRoot.right <= cancelAt.left)
        remove.performClick()
        assertEquals(1, removed)
        rule.onNode(hasText("Cancel") and hasClickAction()).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun neitherFieldIsAPlaceForAPasswordManager() {
        showEntry()
        for (field in listOf(first(), again())) {
            assertEquals(ContentDataType.None, field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDataType))
        }
    }
}
