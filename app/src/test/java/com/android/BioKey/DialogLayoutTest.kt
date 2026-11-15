package com.android.BioKey

import android.content.Context
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.TrustedClock
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.feature.main.PassphraseEntryDialog
import com.android.BioKey.feature.main.PassphraseSetting
import com.android.BioKey.feature.main.ProfileDetailsDialog
import com.android.BioKey.feature.main.beginRestore
import com.android.BioKey.feature.main.profileDetailsCanSave
import com.android.BioKey.ui.theme.BioKeyTheme
import com.android.BioKey.ui.theme.DialogScrollTag
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The dialogs with a text field ([com.android.BioKey.ui.theme.KeyboardSafeDialog]): the field being
 * typed in is wholly in view however little room the keyboard and the font size leave; Cancel on a
 * confirmation goes back to the dialog it came from with what was typed; Save waits for a change;
 * a restore during the hold is refused before any file picker.
 *
 * The room left with the keyboard up is stood in for by a short screen (Robolectric has no
 * keyboard): a 6-inch phone at 200% text with the keyboard up leaves about 480 dp; in landscape,
 * about 230 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DialogLayoutTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val rule = createComposeRule()

    /** As a phone used by touch is: Android then gives a new window's first field no focus of its own accord. */
    @org.junit.Before
    fun touchMode() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @After
    fun restore() {
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun s(id: Int): String = context.getString(id)

    private val isField = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
    private fun field(i: Int): SemanticsNodeInteraction = rule.onAllNodes(isField)[i]
    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInWindow
    private fun scrollArea(): Rect = bounds(rule.onNode(hasTestTag(DialogScrollTag), useUnmergedTree = true))

    /** Where app/build/dialog-shots is made beforehand, the dialog as drawn there, for a look (never compared). */
    private fun shot(name: String) {
        if (!java.io.File("build/dialog-shots").isDirectory) return
        System.setProperty("roborazzi.test.record", "true")
        rule.onNode(isRoot() and SemanticsMatcher("dialog root") { node -> node.children.any { it.config.contains(SemanticsProperties.IsDialog) } })
            .let { runCatching { it.captureRoboImage("build/dialog-shots/$name.png") } }
            .onFailure { rule.onAllNodes(isRoot())[1].captureRoboImage("build/dialog-shots/$name.png") }
    }

    private fun showChange() {
        rule.setContent {
            BioKeyTheme {
                PassphraseEntryDialog(changing = true, onSaved = {}, onRemove = {}, onDismiss = {}, store = { _, _ -> true })
            }
        }
        rule.waitForIdle()
    }

    private fun assertWhollyInView(node: SemanticsNodeInteraction) {
        val area = scrollArea()
        val box = bounds(node)
        assertTrue("$box not inside $area", box.top >= area.top - 0.5f && box.bottom <= area.bottom + 0.5f)
    }

    private fun focusEachFieldAndCheck(shotName: String) {
        // The first, as it opens: the title and text give way to it.
        field(0).assertIsFocused()
        rule.waitForIdle()
        assertWhollyInView(field(0))
        shot("${shotName}_first")
        // Then the second, as Next would.
        field(0).performTextInput("correct horse")
        field(1).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        field(1).assertIsFocused()
        assertWhollyInView(field(1))
        shot("${shotName}_second")
        // The buttons can be reached: in sight, or (with almost no room) at the end of what scrolls.
        val cancel = rule.onNode(hasText(s(R.string.prompt_cancel)) and hasClickAction())
        if (runCatching { cancel.assertIsDisplayed() }.isFailure) cancel.performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h480dp")
    fun atTwiceTheFontWithTheKeyboardUpTheFieldBeingTypedInIsWhollyInView() {
        RuntimeEnvironment.setFontScale(2f)
        showChange()
        focusEachFieldAndCheck("change_fs2_keyboard")
    }

    @Test
    @Config(qualifiers = "w800dp-h230dp-land")
    fun inLandscapeWithTheKeyboardUpTheFieldBeingTypedInIsWhollyInView() {
        RuntimeEnvironment.setFontScale(2f)
        showChange()
        focusEachFieldAndCheck("change_fs2_land_keyboard")
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun withRoomToSpareNothingScrolls() {
        showChange()
        shot("change_fs1_nokb")
        val area = scrollArea()
        // The whole dialog's content in view: the icon's and title's room and the fields'.
        assertWhollyInView(field(0))
        assertWhollyInView(field(1))
        assertTrue(area.height > 0f)
        // "Remove passphrase" with the buttons, at their start, below what scrolls.
        val remove = rule.onNode(hasText(s(R.string.passphrase_remove_passphrase)) and hasClickAction())
        remove.assertIsDisplayed()
        assertTrue(bounds(remove).top >= area.bottom - 0.5f)
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun buttonsThatCannotSitSideBySideStackWithTheMainActionOnTopAllAtTheEnd() {
        RuntimeEnvironment.setFontScale(2f)
        showChange()
        shot("change_fs2_buttons")
        val save = bounds(rule.onNode(hasText(s(R.string.passphrase_save)) and hasClickAction()).apply { if (runCatching { assertIsDisplayed() }.isFailure) performScrollTo() })
        val cancel = bounds(rule.onNode(hasText(s(R.string.prompt_cancel)) and hasClickAction()))
        val remove = bounds(rule.onNode(hasText(s(R.string.passphrase_remove_passphrase)) and hasClickAction()))
        assertTrue("$save above $cancel", save.bottom <= cancel.top + 0.5f)
        assertTrue("$cancel above $remove", cancel.bottom <= remove.top + 0.5f)
        // Lined up on the same edge, as Material stacks a dialog's buttons: the end.
        assertEquals(save.right, cancel.right, 0.5f)
        assertEquals(save.right, remove.right, 0.5f)
    }

    @Test
    fun theDialogsOwnScrollingNeverStopsPartWayIntoTheIcon() {
        assertEquals(0, com.android.BioKey.ui.theme.iconClear(0, 120, 400))
        assertEquals(120, com.android.BioKey.ui.theme.iconClear(30, 120, 400))
        assertEquals(120, com.android.BioKey.ui.theme.iconClear(119, 120, 400))
        assertEquals(200, com.android.BioKey.ui.theme.iconClear(200, 120, 400))
        // Where it can't scroll past the icon, it's left where it is.
        assertEquals(30, com.android.BioKey.ui.theme.iconClear(30, 120, 60))
        // No icon.
        assertEquals(30, com.android.BioKey.ui.theme.iconClear(30, 0, 400))
    }

    @Test
    fun anyStopPartWayIntoTheIconSettlesAtTheNearerEdgeAndThereIsAlwaysRoomToScrollItAway() {
        val rest = { o: Int, b: Int, m: Int -> com.android.BioKey.ui.theme.iconRest(o, b, m) }
        assertEquals(0, rest(0, 120, 400))
        assertEquals(0, rest(59, 120, 400))
        assertEquals(120, rest(60, 120, 400))
        assertEquals(120, rest(119, 120, 400))
        assertEquals(300, rest(300, 120, 400))
        // Never left part of the way in, even where it couldn't go past it.
        assertEquals(0, rest(80, 120, 100))
        val room = { o: Int, b: Int -> com.android.BioKey.ui.theme.iconRoom(o, b) }
        // Content a little taller than its room (a phone with the keyboard up) gets enough at its
        // foot to scroll the whole icon away; none when it fits or already can.
        assertEquals(100, room(20, 120))
        assertEquals(0, room(0, 120))
        assertEquals(0, room(120, 120))
        assertEquals(0, room(500, 120))
        assertEquals(0, room(20, 0))
    }

    /**
     * A new backup's dialog on a phone with the keyboard up at the usual font: its content a little
     * taller than the room, as it was seen with the icon cut to a sliver at the top. The icon is
     * wholly in view or wholly out of it, whichever field has focus.
     */
    @Test
    @Config(qualifiers = "w411dp-h560dp")
    fun aNewBackupsIconIsNeverCutWithTheKeyboardUp() {
        rule.setContent {
            BioKeyTheme {
                com.android.BioKey.feature.main.PasswordDialog(
                    title = s(R.string.backup_title), text = s(R.string.backup_text), confirmLabel = s(R.string.backup_choose_where),
                    creating = true, busy = false, error = null, onConfirm = {}, onDismiss = {}
                )
            }
        }
        rule.waitForIdle()
        fun iconWholeOrGone() {
            rule.mainClock.advanceTimeBy(2_000)
            rule.waitForIdle()
            val area = scrollArea()
            val title = bounds(rule.onNode(hasText(s(R.string.backup_title)), useUnmergedTree = true))
            // The icon sits above the title; with the title less than the icon's height (and the
            // padding above it) from the top, part of the icon would show.
            val iconAndPadding = with(rule.density) { (24 + 16 + 24).dp.toPx() }
            val shown = title.top - area.top
            assertTrue("title ${title.top} in area ${area.top}: icon cut", shown >= iconAndPadding - 0.5f || shown <= with(rule.density) { 24.dp.toPx() } + 0.5f)
        }
        field(0).performSemanticsAction(SemanticsActions.RequestFocus)
        iconWholeOrGone()
        shot("backup_fs1_icon_first")
        field(0).performTextInput("correct horse")
        field(1).performSemanticsAction(SemanticsActions.RequestFocus)
        field(1).performTextInput("wrong")
        iconWholeOrGone()
        shot("backup_fs1_icon_second")
        assertWhollyInView(field(1))
    }

    @Test
    @Config(qualifiers = "w411dp-h480dp")
    fun aNewBackupsMismatchMessageIsWhollyInViewAtTwiceTheFont() {
        RuntimeEnvironment.setFontScale(2f)
        rule.setContent {
            BioKeyTheme {
                com.android.BioKey.feature.main.PasswordDialog(
                    title = s(R.string.backup_title), text = s(R.string.backup_text), confirmLabel = s(R.string.backup_choose_where),
                    creating = true, busy = false, error = null, onConfirm = {}, onDismiss = {}
                )
            }
        }
        rule.waitForIdle()
        field(0).performTextInput("correct horse")
        field(1).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        // Can't be the start of the first: said at once, on two lines at this size.
        field(1).performTextInput("wrong")
        rule.waitForIdle()
        shot("backup_fs2_mismatch")
        val message = rule.onNode(hasText(s(R.string.backup_password_no_match)), useUnmergedTree = true)
        message.assertExists()
        assertWhollyInView(message)
        assertWhollyInView(field(1))
    }

    @Test
    fun buttonsSitSideBySideOnlyWhenEachFitsOnOneLine() {
        assertTrue(com.android.BioKey.ui.theme.dialogButtonsFit(listOf(100, 120), 8, 228))
        assertFalse(com.android.BioKey.ui.theme.dialogButtonsFit(listOf(100, 121), 8, 228))
        assertTrue(com.android.BioKey.ui.theme.dialogButtonsFit(listOf(200), 8, 200))
    }

    @Test
    @Config(qualifiers = "w411dp-h480dp")
    fun aNewBackupsTwoFieldsShowTogetherAtTwiceTheFontWithTheKeyboardUp() {
        RuntimeEnvironment.setFontScale(2f)
        rule.setContent {
            BioKeyTheme {
                com.android.BioKey.feature.main.PasswordDialog(
                    title = s(R.string.backup_title), text = s(R.string.backup_text), confirmLabel = s(R.string.backup_choose_where),
                    creating = true, busy = false, error = null, onConfirm = {}, onDismiss = {}
                )
            }
        }
        rule.waitForIdle()
        field(0).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        shot("backup_fs2_keyboard")
        assertWhollyInView(field(0))
        assertWhollyInView(field(1))
        // The main action on top of Cancel.
        val choose = bounds(rule.onNode(hasText(s(R.string.backup_choose_where)) and hasClickAction()).apply { if (runCatching { assertIsDisplayed() }.isFailure) performScrollTo() })
        val cancel = bounds(rule.onNode(hasText(s(R.string.prompt_cancel)) and hasClickAction()))
        assertTrue("$choose above $cancel", choose.bottom <= cancel.top + 0.5f)
    }

    @Test
    fun cancelOnRemoveGoesBackToTheChangeDialogWithWhatWasTyped() {
        val setting = PassphraseSetting(null)
        rule.setContent { BioKeyTheme { setting.Dialogs() } }
        setting.openChangeForTests()
        rule.waitForIdle()
        field(0).performTextInput("correct horse")
        field(1).performTextInput("correct")
        rule.onNode(hasText(s(R.string.passphrase_remove_passphrase)) and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(s(R.string.passphrase_remove_title)).assertExists()
        rule.onNode(hasText(s(R.string.prompt_cancel)) and hasClickAction()).performClick()
        rule.waitForIdle()
        // Back (its title may have scrolled up for both fields, in this short window).
        rule.onNodeWithText(s(R.string.passphrase_change_title)).assertExists()
        fun typedLength(i: Int) = field(i).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.length ?: 0
        assertEquals("correct horse".length, typedLength(0))
        assertEquals("correct".length, typedLength(1))
        // Nothing said wrong about the half-typed second one: it was only stepped away from. (Which
        // field has the cursor again isn't checked here: Robolectric never gives a dialog's window
        // the focus, which the dialog waits for before putting it back.)
        assertEquals(null, field(1).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error))
    }

    @Test
    fun saveNeedsANameAndForAProfileSomethingChanged() {
        val work = SecurityProfile(id = "p", name = "Work", iconKey = "work")
        assertFalse(profileDetailsCanSave(null, "  ", "shield"))
        assertTrue(profileDetailsCanSave(null, "Travel", "shield"))
        assertFalse(profileDetailsCanSave(work, "Work", "work"))
        assertFalse(profileDetailsCanSave(work, " Work ", "work"))
        assertTrue(profileDetailsCanSave(work, "Office", "work"))
        assertTrue(profileDetailsCanSave(work, "Work", "finance"))
        assertFalse(profileDetailsCanSave(work, "", "finance"))
    }

    @Test
    fun editingAProfileOffersSaveOnlyOnceSomethingChangedAndDoesNotPopTheKeyboard() {
        val work = SecurityProfile(id = "p", name = "Work", iconKey = "work")
        rule.setContent { BioKeyTheme { ProfileDetailsDialog(existing = work, onDismiss = {}, onSave = { _, _ -> }, onDelete = {}) } }
        rule.waitForIdle()
        val save = rule.onNode(hasText(s(R.string.profile_save)) and hasClickAction())
        save.assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("Office")
        save.assertIsEnabled()
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("Work")
        save.assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun theProfileNameIsWhollyInViewAtTwiceTheFontWithTheKeyboardUp() {
        RuntimeEnvironment.setFontScale(2f)
        val work = SecurityProfile(id = "p", name = "Work", iconKey = "work")
        rule.setContent { BioKeyTheme { ProfileDetailsDialog(existing = work, onDismiss = {}, onSave = { _, _ -> }, onDelete = {}) } }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        assertWhollyInView(rule.onNode(hasSetTextAction()))
        shot("profile_edit_fs2_keyboard")
    }

    @Test
    // 320 dp wide: under Robolectric, a value-based text field in a dialog any wider never lets
    // Compose go idle (a bare OutlinedTextField in a Dialog does the same).
    @Config(qualifiers = "w320dp-h915dp")
    fun profileDialogAtRest() {
        val work = SecurityProfile(id = "p", name = "Work", iconKey = "work")
        rule.setContent { BioKeyTheme { ProfileDetailsDialog(existing = work, onDismiss = {}, onSave = { _, _ -> }, onDelete = {}) } }
        rule.waitForIdle()
        // With the buttons, at their start, as "Remove passphrase" is in its dialog.
        val delete = rule.onNode(hasText(s(R.string.profile_delete_profile)) and hasClickAction())
        delete.assertIsDisplayed()
        assertTrue(bounds(delete).top >= scrollArea().bottom - 0.5f)
        val save = bounds(rule.onNode(hasText(s(R.string.profile_save)) and hasClickAction()))
        assertTrue(bounds(delete).left < save.left)
        shot("profile_edit_fs1")
    }

    @Test
    fun aNewProfileStartsWithTheCursorInItsName() {
        rule.setContent { BioKeyTheme { ProfileDetailsDialog(existing = null, onDismiss = {}, onSave = { _, _ -> }, onDelete = null) } }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.onNode(hasText(s(R.string.profile_create)) and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun aRestoreDuringTheHoldIsRefusedBeforeThePickerOpens() {
        context.getSharedPreferences("biokey_unlock_attempts", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("biokey_protection_gaps", Context.MODE_PRIVATE).edit().clear().commit()
        UnlockAttempts.resetForTests()
        ProtectionGaps.resetForTests()
        TrustedClock.resetForTests()
        var picked = 0
        // No hold: on to the picker.
        assertTrue(beginRestore(context) { picked++ })
        assertEquals(1, picked)
        // A face or fingerprint changed just now: refused there and then.
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        try {
            ProtectionGaps.recordEvent(context, ProtectionGaps.Kind.BIOMETRICS_CHANGED)
            assertTrue(UnlockAttempts.heldUntil(context) != null)
            assertFalse(beginRestore(context) { picked++ })
            assertEquals(1, picked)
        } finally {
            ProtectionGaps.restoreHooksForTests()
            ProtectionGaps.resetForTests()
            UnlockAttempts.resetForTests()
        }
    }
}
