package com.android.BioKey

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.ProtectionGaps
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.feature.lock.BioKeyLockScreen
import com.android.BioKey.feature.main.AppIconCache
import com.android.BioKey.feature.main.AppItem
import com.android.BioKey.feature.main.InstalledAppsCache
import com.android.BioKey.feature.main.MainScreenExpressive
import com.android.BioKey.feature.main.SettingsScreenExpressive
import com.android.BioKey.feature.setup.SetupScreenExpressive
import com.android.BioKey.ui.theme.BioKeyTheme
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders BioKey's screens to PNGs (app/build/screenshots) for design review, without a device.
 * Run: ./gradlew :app:testDebugUnitTest -Pbiokey.screenshots=true --tests "*DesignScreenshotTest*"
 * Without -Pbiokey.screenshots=true, test runs leave it out (testOptions in app/build.gradle.kts).
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class DesignScreenshotTest {

    private val samples = listOf(
        "Chase" to 0xFF1A73E8, "Signal" to 0xFF3A76F0, "Photos" to 0xFFFBBC04, "Gmail" to 0xFFEA4335,
        "Authenticator" to 0xFF5F6368, "Instagram" to 0xFFE1306C, "Maps" to 0xFF34A853, "Slack" to 0xFF4A154B,
        "Venmo" to 0xFF008CFF, "Notes" to 0xFFF9AB00, "YouTube" to 0xFFFF0000, "Calendar" to 0xFF4285F4
    )

    @Before
    fun seed() {
        // Decorative loops hold still with animations off, so frames can settle for capture.
        android.provider.Settings.Global.putFloat(
            ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Icons are drawn per size: seed this screen's, and the tablet test's (mdpi).
        val iconSizes = setOf((40 * context.resources.displayMetrics.density).roundToInt(), 40)
        InstalledAppsCache.apps = samples.map { (label, color) ->
            AppItem(
                packageName = "com.example.${label.lowercase()}",
                label = label,
                category = AppCategory.GENERAL
            ).also { app ->
                iconSizes.forEach { px -> AppIconCache.put(AppIconCache.key(app, px), icon(label, color.toInt(), px).asImageBitmap()) }
            }
        }.sortedBy { it.label }
        InstalledAppsCache.version = 0
        // A vault of its own, whatever an earlier test class left: what's shown mustn't depend on test order.
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        val vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        listOf("chase", "signal", "photos", "authenticator", "venmo").forEach { vault.setAppLock("com.example.$it", true) }
        vault.moveAppToProfile("com.example.chase", "profile_finance")
        vault.moveAppToProfile("com.example.venmo", "profile_finance")
        vault.moveAppToProfile("com.example.signal", "profile_social")
        LockStateTracker.resetForTests()
        // Asked and answered, so the one-time question isn't on every screen; mainIntro shows it.
        context.getSharedPreferences("biokey_unlock_attempts", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        UnlockAttempts.resetForTests()
        UnlockAttempts.setEnabled(context, true)
        context.getSharedPreferences("biokey_protection_gaps", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        ProtectionGaps.resetForTests()
        ProtectionEngine.overrideModeForTests = ProtectionEngine.Mode.ACCESSIBILITY
        // The screens as the Play build shows them: no self-protection.
        SecurityVault.selfProtectionRemovedForTests = true
        // Home's calm offer is kept for the process: each picture starts without one showing.
        com.android.BioKey.feature.main.lastOfferShowing = null
    }

    @After
    fun tearDown() {
        ProtectionEngine.overrideModeForTests = null
        SecurityVault.selfProtectionRemovedForTests = false
        TestClocks.restore()
        UnlockAttempts.resetForTests()
        ProtectionGaps.resetForTests()
    }

    private fun icon(label: String, color: Int, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = android.graphics.Color.WHITE
        p.textSize = size * 0.45f
        p.textAlign = Paint.Align.CENTER
        c.drawText(label.take(1), size / 2f, size / 2f + p.textSize / 3f, p)
        return bmp
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        listOf("light" to "", "dark" to "+night").forEach { (mode, qualifier) ->
            RuntimeEnvironment.setQualifiers(qualifier.ifEmpty { "+notnight" })
            captureRoboImage("build/screenshots/${name}_$mode.png") { BioKeyTheme(content) }
        }
    }

    @Test fun main() = shoot("main") { MainScreenExpressive(mode = com.android.BioKey.core.security.ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}) }
    @Test fun mainOff() = shoot("main_off") { MainScreenExpressive(mode = com.android.BioKey.core.security.ProtectionEngine.Mode.NEEDS_COMPATIBILITY_SETUP, onPauseRequested = {}) }
    @Test fun settings() = shoot("settings") { SettingsScreenExpressive(mode = com.android.BioKey.core.security.ProtectionEngine.Mode.ACCESSIBILITY, onNavigateBack = {}, onPauseRequested = {}) }
    /** Tablets and landscape: lists keep a readable width, lock screens stay centred. */
    @Test fun tablet() {
        RuntimeEnvironment.setQualifiers("w900dp-h1280dp")
        shoot("main_tablet") { MainScreenExpressive(mode = com.android.BioKey.core.security.ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}) }
    }

    /** A wide tablet with Settings open: a pane on the right, home beside it on the left. */
    @Test fun tabletSettingsPane() {
        RuntimeEnvironment.setQualifiers("w1280dp-h800dp")
        shoot("main_tablet_settings") {
            MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}, settingsOpenAtStart = true)
        }
    }

    /** The cover in landscape at 200% text, with a message: the icon, name and message stay clear of Unlock. */
    @Test fun coverLandLargeText() {
        RuntimeEnvironment.setQualifiers("w915dp-h412dp-land")
        RuntimeEnvironment.setFontScale(2f)
        try {
            shoot("cover_land_large_text", cover(actions = true, status = context.getString(R.string.cover_prompt_failed)))
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test fun landscape() {
        RuntimeEnvironment.setQualifiers("w915dp-h412dp-land")
        shoot("cover_land", cover(actions = true))
        RuntimeEnvironment.setQualifiers("w915dp-h412dp-land")
        shoot("lock_land") {
            BioKeyLockScreen(onUnlockClicked = {}, onCancelClicked = {})
        }
    }

    /** Home in landscape, and in half of a split screen: the header a row high, the list below it. */
    @Test fun mainShortWindow() {
        RuntimeEnvironment.setQualifiers("w915dp-h412dp-land")
        shoot("main_land") { MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}) }
        RuntimeEnvironment.setQualifiers("w412dp-h400dp-port")
        shoot("main_split") { MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}) }
    }

    /** The whole Settings page, on a tall screen. */
    @Test fun settingsFull() {
        RuntimeEnvironment.setQualifiers("w412dp-h2400dp")
        shoot("settings_full") { SettingsScreenExpressive(mode = com.android.BioKey.core.security.ProtectionEngine.Mode.ACCESSIBILITY, onNavigateBack = {}, onPauseRequested = {}) }
    }
    @Test fun message() {
        // As after switching Chase's lock off: the list agrees with the message.
        SecurityVault.getInstance(context).setAppLock("com.example.chase", false)
        shootMessage()
    }

    private fun shootMessage() = shoot("message") {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            MainScreenExpressive(mode = com.android.BioKey.core.security.ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {})
            val data = object : androidx.compose.material3.SnackbarData {
                override val visuals = com.android.BioKey.ui.theme.BioKeyMessages.Message(
                    "Lock removed from Chase", androidx.compose.material.icons.Icons.Default.LockOpen, "Undo", null
                )
                override fun performAction() {}
                override fun dismiss() {}
            }
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) {
                com.android.BioKey.ui.theme.MessageBar(data)
            }
        }
    }
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun unlockAndLeave(pkg: String) {
        LockStateTracker.onAppUnlocked(pkg)
        LockStateTracker.onAppVisible(pkg)
        LockStateTracker.onAppLeft(pkg, LockTimeoutMode.GRACE_5_MINUTES, hopTarget = null)
    }

    /** Someone's three tries on Venmo at 2:14 PM today, that didn't match. */
    private fun venmoAttempt() {
        val today = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 14); set(java.util.Calendar.MINUTE, 14) }.timeInMillis
        UnlockAttempts.clock = { today }
        repeat(3) { UnlockAttempts.onBiometricFailed(context, "com.example.venmo", "Venmo") }
    }

    /**
     * Home while things are happening: Chase unlocked a moment ago and left (re-locks after 5
     * minutes), and someone's three tries on Venmo that didn't match.
     */
    @Test fun mainActive() {
        SecurityVault.getInstance(context).setTimeoutMode(LockTimeoutMode.GRACE_5_MINUTES)
        unlockAndLeave("com.example.chase")
        venmoAttempt()
        shoot("main_active") { MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}) }
    }

    /** Two apps unlocked, app locking switched off for a while, and large text (200%). */
    @Test fun mainActiveLargeText() {
        SecurityVault.getInstance(context).setTimeoutMode(LockTimeoutMode.GRACE_5_MINUTES)
        unlockAndLeave("com.example.chase")
        unlockAndLeave("com.example.signal")
        val today = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 15); set(java.util.Calendar.MINUTE, 2) }.timeInMillis
        ProtectionGaps.clock = { today }
        ProtectionGaps.elapsed = { 1_000_000L }
        ProtectionGaps.bootCount = { 1 }
        ProtectionGaps.exits = { emptyList() }
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        ProtectionGaps.onEngineUp(context)
        ProtectionGaps.onEngineDown(context)
        ProtectionGaps.clock = { today + 8 * 60_000L }
        ProtectionGaps.elapsed = { 1_000_000L + 8 * 60_000L }
        ProtectionGaps.onEngineUp(context)
        RuntimeEnvironment.setFontScale(2f)
        shoot("main_active_large_text") { MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}) }
        RuntimeEnvironment.setFontScale(1f)
    }

    /** The one-time question about failed unlock alerts. */
    @Test fun mainIntro() {
        context.getSharedPreferences("biokey_unlock_attempts", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        UnlockAttempts.resetForTests()
        UnlockAttempts.load(context)
        UnlockAttempts.hasStrongBiometric = { true }
        shoot("main_intro") { MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}) }
    }

    /** App locking switched off in Accessibility: the one urgent card, and a way to stop the reminders. */
    @Test fun mainSwitchedOff() {
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        shoot("main_switched_off") { MainScreenExpressive(mode = ProtectionEngine.Mode.OFF, onPauseRequested = {}) }
    }

    /** Switched off, at 200% text: the card's two buttons wrap instead of squeezing. */
    @Test fun mainSwitchedOffLargeText() {
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        RuntimeEnvironment.setFontScale(2f)
        shoot("main_switched_off_large_text") { MainScreenExpressive(mode = ProtectionEngine.Mode.OFF, onPauseRequested = {}) }
        RuntimeEnvironment.setFontScale(1f)
    }

    private val reviewAt get() = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 15); set(java.util.Calendar.MINUTE, 2) }.timeInMillis

    /** Review during a hold: a fingerprint change, a change made since, and Close as the only way out. */
    @Test fun review() {
        val at = reviewAt
        val gaps = listOf(
            ProtectionGaps.Gap(at + 60_000L, at + 60_000L, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, 4L, ProtectionGaps.Change.PROFILE_OFF.encodeDuringHold("Finance")),
            ProtectionGaps.Gap(at, at, ProtectionGaps.Kind.BIOMETRICS_CHANGED, 3L),
            ProtectionGaps.Gap(at - 60_000L, at - 60_000L, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, 2L, ProtectionGaps.Change.LOCK_OFF.encode("Chase"))
        )
        shoot("review") {
            com.android.BioKey.feature.main.SecurityActivityDialog(
                attempts = emptyList(), gaps = gaps, labelOf = { it.label },
                onClear = {}, onDismiss = {}, onMarkReviewed = null, heldUntil = at + 24 * 60 * 60_000L
            )
        }
    }

    /** Review after a reset: the reset as a time locking was off, a change made since, and the hold naming it. */
    @Test fun reviewAfterReset() {
        val at = reviewAt
        val gaps = listOf(
            ProtectionGaps.Gap(at + 60_000L, at + 60_000L, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, 4L, ProtectionGaps.Change.LOCK_OFF.encodeDuringHold("Chase", byReset = true)),
            ProtectionGaps.Gap(at - 40 * 60_000L, at, ProtectionGaps.Kind.DATA_CLEARED, 3L)
        )
        shoot("review_reset") {
            com.android.BioKey.feature.main.SecurityActivityDialog(
                attempts = emptyList(), gaps = gaps, labelOf = { it.label },
                onClear = {}, onDismiss = {}, onMarkReviewed = null, heldUntil = at + 24 * 60 * 60_000L, heldByReset = true
            )
        }
    }

    /** The whole Settings page at 200% text: no word breaks in the middle, no time splits. */
    @Test fun settingsFullLargeText() {
        RuntimeEnvironment.setQualifiers("w412dp-h4800dp")
        RuntimeEnvironment.setFontScale(2f)
        try {
            shoot("settings_full_large_text") { SettingsScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onNavigateBack = {}, onPauseRequested = {}) }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    /** Review with every kind of change: the groups' headings said once, one hint each, the note scrolling with them. */
    @Test fun reviewGrouped() {
        val at = reviewAt
        val gaps = listOf(
            ProtectionGaps.Gap(at + 120_000L, at + 120_000L, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, 6L, ProtectionGaps.Change.PROFILE_OFF.encodeDuringHold("Finance")),
            ProtectionGaps.Gap(at + 60_000L, at + 60_000L, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, 5L, ProtectionGaps.Change.LOCK_OFF.encodeDuringHold("Chase")),
            ProtectionGaps.Gap(at, at, ProtectionGaps.Kind.BIOMETRICS_CHANGED, 4L),
            ProtectionGaps.Gap(at - 30_000L, at - 30_000L, ProtectionGaps.Kind.BIOMETRICS_REMOVED, 3L),
            ProtectionGaps.Gap(at - 60_000L, at - 60_000L, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, 2L, ProtectionGaps.Change.LOCK_OFF.encode("Venmo")),
            ProtectionGaps.Gap(at - 20 * 60_000L, at - 12 * 60_000L, ProtectionGaps.Kind.SWITCHED_OFF, 1L)
        )
        RuntimeEnvironment.setQualifiers("w412dp-h2400dp")
        shoot("review_grouped") {
            com.android.BioKey.feature.main.SecurityActivityDialog(
                attempts = emptyList(), gaps = gaps, labelOf = { it.label },
                onClear = {}, onDismiss = {}, onMarkReviewed = null, heldUntil = at + 24 * 60 * 60_000L
            )
        }
    }

    /** The status sheet while notifications show in full, in compatibility mode with an app it can't see. */
    @Test fun sheetNotificationsShowing() = shoot("sheet_notifications_showing") {
        androidx.compose.material3.Surface(
            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = androidx.compose.ui.Modifier.padding(top = 48.dp)
        ) {
            com.android.BioKey.feature.main.ProtectionSheetContent(
                look = com.android.BioKey.feature.main.protectionLook(ProtectionEngine.Mode.COMPATIBILITY, true, 0L, 5, 1, LockTimeoutMode.IMMEDIATE),
                onAction = {}, onPause = {}, onLockAll = {}
            )
        }
    }

    /** What locking a gallery does, the first time: notifications, widgets, access, and the other apps that see photos. */
    @Test fun explainLock() = shoot("explain_lock") {
        val gallery = com.android.BioKey.feature.main.AppItem("com.example.gallery", "Gallery", com.android.BioKey.core.data.AppCategory.PRIVACY)
        val others = listOf("Camera", "Drive", "Editor", "Scanner").map {
            com.android.BioKey.feature.main.AppItem("com.example.${it.lowercase()}", it, com.android.BioKey.core.data.AppCategory.GENERAL)
        }
        androidx.compose.material3.Surface(
            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = androidx.compose.ui.Modifier.padding(top = 48.dp)
        ) {
            com.android.BioKey.feature.main.LockExplainContent(
                app = gallery,
                explanation = com.android.BioKey.feature.main.LockExplainer.Explanation(
                    gallery.packageName, "Use your fingerprint or screen lock to continue", hasWidgets = true,
                    dataKind = com.android.BioKey.feature.main.LockExplainer.DataKind.PHOTOS, otherApps = others
                ),
                justLocked = true, onDontShowAgain = {}, onDone = {}
            )
        }
    }

    /** Review opened with the screen lock, no hold: Mark reviewed, and Clear. */
    @Test fun reviewScreenLock() {
        val at = reviewAt
        val gaps = listOf(
            ProtectionGaps.Gap(at - 60_000L, at - 60_000L, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, 2L, ProtectionGaps.Change.LOCK_OFF.encode("Chase")),
            ProtectionGaps.Gap(at - 20 * 60_000L, at - 12 * 60_000L, ProtectionGaps.Kind.SWITCHED_OFF, 1L)
        )
        shoot("review_screen_lock") {
            com.android.BioKey.feature.main.SecurityActivityDialog(
                attempts = emptyList(), gaps = gaps, labelOf = { it.label },
                onClear = {}, onDismiss = {}, onMarkReviewed = {}
            )
        }
    }

    /** Advanced Protection's card at 200%: its long button wraps instead of being clipped. */
    @Test fun mainOffLargeText() {
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        RuntimeEnvironment.setFontScale(2f)
        shoot("main_off_large_text") { MainScreenExpressive(mode = ProtectionEngine.Mode.NEEDS_COMPATIBILITY_SETUP, onPauseRequested = {}) }
        RuntimeEnvironment.setFontScale(1f)
    }

    /** Face or fingerprint only on, Settings not locked: the calm suggestion, with Not now. */
    @Test fun mainLockSettings() {
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        SecurityVault.getInstance(context).setBiometricOnly(true)
        UnlockAttempts.hasStrongBiometric = { true }
        UnlockAttempts.setEnabled(context, true)
        shoot("main_lock_settings") { MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}) }
    }

    private fun home() = @Composable { MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}) }

    /** Home with one card: three tries on Venmo nobody has looked at yet. */
    @Test fun mainOneCard() {
        venmoAttempt()
        shoot("main_cards_1", home())
    }

    /** Home with two: the record, and the one calm offer (locking Android Settings); failed unlock alerts wait their turn. */
    @Test fun mainTwoCards() {
        venmoAttempt()
        offerLockSettings()
        shoot("main_cards_2", home())
    }

    /** Home with three: the record, Chase unlocked, and the one calm offer. */
    @Test fun mainThreeCards() {
        SecurityVault.getInstance(context).setTimeoutMode(LockTimeoutMode.GRACE_5_MINUTES)
        unlockAndLeave("com.example.chase")
        venmoAttempt()
        offerLockSettings()
        shoot("main_cards_3", home())
    }

    /** The same three at 200% text. */
    @Test fun mainThreeCardsLargeText() {
        SecurityVault.getInstance(context).setTimeoutMode(LockTimeoutMode.GRACE_5_MINUTES)
        unlockAndLeave("com.example.chase")
        venmoAttempt()
        offerLockSettings()
        RuntimeEnvironment.setFontScale(2f)
        try {
            shoot("main_cards_3_large_text", home())
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    /** Three cards in landscape: the header a row high, the controls not pinned. */
    @Test fun mainThreeCardsLandscape() {
        SecurityVault.getInstance(context).setTimeoutMode(LockTimeoutMode.GRACE_5_MINUTES)
        unlockAndLeave("com.example.chase")
        venmoAttempt()
        offerLockSettings()
        RuntimeEnvironment.setQualifiers("w915dp-h412dp-land")
        shoot("main_cards_3_land", home())
    }

    private fun offerLockSettings() {
        SecurityVault.getInstance(context).setBiometricOnly(true)
        UnlockAttempts.hasStrongBiometric = { true }
    }

    /**
     * Interactions first, then a picture of the whole window, light and dark: [act] taps and
     * scrolls what [content] shows.
     */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    private fun shootAfter(name: String, content: @Composable () -> Unit, act: androidx.compose.ui.test.ComposeUiTest.() -> Unit) {
        listOf("light" to "+notnight", "dark" to "+night").forEach { (mode, qualifier) ->
            RuntimeEnvironment.setQualifiers(qualifier)
            androidx.compose.ui.test.runAndroidComposeUiTest<androidx.activity.ComponentActivity> {
                setContent { BioKeyTheme(content = content) }
                waitForIdle()
                act()
                waitForIdle()
                onRoot().captureRoboImage("build/screenshots/${name}_$mode.png")
            }
        }
    }

    /** Finance's page, chosen by its chip: it opens at its top, its card under the controls. */
    @Test fun profilePage() = shootAfter("profile_page", home()) {
        onNodeWithContentDescription("Finance profile, 2 apps").performClick()
    }

    /** Finance's page scrolled a little: its card passes under the pinned controls, never over them. */
    @Test fun profilePageScrolled() {
        val vault = SecurityVault.getInstance(context)
        listOf("gmail", "maps", "notes", "slack", "youtube", "calendar", "instagram").forEach { vault.moveAppToProfile("com.example.$it", "profile_finance") }
        shootAfter("profile_page_scrolled", home()) {
            onNodeWithContentDescription("Finance profile, 9 apps").performClick()
            waitForIdle()
            onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
                .performTouchInput { swipeUp(startY = centerY + 200f, endY = centerY - 100f, durationMillis = 800) }
        }
    }

    /** Review during a hold with what waits opened. */
    @Test fun reviewHoldOpened() {
        val at = reviewAt
        val gaps = listOf(
            ProtectionGaps.Gap(at + 60_000L, at + 60_000L, ProtectionGaps.Kind.CHANGED_WITH_SCREEN_LOCK, 4L, ProtectionGaps.Change.PROFILE_OFF.encodeDuringHold("Finance")),
            ProtectionGaps.Gap(at, at, ProtectionGaps.Kind.BIOMETRICS_CHANGED, 3L)
        )
        shootAfter("review_hold_opened", {
            com.android.BioKey.feature.main.SecurityActivityDialog(
                attempts = emptyList(), gaps = gaps, labelOf = { it.label },
                onClear = {}, onDismiss = {}, onMarkReviewed = null, heldUntil = at + 24 * 60 * 60_000L
            )
        }) {
            onNodeWithText("What waits a day").performScrollTo().performClick()
        }
    }

    @Test fun ownLock() = shoot("lock") {
        BioKeyLockScreen(onUnlockClicked = {}, onCancelClicked = {})
    }
    @Test fun setup() = shoot("setup") { SetupScreenExpressive(onSetupComplete = {}) }
    @Test fun setupAfterStorageCleared() = shoot("setup_cleared") {
        SetupScreenExpressive(cleared = ProtectionGaps.Cleared(System.currentTimeMillis() - 3_600_000L), onSetupComplete = {})
    }

    /** An unfolded foldable's width (Pixel 9 Pro Fold, about 790dp): setup's column, first run and after a reset. */
    private val fold = "w790dp-h860dp"

    @Test fun setupFold() {
        RuntimeEnvironment.setQualifiers(fold)
        shoot("setup_fold") { SetupScreenExpressive(onSetupComplete = {}) }
    }

    @Test fun setupAfterStorageClearedFold() {
        RuntimeEnvironment.setQualifiers(fold)
        shoot("setup_cleared_fold") {
            SetupScreenExpressive(cleared = ProtectionGaps.Cleared(System.currentTimeMillis() - 3_600_000L), onSetupComplete = {})
        }
    }

    /** At 200% text, first run and after a reset, on a phone (tall, to see the whole list). */
    @Test fun setupLargeText() {
        RuntimeEnvironment.setQualifiers("w412dp-h2400dp")
        RuntimeEnvironment.setFontScale(2f)
        try {
            shoot("setup_large_text") { SetupScreenExpressive(onSetupComplete = {}) }
            shoot("setup_cleared_large_text") {
                SetupScreenExpressive(cleared = ProtectionGaps.Cleared(System.currentTimeMillis() - 3_600_000L), onSetupComplete = {})
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    /** After a reset, "What waits a day" opened: the rest of the reset card, on a tall phone. */
    @Test fun setupAfterStorageClearedOpen() {
        RuntimeEnvironment.setQualifiers("w412dp-h1400dp")
        shootAfter("setup_cleared_open", {
            SetupScreenExpressive(cleared = ProtectionGaps.Cleared(System.currentTimeMillis() - 3_600_000L), onSetupComplete = {})
        }) {
            onNodeWithTag(com.android.BioKey.feature.setup.SETUP_RESET_MORE_TAG).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        }
    }

    /** The accessibility disclosure, whole (a very tall window): its sections, the nested list under Security activity. */
    @Test fun disclosureWhole() {
        RuntimeEnvironment.setQualifiers("w412dp-h3600dp")
        shoot("disclosure_whole") {
            val gate = com.android.BioKey.ui.disclosure.rememberDisclosureGate()
            androidx.compose.runtime.LaunchedEffect(Unit) { gate.request(com.android.BioKey.ui.disclosure.Disclosure.ACCESSIBILITY) {} }
        }
    }

    /** Setup with the restricted-settings help showing (back from Accessibility settings without it on): help, not a step. */
    @Test fun setupTall() {
        RuntimeEnvironment.setQualifiers("w412dp-h1400dp")
        shoot("setup_tall") { SetupScreenExpressive(needsScreenLock = true, onSetupComplete = {}) }
    }

    /** Home's first look after setup on an unfolded foldable: the auto-sort card, the list in a readable column, the chips inside it. */
    @Test fun mainFold() {
        context.getSharedPreferences("biokey_home", android.content.Context.MODE_PRIVATE).edit().clear().putInt("auto_sort_note", 5).commit()
        com.android.BioKey.feature.main.lastAutoSortLocked = 5
        val vault = SecurityVault.getInstance(context)
        listOf("Travel", "Family").forEach { vault.createProfile(it, "work") }
        RuntimeEnvironment.setQualifiers(fold)
        val vm = com.android.BioKey.feature.main.HomeViewModel(context as android.app.Application)
        shoot("main_fold") { MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}, homeViewModel = vm) }
    }

    /** Home's first look on a folded foldable's outer screen and narrower: auto-sort's note breaks well, "Got it" under it. */
    @Test fun mainFirstLookNarrow() {
        listOf("w360dp-h780dp" to 1f, "w320dp-h700dp" to 1f, "w360dp-h780dp" to 2f).forEach { (q, scale) ->
            context.getSharedPreferences("biokey_home", android.content.Context.MODE_PRIVATE).edit().clear().putInt("auto_sort_note", 9).commit()
            com.android.BioKey.feature.main.lastAutoSortLocked = 9
            com.android.BioKey.feature.main.lastOfferShowing = null
            RuntimeEnvironment.setQualifiers(q)
            RuntimeEnvironment.setFontScale(scale)
            val vm = com.android.BioKey.feature.main.HomeViewModel(context as android.app.Application)
            try {
                shoot("main_first_look_${q.substringBefore('-')}_fs${scale.toInt()}") {
                    MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}, homeViewModel = vm)
                }
            } finally {
                RuntimeEnvironment.setFontScale(1f)
            }
        }
    }

    /** BioKey's alerts off and a record that needs the owner: the red card leads, the row about alerts under it. */
    @Test fun mainAlertsOffWithUrgentRecord() {
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        val today = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 15); set(java.util.Calendar.MINUTE, 2) }.timeInMillis
        ProtectionGaps.clock = { today }
        ProtectionGaps.elapsed = { 1_000_000L }
        ProtectionGaps.bootCount = { 1 }
        ProtectionGaps.exits = { emptyList() }
        ProtectionGaps.onEngineUp(context)
        ProtectionGaps.onEngineDown(context)
        ProtectionGaps.clock = { today + 8 * 60_000L }
        ProtectionGaps.elapsed = { 1_000_000L + 8 * 60_000L }
        ProtectionGaps.onEngineUp(context)
        try {
            shoot("main_alerts_off_urgent", home())
        } finally {
            org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(true)
        }
    }

    /** A new profile's dialog: its icon isn't Personal's. */
    @Test fun newProfile() = shoot("new_profile") {
        com.android.BioKey.feature.main.ProfileDetailsDialog(existing = null, onDismiss = {}, onSave = { _, _ -> }, onDelete = null)
    }

    /** "Back up settings", as it opens: its icon whole above the title. */
    @Test fun backupDialog() = shoot("backup_dialog") {
        com.android.BioKey.feature.main.PasswordDialog(
            title = context.getString(R.string.backup_title), text = context.getString(R.string.backup_text),
            confirmLabel = context.getString(R.string.backup_choose_where),
            creating = true, busy = false, error = null, onConfirm = {}, onDismiss = {}
        )
    }

    /** The same at 200% text on a phone. */
    @Test fun mainFirstLookLargeText() {
        context.getSharedPreferences("biokey_home", android.content.Context.MODE_PRIVATE).edit().clear().putInt("auto_sort_note", 5).commit()
        com.android.BioKey.feature.main.lastAutoSortLocked = 5
        RuntimeEnvironment.setFontScale(2f)
        val vm = com.android.BioKey.feature.main.HomeViewModel(context as android.app.Application)
        try {
            shoot("main_first_look_large_text") { MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}, homeViewModel = vm) }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    /**
     * The passphrase's entry in landscape with the keyboard up (a window as short as the room the
     * keyboard leaves), on the cover and on BioKey's own lock: one row, the name, the field, its
     * line, Back and Unlock all in view; then with "Wrong passphrase", and at 200% text.
     */
    @Test fun passphraseLandscapeKeyboard() {
        val short = "w915dp-h150dp-land"
        RuntimeEnvironment.setQualifiers(short)
        shoot("cover_land_passphrase", cover(actions = true, passphrase = true))
        RuntimeEnvironment.setQualifiers(short)
        shoot("cover_land_passphrase_wrong", cover(actions = true, passphrase = true, wrong = true))
        RuntimeEnvironment.setQualifiers(short)
        RuntimeEnvironment.setFontScale(2f)
        try {
            shoot("cover_land_passphrase_fs2", cover(actions = true, passphrase = true, wrong = true))
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
        val gate = com.android.BioKey.feature.lock.PassphraseGateState().apply { offered = true; open = true }
        RuntimeEnvironment.setQualifiers(short)
        shootAfter("lock_land_passphrase_wrong", { BioKeyLockScreen(onUnlockClicked = {}, onCancelClicked = {}, passphrase = gate) }) {
            gate.error = context.getString(R.string.lock_passphrase_wrong)
        }
        // Upright on a phone, with room: as it always was.
        RuntimeEnvironment.setQualifiers("w412dp-h915dp-port")
        shoot("cover_passphrase", cover(actions = true, passphrase = true))
    }

    private fun cover(actions: Boolean, status: String? = null, passphrase: Boolean = false, wrong: Boolean = false) = @Composable {
        val resources = androidx.compose.ui.platform.LocalResources.current
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                com.android.BioKey.feature.lock.LockCoverView(it).apply {
                    val size = (88 * resources.displayMetrics.density).toInt()
                    bind("Chase", android.graphics.drawable.BitmapDrawable(resources, icon("Chase", 0xFF1A73E8.toInt(), size)), isBioKey = false)
                    setActionsVisible(actions)
                    if (status != null) setStatus(status, permanentLockout = false)
                    if (passphrase) {
                        setPassphraseOffered(true)
                        showPassphraseEntry(onSubmit = {}, onDismiss = {})
                        if (wrong) setPassphraseError(context.getString(R.string.lock_passphrase_wrong))
                    }
                }
            },
            modifier = androidx.compose.ui.Modifier.fillMaxSize()
        )
    }

    @Test fun coverPrompt() = shoot("cover_prompt", cover(actions = false))
    @Test fun coverActions() = shoot("cover_actions", cover(actions = true))
}
