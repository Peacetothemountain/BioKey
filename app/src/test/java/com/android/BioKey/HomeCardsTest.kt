package com.android.BioKey

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.feature.main.AppItem
import com.android.BioKey.feature.main.AttemptsIntroCard
import com.android.BioKey.feature.main.SecurityActivityCard
import com.android.BioKey.feature.main.UnlockedNowCard
import com.android.BioKey.feature.main.actionsFitBeside
import com.android.BioKey.feature.main.SecurityActivityDialog
import com.android.BioKey.feature.main.groupRepeats
import com.android.BioKey.feature.main.isShortWindow
import com.android.BioKey.feature.main.pauseEndTime
import com.android.BioKey.feature.main.pausedBody
import com.android.BioKey.feature.main.runLine
import com.android.BioKey.feature.main.gapLine
import com.android.BioKey.feature.tile.BioKeyTileService
import com.android.BioKey.feature.tile.BioKeyTileService.Status
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.ui.theme.BioKeyTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The cards at the top of home say the right thing, and their buttons do what they say. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeCardsTest {

    @get:Rule
    val rule = createComposeRule()

    private val chase = AppItem("com.example.chase", "Chase", AppCategory.FINANCE)
    private val signal = AppItem("com.example.signal", "Signal", AppCategory.SOCIAL)
    private val fiveMinutes: (String) -> LockTimeoutMode = { LockTimeoutMode.GRACE_5_MINUTES }

    @Test
    fun oneUnlockedAppIsLockedByItsOwnButton() {
        val locked = mutableListOf<String>()
        rule.setContent {
            BioKeyTheme { UnlockedNowCard(listOf(chase), fiveMinutes, onLock = { locked += it.label }, onChoose = {}, onLockAll = {}) }
        }
        rule.onNodeWithText("Chase is unlocked").assertIsDisplayed()
        rule.onNodeWithText("Lock").performClick()
        assertEquals(listOf("Chase"), locked)
    }

    @Test
    fun severalUnlockedAppsOfferChooseAndLockAll() {
        var all = 0
        var chose = 0
        rule.setContent {
            BioKeyTheme { UnlockedNowCard(listOf(chase, signal), fiveMinutes, onLock = {}, onChoose = { chose++ }, onLockAll = { all++ }) }
        }
        rule.onNodeWithText("2 apps unlocked").assertIsDisplayed()
        rule.onNodeWithText("Chase, Signal").assertIsDisplayed()
        rule.onNodeWithText("See apps").performClick()
        rule.onNodeWithText("Re-lock now").performClick()
        assertEquals(1, chose)
        assertEquals(1, all)
    }

    @Test
    fun securityActivityNamesTheNewestAndReviewOpensTheRest() {
        var reviewed = 0
        val attempt = UnlockAttempts.Attempt("com.example.venmo", "Venmo", 1_000L, 1_000L, failures = 3)
        val gap = ProtectionGaps.Gap(500L, 900L, ProtectionGaps.Kind.SWITCHED_OFF)
        rule.setContent {
            BioKeyTheme { SecurityActivityCard(listOf(attempt), listOf(gap), labelOf = { it.label }, onReview = { reviewed++ }) }
        }
        rule.onNodeWithText("Security activity").assertIsDisplayed()
        rule.onNodeWithText("Review").performClick()
        assertEquals(1, reviewed)
    }

    @Test
    fun theIntroAsksAndAnswers() {
        var answer: Boolean? = null
        rule.setContent { BioKeyTheme { AttemptsIntroCard(onNotNow = { answer = false }, onTurnOn = { answer = true }) } }
        rule.onNodeWithText("Turn on").performClick()
        assertEquals(true, answer)
    }

    @Test
    fun aShortActionSitsBesideOnlyShortTextAtNormalSize() {
        // A countdown and a short title: beside.
        assertTrue(actionsFitBeside("Chase is unlocked", "Re-locks in 5 minutes", 1f))
        // A two-line title in the column the button leaves: under the text.
        assertFalse(actionsFitBeside("Lock Android Settings too", "Someone who knows your screen lock could add a fingerprint there", 1f))
        assertFalse(actionsFitBeside("Lock Android Settings too", "Short", 1f))
        // A long line under a short title: under the text.
        assertFalse(actionsFitBeside("Failed unlock attempt", "Venmo · Nov 14, 2:14 PM · 3 tries didn’t match", 1f))
        // Large text needs the whole width, however short.
        assertFalse(actionsFitBeside("Chase is unlocked", "Re-locks in 5 minutes", 1.3f))
    }

    private val venmoMiss = UnlockAttempts.Attempt("com.example.venmo", "Venmo", 1_000L, 1_000L, failures = 3)
    /** Long enough that the line under the title is past [INLINE_SUBTITLE_MAX], whatever the date looks like. */
    private val longName = "Venmo Business Wallet"

    @Test
    fun calmSecurityActivityKeepsReviewBesideItsText() {
        rule.setContent {
            BioKeyTheme { SecurityActivityCard(listOf(venmoMiss), emptyList(), labelOf = { longName }, onReview = {}, urgent = false) }
        }
        val title = rule.onNodeWithText("Failed unlock").getUnclippedBoundsInRoot()
        val review = rule.onNodeWithContentDescription("Review Security activity").getUnclippedBoundsInRoot()
        // A row's height, not a card's: Review starts level with the title, not under the text.
        assertTrue("Review at ${review.top}, title at ${title.top}", review.top < title.bottom)
    }

    @Test
    fun urgentSecurityActivityPutsReviewUnderALongLine() {
        rule.setContent {
            BioKeyTheme { SecurityActivityCard(listOf(venmoMiss), emptyList(), labelOf = { longName }, onReview = {}, urgent = true) }
        }
        val title = rule.onNodeWithText("Failed unlock").getUnclippedBoundsInRoot()
        val review = rule.onNodeWithContentDescription("Review Security activity").getUnclippedBoundsInRoot()
        assertTrue("Review at ${review.top}, title at ${title.bottom}", review.top > title.bottom)
    }

    private val context: android.content.Context get() = ApplicationProvider.getApplicationContext()

    private fun lockOff(seq: Long, at: Long, app: String = "Messages") =
        ProtectionGaps.Gap(at, at, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, seq, ProtectionGaps.Change.LOCK_OFF.encode(app))

    @Test
    fun theSameRecordRunningIsShownOnceWithHowManyTimesAndWhen() {
        val now = System.currentTimeMillis()
        // Newest first: three of the same, then another app's, then the first again.
        val records = listOf(
            lockOff(5L, now), lockOff(4L, now - 60_000L), lockOff(3L, now - 120_000L),
            lockOff(2L, now - 180_000L, app = "Chase"), lockOff(1L, now - 240_000L)
        )
        val runs = groupRepeats(records)
        assertEquals(listOf(3, 1, 1), runs.map { it.count })
        assertEquals(now - 120_000L, runs[0].oldestAt)
        // Its line spans the first to the last; one alone keeps its own time.
        val line = runLine(context, runs[0])
        assertTrue(line, "lock switched off: Messages" in line && line != gapLine(context, runs[0].newest))
        assertEquals(gapLine(context, runs[1].newest), runLine(context, runs[1]))
        // Times app locking was off each keep their span.
        val off = ProtectionGaps.Gap(now - 60_000L, now, ProtectionGaps.Kind.SWITCHED_OFF, 2L)
        assertEquals(2, groupRepeats(listOf(off, off.copy(seq = 1L))).size)
    }

    @Test
    fun reviewSaysEachHintOnceAndCountsRepeats() {
        val now = System.currentTimeMillis()
        val gaps = listOf(
            lockOff(4L, now), lockOff(3L, now - 60_000L), lockOff(2L, now - 120_000L),
            ProtectionGaps.Gap(now - 200_000L, now - 200_000L, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, 1L, ProtectionGaps.Change.LOCK_OFF.encode("Chase"))
        )
        rule.setContent {
            BioKeyTheme { SecurityActivityDialog(attempts = emptyList(), gaps = gaps, labelOf = { it.label }, onClear = {}, onDismiss = {}) }
        }
        rule.onAllNodesWithText("If it wasn’t you, someone knew your screen lock", substring = true).fetchSemanticsNodes().let {
            assertEquals(1, it.size)
        }
        rule.onNodeWithText("3 times").assertExists()
        rule.onNodeWithText("Check faces, fingerprints and screen lock").assertExists()
    }

    @Test
    fun changesWithinADayOfAFaceOrFingerprintChangeAreSaidSoOnceAndTheirHintsTogether() {
        val now = System.currentTimeMillis()
        val gaps = listOf(
            ProtectionGaps.Gap(now, now, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, 3L, ProtectionGaps.Change.PROFILE_OFF.encodeDuringHold("Finance")),
            ProtectionGaps.Gap(now - 60_000L, now - 60_000L, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, 2L, ProtectionGaps.Change.LOCK_OFF.encodeDuringHold("Chase")),
            ProtectionGaps.Gap(now - 120_000L, now - 120_000L, ProtectionGaps.Kind.BIOMETRICS_CHANGED, 1L)
        )
        // The group, the lines without the words in brackets.
        assertEquals(com.android.BioKey.feature.main.ChangeGroup.DURING_HOLD, com.android.BioKey.feature.main.changeGroup(gaps[0]))
        assertEquals(null, com.android.BioKey.feature.main.changeGroup(gaps[2]))
        assertFalse(gapLine(context, gaps[0], grouped = true).contains("within a day"))
        assertTrue(gapLine(context, gaps[0]).contains("within a day"))
        // One hint for the change and what came after it, not two saying nearly the same.
        val notes = com.android.BioKey.feature.main.changeSectionNotes(gaps)
        assertTrue(R.string.card_change_biometrics_hold_hint in notes)
        assertFalse(R.string.card_change_during_hold_hint in notes)
        assertFalse(R.string.card_change_biometrics_changed_hint in notes)
        rule.setContent {
            BioKeyTheme { SecurityActivityDialog(attempts = emptyList(), gaps = gaps, labelOf = { it.label }, onClear = {}, onDismiss = {}) }
        }
        rule.onAllNodesWithText(context.getString(R.string.activity_changes_during_hold)).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        rule.onAllNodesWithText("(within a day", substring = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    @Test
    fun aPauseEndTimeNeverSplitsAcrossLines() {
        val until = System.currentTimeMillis() + 15 * 60_000L
        assertFalse(pauseEndTime(context, until).contains(' '))
    }

    @Test
    fun reviewSaysWhatClearLeaves() {
        val now = System.currentTimeMillis()
        val gaps = listOf(lockOff(2L, now), ProtectionGaps.Gap(now - 60_000L, now - 60_000L, ProtectionGaps.Kind.BIOMETRICS_CHANGED, 1L))
        rule.setContent {
            BioKeyTheme { SecurityActivityDialog(attempts = emptyList(), gaps = gaps, labelOf = { it.label }, onClear = {}, onDismiss = {}) }
        }
        rule.onNodeWithText("Clear").assertIsDisplayed()
        // At the end of the records, scrolling with them.
        rule.onNodeWithText("Face or fingerprint changes and resets can’t be cleared. Each is kept until you’ve seen it, and for 7 days after it happened.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aTimedPauseSaysItEndsByItselfOrWhenResumed() {
        val now = System.currentTimeMillis()
        val until = now + 15 * 60_000L
        assertEquals(
            "Locked apps open without a check until ${pauseEndTime(context, until)}, or until you resume",
            pausedBody(context, until, now)
        )
        assertEquals("Locked apps open without a check until you resume", pausedBody(context, 0L, now))
        // Ended already (not yet noticed): as an untimed one.
        assertEquals("Locked apps open without a check until you resume", pausedBody(context, now - 1L, now))
    }

    @Test
    fun thePauseTileSaysUntilWhenAndThatATapResumes() {
        val now = System.currentTimeMillis()
        val until = now + 60 * 60_000L
        assertEquals("Paused until ${pauseEndTime(context, until)} · tap to resume", BioKeyTileService.subtitle(context, Status.PAUSED, until, locked = false, now = now))
        assertEquals("Paused · tap to resume", BioKeyTileService.subtitle(context, Status.PAUSED, 0L, locked = false, now = now))
        assertEquals("On", BioKeyTileService.subtitle(context, Status.PROTECTING, 0L, locked = false, now = now))
        // Over the lock screen, nothing under its name: on, paused and off look the same there.
        assertEquals(null, BioKeyTileService.subtitle(context, Status.PROTECTING, 0L, locked = true, now = now))
        assertEquals(null, BioKeyTileService.subtitle(context, Status.PAUSED, until, locked = true, now = now))
        assertEquals(null, BioKeyTileService.subtitle(context, Status.NOT_PROTECTING, 0L, locked = true, now = now))
        // Setup missing still says so, so a tap can finish it.
        assertEquals(context.getString(R.string.tile_setup_required), BioKeyTileService.subtitle(context, Status.SETUP_REQUIRED, 0L, locked = true, now = now))
        assertEquals("Protection", context.getString(R.string.tile_label))
    }

    @Test
    fun bothTilesSayOffOrNotRunningAsHomeDoes() {
        val now = System.currentTimeMillis()
        assertEquals("Off", BioKeyTileService.subtitle(context, Status.NOT_PROTECTING, 0L, locked = false, now = now))
        assertEquals("Not running", BioKeyTileService.subtitle(context, Status.NOT_PROTECTING, 0L, locked = false, now = now, notRunning = true))
    }

    @Test
    fun aHoldIsOneSentenceAndWhatWaitsIsFoldedUnderTheRecords() {
        val now = System.currentTimeMillis()
        val until = now + 24 * 60 * 60_000L
        val gaps = listOf(ProtectionGaps.Gap(now, now, ProtectionGaps.Kind.BIOMETRICS_CHANGED, 1L))
        rule.setContent {
            BioKeyTheme { SecurityActivityDialog(attempts = emptyList(), gaps = gaps, labelOf = { it.label }, onClear = {}, onDismiss = {}, heldUntil = until) }
        }
        rule.onNodeWithText("A face or fingerprint was added or removed, so some changes wait until", substring = true).assertExists()
        // Folded: the list isn't in the way, but one tap shows all of it.
        rule.onAllNodesWithText("Restoring a backup").fetchSemanticsNodes().let { assertEquals(0, it.size) }
        rule.onNodeWithText("What waits a day").performScrollTo().performClick()
        listOf(
            "Marking records as reviewed or clearing them",
            "Switching off “Failed unlock alerts”, “Also record screen lock unlocks” or “Diagnostic log”",
            "Deleting the diagnostic log",
            "Restoring a backup",
            "Anything else you change meanwhile is recorded"
        ).forEach { rule.onNodeWithText(it).assertExists() }
    }

    @Test
    fun theHeaderIsCompactOnlyInShortWindows() {
        // Phones held upright (even small ones) keep the full header.
        assertFalse(isShortWindow(640f))
        assertFalse(isShortWindow(480f))
        // Landscape phones and half of a split screen.
        assertTrue(isShortWindow(411f))
        assertTrue(isShortWindow(360f))
    }
}
