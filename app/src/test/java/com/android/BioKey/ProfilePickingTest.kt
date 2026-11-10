package com.android.BioKey

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Several apps picked on a profile's page and taken out of it together: picking starts with
 * "Select" or a long press on an app, a tap picks, Back ends it, and each app taken out keeps its
 * own switch, as "Remove from …" leaves one app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class ProfilePickingTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var vault: SecurityVault

    private fun pkg(label: String) = "com.example.${label.lowercase()}"

    @Before
    fun seed() {
        // The header's decorative loops hold still with animations off, so the screen can settle.
        android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        InstalledAppsCache.apps = listOf("Chase", "Signal", "Venmo", "Wallet").map { AppItem(pkg(it), it, AppCategory.GENERAL) }
        InstalledAppsCache.version = 0
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        listOf("Chase", "Venmo", "Wallet").forEach { vault.moveAppToProfile(pkg(it), "profile_finance") }
        LockStateTracker.lockAll()
        ProtectionEngine.overrideModeForTests = ProtectionEngine.Mode.ACCESSIBILITY
        rule.setContent { BioKeyTheme { MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}) } }
        rule.onNodeWithContentDescription("Finance profile, 3 apps").performClick()
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        ProtectionEngine.overrideModeForTests = null
    }

    private fun profileOf(label: String) = vault.profileOf(pkg(label))?.id

    @Test
    fun pickedAppsAreTakenOutTogetherAndKeepTheirSwitches() {
        rule.onNodeWithText("Select").performClick()
        rule.onNodeWithText("Chase").performClick()
        rule.onNodeWithText("Venmo").performClick()
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()
        rule.waitForIdle()

        assertNull(profileOf("Chase"))
        assertNull(profileOf("Venmo"))
        assertEquals("profile_finance", profileOf("Wallet"))
        // Out of the profile, each still locked by its own switch.
        val selected = vault.selectedAppsFlow.value
        assertTrue(pkg("Chase") in selected && pkg("Venmo") in selected)
        // Picking is over: the page offers it again.
        rule.onNodeWithText("Select").assertIsDisplayed()
    }

    @Test
    fun aLongPressStartsPickingWithThatAppAndBackEndsItTakingNothingOut() {
        rule.onNodeWithText("Wallet").performTouchInput { longClick() }
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

        rule.onNodeWithText("Select").assertIsDisplayed()
        assertEquals("profile_finance", profileOf("Wallet"))
        // Still on the profile's page: Back ended the picking, not the page.
        rule.onNodeWithText("Apps in Finance").assertIsDisplayed()
    }

    @Test
    fun selectAllPicksEveryAppOnThePageAndSelectNoneNone() {
        rule.onNodeWithText("Select").performClick()
        rule.onNodeWithText("Select all").performClick()
        rule.onNodeWithText("3 selected").assertIsDisplayed()
        rule.onNodeWithText("Select none").performClick()
        rule.onNodeWithText("0 selected").assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()
        rule.waitForIdle()
        // Nothing picked: the button does nothing.
        listOf("Chase", "Venmo", "Wallet").forEach { assertEquals("profile_finance", profileOf(it)) }
    }
}
