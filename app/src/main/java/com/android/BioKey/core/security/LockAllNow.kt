package com.android.BioKey.core.security

import android.content.Context
import com.android.BioKey.R

/**
 * "Lock all now", wherever it's asked for (BioKey's home screen and status sheet, the launcher
 * shortcut, the Quick Settings tile): every locked app asks again the next time it opens.
 *
 * It only ever makes things safer, so it needs no authentication. Asked while protection is
 * paused, it resumes it (as the pause tile does, also without authentication): "lock everything"
 * must never leave everything open. While no engine runs or setup isn't done, it says so rather
 * than claiming apps are locked.
 */
object LockAllNow {

    enum class Outcome { LOCKED, RESUMED_AND_LOCKED, NOT_PROTECTING }

    /** [includeOwnApp]: false from BioKey's own screens, which the owner is using. */
    fun run(context: Context, includeOwnApp: Boolean, reason: String): Outcome {
        val vault = SecurityVault.getInstance(context)
        // A pause ends whatever else is wrong: once an engine runs again, nothing stays open.
        val wasPaused = vault.isSetupCompleted() && !vault.isProtectionActive()
        if (wasPaused) vault.resumeProtection()
        // Sessions end even with nothing covering apps now: they'd carry over once something does.
        LockStateTracker.lockAll(includeOwnApp, reason)
        return when {
            !vault.isSetupCompleted() || !ProtectionEngine.isProtecting(context) -> Outcome.NOT_PROTECTING
            wasPaused -> Outcome.RESUMED_AND_LOCKED
            else -> Outcome.LOCKED
        }
    }

    /** What to tell the user afterwards. */
    fun message(context: Context, outcome: Outcome): String = context.getString(
        when (outcome) {
            Outcome.LOCKED -> R.string.lock_all_done
            Outcome.RESUMED_AND_LOCKED -> R.string.lock_all_resumed
            Outcome.NOT_PROTECTING -> R.string.lock_all_not_protecting
        }
    )
}
