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
    fun android13And14RestrictEverythingNotAllowed() {
        for (sdk in listOf(33, 34)) {
            assertFalse(RestrictedSettingsHelper.isRestricted(sdk, MODE_ALLOWED, PACKAGE_SOURCE_LOCAL_FILE))
            assertTrue(RestrictedSettingsHelper.isRestricted(sdk, MODE_ERRORED, PACKAGE_SOURCE_LOCAL_FILE))
            assertTrue(RestrictedSettingsHelper.isRestricted(sdk, MODE_DEFAULT, PACKAGE_SOURCE_UNSPECIFIED))
        }
    }

    @Test
    fun android15DecidesTheDefaultFromTheInstallSource() {
        for (sdk in listOf(35, 36, 37)) {
            assertTrue(RestrictedSettingsHelper.isRestricted(sdk, MODE_DEFAULT, PACKAGE_SOURCE_LOCAL_FILE))
            assertTrue(RestrictedSettingsHelper.isRestricted(sdk, MODE_DEFAULT, PACKAGE_SOURCE_DOWNLOADED_FILE))
            // adb and other installers aren't restricted.
            assertFalse(RestrictedSettingsHelper.isRestricted(sdk, MODE_DEFAULT, PACKAGE_SOURCE_UNSPECIFIED))
            assertFalse(RestrictedSettingsHelper.isRestricted(sdk, MODE_DEFAULT, PACKAGE_SOURCE_OTHER))
            // Explicitly guarded, or guarded and the dialog has been shown.
            assertTrue(RestrictedSettingsHelper.isRestricted(sdk, MODE_ERRORED, PACKAGE_SOURCE_UNSPECIFIED))
            assertTrue(RestrictedSettingsHelper.isRestricted(sdk, MODE_IGNORED, PACKAGE_SOURCE_UNSPECIFIED))
            assertFalse(RestrictedSettingsHelper.isRestricted(sdk, MODE_ALLOWED, PACKAGE_SOURCE_DOWNLOADED_FILE))
        }
    }
}
