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
        LockStateTracker.lockAll()
        // Asked and answered, so the one-time question isn't on every screen; mainIntro shows it.
        context.getSharedPreferences("biokey_unlock_attempts", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        UnlockAttempts.resetForTests()
        UnlockAttempts.setEnabled(context, true)
        context.getSharedPreferences("biokey_protection_gaps", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        ProtectionGaps.resetForTests()
        ProtectionEngine.overrideModeForTests = ProtectionEngine.Mode.ACCESSIBILITY
        // The screens as the Play build shows them: no self-protection.
        SecurityVault.selfProtectionRemovedForTests = true
    }

    @After
    fun tearDown() {
        ProtectionEngine.overrideModeForTests = null
        SecurityVault.selfProtectionRemovedForTests = false
        UnlockAttempts.clock = System::currentTimeMillis
        UnlockAttempts.hasStrongBiometric = com.android.BioKey.core.security.SecurityEngine::hasEnrolledStrongBiometric
        ProtectionGaps.clock = System::currentTimeMillis
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
                    "Lock removed from Chase", androidx.compose.material.icons.Icons.Default.LockOpen, "Undo", null,
                    androidx.compose.material3.SnackbarDuration.Short
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

    /** Hide notification content on, its access taken away: the calm card, with Turn off and Allow access. */
    @Test fun mainShieldNoAccess() {
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        SecurityVault.getInstance(context).setNotificationShield(true)
        com.android.BioKey.feature.notification.ShieldAccessWatch.hasAccess = { false }
        // As BioKey's start does: the home screen opens with what it found.
        com.android.BioKey.feature.notification.ShieldAccessWatch.check(context)
        try {
            shoot("main_shield_no_access") { MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}) }
        } finally {
            com.android.BioKey.feature.notification.ShieldAccessWatch.hasAccess =
                com.android.BioKey.feature.notification.BioKeyNotificationShieldService::isNotificationAccessGranted
            SecurityVault.getInstance(context).setNotificationShield(false)
        }
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

    /** The status sheet while notifications show in full, in compatibility mode with an app it can't see. */
    @Test fun sheetNotificationsShowing() = shoot("sheet_notifications_showing") {
        androidx.compose.material3.Surface(
            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = androidx.compose.ui.Modifier.padding(top = 48.dp)
        ) {
            com.android.BioKey.feature.main.ProtectionSheetContent(
                look = com.android.BioKey.feature.main.protectionLook(
                    ProtectionEngine.Mode.COMPATIBILITY, true, 0L, 5, 1, LockTimeoutMode.IMMEDIATE, notificationsShowing = true
                ),
                onAction = {}, onFix = {}, onTurnOffFix = {}, onPause = {}, onLockAll = {}
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
                    gallery.packageName, "Use your fingerprint or screen lock to continue",
                    com.android.BioKey.feature.main.LockExplainer.Notifications.SHIELD_OFF, hasWidgets = true,
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

    @Test fun ownLock() = shoot("lock") {
        BioKeyLockScreen(onUnlockClicked = {}, onCancelClicked = {})
    }
    @Test fun setup() = shoot("setup") { SetupScreenExpressive(onSetupComplete = {}) }
    @Test fun setupAfterStorageCleared() = shoot("setup_cleared") {
        SetupScreenExpressive(cleared = ProtectionGaps.Cleared(System.currentTimeMillis() - 3_600_000L), onSetupComplete = {})
    }

    private fun cover(actions: Boolean, status: String? = null) = @Composable {
        val resources = androidx.compose.ui.platform.LocalResources.current
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                com.android.BioKey.feature.lock.LockCoverView(it).apply {
                    val size = (88 * resources.displayMetrics.density).toInt()
                    bind("Chase", android.graphics.drawable.BitmapDrawable(resources, icon("Chase", 0xFF1A73E8.toInt(), size)), isBioKey = false)
                    setActionsVisible(actions)
                    if (status != null) setStatus(status, permanentLockout = false)
                }
            },
            modifier = androidx.compose.ui.Modifier.fillMaxSize()
        )
    }

    @Test fun coverPrompt() = shoot("cover_prompt", cover(actions = false))
    @Test fun coverActions() = shoot("cover_actions", cover(actions = true))
}
