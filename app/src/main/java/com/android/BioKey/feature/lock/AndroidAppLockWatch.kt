package com.android.BioKey.feature.lock

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.VisibleForTesting
import com.android.BioKey.BioKeyApplication
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.diagnostics.EngineTrace
import com.android.BioKey.core.security.AlsoLockedByAndroid
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.UnlockAttempts
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The accessibility engine living alongside Android's own App lock (Android 17 QPR3): its cover
 * and prompt coming and going, which of BioKey's locked apps they're for, and an app that got
 * through it, whose session then starts without BioKey asking again. What it knows is kept in
 * [state] ([SystemAppLock]); the window lists and window events come from the engine ([Host]).
 */
internal class AndroidAppLockWatch(
    private val context: Context,
    private val handler: Handler,
    private val host: Host
) {

    /** What the engine gives the watch. */
    interface Host {
        fun windowList(): List<AccessibilityWindowInfo>?
        /** The package window [windowId]'s own events named, if any. */
        fun knownPackage(windowId: Int): String?
        fun isLocked(pkg: String): Boolean
        /** [pkg] has a session its re-lock delay still honours. */
        fun isUnlocked(pkg: String): Boolean
        fun isBiometricOnly(): Boolean
        /**
         * A cover BioKey put up at a tap is lowered, so Android's prompt shows. Returns the app it
         * was for if the tap named that one locked app only, moments ago; else null.
         */
        fun lowerTapCover(now: Long): String?
        fun guarded(what: String, block: () -> Unit)
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        /** The prompt is looked for again this long after its cover came ([readPrompt]). */
        private const val PROMPT_READ_DELAY_MS = 400L
        /** At most this many looks at a cover's prompt, and this many of its nodes each time: it has a handful. */
        private const val MAX_PROMPT_READS = 6
        private const val MAX_PROMPT_NODES = 80
        /** The apps' names are read again this long after the last change to the phone's apps: an update of many is one read. */
        private const val APPS_SETTLE_MS = 2_000L
    }

    /** Android's App lock cover, and which locked app it's for. */
    val state = SystemAppLock()

    /**
     * Android's App lock prompt's words in the phone's language (none before Android 17 QPR3): see
     * [SystemAppLockPrompt]. Read again as the language changes ([onLanguage]); tests set their own.
     */
    @VisibleForTesting
    internal var promptWords: SystemAppLockPrompt? = null

    /** The windows of the last window list read: what was on screen just before a cover came. */
    private var lastWindowIds: Set<Int> = emptySet()

    /** Looks at the prompt of a waiting cover this many times at most (each a read of SystemUI's window). */
    private var promptReads = 0
    private val readPromptLater = Runnable { host.guarded("Android's App lock prompt") { readPrompt() } }

    /**
     * What the names of the apps that can be opened were read for: the phone's apps (this
     * profile's, [BioKeyApplication.packageChangeVersion], and every profile's, [appsChanged]) and
     * its language.
     */
    private data class NamesFor(val packages: Int, val changes: Int, val locales: String)

    /**
     * Counts the changes to the apps the launcher shows, in any of the phone's profiles (a work
     * app installed is heard of only this way), and reads their names again a moment after the
     * last of a run of them ([APPS_SETTLE_MS]): ahead of the next prompt, which is passed in less
     * time than the read can take. Engine's thread.
     */
    private var appsChanged = 0
    private var watchingApps = false
    private val readNamesSoon = Runnable { warmUp() }
    private val appsWatch = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed()
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()

        private fun changed() {
            appsChanged++
            handler.removeCallbacks(readNamesSoon)
            handler.postDelayed(readNamesSoon, APPS_SETTLE_MS)
        }
    }

    /** The read asked for last ([namesNow]): one queued before it reads nothing. */
    @Volatile private var namesWanted: NamesFor? = null

    /** Every app that can be opened, by name ([namesNow]), and what that was read for. Engine's thread. */
    private var launchableNames: Pair<NamesFor, Map<String, List<String>>>? = null

    /** The read of them now under way, if any ([namesNow]). Engine's thread. */
    private var readingNamesFor: NamesFor? = null

    /**
     * Where the names are read: a package-manager call and a label (from the app's own resources)
     * for every app on the phone, which is never done on the engine's thread, where a cover could
     * wait behind it. Tests read in place.
     */
    @VisibleForTesting
    internal var namesReader: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-app-lock-names").apply { isDaemon = true } }

    fun isPending(now: Long = SystemClock.elapsedRealtime()) = state.isPending(now)

    /** The phone's language changed: Android's prompt is worded in the new one, and names apps in it. */
    fun onLanguage() {
        promptWords = SystemAppLockPrompt.fromSystem()
        warmUp()
    }

    /**
     * Reads the apps' names ahead of the first prompt, and from then on whenever the phone's apps
     * change ([appsWatch]). Only where the phone has Android's App lock at all.
     */
    fun warmUp() {
        if (promptWords == null) return
        if (!watchingApps) {
            val apps = context.getSystemService(LauncherApps::class.java)
            watchingApps = apps != null && runCatching { apps.registerCallback(appsWatch, handler) }.isSuccess
        }
        namesNow()
    }

    /** The engine is going: nothing more is read. */
    fun shutdown() {
        clear()
        handler.removeCallbacks(readNamesSoon)
        if (watchingApps) {
            watchingApps = false
            runCatching { context.getSystemService(LauncherApps::class.java)?.unregisterCallback(appsWatch) }
        }
        (namesReader as? ExecutorService)?.shutdownNow()
    }

    /** The phone locked, or the engine stopped: whatever Android's App lock was doing is over. */
    fun clear() {
        handler.removeCallbacks(readPromptLater)
        state.clear()
    }

    /**
     * Android's own App lock cover ([SystemAppLock]) came to the front, in window [windowId], and
     * its prompt with it. It's a system overlay to the rest of the engine (nothing is left for it,
     * nothing is covered for it), and its prompt must stay in view: a cover BioKey put up at the
     * tap that opened it is lowered, or it would hide Android's prompt and the owner would face
     * two at once. Nothing of the app shows meanwhile: Android starts it only once its prompt is
     * passed (or, for one already open, keeps it behind an opaque cover in its own window).
     *
     * Which app it's for: only Android's prompt says ([readPrompt]). The app tapped a moment ago,
     * if it's the one locked app of that name, is a guess: logged, and noted as also locked by
     * Android, never a reason to skip BioKey's own prompt.
     */
    fun onCover(windowId: Int) {
        val now = SystemClock.elapsedRealtime()
        val wasPending = state.isPending(now)
        state.onCover(windowId, now, before = lastWindowIds)
        host.lowerTapCover(now)?.let { tapped ->
            state.guess(tapped)
            AlsoLockedByAndroid.note(context, tapped)
        }
        // Once per cover: it sends several events as it comes.
        if (!wasPending) DiagnosticLog.log(
            state.guessed?.let { "Android's App lock is asking, just after a tap on $it" } ?: "Android's App lock is asking for an app"
        )
        // Its prompt names an app by name: the names, if they aren't read yet, are from now.
        if (promptWords != null) namesNow()
        // Its prompt names the app: read as it comes ([readPrompt]), and once more in a moment, in
        // case it came without saying so.
        promptReads = 0
        handler.removeCallbacks(readPromptLater)
        handler.postDelayed(readPromptLater, PROMPT_READ_DELAY_MS)
    }

    /**
     * Every window list read ([WindowReader]): while Android's App lock cover waits, whether it or
     * its prompt is still on screen ([SystemAppLock.onWindows]); its prompt is a biometric
     * prompt's window ([SystemAppLockPrompt.isPromptWindow]) that wasn't there before the cover.
     */
    fun onWindows(list: List<AccessibilityWindowInfo>) {
        val ids = list.mapTo(HashSet()) { it.id }
        val now = SystemClock.elapsedRealtime()
        if (state.isPending(now)) {
            val words = promptWords
            for (w in list) {
                if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM && state.mayBePromptWindow(w.id) && words?.isPromptWindow(w.title) == true) {
                    state.onPromptWindow(w.id)
                }
            }
            state.onWindows(ids, now)
        }
        lastWindowIds = ids
    }

    /**
     * While Android's App lock cover waits, reads its prompt for the app it names: its name beside
     * its icon, or "X and Y are protected with app lock". That says which app the cover is for,
     * whoever opened it (a tap, a notification, a link). Only the prompt itself is read: SystemUI's
     * window that came with the cover and is titled as a biometric prompt, never the notification
     * shade or a heads-up, which could show any words. A name counts only if exactly one app on
     * the phone that can be opened (in any of its profiles) is called that, and BioKey locks it;
     * a prompt naming one app must name exactly one. A prompt naming none of them means the cover
     * isn't for any of BioKey's. The words read aren't kept: only which locked app they named
     * ([AlsoLockedByAndroid], and the diagnostic log).
     */
    fun readPrompt() {
        val now = SystemClock.elapsedRealtime()
        if (!state.isPending(now) || state.isNamedByPrompt || promptReads >= MAX_PROMPT_READS) return
        val words = promptWords ?: return
        // Not read yet (the phone's apps or language just changed): looked at again once they are,
        // which isn't one of this cover's reads.
        val named = namesNow() ?: return
        val list = host.windowList() ?: return
        promptReads++
        for (w in list) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM || !state.mayBePromptWindow(w.id) || !words.isPromptWindow(w.title)) continue
            val root = runCatching { w.root }.getOrNull() ?: continue
            try {
                if (root.packageName?.toString() != SYSTEM_UI) continue
                val texts = AccessibilityNodes.texts(root, MAX_PROMPT_NODES)
                val names = words.namesIn(texts) ?: continue
                state.onPromptWindow(w.id)
                // Each name with the apps called it; a name no app has (a button) says nothing.
                val found = names.map { named[it].orEmpty() }.filter { it.isNotEmpty() }
                val apps = when {
                    words.namesOneApp(texts) -> found.singleOrNull()?.singleOrNull()?.let(::listOf).orEmpty()
                    found.size == names.size && found.all { it.size == 1 } -> found.map { it.single() }
                    else -> emptyList()
                }.filter(host::isLocked)
                state.attribute(apps, now)
                for (pkg in apps) AlsoLockedByAndroid.note(context, pkg)
                if (apps.isNotEmpty()) DiagnosticLog.log("Android's App lock prompt names ${apps.joinToString()}")
                return
            } finally {
                root.release()
            }
        }
    }

    /**
     * The apps that can be opened (from the home screen, in any of the phone's profiles BioKey can
     * see), by their exact name: a package in this profile, `package@profile` in another (so one
     * installed in both is two). Android's prompt names an app by its name alone, so only a name
     * one app has says which. Read once per change of the phone's apps or language, on
     * [namesReader]; null until that read is done, which then looks at a waiting prompt again.
     * (Until then that prompt can't be placed, and passed meanwhile, BioKey asks too: the reason
     * the names are read as the apps change, [appsWatch], not as a prompt waits.)
     */
    private fun namesNow(): Map<String, List<String>>? {
        val wanted = NamesFor(
            BioKeyApplication.packageChangeVersion.value, appsChanged, context.resources.configuration.locales.toLanguageTags()
        )
        launchableNames?.takeIf { it.first == wanted }?.let { return it.second }
        if (readingNamesFor == wanted) return null
        readingNamesFor = wanted
        namesWanted = wanted
        runCatching {
            namesReader.execute {
                // Overtaken while it waited its turn (more apps changed): the read after it is the one.
                if (namesWanted != wanted) return@execute
                val read = readLaunchableNames()
                val done = Runnable {
                    launchableNames = wanted to read
                    if (readingNamesFor == wanted) readingNamesFor = null
                }
                // Read in place (tests): there to use at once.
                if (Looper.myLooper() == handler.looper) done.run() else handler.post {
                    done.run()
                    readPromptLater.run()
                }
            }
        }.onFailure { readingNamesFor = null }
        return launchableNames?.takeIf { it.first == wanted }?.second
    }

    private fun readLaunchableNames(): Map<String, List<String>> = runCatching {
        val found = HashMap<String, MutableSet<String>>()
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (pkg in pm.queryIntentActivities(launcher, 0).mapNotNullTo(HashSet()) { it.activityInfo?.packageName }) {
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull() ?: continue
            found.getOrPut(label) { HashSet() } += pkg
        }
        val me = Process.myUserHandle()
        val apps = context.getSystemService(LauncherApps::class.java)
        for (user in context.getSystemService(UserManager::class.java)?.userProfiles.orEmpty()) {
            if (user == me) continue
            for (activity in runCatching { apps?.getActivityList(null, user) }.getOrNull().orEmpty()) {
                val label = runCatching { pm.getApplicationLabel(activity.applicationInfo).toString() }.getOrNull() ?: continue
                found.getOrPut(label) { HashSet() } += "${activity.componentName.packageName}@${user.hashCode()}"
            }
        }
        found.mapValues { it.value.toList() }
    }.getOrDefault(emptyMap())

    /**
     * [pkg]'s own window is in front: if Android's App lock cover was waiting for it and has just
     * gone, its prompt was passed ([onPassed]). The window list is read first, so the cover's
     * going is known.
     */
    fun checkPassed(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (pkg !in state.appsFor(now)) return
        host.windowList()
        if (state.onInFront(pkg, now)) onPassed(pkg)
    }

    /**
     * [target] came to the front by a window event (not a stale launcher one while the cover is
     * still up: the engine sees to that). Android's App lock cover's moment is over with it: the
     * app it was for, by a window of its own ([identified]), passed its prompt.
     */
    fun onAppInFront(target: String, identified: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!state.isPending(now)) return
        if (state.onInFront(target, now) && identified) onPassed(target)
    }

    /**
     * The visibility pass's app in front, [front], from window [windowId]. A regular app there
     * ends Android's App lock cover's moment (cancelled, it closed onto whatever was below); the
     * app it was for, by a window of its own, passed its prompt.
     */
    fun onFront(front: String, windowId: Int) {
        val now = SystemClock.elapsedRealtime()
        if (!state.isPending(now)) return
        // The cover itself, its prompt, BioKey's own windows and windows nobody can read say nothing.
        if (front == SystemAppLock.PACKAGE || front == SYSTEM_UI || front == context.packageName ||
            front == SessionRules.UNIDENTIFIED_APP || state.isCoverWindow(windowId)
        ) return
        val passed = state.onInFront(front, now)
        if (passed && windowId >= 0 && host.knownPackage(windowId) == front) onPassed(front)
    }

    /**
     * [pkg], a locked app, came to the front as Android's App lock cover for it went: Android let
     * it through, its prompt passed. One prompt is enough: its session starts without BioKey
     * asking again. Except where the owner wants BioKey's own say, which Android's prompt can't
     * give: face or fingerprint only (Android always accepts the screen lock), or failed unlock
     * alerts (a face or fingerprint that didn't match on Android's prompt never reaches BioKey,
     * nor does how it was passed). Then it's asked for as usual.
     */
    private fun onPassed(pkg: String) {
        if (!host.isLocked(pkg) || host.isUnlocked(pkg)) return
        UnlockAttempts.load(context)
        val why = when {
            host.isBiometricOnly() -> "face or fingerprint only"
            UnlockAttempts.enabled.value -> "failed unlock alerts are on"
            else -> null
        }
        if (why != null) {
            DiagnosticLog.log("$pkg opened through Android's App lock; asking again ($why)")
            return
        }
        EngineTrace.record("u.android", pkg)
        if (LockStateTracker.onAppUnlocked(pkg)) DiagnosticLog.log("$pkg opened through Android's App lock: not asked again")
    }
}
