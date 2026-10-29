package com.android.BioKey

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Rect
import android.os.CancellationSignal
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityRecord
import android.view.accessibility.AccessibilityWindowInfo
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.diagnostics.EngineTrace
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.feature.lock.InstantShieldOverlay
import com.android.BioKey.feature.lock.LockCoverView
import com.android.BioKey.feature.lock.LockScreenActivity
import com.android.BioKey.feature.lock.UsageReader
import com.android.BioKey.feature.lock.UsageWatchService
import java.lang.ref.WeakReference
import java.time.Duration
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowUsageStatsManager

/**
 * Plays an engine trace (core/diagnostics/EngineTrace) back into the real engine, on Robolectric's
 * clock: the phone it was recorded on set up as the trace says (its `env` records), then every
 * input at the time it came, relative to the first. What the engine decides is recorded as it
 * was on the phone, so a replay's [decisions] can be compared with the trace's own.
 *
 * Not replayed, so not part of [decisions]: what BioKey's lock screen does by itself (it doesn't
 * run here; what the engines were told of it is replayed), and what the notification shield does.
 * Names of apps that aren't locked aren't in a trace either, so an unreadable window titled after
 * one of those isn't told apart here.
 */
internal class EngineReplay(private val context: Application) {

    class Record(val time: Long, val kind: String, val fields: List<String>)

    companion object {
        /** A trace's lines as records; comments and blank lines skipped. */
        fun parse(lines: List<String>): List<Record> = lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val parts = line.split('\t')
            Record(parts[0].toLong(), parts[1], parts.drop(2).map(EngineTrace::unescape))
        }

        /** What the engines decided, in order: the lock screens they started, Home, and their lock decisions logged. */
        fun decisions(records: List<Record>): List<String> = records.mapNotNull { r ->
            when (r.kind) {
                "o.launch" -> "launch ${r.fields[0]} on ${r.fields[1]}" + if (r.fields.getOrNull(2) == "1") " (again)" else ""
                "o.home" -> "home"
                "o.log" -> r.fields[0].takeIf { text -> DECISIONS.any(text::startsWith) && !text.endsWith(LOCK_SCREEN_LEFT) }?.let { "log $it" }
                else -> null
            }
        }

        /** The diagnostic log lines that are an engine's decisions (the rest say what the owner did). */
        private val DECISIONS = listOf("Covered ", "Tap on ", "Kept ", "Lock screen for ", "Session ended", "All sessions ended")

        /** How BioKey's lock screen logs being left ("Lock screen for X left without unlocking"): its own line, not a replayed engine's. */
        private const val LOCK_SCREEN_LEFT = " without unlocking"

        /** The window state flags of a trace's `a.win` entries. */
        private const val ACTIVE = 'a'
        private const val FOCUSED = 'f'
        private const val PIP = 'p'
    }

    private var a11y: ServiceController<BioKeyAccessibilityService>? = null
    private var compat: ServiceController<UsageWatchService>? = null
    private var worker: Looper? = null
    private var startedAt = 0L
    private var firstTime = 0L
    private var lastBroadcast: Pair<Long, String>? = null
    private var lastUsageTime = 0L
    private val displaysSet = HashSet<Int>()

    /** Plays [records] back, from a freshly set-up phone, and returns what the engine decided. */
    fun play(records: List<Record>): List<String> {
        val env = records.filter { it.kind == "env" }
        setUp(env)
        val engine = env.firstOrNull { it.fields[0] == "engine" }?.fields?.get(1) ?: "accessibility"
        startEngine(engine)
        startedAt = SystemClock.uptimeMillis()
        val played = records.filter { it.kind != "env" }
        firstTime = played.firstOrNull()?.time ?: 0L
        val applied = BooleanArray(played.size)
        for ((i, r) in played.withIndex()) {
            if (applied[i] || r.kind.startsWith("o.")) continue
            val at = startedAt + (r.time - firstTime)
            if (isState(r)) {
                // What a read found at that moment (a timer's, not an input's): there by then.
                runUntil(at - 1)
                applyState(r)
                continue
            }
            runUntil(at)
            // What the engine read while handling this input is recorded after it, at the same
            // moment: it was so before the input came.
            var firstWindows = true
            for (j in i + 1 until played.size) {
                val next = played[j]
                if (next.time != r.time) break
                if (!isState(next)) {
                    if (next.kind.startsWith("o.")) continue else break
                }
                if (next.kind == "a.win") {
                    // Only the first: an event's handling reads the window list once.
                    if (!firstWindows) break
                    firstWindows = false
                }
                applyState(next)
                applied[j] = true
            }
            apply(r)
        }
        return decisions(parse(EngineTrace.lines()))
    }

    /** A record of what a read found (the window list, the phone's state), not an input. */
    private fun isState(r: Record) = r.kind == "a.win" || r.kind == "d.interactive" || r.kind == "d.deviceLocked" || r.kind == "d.keyguard"

    private fun applyState(r: Record) {
        if (r.kind == "a.win") applyWindows(r.fields) else apply(r)
    }

    fun stop() {
        a11y?.destroy()
        compat?.destroy()
        setLockScreen(resumed = false, target = null)
        setPrompt(null)
        ForegroundRules.resetSystemLists()
    }

    private fun setUp(env: List<Record>) {
        SecurityVault.getInstance(context).awaitPendingWrites()
        context.getSharedPreferences("biokey_vault", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityVault.resetForTests()
        LockStateTracker.lockAll()
        // Robolectric's clock starts again for each test; a lock screen "resumed" in an earlier one
        // in this JVM would otherwise look newer than anything started here.
        LockScreenActivity::class.java.getDeclaredField("resumedAt").apply { isAccessible = true }.setLong(null, 0L)
        DiagnosticLog.clear()
        // A fresh phone: no usage history from before the trace (a recording made in the same test, say).
        ShadowUsageStatsManager.reset()
        val vault = SecurityVault.getInstance(context)
        vault.setSetupCompletedSync(true)
        val locked = env.filter { it.fields[0] == "locked" }
        // Re-lock delays: the commonest is the one for every app, the others get a profile each.
        val modes = locked.groupBy { LockTimeoutMode.valueOf(it.fields[2]) }
        val common = modes.maxByOrNull { it.value.size }?.key
        if (common != null) vault.setTimeoutMode(common)
        for (r in locked) {
            val pkg = r.fields[1]
            val label = r.fields.getOrNull(3).orEmpty()
            shadowOf(context.packageManager).installPackage(PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    nonLocalizedLabel = label
                }
            })
            vault.setAppLock(pkg, true)
        }
        for ((mode, apps) in modes) {
            if (mode == common) continue
            val profile = vault.createProfile("Replay ${mode.name}", "shield", mode)
            for (r in apps) vault.moveAppToProfile(r.fields[1], profile.id)
        }
        if (env.any { it.fields[0] == "protection" && it.fields[1] == "0" }) vault.setGlobalLockEnabled(false)
        val homes = env.filter { it.fields[0] == "home" }
        ForegroundRules.pinSystemLists(
            homeApps = homes.associate { it.fields[1] to (it.fields[2] == "1") },
            defaultHome = homes.firstOrNull { it.fields[3] == "1" }?.fields?.get(1),
            helpers = env.filter { it.fields[0] == "picker" }.mapTo(HashSet()) { it.fields[1] },
            inputMethods = env.filter { it.fields[0] == "keyboard" }.mapTo(HashSet()) { it.fields[1] }
        )
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).apply {
            setIsDeviceLocked(false)
            setKeyguardLocked(false)
        }
    }

    private fun startEngine(engine: String) {
        if (engine == "compatibility") {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
            ShadowSettings.setCanDrawOverlays(true)
            compat = Robolectric.buildService(UsageWatchService::class.java).create().also { c ->
                worker = (UsageWatchService::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(c.get()) as HandlerThread).looper
            }
        } else {
            a11y = Robolectric.buildService(BioKeyAccessibilityService::class.java).create().also { c ->
                BioKeyAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(c.get())
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Lets time pass up to [at] (uptime), a poll's worth at most at a time, as on the phone. */
    private fun runUntil(at: Long) {
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now >= at) break
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(minOf(150L, at - now)))
            worker?.let { shadowOf(it).idle() }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun apply(r: Record) {
        val f = r.fields
        when (r.kind) {
            "a.ev" -> a11y?.get()?.onAccessibilityEvent(event(f))
            "c.poll" -> poll(lookBack = f[0] == "1", events = f.drop(1))
            "d.interactive" -> shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(f[0] == "1")
            "d.deviceLocked" -> shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(f[0] == "1")
            "d.keyguard" -> shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(f[0] == "1")
            "d.bc" -> {
                // Both the app's screen receiver and the engine's record a screen-off: sent once.
                val sent = r.time to f[0]
                if (lastBroadcast != sent) context.sendBroadcast(Intent(f[0]))
                lastBroadcast = sent
            }
            "l.resumed" -> setLockScreen(resumed = f[0] == "1", target = LockScreenActivity.activeLockedTarget)
            "l.target" -> setLockScreen(resumed = LockScreenActivity.isLockScreenResumed, target = f.getOrNull(0)?.takeIf { it.isNotEmpty() })
            "l.dismiss" -> InstantShieldOverlay.dismiss(f[0])
            "p.up" -> setPrompt(f[0])
            "p.down" -> setPrompt(null)
            "u.unlock" -> LockStateTracker.onAppUnlocked(f[0])
            "u.abandon" -> LockStateTracker.markLockAbandoned(f[0])
            "u.displaced" -> LockStateTracker.markLockDisplaced(f[0])
            "s.tap" -> shieldCover()?.onUnlock?.invoke()
            "s.cancel" -> shieldCover()?.onCancel?.invoke()
            "s.lockDevice" -> shieldCover()?.onLockDevice?.invoke()
        }
    }

    private fun event(f: List<String>): AccessibilityEvent = AccessibilityEvent(f[0].toInt()).apply {
        packageName = f[1].takeIf { it.isNotEmpty() }
        className = f[2].takeIf { it.isNotEmpty() }
        hidden(AccessibilityRecord::class.java, "setWindowId", f[3].toInt())
        if (f[4].toInt() >= 0) hidden(AccessibilityEvent::class.java, "setDisplayId", f[4].toInt())
        hidden(AccessibilityEvent::class.java, "setWindowChanges", f[5].toInt())
        contentChangeTypes = f[6].toInt()
        contentDescription = f[7].takeIf { it != EngineTrace.NONE }
        text.addAll(f.drop(8))
    }

    /** Calls one of Android's hidden setters ([name]) on this event, as the system does. */
    private fun AccessibilityEvent.hidden(owner: Class<*>, name: String, value: Int) {
        runCatching { owner.getDeclaredMethod(name, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(this, value) }
    }

    private fun applyWindows(fields: List<String>) {
        val engine = a11y?.get() ?: return
        val byDisplay = HashMap<Int, MutableList<AccessibilityWindowInfo>>()
        var activeRoot: AccessibilityNodeInfo? = null
        for (field in fields) {
            val v = field.split(',').map(EngineTrace::unescape)
            val (l, t, r, b) = v[5].split(' ').map(String::toInt)
            val pkg = v[7].takeIf { it.isNotEmpty() }
            val root = pkg?.let { p -> AccessibilityNodeInfo.obtain().apply { packageName = p } }
            val w = AccessibilityWindowInfo.obtain().also { w ->
                shadowOf(w).apply {
                    setId(v[0].toInt())
                    setType(v[1].toInt())
                    setDisplayId(v[2].toInt())
                    setActive(ACTIVE in v[3])
                    setFocused(FOCUSED in v[3])
                    setPictureInPicture(PIP in v[3])
                    setLayer(v[4].toInt())
                    setBoundsInScreen(Rect(l, t, r, b))
                    setTitle(v[6].takeIf { it.isNotEmpty() })
                    if (root != null) setRoot(root)
                }
            }
            if (ACTIVE in v[3] && activeRoot == null) activeRoot = root
            byDisplay.getOrPut(v[2].toInt(), ::ArrayList) += w
        }
        val service = shadowOf(engine)
        for (display in displaysSet + byDisplay.keys) service.setWindowsOnDisplay(display, byDisplay[display].orEmpty())
        displaysSet += byDisplay.keys
        service.setRootInActiveWindow(activeRoot)
    }

    /**
     * A compatibility-mode poll as on the phone: the usage events it read for the first time are
     * logged (each as long before now as it was before that read, in their order), and the engine
     * polls now, its result handled at once. A look back ([lookBack]) is one here too.
     */
    private fun poll(lookBack: Boolean, events: List<String>) {
        val service = compat?.get() ?: return
        val usage = shadowOf(context.getSystemService(UsageStatsManager::class.java))
        for (field in events) {
            val v = field.split(',').map(EngineTrace::unescape)
            // Usage access stamps events with the wall clock: kept in order, never in the future.
            val late = if (lookBack) v[3].toLong().coerceAtLeast(0L) else v[3].toLong().coerceIn(0L, 1_500L)
            var at = System.currentTimeMillis() - late
            if (at <= lastUsageTime) at = lastUsageTime + 1
            while (System.currentTimeMillis() < at) Thread.sleep(1)
            lastUsageTime = at
            usage.addEvent(
                ShadowUsageStatsManager.EventBuilder.buildEvent()
                    .setPackage(v[1]).setClass(v[2].takeIf { it.isNotEmpty() }).setEventType(v[0].toInt()).setTimeStamp(at).build()
            )
        }
        // The engine's own next poll is put off: this one is it.
        val pollTask = UsageWatchService::class.java.getDeclaredField("pollTask").apply { isAccessible = true }.get(service) as Runnable
        val handler = UsageWatchService::class.java.getDeclaredField("workerHandler").apply { isAccessible = true }.get(service) as android.os.Handler
        if (lookBack) {
            // The service reads through its UsageReader: that look is a look back.
            val reader = UsageWatchService::class.java.getDeclaredField("reader\$delegate").apply { isAccessible = true }.get(service) as Lazy<*>
            handler.post { (reader.value as UsageReader).lookBackNext() }
        }
        handler.removeCallbacks(pollTask)
        handler.post(pollTask)
        worker?.let { shadowOf(it).idle() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun setLockScreen(resumed: Boolean, target: String?) {
        LockScreenActivity::class.java.getDeclaredField("isLockScreenResumed").apply { isAccessible = true }.setBoolean(null, resumed)
        LockScreenActivity::class.java.getDeclaredField("activeLockedTarget").apply { isAccessible = true }.set(null, target)
        if (resumed) LockScreenActivity::class.java.getDeclaredField("resumedAt").apply { isAccessible = true }.setLong(null, SystemClock.uptimeMillis())
    }

    private fun setPrompt(pkg: String?) {
        val active = SecurityEngine::class.java.getDeclaredField("active").apply { isAccessible = true }
        if (pkg == null) return active.set(SecurityEngine.INSTANCE, null)
        val attempt = Class.forName("${SecurityEngine::class.java.name}\$Attempt").declaredConstructors.single().apply { isAccessible = true }
        active.set(SecurityEngine.INSTANCE, attempt.newInstance(pkg, WeakReference<Activity>(null), CancellationSignal()))
    }

    /** The cover of the engine's shield, whose buttons the owner pressed. */
    private fun shieldCover(): LockCoverView? {
        val service: Any = a11y?.get() ?: compat?.get() ?: return null
        val shield = service.javaClass.getDeclaredField("shield").apply { isAccessible = true }.get(service) as InstantShieldOverlay
        return InstantShieldOverlay::class.java.getDeclaredField("cover").apply { isAccessible = true }.get(shield) as LockCoverView
    }
}
