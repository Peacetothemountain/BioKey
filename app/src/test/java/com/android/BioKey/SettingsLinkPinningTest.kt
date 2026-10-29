package com.android.BioKey

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.feature.main.IntentNavigationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings pages BioKey opens go to the system app that handles them, never to another app that
 * claims the same action and could pose as Settings while BioKey's leave pass is open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsLinkPinningTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() = LockStateTracker.lockAll()

    private fun handler(pkg: String, cls: String, system: Boolean) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = cls
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                if (system) flags = ApplicationInfo.FLAG_SYSTEM
            }
        }
    }

    private fun nextStarted(): Intent = shadowOf(context as Application).nextStartedActivity

    @Test
    fun aSettingsPageGoesToTheSystemHandlerNotAnImpostor() {
        val action = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val pm = shadowOf(context.packageManager)
        pm.addResolveInfoForIntent(action, handler("com.example.fake", "com.example.fake.FakeSettings", system = false))
        pm.addResolveInfoForIntent(action, handler("com.android.settings", "com.android.settings.AccessibilitySettings", system = true))

        assertTrue(IntentNavigationHelper.launchFallbackCascade(context, listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))))
        val started = nextStarted()
        assertEquals("com.android.settings", started.component?.packageName)
        assertEquals("com.android.settings.AccessibilitySettings", started.component?.className)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, started.action)
    }

    @Test
    fun withNoSystemHandlerInSightThePageStaysImplicit() {
        // Only a non-system app claims it: the intent is left as it was, as before pinning.
        val action = Intent(Settings.ACTION_SECURITY_SETTINGS)
        shadowOf(context.packageManager)
            .addResolveInfoForIntent(action, handler("com.example.fake", "com.example.fake.FakeSettings", system = false))

        assertTrue(IntentNavigationHelper.launchFallbackCascade(context, listOf(Intent(Settings.ACTION_SECURITY_SETTINGS))))
        assertNull(nextStarted().component)
    }

    @Test
    fun theChooserIsNeverPinned() {
        val intent = Intent(Settings.ACTION_SETTINGS)
        shadowOf(context.packageManager)
            .addResolveInfoForIntent(intent, handler("android", "com.android.internal.app.ResolverActivity", system = true))

        IntentNavigationHelper.pinToSystemHandler(context, intent)
        assertNull(intent.component)
    }
}
