package com.android.BioKey.baselineprofile

import android.app.UiAutomation
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates BioKey's baseline profile and its startup profile: the code Android compiles ahead of
 * time, so the first cover after an install or update isn't drawn by interpreted code, and the
 * order R8 lays that code out in. Two journeys: BioKey starting to its main screen, and the lock
 * path, a locked app opened, covered, its lock screen up and cancelled, then Home.
 *
 *     ./gradlew :app:generateBaselineProfile
 *
 * writes app/src/release/generated/baselineProfiles/ (baseline-prof.txt, startup-prof.txt), which
 * is checked in. It needs a device or emulator running Android 13 or later (or a rooted one), set
 * up as the emulator gantlet sets one up (tools/emulator_gantlet.py, set_up), by hand:
 *
 * 1. A screen lock (PIN) and an enrolled fingerprint, so BioKey can be set up.
 * 2. BioKey's debug build installed (`./gradlew :app:installDebug`) and set up: Gradle then
 *    installs its nonMinifiedRelease build over it, signed with the same debug key, which keeps
 *    its settings. Restricted settings allowed, notifications, usage access and display over
 *    other apps granted, the accessibility engine on.
 * 3. The gantlet's target apps installed (`./gradlew -p tools/gantlet-targets assembleDebug`,
 *    then `adb install -r -g` each APK) and com.biokey.gantlet.plain ("Gantlet Plain") locked in
 *    BioKey. Another locked app can be named with
 *    `-Pandroid.testInstrumentationRunnerArguments.biokey.lockedApp=<package>`.
 * 4. The phone awake and unlocked, on its home screen.
 *
 * Nothing here sets those up or authenticates. The rule stops BioKey between iterations with a
 * force-stop, which also turns its accessibility service off: the lock path turns it back on as
 * the run found it (or, in compatibility mode, starts BioKey so its engine starts with it).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    companion object {
        private const val BIOKEY = "com.nbdesigns.biokey"
        private const val ACCESSIBILITY_SERVICE = "$BIOKEY/com.android.BioKey.feature.lock.BioKeyAccessibilityService"
        private const val LOCK_SCREEN = "$BIOKEY/com.android.BioKey.feature.lock.LockScreenActivity"
        private const val DEFAULT_LOCKED_APP = "com.biokey.gantlet.plain"
        private const val LOCK_SCREEN_WAIT_MS = 10_000L

        /** The accessibility services on when the run started: BioKey's engine, if it is the one running. */
        private var accessibilityServices = ""

        @JvmStatic
        @BeforeClass
        fun keepAccessibilityServicesRunning() {
            // UiAutomation suppresses every accessibility service while it is connected, unless asked
            // not to, and BioKey takes that for its engine stopping: every session ends and nothing
            // is covered. Connected once here with the flag, the connection UiAutomator and the
            // rule's shell commands use afterwards is this one.
            Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            accessibilityServices = UiDevice.getInstance(instrumentation)
                .executeShellCommand("settings get secure enabled_accessibility_services").trim()
        }
    }

    @get:Rule
    val rule = BaselineProfileRule()

    private val lockedApp: String =
        InstrumentationRegistry.getArguments().getString("biokey.lockedApp") ?: DEFAULT_LOCKED_APP

    /** Process start and the main screen (with BioKey's own prompt over it). */
    @Test
    fun startup() = rule.collect(packageName = BIOKEY, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
    }

    /**
     * A locked app opened from Home: the engine's event, the shield's cover, the lock screen and
     * its prompt; then Back out of both, as the gantlet's "Back from the lock screen" does. In the
     * startup profile too: BioKey's process starts for its engine far more often than for its main
     * screen, and covering the first locked app is the start that has to be fast.
     */
    @Test
    fun lockPath() = rule.collect(packageName = BIOKEY, includeInStartupProfile = true) {
        restartEngine()
        pressHome()
        val component = device.executeShellCommand(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $lockedApp"
        ).trim().lines().last()
        check("/" in component) { "$lockedApp isn't installed, or has no launcher activity" }
        device.executeShellCommand(
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n $component"
        )
        check(waitForLockScreen()) { "No lock screen for $lockedApp: is it locked in BioKey, and is BioKey set up?" }
        // The prompt first, then the lock screen: each Back closes one.
        repeat(3) {
            if (!isLockScreenInFront()) return@repeat
            device.pressBack()
            device.waitForIdle()
        }
        pressHome()
    }

    /**
     * Puts BioKey's engine back after the rule's force-stop: its accessibility service turned on
     * again, as the run found the setting; in compatibility mode, BioKey's process started, which
     * starts the engine.
     */
    private fun MacrobenchmarkScope.restartEngine() {
        if (ACCESSIBILITY_SERVICE in accessibilityServices) {
            val now = device.executeShellCommand("settings get secure enabled_accessibility_services")
            if (ACCESSIBILITY_SERVICE in now) return
            device.executeShellCommand("settings put secure enabled_accessibility_services $accessibilityServices")
            device.executeShellCommand("settings put secure accessibility_enabled 1")
        } else {
            startActivityAndWait()
        }
        // The engine connects (the gantlet waits as long after turning it on).
        SystemClock.sleep(4_000)
    }

    private fun MacrobenchmarkScope.isLockScreenInFront(): Boolean =
        device.executeShellCommand("dumpsys activity activities")
            .lineSequence()
            .any { "topResumedActivity" in it && LOCK_SCREEN in it }

    private fun MacrobenchmarkScope.waitForLockScreen(): Boolean {
        val until = SystemClock.uptimeMillis() + LOCK_SCREEN_WAIT_MS
        while (SystemClock.uptimeMillis() < until) {
            if (isLockScreenInFront()) return true
            SystemClock.sleep(200)
        }
        return false
    }
}
