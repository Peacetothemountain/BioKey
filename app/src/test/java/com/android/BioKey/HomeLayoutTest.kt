package com.android.BioKey

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.AppItem
import com.android.BioKey.feature.main.CHIPS_TAG
import com.android.BioKey.feature.main.CONTROLS_KEY
import com.android.BioKey.feature.main.HomeColumnWidth
import com.android.BioKey.feature.main.homeSidePad
import com.android.BioKey.feature.main.lastAutoSortLocked
import com.android.BioKey.feature.main.HomeViewModel
import com.android.BioKey.feature.main.lastOfferShowing
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import com.android.BioKey.feature.main.InstalledAppsCache
import com.android.BioKey.feature.main.MainScreenExpressive
import com.android.BioKey.feature.main.PROFILE_CARD_TAG
import com.android.BioKey.feature.main.pinsControls
import com.android.BioKey.ui.theme.BioKeyTheme
import org.junit.After
import androidx.compose.ui.test.performScrollToKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home's search bar and profile chips, pinned at the top of the list: nothing scrolling under them
 * shows through or over them, a chip opens its page at the top, and in a short window they
 * aren't pinned at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class HomeLayoutTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var surface = Color.Unspecified

    private fun pkg(label: String) = "com.example.${label.lowercase()}"

    /** Enough apps in Finance for its page to scroll well past its card. */
    private val finance = (1..14).map { "Bank$it" }

    @Before
    fun seed() {
        android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        InstalledAppsCache.apps = (finance + listOf("Signal", "Notes", "Maps")).map { AppItem(pkg(it), it, AppCategory.GENERAL) }
        InstalledAppsCache.version = 0
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        val vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        finance.forEach { vault.moveAppToProfile(pkg(it), "profile_finance") }
        LockStateTracker.resetForTests()
        ProtectionEngine.overrideModeForTests = ProtectionEngine.Mode.ACCESSIBILITY
    }

    @After
    fun tearDown() {
        ProtectionEngine.overrideModeForTests = null
    }

    private fun show(vm: HomeViewModel? = null) {
        rule.setContent {
            BioKeyTheme {
                surface = MaterialTheme.colorScheme.surface
                if (vm != null) MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}, homeViewModel = vm)
                else MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {})
            }
        }
        // A status bar over the top of the list, as on a phone: the controls pinned there must clear it.
        rule.runOnUiThread {
            val view = rule.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            view.dispatchApplyWindowInsets(
                android.view.WindowInsets.Builder()
                    .setInsets(android.view.WindowInsets.Type.statusBars(), android.graphics.Insets.of(0, statusBar, 0, 0))
                    .build()
            )
        }
        rule.waitForIdle()
    }

    private val statusBar get() = (24 * context.resources.displayMetrics.density).toInt()

    /** The search field's top, in the root's px. */
    private fun searchTop() = rule.onNode(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNode().boundsInRoot.top

    private fun chooseFinance() {
        rule.onNodeWithContentDescription("Finance profile, 14 apps").performClick()
        rule.waitForIdle()
    }

    private val px get() = context.resources.displayMetrics.density

    /** Home's list: the one thing that scrolls up and down (the chips scroll across). */
    private val homeList = androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)

    /**
     * A chip keeps the list's scroll: the search bar and chips stay exactly where they were, on
     * the new page and back on All apps, and nothing moves them after. Scrolled down to [row]
     * first (null: at the top), the new page's card is right under the pinned controls.
     */
    private fun assertKeepsTheScroll(chip: String, row: String?) {
        show()
        if (row != null) {
            rule.onNode(homeList).performScrollToKey(pkg(row))
            rule.waitForIdle()
        }
        val searchBefore = searchTop()
        rule.onNodeWithContentDescription(chip).performClick()
        rule.waitForIdle()
        assertEquals(searchBefore, searchTop(), 1f)
        val controls = rule.onNodeWithTag(CONTROLS_KEY).getBoundsInRoot()
        val card = rule.onNodeWithTag(PROFILE_CARD_TAG).getBoundsInRoot()
        assertTrue("card at ${card.top}, controls end at ${controls.bottom}", card.top >= controls.bottom - 1.dp)
        if (row != null) assertTrue("card at ${card.top}, controls end at ${controls.bottom}", card.top <= controls.bottom + 24.dp)
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals(searchBefore, searchTop(), 1f)
        rule.onNode(androidx.compose.ui.test.hasText("All apps") and androidx.compose.ui.test.hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(searchBefore, searchTop(), 1f)
    }

    @Test
    fun atTheTopAChipKeepsThePageWhereItWas() = assertKeepsTheScroll("Finance profile, 14 apps", row = null)

    @Test
    fun scrolledDownAChipKeepsTheControlsPinnedWithTheNewPageUnderThem() = assertKeepsTheScroll("Finance profile, 14 apps", row = "Signal")

    @Test
    fun aShortProfileKeepsTheScrollToo() {
        // Two apps only: the page is shorter than the window.
        val vault = SecurityVault.getInstance(context)
        finance.drop(2).forEach { vault.removeAppFromProfile(pkg(it)) }
        assertKeepsTheScroll("Finance profile, 2 apps", row = null)
    }

    @Test
    fun scrolledDownAShortProfileStillStartsRightUnderThePinnedControls() {
        val vault = SecurityVault.getInstance(context)
        finance.drop(2).forEach { vault.removeAppFromProfile(pkg(it)) }
        assertKeepsTheScroll("Finance profile, 2 apps", row = "Signal")
    }

    @Test
    fun whatScrollsUnderThePinnedControlsNeverShowsOverThem() {
        show()
        chooseFinance()
        // The page opens at its top: past its header first, then the card, and the rows after it,
        // pass under the controls.
        rule.onNode(homeList).performScrollToKey(CONTROLS_KEY)
        rule.waitForIdle()
        // Held mid-drag: let go, the card settles wholly in sight or wholly under them.
        rule.onNode(homeList).performTouchInput {
            down(androidx.compose.ui.geometry.Offset(centerX, centerY + 300f))
            repeat(6) { moveBy(androidx.compose.ui.geometry.Offset(0f, -40f)) }
        }
        rule.waitForIdle()
        val controls = rule.onNodeWithTag(CONTROLS_KEY).getBoundsInRoot()
        val card = rule.onNodeWithTag(PROFILE_CARD_TAG).getBoundsInRoot()
        assertTrue("the card should be partly under the controls: card ${card.top}..${card.bottom}, controls end ${controls.bottom}",
            card.top < controls.bottom && card.bottom > controls.bottom)
        // Along the controls' lowest band, in the middle of the window (over the card), the page's own colour, not the card's.
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val y = ((controls.bottom.value - 2f) * px).toInt()
        val x = image.width / 2
        val seen = image.getPixel(x, y)
        assertEquals("pixel at ($x, $y)", surface.toArgb(), seen)
        // Behind the status bar too: the page's colour, not the card passing under.
        assertEquals("pixel at ($x, ${statusBar / 2})", surface.toArgb(), image.getPixel(x, statusBar / 2))
        assertTrue("search at ${searchTop()}", searchTop() >= statusBar)
        rule.onNode(homeList).performTouchInput { up() }
    }

    @Test
    fun inAShortWindowTheControlsScrollAwayWithTheList() {
        RuntimeEnvironment.setQualifiers("w915dp-h412dp-land")
        show()
        rule.onNode(homeList).performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f, durationMillis = 800) }
        rule.waitForIdle()
        rule.onNode(homeList).performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f, durationMillis = 800) }
        rule.waitForIdle()
        val controls = rule.onAllNodesWithTagOrNull(CONTROLS_KEY)
        // Gone off the top (or out of the list altogether), not held there.
        assertTrue("controls at ${controls?.top}", controls == null || controls.bottom.value <= 0f)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagOrNull(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.let {
            androidx.compose.ui.unit.DpRect(
                (it.left / px).dp, (it.top / px).dp, (it.right / px).dp, (it.bottom / px).dp
            )
        }

    @Test
    fun theControlsPinOnlyInWindowsThatArentShort() {
        assertTrue(pinsControls(915f))
        assertTrue(pinsControls(480f))
        assertFalse(pinsControls(412f))
        assertFalse(pinsControls(400f))
    }

    /** Home's column on a window [width] wide: where the search bar and chips must stay. */
    private fun column(width: Float) = homeSidePad(width.dp).value.let { it..(width - it) }

    @Test
    fun onAnUnfoldedFoldableTheListKeepsAReadableColumnAndTheChipsStayInIt() {
        // An unfolded Pixel 9 Pro Fold's width: only a little wider than the old 720dp cap, which
        // left its rows nearly full width and the last chip past the list's edge.
        RuntimeEnvironment.setQualifiers("w790dp-h860dp")
        // Enough profiles that the chips can't fit one line of the column.
        val vault = SecurityVault.getInstance(context)
        listOf("Travel", "Family", "Health", "Shopping").forEach { vault.createProfile(it, "work") }
        show()
        val edges = column(790f)
        assertTrue("a column narrower than the window: $edges", edges.endInclusive - edges.start <= HomeColumnWidth.value + 0.5f)
        val search = rule.onNode(androidx.compose.ui.test.hasSetTextAction()).getBoundsInRoot()
        val chips = rule.onNodeWithTag(CHIPS_TAG).getBoundsInRoot()
        // Search bar and chips within the column (inset alike), nothing past its edges.
        listOf("search" to search, "chips" to chips).forEach { (name, it) ->
            assertTrue("$name ${it.left}..${it.right} outside $edges", it.left.value >= edges.start - 0.5f && it.right.value <= edges.endInclusive + 0.5f)
        }
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyAncestor(hasTestTag(CHIPS_TAG)))
            .fetchSemanticsNodes().also { assertTrue(it.size >= 8) }
            .forEach {
                val left = it.boundsInRoot.left / px
                val right = it.boundsInRoot.right / px
                assertTrue("a chip at $left..$right sticks out of $edges", left >= edges.start - 0.5f && right <= edges.endInclusive + 0.5f)
            }
        // The first chip lined up with the search bar.
        val firstChip = rule.onAllNodes(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyAncestor(hasTestTag(CHIPS_TAG)))
            .fetchSemanticsNodes().minOf { it.boundsInRoot.left } / px
        assertEquals(search.left.value, firstChip, 1f)
    }

    @Test
    fun onAPhoneTheListUsesTheWholeWidth() {
        show()
        val search = rule.onNode(androidx.compose.ui.test.hasSetTextAction()).getBoundsInRoot()
        assertEquals(0f, homeSidePad(412.dp).value, 0f)
        assertEquals(16f, search.left.value, 1f)
        assertEquals(412f - 16f, search.right.value, 1f)
    }

    @Test
    fun theAutoSortCardOutlastsLeavingHomeAndGoesOnGotIt() {
        // Home's first look after setup, its auto-sort pass having locked 2 apps (as
        // AutoSortFirstPassTest has it): the note kept, and home's own view model reading it.
        context.getSharedPreferences("biokey_home", Context.MODE_PRIVATE).edit().clear().putInt("auto_sort_note", 2).commit()
        lastAutoSortLocked = 2
        val vm = HomeViewModel(context as android.app.Application)
        lastOfferShowing = null
        show(vm)
        val title = context.resources.getQuantityString(R.plurals.home_auto_sort_title, 2, 2)
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText(title)).fetchSemanticsNodes().isNotEmpty() }
        // Home left (a locked app tried) and come back to: still there.
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNodeWithText(title).assertExists()
        assertEquals(2, vm.state.value.autoSortLocked)
        assertEquals(2, context.getSharedPreferences("biokey_home", Context.MODE_PRIVATE).getInt("auto_sort_note", 0))
        // "Got it" puts it away, for good.
        rule.onNodeWithText(context.getString(R.string.home_auto_sort_got_it)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(title).assertDoesNotExist()
        assertFalse(context.getSharedPreferences("biokey_home", Context.MODE_PRIVATE).contains("auto_sort_note"))
    }
}
