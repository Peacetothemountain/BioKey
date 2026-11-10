package com.android.BioKey.feature.lock

import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.security.LockStateTracker

/**
 * The accessibility engine's session rules, kept apart from Android so they can be tested.
 *
 * Given what is on screen ([Screen]), [reconcile] decides which unlocked apps the user has left
 * (their sessions then age, or end, in [LockStateTracker]) and whether a locked app on screen
 * without a session needs covering. The service turns the window list into a [Screen] and
 * acts on the [Outcome].
 *
 * The rules:
 * 1. An app on screen is in use: nothing ages.
 * 2. An app with a session counts as left once its windows have been gone for [LEAVE_CONFIRM_MS]
 *    (rotations, an app restarting its own screen; just unlocked, it first has [UNLOCK_ARRIVAL_MS]
 *    to reach the screen, unless Home comes forward), unless the screen in front can't be read and
 *    no other app has come forward (apps that hide their windows from accessibility services).
 *    An unreadable screen titled after another app, and not after one of the app's own screens,
 *    is that other app coming forward. Left for a helper, a system screen it opened, or BioKey
 *    itself, it is a hop.
 * 3. A hop ends when the user moves on to anything that isn't another hop target.
 * 4. A locked app on screen without a session is covered, except a just-left app next to the
 *    launcher, which must stay [EXIT_SETTLE_MS] first (its window flickers back as it leaves).
 * 5. Unlocked apps on screen are remembered, so step 2 can notice them leave.
 *
 * And, outside a pass: an app whose lock screen closed onto a helper it had open has arrived,
 * and hopped there ([noteUnlocked], [noteInFront]).
 */
internal class SessionRules(private val env: Env) {

    companion object {
        /** An app must be off screen this long before it counts as left. */
        const val LEAVE_CONFIRM_MS = 400L
        /** How long a just-left app's window must stay next to the launcher to count as back. */
        const val EXIT_SETTLE_MS = 500L
        /** A window that hides from accessibility services: its owner can't be read. */
        const val UNIDENTIFIED_APP = "biokey:unidentified-app"
        /**
         * After an unlock, how long the screen the lock screen closes onto may be a helper the app
         * had open over itself ([noteInFront]): longer than an unreadable window is taken to be
         * the unlocked app's, as a sign-in link can cold-start a browser.
         */
        const val UNLOCK_LANDING_MS = 5_000L
        /**
         * After an unlock, how long the app may take to reach the screen before being off it
         * counts as leaving: a split screen the lock screen hid takes about a second to form again.
         */
        const val UNLOCK_ARRIVAL_MS = 2_500L
    }

    interface Env {
        fun isLocked(pkg: String): Boolean
        fun timeoutOf(pkg: String): LockTimeoutMode
        fun isLauncher(pkg: String): Boolean
        fun isHopTarget(pkg: String): Boolean
        /** A locked app without a session that may be covered because it is on screen. */
        fun mayCover(pkg: String): Boolean
        /** The one app whose name is [title], if exactly one is. */
        fun appTitled(title: String): String? = null
        /** Whether [title] is [pkg]'s name or the title of one of its screens. */
        fun isTitleOf(pkg: String, title: String): Boolean = false
    }

    /**
     * What is on screen. [visible] is in z-order (top first); [pip] are apps shown only in
     * picture-in-picture; [lastForeground] is the app the latest window event brought to the
     * front; [coverAllowed] is false while a cover, the lock screen or a prompt is already up;
     * [frontTitle] is the title of the window in front when [front] is [UNIDENTIFIED_APP].
     */
    data class Screen(
        val visible: Set<String>,
        val pip: Set<String> = emptySet(),
        val front: String = visible.first(),
        val lastForeground: String? = null,
        val now: Long,
        val coverAllowed: Boolean = true,
        val frontTitle: String? = null
    )

    data class Outcome(
        /** A locked app to cover now, if any. */
        val cover: String? = null,
        /** It is only in picture-in-picture, which floats above any cover. */
        val coverInPip: Boolean = false,
        /** Look again after this long: something is waiting to be confirmed. */
        val recheckInMs: Long? = null
    )

    private val onScreenSessions = HashSet<String>()
    private val pendingLeaves = HashMap<String, Long>()
    private val returningSince = HashMap<String, Long>()
    /** The app unlocked last, and when, until the first app in front after that ([noteInFront]). */
    private var unlockLanding: Pair<String, Long>? = null
    /** Apps just unlocked, and when, until they reach the screen ([UNLOCK_ARRIVAL_MS]). */
    private val arriving = HashMap<String, Long>()

    /** A window event put [pkg], unlocked, in front: it is in use. */
    fun noteOnScreen(pkg: String) {
        pendingLeaves.remove(pkg)
        onScreenSessions += pkg
    }

    /** [pkg] was just unlocked (at [now]): the next app in front says where its lock screen closed. */
    fun noteUnlocked(pkg: String, now: Long) {
        unlockLanding = pkg to now
        arriving[pkg] = now
    }

    /**
     * [front] came to the front at [now] (BioKey's own screens and floating windows aside). Only
     * the first app in front after an unlock says where its lock screen closed onto: a helper the
     * unlocked app had open over itself, within [UNLOCK_LANDING_MS], is its session arriving
     * ([noteUnlockedOnto]). Home, Recents or another app first, and the session must still reach
     * the screen in time, as in compatibility mode: a helper opened from there is no landing.
     */
    fun noteInFront(front: String, now: Long) {
        val (unlocked, at) = unlockLanding ?: return
        unlockLanding = null
        if (now - at < UNLOCK_LANDING_MS) noteUnlockedOnto(unlocked, front)
    }

    /**
     * [unlocked] was just unlocked and its lock screen closed onto [front]. If that is a helper
     * it had open over itself (a sign-in screen or custom tab it opened as it started, a call),
     * the app is right behind it: its session has arrived, and it has hopped there, rather than
     * ending as never on screen ([LockStateTracker.ARRIVAL_GRACE_MS]) while the user is still
     * busy in the helper. Compatibility mode does the same. Returns whether it did.
     */
    fun noteUnlockedOnto(unlocked: String, front: String): Boolean {
        if (front in PasswordManager.aliasesOf(unlocked) || env.isLauncher(front) || !env.isHopTarget(front)) return false
        val mode = env.timeoutOf(unlocked)
        if (!env.isLocked(unlocked) || !LockStateTracker.isAppUnlocked(unlocked, mode)) return false
        LockStateTracker.onAppVisible(unlocked)
        LockStateTracker.onAppLeft(unlocked, mode, hopTarget = front)
        return true
    }

    /** Sessions just ended (screen off, lock-all): nothing on screen has one any more. */
    fun reset() {
        onScreenSessions.clear()
        pendingLeaves.clear()
        returningSince.clear()
        unlockLanding = null
        arriving.clear()
    }

    fun reconcile(screen: Screen): Outcome {
        val visible = screen.visible
        val now = screen.now
        var recheck: Long? = null
        fun recheckIn(ms: Long) {
            recheck = minOf(recheck ?: ms, ms)
        }

        val front = titledFront(screen) ?: screen.front

        // 1. On screen: in use, nothing ages.
        for (pkg in visible) {
            pendingLeaves.remove(pkg)
            arriving.remove(pkg)
            if (env.isLocked(pkg)) LockStateTracker.onAppVisible(pkg)
        }
        // Home in front: whatever was on its way isn't.
        if (env.isLauncher(front)) arriving.clear()

        // 2. Gone from the screen long enough: the user left.
        for (pkg in onScreenSessions.toList()) {
            if (pkg in visible) continue
            if (front == UNIDENTIFIED_APP && screen.lastForeground == pkg) {
                pendingLeaves.remove(pkg)
                continue
            }
            // Just unlocked and not on screen yet (a split screen forming again): not left.
            val unlockedAt = arriving[pkg]
            if (unlockedAt != null && now - unlockedAt < UNLOCK_ARRIVAL_MS) {
                pendingLeaves.remove(pkg)
                recheckIn(UNLOCK_ARRIVAL_MS - (now - unlockedAt) + 20L)
                continue
            }
            arriving.remove(pkg)
            val goneSince = pendingLeaves.getOrPut(pkg) { now }
            if (now - goneSince < LEAVE_CONFIRM_MS) {
                recheckIn(LEAVE_CONFIRM_MS - (now - goneSince) + 20L)
                continue
            }
            pendingLeaves.remove(pkg)
            onScreenSessions.remove(pkg)
            val hop = front.takeIf { it != pkg && !env.isLauncher(it) && env.isHopTarget(it) }
            LockStateTracker.onAppLeft(pkg, env.timeoutOf(pkg), hop)
        }

        // 3. A helper hop that moved on to somewhere else ends.
        LockStateTracker.onForegroundChanged(front, env::timeoutOf, env::isHopTarget)

        // 4. Locked content on screen without a session (split screen, PiP, freeform, missed
        // events): cover it.
        if (screen.coverAllowed) {
            for (pkg in visible) {
                if (!env.mayCover(pkg)) continue
                val inPip = pkg in screen.pip
                if (!inPip) {
                    val wait = settleWait(pkg, visible, now)
                    if (wait > 0L) {
                        recheckIn(wait)
                        continue
                    }
                }
                return Outcome(cover = pkg, coverInPip = inPip, recheckInMs = recheck)
            }
        }

        // 5. Remember which unlocked apps are on screen so step 2 can notice them leave.
        for (pkg in visible) {
            if (env.isLocked(pkg) && LockStateTracker.isAppUnlocked(pkg, env.timeoutOf(pkg))) {
                onScreenSessions += pkg
            }
        }
        returningSince.keys.retainAll(visible)
        return Outcome(recheckInMs = recheck)
    }

    /**
     * The app an unreadable window in front belongs to, when its title says so: the one app of
     * that name, as long as it isn't the app last in front or titled like one of that app's own
     * screens (an app's own unreadable screens never count as leaving it). Null when unsure.
     */
    private fun titledFront(screen: Screen): String? {
        if (screen.front != UNIDENTIFIED_APP) return null
        val title = screen.frontTitle?.takeIf { it.isNotBlank() } ?: return null
        val app = env.appTitled(title) ?: return null
        val last = screen.lastForeground ?: return app
        if (app in PasswordManager.aliasesOf(last) || env.isTitleOf(last, title)) return null
        return app
    }

    /**
     * How much longer a just-left app's window must stay next to the launcher before it counts as
     * back (0: it has settled, or there's nothing to wait for).
     */
    private fun settleWait(pkg: String, visible: Set<String>, now: Long): Long {
        if (!LockStateTracker.justLeft(pkg) || visible.none(env::isLauncher)) {
            returningSince.remove(pkg)
            return 0L
        }
        val since = returningSince.getOrPut(pkg) { now }
        if (now - since >= EXIT_SETTLE_MS) {
            returningSince.remove(pkg)
            return 0L
        }
        return EXIT_SETTLE_MS - (now - since) + 20L
    }
}
