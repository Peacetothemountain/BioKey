package com.android.BioKey

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.UsageWatchService
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * Compatibility mode only counts as protecting once it runs or has been asked to start, not just
 * because its permissions are granted; a lost permission is named for the alert, and an engine
 * that has its permissions but didn't start isn't asked for them again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompatibilityStatusTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun freshVault() {
        // As the other test classes do: whatever the last vault was writing lands first.
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        forgetStartRequest()
    }

    /** A start asked for here is never followed by the service being created: none may outlive a test. */
    @After
    fun forgetStartRequest() {
        UsageWatchService::class.java.getDeclaredField("startPending").apply { isAccessible = true }.setBoolean(null, false)
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
    fun anEngineThatDidNotStartIsSaidSo() {
        ShadowSettings.setCanDrawOverlays(true)
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        // Set up and granted, but its start was refused: the alert mustn't ask for those permissions again.
        ProtectionAlerts.serviceStopped(context, now = true)
        val alert = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val text = alert.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.contains("compatibility mode isn't running"))
    }

    @Test
    fun aLostPermissionIsNamed() {
        ShadowSettings.setCanDrawOverlays(false)
        assertEquals("display over other apps", ProtectionEngine.missingCompatibilityPermissions(context))
        ShadowSettings.setCanDrawOverlays(true)
        assertNull(ProtectionEngine.missingCompatibilityPermissions(context))
        assertEquals(false, UsageWatchService.isRunning)
    }

    private fun alerts() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        .map { it.extras.getCharSequence(Notification.EXTRA_TEXT).toString() }

    @Test
    fun aSwitchedOnServiceThatNeverConnectsIsSaidSo() {
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        // Switched on in Settings, but Android stopped restarting it (it kept crashing).
        Settings.Secure.putString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(context, BioKeyAccessibilityService::class.java).flattenToString()
        )
        ProtectionAlerts.checkAccessibilityConnects(context)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertTrue(alerts().toString(), alerts().any { "app lock service stopped" in it })
    }

    @Test
    fun aSwitchedOffServiceIsNoAlertAtStart() {
        SecurityVault.getInstance(context).setSetupCompletedSync(true)
        ProtectionAlerts.checkAccessibilityConnects(context)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertFalse(alerts().toString(), alerts().any { "app lock service stopped" in it })
    }
}
