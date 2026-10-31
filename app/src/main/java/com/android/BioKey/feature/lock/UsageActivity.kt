package com.android.BioKey.feature.lock

import android.app.usage.UsageEvents
import com.android.BioKey.core.data.PasswordManager

/**
 * One activity event from usage access. [app] is what it means for locking: the package, Google
 * Password Manager's virtual one ([PasswordManager.resolve]), or null for a window that floats over
 * whichever app is below (a system dialog or sheet, a sign-in or passkey sheet). [lockedOverlay]:
 * [pkg] floats like those but the user locked it (Digital Wellbeing, with its icon turned on), so
 * every screen of it is that app's.
 */
internal class UsageEvent(val type: Int, val pkg: String, val cls: String?, val time: Long, lockedOverlay: Boolean = false) {
    val app: String? = if (pkg in ForegroundRules.SYSTEM_OVERLAYS && !lockedOverlay) null else PasswordManager.resolve(pkg, cls)
    /** Which activity this is about. */
    val activity: String get() = pkg + '/' + cls.orEmpty()
}

/**
 * Each activity's latest event, so the engine knows what is still showing: resumed, or paused
 * (under a floating window, in picture-in-picture, on its way out) and not yet stopped. Fed every
 * poll's events, re-read ones included, which change nothing. Floating windows aren't apps and
 * aren't kept.
 */
internal class ActivityStates {

    private companion object {
        const val RESUMED = UsageEvents.Event.ACTIVITY_RESUMED
        const val PAUSED = UsageEvents.Event.ACTIVITY_PAUSED
        const val STOPPED = UsageEvents.Event.ACTIVITY_STOPPED
    }

    private class State(val app: String, var type: Int, var time: Long)

    private val byActivity = HashMap<String, State>()

    /** Records [event]. True if it is news, not an event already recorded. */
    fun apply(event: UsageEvent): Boolean {
        val app = event.app ?: return false
        val key = event.activity
        val known = byActivity[key]
        if (known == null) {
            byActivity[key] = State(app, event.type, event.time)
            return true
        }
        // Within one millisecond, resumed, paused and stopped follow each other in that order.
        if (event.time < known.time || (event.time == known.time && order(event.type) <= order(known.type))) return false
        known.type = event.type
        known.time = event.time
        return true
    }

    /** The latest event recorded for [event]'s activity (resumed, paused or stopped), or null if none is. */
    fun lastTypeOf(event: UsageEvent): Int? = byActivity[event.activity]?.type

    /** Whether [activity] ([UsageEvent.activity]) is paused: showing, but not in front. */
    fun isPaused(activity: String): Boolean = byActivity[activity]?.type == PAUSED

    /** [app]'s activities that are paused. */
    fun pausedActivitiesOf(app: String): List<String> = byActivity.filter { (_, s) -> s.app == app && s.type == PAUSED }.keys.toList()

    fun isResumed(app: String): Boolean = byActivity.values.any { it.app == app && it.type == RESUMED }

    /** The apps with an activity resumed: on screen, in front or beside the one in front. */
    fun resumedApps(): Set<String> = byActivity.values.filter { it.type == RESUMED }.mapTo(HashSet()) { it.app }

    /** Whether some activity of [app] other than [except] ([UsageEvent.activity]) is resumed. */
    fun isResumed(app: String, except: String): Boolean = byActivity.any { (activity, s) -> activity != except && s.app == app && s.type == RESUMED }

    /** Some activity of [app] is resumed or paused: it is (or may still be) on screen. */
    fun isVisible(app: String): Boolean = byActivity.values.any { it.app == app && it.type != STOPPED }

    fun knows(app: String): Boolean = byActivity.values.any { it.app == app }

    /** Every activity of [app] seen has stopped. False for an app nothing is known of. */
    fun isGone(app: String): Boolean = knows(app) && !isVisible(app)

    /** Forgets activities that stopped before [time]: no re-read reaches back that far any more. */
    fun prune(time: Long) {
        byActivity.values.removeAll { it.type == STOPPED && it.time < time }
    }

    /**
     * The clock was changed: what each activity did last still holds, but its time can't be
     * compared with new ones. Each keeps its state, timed before anything on the new clock, so
     * whatever is read next applies; stopped ones then go at the next [prune].
     */
    fun rebase() {
        for (state in byActivity.values) state.time = Long.MIN_VALUE
    }

    fun clear() = byActivity.clear()

    private fun order(type: Int) = when (type) {
        RESUMED -> 0
        PAUSED -> 1
        else -> 2
    }
}
