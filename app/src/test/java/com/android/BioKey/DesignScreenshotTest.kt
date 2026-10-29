package com.android.BioKey

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

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
        RuntimeEnvironment.setQualifiers("w412dp-h1900dp")
        shoot("settings_full") { SettingsScreenExpressive(mode = com.android.BioKey.core.security.ProtectionEngine.Mode.ACCESSIBILITY, onNavigateBack = {}, onPauseRequested = {}) }
    }
    @Test fun message() = shoot("message") {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            MainScreenExpressive(mode = com.android.BioKey.core.security.ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {})
            val data = object : androidx.compose.material3.SnackbarData {
                override val visuals = com.android.BioKey.ui.theme.BioKeyMessages.Message(
                    "Chase unlocked", androidx.compose.material.icons.Icons.Default.LockOpen, "Undo", null,
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
        ProtectionGaps.bootCount = { 1 }
        ProtectionGaps.installTime = { 1L }
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        ProtectionGaps.onEngineUp(context)
        ProtectionGaps.onEngineDown(context)
        ProtectionGaps.clock = { today + 8 * 60_000L }
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
        shoot("main_intro") { MainScreenExpressive(mode = ProtectionEngine.Mode.ACCESSIBILITY, onPauseRequested = {}) }
    }

    @Test fun ownLock() = shoot("lock") {
        BioKeyLockScreen(onUnlockClicked = {}, onCancelClicked = {})
    }
    @Test fun setup() = shoot("setup") { SetupScreenExpressive(onSetupComplete = {}) }

    private fun cover(actions: Boolean) = @Composable {
        val resources = androidx.compose.ui.platform.LocalResources.current
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                com.android.BioKey.feature.lock.LockCoverView(it).apply {
                    val size = (88 * resources.displayMetrics.density).toInt()
                    bind("Chase", android.graphics.drawable.BitmapDrawable(resources, icon("Chase", 0xFF1A73E8.toInt(), size)), isBioKey = false)
                    setActionsVisible(actions)
                }
            },
            modifier = androidx.compose.ui.Modifier.fillMaxSize()
        )
    }

    @Test fun coverPrompt() = shoot("cover_prompt", cover(actions = false))
    @Test fun coverActions() = shoot("cover_actions", cover(actions = true))
}
