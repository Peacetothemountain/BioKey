package com.android.BioKey

import android.app.AppOpsManager.MODE_ALLOWED
import android.app.AppOpsManager.MODE_DEFAULT
import android.app.AppOpsManager.MODE_ERRORED
import android.app.AppOpsManager.MODE_IGNORED
import android.content.pm.PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
import android.content.pm.PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
import android.content.pm.PackageInstaller.PACKAGE_SOURCE_OTHER
import android.content.pm.PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.feature.setup.RestrictedSettingsHelper
import com.android.BioKey.feature.setup.RestrictedSettingsHelper.Verdict.BLOCKED
import com.android.BioKey.feature.setup.RestrictedSettingsHelper.Verdict.NOT_BLOCKED
import com.android.BioKey.feature.setup.RestrictedSettingsHelper.Verdict.UNSURE
import com.android.BioKey.feature.tile.BioKeyTileService
import com.android.BioKey.feature.tile.BioKeyTileService.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the tile, the setup screen and the notification shield report about protection. */
class GuardStatusTest {

    @Test
    fun theTileOnlySaysProtectingWhileAnEngineRuns() {
        assertEquals(Status.PROTECTING, BioKeyTileService.status(setupCompleted = true, enabled = true, engineRunning = true))
        assertEquals(Status.NOT_PROTECTING, BioKeyTileService.status(setupCompleted = true, enabled = true, engineRunning = false))
        assertEquals(Status.PAUSED, BioKeyTileService.status(setupCompleted = true, enabled = false, engineRunning = true))
        assertEquals(Status.SETUP_REQUIRED, BioKeyTileService.status(setupCompleted = false, enabled = true, engineRunning = true))
        // Paused with nothing running: resuming wouldn't protect anything, so it says so.
        assertEquals(Status.NOT_PROTECTING, BioKeyTileService.status(setupCompleted = true, enabled = false, engineRunning = false))
    }

    @Test
    fun aSwitchedOnServiceThatIsNotRunningIsNotProtecting() {
        // Crashed, or not yet connected: the tile, the home screen and Settings all say so.
        assertEquals(ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING, ProtectionEngine.accessibilityMode(switchedOn = true, running = false))
        assertFalse(ProtectionEngine.isProtecting(ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING))
        assertEquals(ProtectionEngine.Mode.ACCESSIBILITY, ProtectionEngine.accessibilityMode(switchedOn = true, running = true))
        assertTrue(ProtectionEngine.isProtecting(ProtectionEngine.Mode.ACCESSIBILITY))
        // Switched off: compatibility mode decides.
        assertNull(ProtectionEngine.accessibilityMode(switchedOn = false, running = false))
        assertFalse(ProtectionEngine.isProtecting(ProtectionEngine.Mode.OFF))
        assertTrue(ProtectionEngine.isProtecting(ProtectionEngine.Mode.COMPATIBILITY))
    }

    @Test
    fun android13And14RestrictOnlyWhatAndroidMarked() {
        for (sdk in listOf(33, 34)) {
            assertEquals(NOT_BLOCKED, RestrictedSettingsHelper.verdictFor(sdk, MODE_ALLOWED, PACKAGE_SOURCE_LOCAL_FILE, null, false))
            assertEquals(BLOCKED, RestrictedSettingsHelper.verdictFor(sdk, MODE_ERRORED, PACKAGE_SOURCE_LOCAL_FILE, null, false))
            // The dialog has been seen: still restricted, and "Allow restricted settings" is offered now.
            assertEquals(BLOCKED, RestrictedSettingsHelper.verdictFor(sdk, MODE_IGNORED, PACKAGE_SOURCE_LOCAL_FILE, null, false))
            // Not Android's own starting value there: never shown as a step, only offered as help.
            assertEquals(UNSURE, RestrictedSettingsHelper.verdictFor(sdk, MODE_DEFAULT, PACKAGE_SOURCE_UNSPECIFIED, null, false))
        }
        // Nothing to restrict before Android 13.
        assertEquals(NOT_BLOCKED, RestrictedSettingsHelper.verdictFor(32, MODE_ERRORED, PACKAGE_SOURCE_LOCAL_FILE, null, false))
    }

    @Test
    fun android15AndLaterDecideTheDefaultFromTheInstallSource() {
        for (sdk in listOf(35, 36, 37)) {
            // A file: Android decides, maybe with an installer it trusts, so only help after a try.
            assertEquals(UNSURE, RestrictedSettingsHelper.verdictFor(sdk, MODE_DEFAULT, PACKAGE_SOURCE_LOCAL_FILE, "com.example.files", false))
            assertEquals(UNSURE, RestrictedSettingsHelper.verdictFor(sdk, MODE_DEFAULT, PACKAGE_SOURCE_DOWNLOADED_FILE, null, false))
            // Put in by Play or the phone's own store.
            assertEquals(NOT_BLOCKED, RestrictedSettingsHelper.verdictFor(sdk, MODE_DEFAULT, PACKAGE_SOURCE_LOCAL_FILE, "com.android.vending", false))
            assertEquals(NOT_BLOCKED, RestrictedSettingsHelper.verdictFor(sdk, MODE_DEFAULT, PACKAGE_SOURCE_DOWNLOADED_FILE, "com.sec.android.app.samsungapps", true))
            // adb and other installers aren't restricted (an adb install on Android 17: "default", source "other").
            assertEquals(NOT_BLOCKED, RestrictedSettingsHelper.verdictFor(sdk, MODE_DEFAULT, PACKAGE_SOURCE_UNSPECIFIED, null, false))
            assertEquals(NOT_BLOCKED, RestrictedSettingsHelper.verdictFor(sdk, MODE_DEFAULT, PACKAGE_SOURCE_OTHER, null, false))
            // Explicitly guarded, or guarded and the dialog has been shown.
            assertEquals(BLOCKED, RestrictedSettingsHelper.verdictFor(sdk, MODE_ERRORED, PACKAGE_SOURCE_UNSPECIFIED, null, false))
            assertEquals(BLOCKED, RestrictedSettingsHelper.verdictFor(sdk, MODE_IGNORED, PACKAGE_SOURCE_UNSPECIFIED, null, false))
            assertEquals(NOT_BLOCKED, RestrictedSettingsHelper.verdictFor(sdk, MODE_ALLOWED, PACKAGE_SOURCE_DOWNLOADED_FILE, null, false))
        }
    }

    @Test
    fun setupShowsTheRestrictedStepOnlyWhenItIsNeededOrMightBe() {
        // Sure: from the start.
        assertTrue(RestrictedSettingsHelper.showStep(BLOCKED, cameBackWithoutService = false))
        // Unsure: only once the owner came back from Android's settings without BioKey on.
        assertFalse(RestrictedSettingsHelper.showStep(UNSURE, cameBackWithoutService = false))
        assertTrue(RestrictedSettingsHelper.showStep(UNSURE, cameBackWithoutService = true))
        // Not restricted: never, even after a try (the owner may just have gone back).
        assertFalse(RestrictedSettingsHelper.showStep(NOT_BLOCKED, cameBackWithoutService = false))
        assertFalse(RestrictedSettingsHelper.showStep(NOT_BLOCKED, cameBackWithoutService = true))
    }
}
