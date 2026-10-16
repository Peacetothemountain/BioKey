package com.android.BioKey

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.LockScreenUIExpressive
import com.android.BioKey.feature.main.AppItem
import com.android.BioKey.feature.main.InstalledAppsCache
import com.android.BioKey.feature.main.MainScreenExpressive
import com.android.BioKey.feature.main.SettingsScreenExpressive
import com.android.BioKey.feature.setup.SetupScreenExpressive
import com.android.BioKey.ui.theme.BioKeyTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders BioKey's screens to PNGs (app/build/screenshots) for design review, without a device.
 * Run: ./gradlew :app:testDebugUnitTest --tests "*DesignScreenshotTest*"
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
        val iconPx = (40 * context.resources.displayMetrics.density).toInt()
        InstalledAppsCache.apps = samples.map { (label, color) ->
            AppItem(
                packageName = "com.example.${label.lowercase()}",
                label = label,
                iconBitmap = icon(label, color.toInt(), iconPx).asImageBitmap(),
                category = AppCategory.GENERAL
            )
        }.sortedBy { it.label }
        InstalledAppsCache.version = 0
        InstalledAppsCache.iconPx = iconPx
        val vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        listOf("chase", "signal", "photos", "authenticator", "venmo").forEach { vault.setAppLock("com.example.$it", true) }
        vault.moveAppToProfile("com.example.chase", "profile_finance")
        vault.moveAppToProfile("com.example.venmo", "profile_finance")
        vault.moveAppToProfile("com.example.signal", "profile_social")
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
    @Test fun settings() = shoot("settings") { SettingsScreenExpressive(onNavigateBack = {}) }
    @Test fun ownLock() = shoot("lock") {
        LockScreenUIExpressive(appName = "BioKey", appIcon = null, onUnlockClicked = {}, onCancelClicked = {}, isBioKeyLock = true)
    }
    @Test fun setup() = shoot("setup") { SetupScreenExpressive(onSetupComplete = {}) }

    private fun cover(actions: Boolean) = @Composable {
        val context = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                com.android.BioKey.feature.lock.LockCoverView(it).apply {
                    val size = (88 * context.resources.displayMetrics.density).toInt()
                    bind("Chase", android.graphics.drawable.BitmapDrawable(context.resources, icon("Chase", 0xFF1A73E8.toInt(), size)), isBioKey = false)
                    setActionsVisible(actions)
                }
            },
            modifier = androidx.compose.ui.Modifier.fillMaxSize()
        )
    }

    @Test fun coverPrompt() = shoot("cover_prompt", cover(actions = false))
    @Test fun coverActions() = shoot("cover_actions", cover(actions = true))
}
