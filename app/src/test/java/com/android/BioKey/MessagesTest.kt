package com.android.BioKey

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.BioKey.ui.theme.BioKeyMessageHost
import com.android.BioKey.ui.theme.BioKeyMessages
import com.android.BioKey.ui.theme.MessageHost
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BioKey's messages show once, on the host on top (a sheet's over the screen's), only while
 * BioKey is on screen: one raised while it's away waits for it, and one showing when it leaves
 * is gone when it comes back, behind its lock, with its action no longer offered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessagesTest {

    @get:Rule
    val rule = createComposeRule()

    /** BioKey's screen, started and stopped by the test. */
    private val screen = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun moveTo(state: Lifecycle.State) = rule.runOnIdle { screen.registry.currentState = state }

    /** The screen's host, and a sheet's host when [sheetOpen], composed before it like a sheet restored on recreation. */
    private fun hosts(sheetOpen: () -> Boolean = { false }) = rule.setContent {
        CompositionLocalProvider(LocalLifecycleOwner provides screen) {
            MaterialTheme {
                Box {
                    if (sheetOpen()) Box(Modifier.testTag("sheet")) { BioKeyMessageHost() }
                    Box(Modifier.testTag("screen")) { MessageHost(Modifier, isScreen = true) }
                }
            }
        }
    }

    private fun shownOn(host: String, text: String) = hasText(text) and hasAnyAncestor(hasTestTag(host))

    @Test
    fun aMessageRaisedWhileAwayWaitsForTheScreen() {
        screen.registry.currentState = Lifecycle.State.CREATED
        hosts()
        BioKeyMessages.show("Backup saved")
        rule.onNodeWithText("Backup saved").assertDoesNotExist()
        moveTo(Lifecycle.State.RESUMED)
        rule.onNodeWithText("Backup saved").assertExists()
    }

    @Test
    fun aBarShowingWhenBioKeyLeavesIsGoneOnReturn() {
        screen.registry.currentState = Lifecycle.State.RESUMED
        rule.mainClock.autoAdvance = false
        var undone = false
        hosts()
        BioKeyMessages.show("Chase unlocked", actionLabel = "Undo") { undone = true }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Chase unlocked").assertExists()
        // The screen turns off: back on, the first frame over the lock has no bar, not even fading out.
        moveTo(Lifecycle.State.CREATED)
        moveTo(Lifecycle.State.RESUMED)
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Chase unlocked").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Chase unlocked").assertDoesNotExist()
        assertFalse(undone)
    }

    @Test
    fun theHostOnTopShowsEachMessageOnce() {
        screen.registry.currentState = Lifecycle.State.RESUMED
        var sheetOpen by mutableStateOf(true)
        hosts { sheetOpen }
        BioKeyMessages.show("Chase unlocked", actionLabel = "Undo") {}
        rule.onNode(shownOn("sheet", "Chase unlocked")).assertExists()
        rule.onNode(shownOn("screen", "Chase unlocked")).assertDoesNotExist()
        // Closing the sheet hands the bar, and its Undo, to the screen beneath.
        rule.runOnIdle { sheetOpen = false }
        rule.onNode(shownOn("screen", "Chase unlocked")).assertExists()
    }

    @Test
    fun aMessageRaisedAsTheSheetClosesStillShows() {
        screen.registry.currentState = Lifecycle.State.RESUMED
        var sheetOpen by mutableStateOf(true)
        hosts { sheetOpen }
        // Like "Remove from profile" in an app's sheet: one tap closes the sheet and offers an Undo.
        rule.runOnIdle {
            sheetOpen = false
            BioKeyMessages.show("Removed Chase from Banking", actionLabel = "Undo") {}
        }
        rule.onNode(shownOn("screen", "Removed Chase from Banking")).assertExists()
    }
}
