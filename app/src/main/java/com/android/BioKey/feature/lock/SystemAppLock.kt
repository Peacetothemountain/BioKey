package com.android.BioKey.feature.lock

/**
 * Android's own App lock (Android 17 QPR3 and later), as the accessibility engine sees it from
 * outside. BioKey can't ask Android which apps it locks (that needs LOCK_APPS, which only the home
 * screen and the system hold), but it can see its cover: a window of the `android` package's
 * [COVER_CLASS]. Only the system can show one (no other app can have a window under that package,
 * as Android checks whose an event is), and Android's prompt comes up over it. Opening an app it
 * locks, that app doesn't even start until the prompt is passed: Android then starts it as it was
 * opened, and the cover goes. Cancelled, the cover goes and nothing opens.
 *
 * Kept here: which of BioKey's locked apps a cover is for, as far as the engine can tell
 * ([attribute]: the app tapped just before it came, or the one Android's prompt names), until
 * something else comes to the front. That app coming to the front first, by a window of its own
 * (never one that can't be read), can only be Android letting it through: its prompt was passed
 * ([onInFront]). Only an app the cover was taken to be for counts, so a cover for an app BioKey
 * doesn't lock changes nothing, and one BioKey couldn't place changes nothing either: the app is
 * asked for as usual.
 *
 * Not thread-safe: the accessibility engine's main thread only.
 */
internal class SystemAppLock {

    companion object {
        /** The package Android's own screens run under. */
        const val PACKAGE = "android"
        /** Android's App lock cover, which hosts its prompt. */
        const val COVER_CLASS = "com.android.internal.app.LockedAppActivity"
        /**
         * How long a cover is taken to still be waiting on its prompt once seen, with nothing else
         * in front since: the prompt has no time limit, but past this the app is asked for again.
         */
        const val PENDING_MS = 120_000L

        /** Whether a window event of [pkg]'s [cls] is Android's App lock cover. */
        fun isCover(pkg: String?, cls: String?): Boolean = pkg == PACKAGE && cls == COVER_CLASS
    }

    /** When a cover was last seen coming (elapsed realtime), or 0 when none is waiting. */
    private var coverAt = 0L
    /** The locked apps the waiting cover is taken to be for. */
    private val apps = LinkedHashSet<String>()
    /** The covers' windows seen, by id: one in the window list is a cover still up. */
    private val coverWindows = HashSet<Int>()
    /** Whether [apps] came from Android's own prompt, which a guess from a tap mustn't then replace. */
    private var namedByPrompt = false

    /** A cover came to the front at [now], in window [windowId] (-1: unknown). */
    fun onCover(windowId: Int, now: Long) {
        if (!isPending(now)) {
            apps.clear()
            namedByPrompt = false
        }
        coverAt = now
        if (windowId >= 0) coverWindows += windowId
    }

    /** A cover is waiting on its prompt, as far as anything since has said. */
    fun isPending(now: Long): Boolean = coverAt != 0L && now - coverAt < PENDING_MS

    /** Whether window [id] is (or was) one of Android's covers. */
    fun isCoverWindow(id: Int): Boolean = id in coverWindows

    /**
     * The waiting cover is taken to be for [packages]. [byPrompt]: Android's prompt names them,
     * which stands over whatever a tap suggested, and isn't replaced by one.
     */
    fun attribute(packages: Collection<String>, byPrompt: Boolean, now: Long) {
        if (!isPending(now) || packages.isEmpty()) return
        if (byPrompt) {
            apps.clear()
            apps += packages
            namedByPrompt = true
        } else if (!namedByPrompt) {
            apps += packages
        }
    }

    /** The locked apps the waiting cover is taken to be for (none while none waits). */
    fun appsFor(now: Long): Set<String> = if (isPending(now)) apps.toSet() else emptySet()

    /** Whether Android's prompt has named the apps already: no need to read it again. */
    val isNamedByPrompt: Boolean get() = namedByPrompt

    /**
     * [pkg]'s own window came to the front at [now]. True if it's an app the waiting cover was for:
     * Android let it through, so its prompt was passed. Either way the cover's moment is over (the
     * cover was cancelled, or Android's unlock has been taken up).
     */
    fun onInFront(pkg: String, now: Long): Boolean {
        val passed = pkg in appsFor(now)
        clear()
        return passed
    }

    /** Nothing is waiting any more: the screen went off, every session ended, or the engine stopped. */
    fun clear() {
        coverAt = 0L
        apps.clear()
        namedByPrompt = false
    }

    /** The window list now has these windows: covers no longer there are forgotten. */
    fun retainWindows(liveIds: Set<Int>) {
        coverWindows.retainAll(liveIds)
    }
}
