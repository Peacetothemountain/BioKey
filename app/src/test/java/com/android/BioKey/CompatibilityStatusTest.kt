package com.android.BioKey

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.UsageWatchService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * Compatibility mode only counts as protecting once it runs or has been asked to start, not just
 * because its permissions are granted; and a lost permission is named for the alert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompatibilityStatusTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun freshVault() {
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
    }

    @Test
    fun grantedPermissionsAloneDoNotMeanProtected() {
        ShadowSettings.setCanDrawOverlays(true)
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        // Set up, permissions granted, but nothing started it (a background start was refused).
        assertEquals(ProtectionEngine.Mode.OFF, ProtectionEngine.mode(context))

        assertEquals(true, ProtectionEngine.refresh(context))
        assertEquals(ProtectionEngine.Mode.COMPATIBILITY, ProtectionEngine.mode(context))
    }

    @Test
    fun aLostPermissionIsNamed() {
        ShadowSettings.setCanDrawOverlays(false)
        assertEquals("display over other apps", ProtectionEngine.missingCompatibilityPermissions(context))
        ShadowSettings.setCanDrawOverlays(true)
        assertNull(ProtectionEngine.missingCompatibilityPermissions(context))
        assertEquals(false, UsageWatchService.isRunning)
    }
}
