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
        if (!vault.isSetupCompleted() || !ProtectionEngine.isProtecting(context)) {
            // Nothing covers apps now, but sessions still end: they'd carry over once it does.
            LockStateTracker.lockAll(includeOwnApp, reason)
            return Outcome.NOT_PROTECTING
        }
        if (!vault.isProtectionActive()) {
            // Resuming already ends every session but BioKey's own.
            vault.resumeProtection()
            if (includeOwnApp) LockStateTracker.lockOwnApp()
            return Outcome.RESUMED_AND_LOCKED
        }
        LockStateTracker.lockAll(includeOwnApp, reason)
        return Outcome.LOCKED
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
