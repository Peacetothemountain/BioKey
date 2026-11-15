package com.android.BioKey

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.setup.SetupScreenExpressive
import com.android.BioKey.feature.setup.notificationsText
import com.android.BioKey.feature.setup.SETUP_HELP_TAG
import com.android.BioKey.feature.setup.SETUP_OPTIONAL_TAG
import com.android.BioKey.feature.setup.SETUP_STEP_TAG
import com.android.BioKey.feature.setup.SETUP_RESET_MORE_TAG
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.ui.format.attemptTime
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import com.android.BioKey.ui.theme.BioKeyTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Setup moves on only when the owner taps: back with the accessibility service on, it shows the
 * step done (in words that no longer say how to do it) and offers the next one, and opens no
 * question or prompt by itself. An Agree to the disclosure holds for the rest of setup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        // The header's constellation holds still with animations off, so the screen can go idle.
        android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("biokey_disclosures", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        ProtectionEngine.overrideModeForTests = null
        // Notifications off: setup used to ask Android's question by itself on the way to finishing.
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
    }

    @After
    fun restore() {
        android.provider.Settings.Secure.putString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
    }

    private fun accessibilityOn() {
        android.provider.Settings.Secure.putString(
            context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${context.packageName}/com.android.BioKey.feature.lock.BioKeyAccessibilityService"
        )
        android.provider.Settings.Secure.putInt(context.contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    @Test
    fun backWithTheServiceOnNothingOpensUntilTheOwnerTaps() {
        accessibilityOn()
        var finished = 0
        rule.setContent { BioKeyTheme { SetupScreenExpressive(onSetupComplete = { finished++ }) } }
        rule.waitForIdle()
        // No prompt, and no notification question, by themselves.
        assertEquals(0, finished)
        assertNull(shadowOf(rule.activity).lastRequestedPermission)
        // The step is done, and says so rather than how to do it.
        rule.onNodeWithText(context.getString(R.string.setup_accessibility_done)).assertIsDisplayed()
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.setup_progress, 2, 1, 2)).assertIsDisplayed()
        // The button has moved on to finishing, for the owner to tap.
        rule.onNodeWithText(context.getString(R.string.setup_button_finish)).performClick()
        assertEquals(1, finished)
        assertNull(shadowOf(rule.activity).lastRequestedPermission)
    }

    @Test
    fun anAgreeHoldsForTheRestOfSetup() {
        rule.setContent { BioKeyTheme { SetupScreenExpressive(onSetupComplete = {}) } }
        val open = context.getString(R.string.setup_button_accessibility)
        val agree = context.getString(R.string.disclosure_agree)
        rule.onNodeWithText(open).performClick()
        rule.onNodeWithText(agree).performScrollTo().performClick()
        rule.waitForIdle()
        // Tapped again in the same setup: straight to Android's settings, no second disclosure.
        rule.onNodeWithText(open).performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText(agree)).fetchSemanticsNodes().size)
    }

    @Test
    fun theNotificationStepAfterAResetClaimsNoHistory() {
        val tiramisu = Build.VERSION_CODES.TIRAMISU
        assertEquals(R.string.setup_notifications_text_reset, notificationsText(cleared = true, allowed = false, sdk = tiramisu))
        assertEquals(R.string.setup_notifications_text, notificationsText(cleared = true, allowed = true, sdk = tiramisu))
        assertEquals(R.string.setup_notifications_text, notificationsText(cleared = false, allowed = false, sdk = tiramisu))
        assertEquals(R.string.setup_notifications_text, notificationsText(cleared = true, allowed = false, sdk = tiramisu - 1))
        // After a clearing BioKey can't know whether they were ever allowed: it says how they are, not how they got so.
        val reset = context.getString(R.string.setup_notifications_text_reset).lowercase()
        listOf("took", "back", "again", "when biokey was reset").forEach { assertFalse("\"$it\" in \"$reset\"", it in reset) }
    }

    private fun tagged(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun theCountIsOfTheNumberedStepsShownAndTheOptionalOneStandsApart() {
        rule.setContent { BioKeyTheme { SetupScreenExpressive(onSetupComplete = {}) } }
        rule.waitForIdle()
        // Turn on BioKey, then confirm it's you: two numbered steps, and "0 of 2" over them.
        assertEquals(2, tagged(SETUP_STEP_TAG))
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.setup_progress, 2, 0, 2)).assertExists()
        rule.onNodeWithText("1").assertExists()
        rule.onNodeWithText("2").assertExists()
        // Notifications: under "Optional", not numbered nor counted.
        assertEquals(1, tagged(SETUP_OPTIONAL_TAG))
        rule.onNodeWithText(context.getString(R.string.setup_optional_heading)).assertExists()
        rule.onNodeWithText("3").assertDoesNotExist()
        assertEquals(0, tagged(SETUP_HELP_TAG))
    }

    @Test
    fun aStepAddedIsCountedAndNumbered() {
        accessibilityOn()
        // The final check found no screen lock: setting one is a step too.
        rule.setContent { BioKeyTheme { SetupScreenExpressive(needsScreenLock = true, onSetupComplete = {}) } }
        rule.waitForIdle()
        assertEquals(3, tagged(SETUP_STEP_TAG))
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.setup_progress, 3, 1, 3)).assertExists()
    }

    @Test
    fun afterAResetTheCardSaysWhenARestoreIsPossibleAndWhy() {
        val cleared = ProtectionGaps.Cleared(System.currentTimeMillis() - 3_600_000L)
        val before = System.currentTimeMillis()
        rule.setContent { BioKeyTheme { SetupScreenExpressive(cleared = cleared, onSetupComplete = {}) } }
        rule.waitForIdle()
        // Behind "What waits a day", collapsed at first, so the steps stay in view.
        val waitWords = context.getString(R.string.setup_reset_wait_text)
        assertTrue(rule.onAllNodes(hasText(waitWords, substring = true)).fetchSemanticsNodes().isEmpty())
        // Its click, not a tap: in a small window it can sit under the button bar.
        rule.onNodeWithTag(SETUP_RESET_MORE_TAG).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText(waitWords, substring = true).assertExists()
        // Not recorded yet (setup isn't done): a day from finishing, said as if finished now.
        val expected = listOf(before, System.currentTimeMillis()).map {
            context.getString(R.string.setup_restore_wait, attemptTime(context, it + UnlockAttempts.BIOMETRIC_HOLD_MS))
        }
        assertTrue(expected.any { rule.onAllNodes(hasText(it, substring = true)).fetchSemanticsNodes().isNotEmpty() })
        val words = context.getString(R.string.setup_restore_wait).lowercase()
        assertTrue("restore" in words && "a day after you finish setup" in words && "in case" in words)
    }

    @Test
    fun theResetCardSaysWhatHappenedInWordsThatAgree() {
        listOf(R.string.setup_storage_cleared, R.string.setup_storage_cleared_unknown, R.string.setup_reset_wait_text).forEach { id ->
            val words = context.getString(id)
            // Records are "recorded", never "noted"; the setting's new name.
            assertFalse(words, "note" in words.lowercase())
        }
        assertTrue("an unknown time" in context.getString(R.string.setup_storage_cleared_unknown))
        assertTrue("“Also record screen lock unlocks”" in context.getString(R.string.setup_reset_wait_text))
    }

    @Test
    fun turningOffAutoSortIsntOfferedAsAWayToUnlock() {
        val setting = context.getString(R.string.settings_auto_sort_title)
        val note = context.getString(R.string.setup_auto_sort_note, setting)
        assertTrue("apps you install later" in note && "already locked stay locked" in note)
        listOf(1, 5).forEach { n ->
            val text = context.resources.getQuantityString(R.plurals.home_auto_sort_text, n, n, setting)
            assertTrue(text, "apps you install later" in text && "or turn off" !in text)
        }
    }
}
