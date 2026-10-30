package com.android.BioKey.core.security

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Locked apps that Android's own App lock (Android 17 QPR3 and later) was seen locking too, for
 * home and Settings to say "Also locked by Android". BioKey can't ask Android which apps it locks
 * (that needs LOCK_APPS); the accessibility engine sees its cover come up for an app, and notes it
 * here ([note]). Only apps BioKey locks itself, as the diagnostic log names only those.
 *
 * Android doesn't say when its lock is taken off again either, so an app not seen locked by it for
 * [FORGET_AFTER_MS] drops out. Kept in BioKey's private storage (no cloud backup, no device
 * transfer, like everything else), and never in a backup: it's what the phone did, not a setting.
 */
object AlsoLockedByAndroid {

    private const val PREFS = "biokey_android_app_lock"
    /** An app not seen locked by Android for this long is taken not to be any more. */
    const val FORGET_AFTER_MS = 30L * 24 * 60 * 60_000L
    /** At most this many are kept: more apps than anyone locks twice. */
    private const val MAX_KEPT = 200

    private val _apps = MutableStateFlow<Set<String>>(emptySet())
    /** The apps seen locked by Android lately. Call [load] first (it reads storage). */
    val apps: StateFlow<Set<String>> = _apps.asStateFlow()

    @Volatile private var loaded = false
    /** Each app, and when it was last seen locked by Android (wall clock: it only ages a label). */
    private val seenAt = HashMap<String, Long>()
    private val lock = Any()

    /** Wall clock, replaceable in tests. */
    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reads what's kept, once per process. Storage: not on the main thread at start-up. */
    fun load(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            seenAt.clear()
            for ((pkg, at) in prefs(context).all) if (at is Long) seenAt[pkg] = at
            loaded = true
            publishLocked()
        }
    }

    /** Android's App lock was just seen locking [pkg]. */
    fun note(context: Context, pkg: String) {
        load(context)
        val now = clock()
        synchronized(lock) {
            val before = seenAt.put(pkg, now)
            // Seen moments ago already: nothing worth a write.
            if (before != null && now - before in 0 until 60_000L) return
            while (seenAt.size > MAX_KEPT) seenAt.remove(seenAt.minBy { it.value }.key)
            val kept = HashMap(seenAt)
            prefs(context).edit {
                clear()
                for ((p, at) in kept) putLong(p, at)
            }
            publishLocked()
        }
    }

    /** Forgets everything (BioKey's storage cleared from inside, tests). */
    @VisibleForTesting
    internal fun clear(context: Context) {
        synchronized(lock) {
            seenAt.clear()
            prefs(context).edit { clear() }
            loaded = true
            publishLocked()
        }
    }

    private fun publishLocked() {
        val now = clock()
        _apps.value = seenAt.filterValues { now - it < FORGET_AFTER_MS }.keys.toSet()
    }
}
