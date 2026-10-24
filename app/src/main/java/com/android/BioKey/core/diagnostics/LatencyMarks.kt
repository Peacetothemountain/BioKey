package com.android.BioKey.core.diagnostics

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import com.android.BioKey.BuildConfig

/**
 * How fast BioKey covers a locked app, for the device gantlet (tools/device_gantlet.py) to read:
 * one logcat line per moment on the lock path, `BioKeyLatency: <mark> <pkg> <uptimeMs> [engine]`.
 *
 * - `trigger`: when the launch started (the tap on its icon, or the event that brought it up),
 *   with the engine that saw it: `accessibility` or `compatibility`.
 * - `cover`: when the shield's first frame after it went up is drawn.
 * - `prompt`: when the system prompt was asked for.
 *
 * Every time is on [SystemClock.uptimeMillis], the clock accessibility events already carry, so the
 * gantlet subtracts one from another without syncing its own clock with the phone's.
 *
 * Debug builds only. These lines name locked apps, so a release build writes nothing: every call
 * returns at once on [BuildConfig.DEBUG] (a constant, so R8 drops the rest), and R8 strips Log.i in
 * release builds anyway (proguard-rules.pro).
 */
object LatencyMarks {

    private const val TAG = "BioKeyLatency"
    /**
     * A launch BioKey hears about this late isn't one being opened now: the engine is covering an
     * app that was already on screen (every session ending, say), and how long ago it opened says
     * nothing about how fast it is covered.
     */
    private const val STALE_MS = 5_000L

    /** [pkg]'s launch started at [uptimeMs] (on the uptime clock), and [engine] is covering it. */
    fun trigger(engine: String, pkg: String, uptimeMs: Long) {
        if (!BuildConfig.DEBUG) return
        if (SystemClock.uptimeMillis() - uptimeMs > STALE_MS) return
        Log.i(TAG, "trigger $pkg $uptimeMs $engine")
    }

    /**
     * [trigger], for a launch whose time is on the wall clock ([wallMs], as usage events give it):
     * moved to the uptime clock by how far apart the two clocks are now.
     */
    fun triggerAtWallClock(engine: String, pkg: String, wallMs: Long) {
        if (!BuildConfig.DEBUG) return
        trigger(engine, pkg, wallMs - (System.currentTimeMillis() - SystemClock.uptimeMillis()))
    }

    /** [mark] for [pkg] happened now. */
    fun mark(mark: String, pkg: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "$mark $pkg ${SystemClock.uptimeMillis()}")
    }

    /**
     * [mark] for [pkg] once [view] next draws: a view made visible is only on screen at its next
     * frame, and that frame is what hides the app.
     */
    fun markOnNextDraw(view: View, mark: String, pkg: String) {
        if (!BuildConfig.DEBUG) return
        val observer = view.viewTreeObserver
        val listener = object : ViewTreeObserver.OnDrawListener {
            private var done = false
            override fun onDraw() {
                if (done) return
                done = true
                mark(mark, pkg)
                // A draw listener can't be removed while the draw that calls it runs.
                view.post { view.viewTreeObserver.removeOnDrawListener(this) }
            }
        }
        observer.addOnDrawListener(listener)
        // A cover handed from one app to another was visible already: a frame is drawn all the same.
        view.invalidate()
    }
}
