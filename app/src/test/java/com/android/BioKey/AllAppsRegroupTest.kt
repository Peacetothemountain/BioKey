package com.android.BioKey

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.AppItem
import com.android.BioKey.feature.main.InstalledAppsCache
import com.android.BioKey.feature.main.MainScreenExpressive
import com.android.BioKey.ui.theme.BioKeyTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On the All apps page, an app whose lock is switched off stays under "Locked" while its
 * "Lock removed · Undo" is up (so its row doesn't move under the finger), the count drops at once,
 * and the row moves to "Not locked" as the message goes; Undo keeps it where it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class AllAppsRegroupTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var vault: SecurityVault

    private fun pkg(label: String) = "com.example.${label.lowercase()}"

    @Before
    fun seed() {
        android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        InstalledAppsCache.apps = listOf("Chase", "Signal", "Venmo").map { AppItem(pkg(it), it, AppCategory.GENERAL) }
        InstalledAppsCache.version = 0
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        listOf("Chase", "Signal").forEach { vault.setAppLock(pkg(it), true) }
        LockStateTracker.resetForTests()
        ProtectionEngine.overrideModeForTests = ProtectionEngine.Mode.ACCESSIBILITY
        rule.setContent { BioKeyTheme { MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}) } }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        ProtectionEngine.overrideModeForTests = null
    }

    private fun SemanticsNodeInteraction.top() = fetchSemanticsNode().boundsInRoot.top
    private fun row(label: String) = rule.onNode(hasText(label, substring = false) and !isToggleable(), useUnmergedTree = true)
    /** "Locked · 2 · re-locks immediately". */
    private fun lockedHeader() = rule.onNode(
        androidx.compose.ui.test.SemanticsMatcher("the Locked header") { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("")?.startsWith("Locked") == true
        },
        useUnmergedTree = true
    )
    private fun notLockedHeader() = rule.onNodeWithText("Not locked", useUnmergedTree = true)
    private fun switchOf(label: String) = rule.onNode(androidx.compose.ui.test.hasContentDescription("Lock $label") and isToggleable())

    private fun underLocked(label: String) = row(label).top() < notLockedHeader().top()

    @Test
    fun aLockSwitchedOffMovesToNotLockedWhenItsMessageGoes() {
        assertTrue(lockedHeader().fetchSemanticsNode().config.toString(), lockedHeaderText().startsWith("Locked${com.android.BioKey.ui.theme.SEPARATOR}2"))
        rule.mainClock.autoAdvance = false
        switchOf("Chase").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Lock removed from Chase").assertExists()
        // While Undo is up: the row stays, the count already says 1.
        assertTrue("Chase moved while Undo was up", underLocked("Chase"))
        assertTrue(lockedHeaderText(), lockedHeaderText().startsWith("Locked${com.android.BioKey.ui.theme.SEPARATOR}1"))
        // The message gone: under "Not locked".
        rule.mainClock.advanceTimeBy(7_000)
        rule.onNodeWithText("Lock removed from Chase").assertDoesNotExist()
        assertTrue("Chase still under Locked", !underLocked("Chase"))
        assertTrue("Signal moved", underLocked("Signal"))
    }

    @Test
    fun undoneItStaysUnderLocked() {
        rule.mainClock.autoAdvance = false
        switchOf("Chase").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Undo").performClick()
        rule.mainClock.advanceTimeBy(7_000)
        assertTrue(pkg("Chase") in vault.getLockedPackages())
        assertTrue("Chase moved after Undo", underLocked("Chase"))
    }

    private fun lockedHeaderText(): String =
        lockedHeader().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("")
}
