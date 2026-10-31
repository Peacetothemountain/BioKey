package com.android.BioKey

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.AppItem
import com.android.BioKey.feature.main.LockExplainContent
import com.android.BioKey.feature.main.LockExplainer
import com.android.BioKey.feature.main.homePrefs
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
 * What locking an app does: said once per app locked by hand, honestly (notifications, widgets,
 * apps with access), and for a gallery, files or messaging app, which other apps see the same data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockExplainerTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val gallery = AppItem("com.example.gallery", "Gallery", AppCategory.PRIVACY)
    private val others = listOf("Camera", "Drive", "Editor", "Feed", "Scanner").map { AppItem("com.example.${it.lowercase()}", it, AppCategory.GENERAL) }
    private val installed = listOf(gallery) + others

    private fun launchable(pkg: String, vararg categories: String) {
        val activity = ComponentName(pkg, "$pkg.Main")
        shadowOf(context.packageManager).addActivityIfNotPresent(activity)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            activity,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER); categories.forEach(::addCategory) }
        )
    }

    @Before
    fun reset() {
        homePrefs(context).edit().clear().commit()
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        LockExplainer.defaultSmsApp = { null }
        LockExplainer.hasWidgets = { _, _ -> false }
        LockExplainer.holdersOf = { _, _ -> emptySet() }
    }

    @After
    fun restore() {
        LockExplainer.defaultSmsApp = { null }
    }

    @Test
    fun aGalleryIsTheHomeOfPhotosAndAnOrdinaryAppOfNothing() {
        launchable(gallery.packageName, Intent.CATEGORY_APP_GALLERY)
        launchable("com.example.puzzle")
        assertEquals(LockExplainer.DataKind.PHOTOS, LockExplainer.dataKindOf(context, gallery.packageName))
        assertNull(LockExplainer.dataKindOf(context, "com.example.puzzle"))
    }

    @Test
    fun aFilesAppThatAlsoPicksPhotosIsAFilesApp() {
        // A files app picks images for other apps too: what it says it is wins.
        launchable("com.example.files", Intent.CATEGORY_APP_FILES)
        pickImages("com.example.files")
        assertEquals(LockExplainer.DataKind.FILES, LockExplainer.dataKindOf(context, "com.example.files"))
        // An app that says nothing is judged by what it picks.
        launchable("com.example.photos")
        pickImages("com.example.photos")
        assertEquals(LockExplainer.DataKind.PHOTOS, LockExplainer.dataKindOf(context, "com.example.photos"))
    }

    private fun pickImages(pkg: String) {
        shadowOf(context.packageManager).addIntentFilterForActivity(
            ComponentName(pkg, "$pkg.Main"),
            IntentFilter(Intent.ACTION_PICK).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataType("image/*") }
        )
    }

    @Test
    fun theSmsAppIsTheHomeOfMessages() {
        launchable("com.example.sms")
        LockExplainer.defaultSmsApp = { "com.example.sms" }
        assertEquals(LockExplainer.DataKind.MESSAGES, LockExplainer.dataKindOf(context, "com.example.sms"))
    }

    @Test
    fun theOtherAppsAreThoseHoldingItsPermissionsNotItself() {
        launchable(gallery.packageName, Intent.CATEGORY_APP_GALLERY)
        var asked: List<String> = emptyList()
        LockExplainer.holdersOf = { _, permissions ->
            asked = permissions
            setOf(gallery.packageName, "com.example.scanner", "com.example.drive", "com.example.notlisted")
        }
        LockExplainer.hasWidgets = { _, app -> app == gallery }
        val explanation = LockExplainer.explain(context, gallery, installed)
        assertTrue(android.Manifest.permission.READ_MEDIA_IMAGES in asked)
        assertEquals(listOf("Drive", "Scanner"), explanation.otherApps.map { it.label })
        assertTrue(explanation.hasWidgets)
        assertEquals(LockExplainer.Notifications.SHIELD_OFF, explanation.notifications)
    }

    @Test
    fun eachAppIsExplainedOnceAndNeverAfterDontShowAgain() {
        assertTrue(LockExplainer.shouldExplain(context, gallery.packageName))
        LockExplainer.markExplained(context, gallery.packageName)
        assertFalse(LockExplainer.shouldExplain(context, gallery.packageName))
        assertTrue(LockExplainer.shouldExplain(context, "com.example.camera"))
        LockExplainer.switchOff(context)
        assertFalse(LockExplainer.shouldExplain(context, "com.example.camera"))
    }

    @Test
    fun threeOtherAppsShowThenViewAllShowsTheRest() {
        val explanation = LockExplainer.Explanation(
            gallery.packageName, "Opening it asks for your face, fingerprint or screen lock.", LockExplainer.Notifications.HIDDEN,
            hasWidgets = true, dataKind = LockExplainer.DataKind.PHOTOS, otherApps = others
        )
        var dontShow = 0
        rule.setContent {
            BioKeyTheme { LockExplainContent(gallery, explanation, justLocked = true, onDontShowAgain = { dontShow++ }, onDone = {}) }
        }
        rule.onNodeWithText("Gallery is locked").assertIsDisplayed()
        rule.onNodeWithText("Home-screen widgets").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Your photos may still show in other apps").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Editor").performScrollTo().assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTextCount("Feed") == 0)
        rule.onNodeWithText("View all 5 apps").performScrollTo().performClick()
        rule.onNodeWithText("Scanner").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Don't show again").performScrollTo().performClick()
        assertEquals(1, dontShow)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
