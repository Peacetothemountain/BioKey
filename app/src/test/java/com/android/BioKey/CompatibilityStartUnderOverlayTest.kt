package com.android.BioKey

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.security.SecurityVault
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl

/**
 * Android 15 and later refuse an app holding "display over other apps" a foreground service
 * started from the background unless an overlay of its own shows: compatibility mode is then
 * started again under a 1-pixel overlay, which comes down once it has started. Without that,
 * compatibility mode never starts when the accessibility engine hands over to it as it stops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompatibilityStartUnderOverlayTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val main get() = shadowOf(Looper.getMainLooper())

    /**
     * BioKey's context, with Android refusing its first [refusals] foreground-service starts as it
     * does in the background, and, unless [overlays], refusing its overlay too.
     */
    private class Phone(base: Context, var refusals: Int, val overlays: Boolean = true) : ContextWrapper(base) {
        var starts = 0

        override fun getApplicationContext(): Context = this

        override fun startForegroundService(service: Intent): ComponentName? {
            starts++
            if (refusals > 0) {
                refusals--
                throw ForegroundServiceStartNotAllowedException("startForegroundService() not allowed")
            }
            return super.startForegroundService(service)
        }

        override fun getSystemService(name: String): Any? {
            val real = super.getSystemService(name)
            if (name != WINDOW_SERVICE || overlays) return real
            return object : WindowManager by real as WindowManager {
                override fun addView(view: View?, params: ViewGroup.LayoutParams?) =
                    throw WindowManager.BadTokenException("permission denied for window type")
            }
        }
    }

    @Before
    fun start() {
        // BioKey's own start (refreshing the engines) finishes first: it mustn't start one mid-test.
        BioKeyApplication.awaitInitForTests()
        SecurityVault.getInstance(app).awaitPendingWrites()
        app.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        app.getSharedPreferences("biokey_protection_alerts", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.getInstance(app).setSetupCompletedSync(true)
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ShadowSettings.setCanDrawOverlays(true)
        forgetStartRequest()
        // Services started before this test (BioKey's own start) aren't what it looks for.
        while (shadowOf(app).nextStartedService != null) continue
    }

    /** A start asked for here is never followed by the service being created: none may outlive a test. */
    @After
    fun forgetStartRequest() {
        UsageWatchService::class.java.getDeclaredField("startPending").apply { isAccessible = true }.setBoolean(null, false)
        // Nor a refusal: the next test's first start is its first.
        UsageWatchService::class.java.getDeclaredField("refusedOnce").apply { isAccessible = true }.setBoolean(null, false)
    }

    /** Android restarts the service (after a crash, say) and refuses it its place in the foreground. */
    private fun restartedAndRefused(): org.robolectric.android.controller.ServiceController<UsageWatchService> {
        val restarted = Robolectric.buildService(UsageWatchService::class.java)
        shadowOf(restarted.get()).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("startForeground() not allowed"))
        return restarted.create()
    }

    @Test
    fun aRestartRefusedTheForegroundIsStartedOnceMore() {
        val refused = restartedAndRefused()
        // Asked for again, and that start isn't taken back as the refused one goes.
        assertEquals(ComponentName(app, UsageWatchService::class.java), startedService()?.component)
        assertTrue(shadowOf(refused.get()).isStoppedBySelf)
        refused.destroy()
        assertTrue(UsageWatchService.startPending)
        assertFalse(UsageWatchService.isRunning)
        // It comes up this time: a later refusal is a first one again.
        Robolectric.buildService(UsageWatchService::class.java).create().destroy()
        assertFalse(UsageWatchService.startPending)
        restartedAndRefused().destroy()
        assertEquals(ComponentName(app, UsageWatchService::class.java), startedService()?.component)
    }

    @Test
    fun refusedAgainItSaysSoRatherThanTryForEver() {
        restartedAndRefused().destroy()
        assertEquals(ComponentName(app, UsageWatchService::class.java), startedService()?.component)
        restartedAndRefused().destroy()
        assertNull("no third start", startedService())
        assertFalse(UsageWatchService.startPending)
        advance(60_000)
        assertTrue(alerts().toString(), alerts().isNotEmpty())
    }

    @Test
    @Config(sdk = [34])
    fun beforeAndroid15ARefusalIsFinal() {
        // There an app that may draw over others may start in the background: a refusal is something else.
        restartedAndRefused().destroy()
        assertNull(startedService())
        advance(60_000)
        assertTrue(alerts().toString(), alerts().isNotEmpty())
    }

    private fun overlays(): List<View> = Shadow.extract<ShadowWindowManagerImpl>(app.getSystemService(WindowManager::class.java)).views

    /**
     * [ms] pass, 10 ms at a time: Robolectric runs what is due at the end of each step with its
     * clock already there, so a retry posted along the way would otherwise come a step late.
     */
    private fun advance(ms: Long) = repeat((ms / 10).toInt()) { main.idleFor(Duration.ofMillis(10)) }

    private fun startedService(): Intent? = shadowOf(app).nextStartedService

    @Test
    fun refusedInTheBackgroundItStartsUnderAnOverlayThatThenComesDown() {
        val phone = Phone(app, refusals = 1)
        UsageWatchService.start(phone)
        assertTrue(UsageWatchService.startPending)
        main.idle()
        val overlay = overlays().single()
        val params = overlay.layoutParams as WindowManager.LayoutParams
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertEquals(1 to 1, params.width to params.height)
        assertTrue("it takes no touches", params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertEquals("not tried again until the overlay is drawn", 1, phone.starts)

        advance(100)
        assertEquals(2, phone.starts)
        assertEquals(ComponentName(app, UsageWatchService::class.java), startedService()?.component)
        // Still starting: only the service being created ends that.
        assertTrue(UsageWatchService.startPending)
        assertEquals("kept up a moment, while the service reaches the foreground", 1, overlays().size)

        advance(1_000)
        assertTrue(overlays().isEmpty())
    }

    @Test
    fun aRefusalWhileTheOverlayIsUpIsTriedAgain() {
        val phone = Phone(app, refusals = 3)
        UsageWatchService.start(phone)
        main.idle()
        assertEquals(1, overlays().size)
        advance(100 + 250)
        assertEquals(3, phone.starts)
        assertNull(startedService())
        advance(250)
        assertEquals(4, phone.starts)
        assertEquals(ComponentName(app, UsageWatchService::class.java), startedService()?.component)
        advance(1_000)
        assertTrue(overlays().isEmpty())
    }

    @Test
    fun refusedEvenUnderTheOverlayItGivesUpAndSaysSo() {
        // Refused every time: the first start, then the first try and five more under the overlay.
        val phone = Phone(app, refusals = 1 + 6)
        UsageWatchService.start(phone)
        main.idle()
        assertEquals(1, overlays().size)
        advance(100 + 5 * 250)
        assertEquals(7, phone.starts)
        assertNull(startedService())
        assertTrue("the overlay doesn't stay up", overlays().isEmpty())
        assertFalse("another start isn't waited for", UsageWatchService.startPending)
        advance(60_000)
        assertTrue(alerts().toString(), alerts().any { it.contains("compatibility mode isn't running") })
    }

    @Test
    fun withoutAnOverlayNothingIsLeftAttachedAndItSaysSo() {
        val phone = Phone(app, refusals = 1, overlays = false)
        UsageWatchService.start(phone)
        main.idle()
        assertTrue(overlays().isEmpty())
        assertFalse("another start isn't waited for", UsageWatchService.startPending)
        advance(60_000)
        assertEquals("not tried again without an overlay", 1, phone.starts)
        assertNull(startedService())
        assertTrue(alerts().toString(), alerts().any { it.contains("compatibility mode isn't running") })
    }

    private fun alerts() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        .map { it.extras.getCharSequence(Notification.EXTRA_TEXT).toString() }
}
