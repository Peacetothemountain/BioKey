package com.android.BioKey

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.feature.main.AppItem
import com.android.BioKey.feature.main.AttemptsIntroCard
import com.android.BioKey.feature.main.SecurityActivityCard
import com.android.BioKey.feature.main.UnlockedNowCard
import com.android.BioKey.ui.theme.BioKeyTheme
import org.junit.Assert.assertEquals
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
        rule.onNodeWithText("Choose").performClick()
        rule.onNodeWithText("Lock all").performClick()
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
}
