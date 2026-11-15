package com.android.BioKey

import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.feature.main.PROFILE_NAME_MAX
import com.android.BioKey.feature.main.ProfileDetailsDialog
import com.android.BioKey.feature.main.fitProfileName
import com.android.BioKey.ui.theme.BioKeyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The profile dialogs: the name's limit said and kept, and deleting a profile said truthfully. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileSheetsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aLongPasteIsCutToFitAndTypingIntoAFullNameIsRefused() {
        val long = "Travel and holidays abroad 2027"
        assertEquals(long.take(PROFILE_NAME_MAX), fitProfileName("", long))
        val full = "x".repeat(PROFILE_NAME_MAX)
        assertEquals(full, fitProfileName(full, full.substring(0, 3) + "y" + full.substring(3)))
        assertEquals("Work", fitProfileName("Wor", "Work"))
        // Never through the middle of a character made of two (an emoji).
        val emojiAtEdge = "a".repeat(PROFILE_NAME_MAX - 1) + "😀" + "more"
        val cut = fitProfileName("", emojiAtEdge)
        assertEquals(PROFILE_NAME_MAX - 1, cut.length)
        assertTrue(!cut.last().isHighSurrogate())
    }

    @Test
    fun theCounterShowsNearTheLimitAndAPasteFillsTheField() {
        rule.setContent { BioKeyTheme { ProfileDetailsDialog(existing = null, onDismiss = {}, onSave = { _, _ -> }, onDelete = null) } }
        val field = rule.onNode(hasSetTextAction())
        field.performTextInput("Bank")
        // Quiet until it matters.
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("4/24")).fetchSemanticsNodes().size)
        field.performTextReplacement("Travel and holidays abroad 2027")
        assertEquals("Travel and holidays abro", field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
        rule.onNodeWithContentDescription("24 of 24 characters").assertIsDisplayed()
    }

    @Test
    // The confirmation is a second dialog window: looked for, not "displayed" (Robolectric).
    fun deletingAnEmptyProfileDoesNotTalkOfItsApps() {
        val empty = SecurityProfile(id = "p1", name = "Travel")
        rule.setContent { BioKeyTheme { ProfileDetailsDialog(existing = empty, onDismiss = {}, onSave = { _, _ -> }, onDelete = {}) } }
        rule.onNodeWithText("Delete profile").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Delete “Travel”?").assertExists()
        rule.onNodeWithText("It has no apps, so nothing else changes").assertExists()
    }

    @Test
    fun deletingAProfileWithAppsSaysTheyAreNoLongerLocked() {
        val withApps = SecurityProfile(id = "p1", name = "Finance", packages = setOf("com.example.bank"))
        rule.setContent { BioKeyTheme { ProfileDetailsDialog(existing = withApps, onDismiss = {}, onSave = { _, _ -> }, onDelete = {}) } }
        rule.onNodeWithText("Delete profile").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Its apps will no longer be locked. You can lock them again one by one.").assertExists()
    }

    @Test
    fun aNewProfileStartsWithAnIconNoBuiltInProfileWears() {
        val builtIn = setOf("shield", "finance", "social", "work")
        val newIcon = com.android.BioKey.feature.main.NEW_PROFILE_ICON
        assertTrue(newIcon !in builtIn)
        // Nor the lock, nor the crossed-out eye (it reads as hidden), and one the picker offers.
        assertTrue(newIcon != "lock" && newIcon != "privacy")
        assertTrue(com.android.BioKey.feature.main.PROFILE_ICONS.any { it.key == newIcon })
    }

    @Test
    fun noProfileWearsAnIconOfItsOwnNotTheLocks() {
        val none = com.android.BioKey.feature.main.profileIcon(com.android.BioKey.feature.main.NO_PROFILE_ICON)
        assertTrue(none != androidx.compose.material.icons.Icons.Default.Lock)
        assertTrue(com.android.BioKey.feature.main.PROFILE_ICONS.none { it.vector == none })
    }
}
