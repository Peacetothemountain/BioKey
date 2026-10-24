package com.android.BioKey

import android.app.Application
import android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
import android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
import android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Rect
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.diagnostics.EngineTrace
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.feature.lock.InstantShieldOverlay
import com.android.BioKey.feature.lock.LockScreenActivity
import com.android.BioKey.feature.lock.UsageWatchService
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowUsageStatsManager

/**
 * Engine traces (core/diagnostics/EngineTrace) replay what they recorded: a scenario played on an
 * engine, recorded, then played back from its trace alone into a fresh one, decides the same,
 * for both engines. And every trace kept in app/src/test/resources/traces (one recorded on a phone,
 * say, with the bug it shows fixed) still decides what it did when it was recorded there.
 *
 * A trace from a phone: with the debug build installed, reproduce the problem, then
 * `adb shell run-as com.android.BioKey cat files/engine-trace.txt > app/src/test/resources/traces/<name>.trace`.
 * Its decisions (`o.*` records) are what the engine did then: if that was the bug, change those
 * records to what it should have done, and this test fails until the engine does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [CompatibilityEngineTest.PackageFilteringUsageStats::class, InstallTimePermissions::class])
class EngineReplayTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val x = "com.twitter.android"
    private val bank = "com.example.bank"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val whole = Rect(0, 0, 320, 470)
    private lateinit var replay: EngineReplay

    @Before
    fun start() {
        EngineTrace.clear()
        replay = EngineReplay(context)
    }

    @After
    fun stop() {
        replay.stop()
        EngineTrace.clear()
        ForegroundRules.resetSystemLists()
    }

    private fun freshVault(locked: String, label: String): SecurityVault {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        LockStateTracker.lockAll()
        DiagnosticLog.clear()
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = locked
            applicationInfo = ApplicationInfo().apply {
                packageName = locked
                nonLocalizedLabel = label
            }
        })
        return SecurityVault.getInstance(context).apply {
            setSetupCompletedSync(true)
            setAppLock(locked, true)
        }
    }

    private fun idle(ms: Long = 500) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    // What BioKey's lock screen tells the engines, told as it does (and so recorded).

    private fun lockScreen(resumed: Boolean, target: String?) {
        if (target != LockScreenActivity.activeLockedTarget) EngineTrace.record("l.target", target)
        if (resumed != LockScreenActivity.isLockScreenResumed) EngineTrace.record("l.resumed", if (resumed) 1 else 0)
        LockScreenActivity::class.java.getDeclaredField("isLockScreenResumed").apply { isAccessible = true }.setBoolean(null, resumed)
        LockScreenActivity::class.java.getDeclaredField("activeLockedTarget").apply { isAccessible = true }.set(null, target)
        if (resumed) LockScreenActivity::class.java.getDeclaredField("resumedAt").apply { isAccessible = true }.setLong(null, android.os.SystemClock.uptimeMillis())
    }

    private fun lockScreenDrawn(pkg: String) {
        EngineTrace.record("l.dismiss", pkg)
        InstantShieldOverlay.dismiss(pkg)
    }

    private fun cancelled(pkg: String) {
        EngineTrace.record("u.abandon", pkg)
        LockStateTracker.markLockAbandoned(pkg)
        lockScreen(resumed = false, target = null)
    }

    private fun unlocked(pkg: String) {
        EngineTrace.record("u.unlock", pkg)
        LockStateTracker.onAppUnlocked(pkg)
        lockScreenDrawn(pkg)
        lockScreen(resumed = false, target = null)
    }

    /** Records the scenario [play] runs, replays the trace into a fresh engine, and compares what each decided. */
    private fun roundTrip(name: String, play: () -> Unit, teardown: () -> Unit) {
        play()
        // Where the recording ends: a replay plays up to here, and no further.
        EngineTrace.record("z.end")
        val recorded = EngineTrace.lines()
        teardown()
        // Kept for a look (and, recorded on purpose, as a fixture for app/src/test/resources/traces).
        File("build/traces").apply { mkdirs() }.resolve("$name.trace").writeText(recorded.joinToString("\n", postfix = "\n"))
        val expected = EngineReplay.decisions(EngineReplay.parse(recorded))
        assertTrue("the scenario decided nothing: $recorded", expected.any { it.startsWith("launch ") })
        val played = replay.play(EngineReplay.parse(recorded))
        // The replay's own trace, beside the recording's, for telling where they part.
        File("build/traces/$name.replay.trace").writeText(EngineTrace.lines().joinToString("\n", postfix = "\n"))
        assertEquals(expected, played)
    }

    @Test
    fun anAccessibilityTraceReplaysTheSameDecisions() {
        freshVault(x, "X")
        val home = ComponentName(launcher, "$launcher.NexusLauncherActivity")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(home)
            addIntentFilterForActivity(home, IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            })
        }
        ForegroundRules.resetSystemLists()
        val engine = Robolectric.buildService(BioKeyAccessibilityService::class.java).create()
        fun window(id: Int, pkg: String?, active: Boolean = true) = AccessibilityWindowInfo.obtain().also { w ->
            shadowOf(w).apply {
                setId(id)
                setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                setDisplayId(Display.DEFAULT_DISPLAY)
                setBoundsInScreen(whole)
                setActive(active)
                setFocused(active)
                if (pkg != null) setRoot(AccessibilityNodeInfo.obtain().apply { packageName = pkg })
            }
        }
        fun onScreen(vararg windows: AccessibilityWindowInfo) = shadowOf(engine.get()).setWindowsOnDisplay(Display.DEFAULT_DISPLAY, windows.toList())
        fun send(type: Int, pkg: String, cls: String, text: String? = null) {
            engine.get().onAccessibilityEvent(AccessibilityEvent(type).apply {
                packageName = pkg
                className = cls
                if (text != null) this.text.add(text)
            })
            idle()
        }
        roundTrip("accessibility", play = {
            BioKeyAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(engine.get())
            idle()
            onScreen(window(1, launcher))
            send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, launcher, "$launcher.NexusLauncherActivity")
            // A tap on X's icon, then X opens: covered, its lock screen comes up and is drawn.
            send(AccessibilityEvent.TYPE_VIEW_CLICKED, launcher, "android.widget.TextView", "X")
            onScreen(window(2, x))
            send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, x, "$x.MainActivity")
            lockScreen(resumed = true, target = x)
            onScreen(window(3, context.packageName), window(2, x, active = false))
            send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, context.packageName, LockScreenActivity::class.java.name)
            lockScreenDrawn(x)
            // Cancelled, and straight back to X: Home.
            cancelled(x)
            onScreen(window(2, x))
            send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, x, "$x.MainActivity")
            idle(3_000)
            // Opened again later and unlocked; then left for Home, which ends the session.
            onScreen(window(1, launcher))
            send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, launcher, "$launcher.NexusLauncherActivity")
            onScreen(window(2, x))
            send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, x, "$x.MainActivity")
            lockScreen(resumed = true, target = x)
            unlocked(x)
            send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, x, "$x.MainActivity")
            onScreen(window(1, launcher))
            send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, launcher, "$launcher.NexusLauncherActivity")
            idle(2_000)
        }, teardown = {
            engine.destroy()
            lockScreen(resumed = false, target = null)
            ForegroundRules.resetSystemLists()
        })
    }

    @Test
    fun aCompatibilityTraceReplaysTheSameDecisions() {
        freshVault(bank, "Bank")
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        ShadowSettings.setCanDrawOverlays(true)
        val usage = context.getSystemService(UsageStatsManager::class.java)
        fun event(type: Int, pkg: String, cls: String = "$pkg.MainActivity") {
            shadowOf(usage).addEvent(
                ShadowUsageStatsManager.EventBuilder.buildEvent()
                    .setPackage(pkg).setClass(cls).setEventType(type).setTimeStamp(System.currentTimeMillis()).build()
            )
            Thread.sleep(2)
        }
        lateinit var worker: Looper
        fun step(ms: Long = 200) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
            shadowOf(worker).idle()
            shadowOf(Looper.getMainLooper()).idle()
        }
        val engine = Robolectric.buildService(UsageWatchService::class.java)
        roundTrip("compatibility", play = {
            engine.create()
            worker = (UsageWatchService::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(engine.get()) as HandlerThread).looper
            step()
            event(ACTIVITY_RESUMED, launcher, "$launcher.NexusLauncherActivity")
            step()
            // The bank opens: covered, its lock screen comes up over it and is unlocked.
            event(ACTIVITY_PAUSED, launcher, "$launcher.NexusLauncherActivity")
            event(ACTIVITY_RESUMED, bank)
            step()
            lockScreen(resumed = true, target = bank)
            event(ACTIVITY_PAUSED, bank)
            event(ACTIVITY_RESUMED, context.packageName, LockScreenActivity::class.java.name)
            step()
            lockScreenDrawn(bank)
            unlocked(bank)
            event(ACTIVITY_PAUSED, context.packageName, LockScreenActivity::class.java.name)
            event(ACTIVITY_RESUMED, bank)
            step()
            // Home: the bank re-locks (at once, the default), and opening it again is covered again.
            event(ACTIVITY_PAUSED, bank)
            event(ACTIVITY_RESUMED, launcher, "$launcher.NexusLauncherActivity")
            event(ACTIVITY_STOPPED, bank)
            step(1_000)
            event(ACTIVITY_PAUSED, launcher, "$launcher.NexusLauncherActivity")
            event(ACTIVITY_RESUMED, bank)
            step(1_000)
        }, teardown = {
            engine.destroy()
            lockScreen(resumed = false, target = null)
        })
    }

    @Test
    fun aFieldReadsBackAsItWasWhateverItHolds() {
        for (s in listOf("", "plain", "a,b", "tab\there", "line\nbreak", "100%", "%2C", EngineTrace.NONE, "Messages, 3 notifications")) {
            val written = EngineTrace.escape(s)
            assertTrue(written, written.none { it == '\t' || it == '\n' || it == ',' })
            assertEquals(s, EngineTrace.unescape(written))
        }
        // A window list's entries, escaped inside a field that is escaped again, read back too.
        val entry = listOf("7", "Title, with a comma", "50%").joinToString(",") { EngineTrace.escape(it) }
        assertEquals(listOf("7", "Title, with a comma", "50%"), EngineTrace.unescape(EngineTrace.escape(entry)).split(',').map(EngineTrace::unescape))
    }

    @Test
    fun everyKeptTraceStillDecidesWhatItDid() {
        val dir = javaClass.classLoader?.getResource("traces")?.let { File(it.toURI()) } ?: return
        for (file in dir.listFiles { f -> f.name.endsWith(".trace") }.orEmpty().sortedBy { it.name }) {
            val records = EngineReplay.parse(file.readLines())
            val expected = EngineReplay.decisions(records)
            val played = replay.play(records)
            replay.stop()
            assertEquals("${file.name}: what the engine decides now", expected, played)
        }
    }
}
