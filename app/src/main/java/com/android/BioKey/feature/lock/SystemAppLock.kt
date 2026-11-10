package com.android.BioKey.feature.lock

/**
 * Android's own App lock (Android 17 QPR3 and later), as the accessibility engine sees it from
 * outside. BioKey can't ask Android which apps it locks (that needs LOCK_APPS, which only the home
 * screen and the system hold), but it can see its cover: a window of the `android` package's
 * [COVER_CLASS]. Only the system can show one (no other app can have a window under that package,
 * as Android checks whose an event is), and Android's prompt comes up over it, in a window of
 * SystemUI's ([onPromptWindow]). Opening an app it locks, that app doesn't even start until the
 * prompt is passed: Android then starts it as it was opened, and the cover goes. Cancelled, the
 * cover goes and nothing opens.
 *
 * Kept here: which of BioKey's locked apps a cover is for, only as Android's own prompt names it
 * ([attribute]); a tap just before it came is a guess, for the log and "Also locked by Android"
 * only ([guess]), never enough to skip BioKey's own prompt. Then the moment the cover goes: that
 * app's own window (never one that can't be read) coming to the front while the cover or its
 * prompt is still on screen, or within [HANDOFF_MS] of both leaving it, can only be Android
 * letting it through, its prompt passed ([onInFront]). Anything else, or nothing in time, and the
 * moment is over: the app is asked for as usual.
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
         * How long a cover is taken to still be waiting on its prompt while it's up: the prompt has
         * no time limit, but past this the app is asked for again.
         */
        const val PENDING_MS = 120_000L
        /** How soon after the cover and its prompt left the screen the app it was for must be in front. */
        const val HANDOFF_MS = 1_500L
        /** A cover never seen in a window list counts as gone once a list without it comes this long after it. */
        private const val UNLISTED_GONE_MS = 500L

        /** Whether a window event of [pkg]'s [cls] is Android's App lock cover. */
        fun isCover(pkg: String?, cls: String?): Boolean = pkg == PACKAGE && cls == COVER_CLASS
    }

    /** When a cover was last seen coming (elapsed realtime), or 0 when none is waiting. */
    private var coverAt = 0L
    /** When the cover and its prompt were first missing from the window list, or 0 while they're on it. */
    private var goneAt = 0L
    /** Whether a window list has had the cover or its prompt on it yet. */
    private var listed = false
    /** The locked apps the waiting cover is for, as its prompt names them. */
    private val apps = LinkedHashSet<String>()
    /** Apps the waiting cover has let through already. */
    private val passed = HashSet<String>()
    /** The app tapped just before the cover came, if any: a guess, never an attribution. */
    var guessed: String? = null
        private set
    /** The covers' windows seen, by id. */
    private val coverWindows = HashSet<Int>()
    /** The windows of the cover's prompt (SystemUI's), by id. */
    private val promptWindows = HashSet<Int>()
    /** The windows on screen just before the cover came: its prompt is none of them. */
    private var windowsBefore: Set<Int> = emptySet()
    /** Whether [apps] came from Android's own prompt already: no need to read it again. */
    private var namedByPrompt = false

    /**
     * A cover came to the front at [now], in window [windowId] (-1: unknown); [before] are the
     * windows that were on screen just before (when it's a new cover).
     */
    fun onCover(windowId: Int, now: Long, before: Set<Int>) {
        if (!isPending(now)) {
            clear()
            windowsBefore = before
        }
        coverAt = now
        goneAt = 0L
        if (windowId >= 0) coverWindows += windowId
    }

    /**
     * A cover is waiting: up (its window or its prompt's on screen, for at most [PENDING_MS]), or
     * gone from the screen less than [HANDOFF_MS] ago.
     */
    fun isPending(now: Long): Boolean = coverAt != 0L &&
        (if (goneAt == 0L) now - coverAt < PENDING_MS else now - goneAt < HANDOFF_MS)

    /** Whether window [id] is one of Android's covers. */
    fun isCoverWindow(id: Int): Boolean = id in coverWindows

    /** Whether window [id] can be the waiting cover's prompt: one that wasn't on screen before the cover came. */
    fun mayBePromptWindow(id: Int): Boolean = coverAt != 0L && id !in windowsBefore

    /** Window [id] is the waiting cover's prompt. */
    fun onPromptWindow(id: Int) {
        if (coverAt != 0L) promptWindows += id
    }

    /**
     * A window list was read at [now], with these windows ([liveIds]): the cover or its prompt
     * still on it, or the moment they left it, from which the app has [HANDOFF_MS] to come.
     */
    fun onWindows(liveIds: Set<Int>, now: Long) {
        if (coverAt == 0L) return
        val up = liveIds.any { it in coverWindows || it in promptWindows }
        when {
            up -> {
                listed = true
                goneAt = 0L
            }
            goneAt == 0L && (listed || now - coverAt >= UNLISTED_GONE_MS) -> goneAt = now
        }
        if (!isPending(now)) clear()
    }

    /** The waiting cover is for [packages], as its prompt names them (none of BioKey's locked apps, when empty). */
    fun attribute(packages: Collection<String>, now: Long) {
        if (!isPending(now)) return
        apps.clear()
        apps += packages
        namedByPrompt = true
    }

    /** The app tapped just before the cover came: for the log and "Also locked by Android", never to skip a prompt. */
    fun guess(pkg: String?) {
        guessed = pkg
    }

    /** The locked apps the waiting cover is for (none while none waits). */
    fun appsFor(now: Long): Set<String> = if (isPending(now)) apps.toSet() else emptySet()

    /** Whether Android's prompt has named the apps already: no need to read it again. */
    val isNamedByPrompt: Boolean get() = namedByPrompt

    /**
     * [pkg]'s own window came to the front at [now] (the window list as of now told first,
     * [onWindows]). True if it's an app the waiting cover was for: Android let it through, so its
     * prompt was passed, and its unlock is taken up. Any other app, and the cover's moment is over
     * (it was cancelled, and closed onto what was below). One prompt for several apps (split
     * screen) lets them all through: the others still count as they come.
     */
    fun onInFront(pkg: String, now: Long): Boolean {
        if (pkg in appsFor(now)) {
            apps -= pkg
            passed += pkg
            return true
        }
        // One already let through, still coming forward (its next screen, its next event).
        if (isPending(now) && pkg in passed) return false
        clear()
        return false
    }

    /** Nothing is waiting any more: the screen went off, every session ended, or the engine stopped. */
    fun clear() {
        coverAt = 0L
        goneAt = 0L
        listed = false
        apps.clear()
        passed.clear()
        guessed = null
        coverWindows.clear()
        promptWindows.clear()
        windowsBefore = emptySet()
        namedByPrompt = false
    }
}
