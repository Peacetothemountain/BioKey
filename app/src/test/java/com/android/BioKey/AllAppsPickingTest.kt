package com.android.BioKey

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.AppItem
import com.android.BioKey.feature.main.InstalledAppsCache
import com.android.BioKey.feature.main.MainScreenExpressive
import com.android.BioKey.ui.theme.BioKeyTheme
import com.android.BioKey.ui.theme.SEPARATOR
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Several locked apps picked on All apps and their locks switched off together: a long press on a
 * locked app starts picking, "Turn off" switches the picked ones off, they leave "Locked" at
 * once, and Undo switches them back on and brings them back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class AllAppsPickingTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var vault: SecurityVault

    private fun pkg(label: String) = "com.example.${label.lowercase()}"

    @Before
    fun seed() {
        android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        InstalledAppsCache.apps = listOf("Chase", "Signal", "Venmo", "Wallet").map { AppItem(pkg(it), it, AppCategory.GENERAL) }
        InstalledAppsCache.version = 0
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        listOf("Chase", "Signal", "Venmo").forEach { vault.setAppLock(pkg(it), true) }
        LockStateTracker.resetForTests()
        ProtectionEngine.overrideModeForTests = ProtectionEngine.Mode.ACCESSIBILITY
        rule.setContent { BioKeyTheme { MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}) } }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        ProtectionEngine.overrideModeForTests = null
    }

    private fun isOn(label: String) = pkg(label) in vault.selectedAppsFlow.value

    /** The "Locked" section's header, which counts the apps in it. */
    private fun lockedHeader(count: Int) = rule.onAllNodes(hasText("Locked$SEPARATOR$count", substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun pickedLocksGoOffTogetherAndLeaveLockedAtOnceAndUndoBringsThemBack() {
        rule.onNodeWithText("Chase").performTouchInput { longClick() }
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        rule.onNodeWithText("Venmo").performClick()
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Turn off").performClick()
        rule.mainClock.advanceTimeBy(300)

        assertFalse(isOn("Chase"))
        assertFalse(isOn("Venmo"))
        assertTrue("an app not picked keeps its lock", isOn("Signal"))
        // Out of "Locked" at once, not when the message goes.
        assertTrue(lockedHeader(1))
        rule.onNodeWithText("Locks removed from 2 apps").assertIsDisplayed()

        rule.onNodeWithText("Undo").performClick()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(isOn("Chase"))
        assertTrue(isOn("Venmo"))
        assertTrue(lockedHeader(3))
    }

    @Test
    fun selectAllPicksOnlyLockedAppsAndBackEndsPickingChangingNothing() {
        rule.onNodeWithText("Signal").performTouchInput { longClick() }
        rule.onNodeWithText("Select all").performClick()
        // Wallet's lock is already off: nothing to turn off there.
        rule.onNodeWithText("3 selected").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        listOf("Chase", "Signal", "Venmo").forEach { assertTrue(it, isOn(it)) }
        assertEquals(0, rule.onAllNodes(hasText("selected", substring = true)).fetchSemanticsNodes().size)
    }
}
