package com.android.BioKey.core.security

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.android.BioKey.feature.main.MainActivity

/**
 * Brings BioKey back to the front once its accessibility service has been switched on, when it
 * was BioKey that sent the owner to Android's Accessibility settings a moment ago. Otherwise they
 * are left on Android's page with nothing to do there, and it takes Back twice to get out.
 *
 * Only a return: BioKey's own screen comes back as it was, locked if it had locked itself.
 */
object AccessibilityReturn {

    /** How long after being sent there a switch-on still counts as the owner's answer to it. */
    private const val WINDOW_MS = 5 * 60_000L

    @Volatile private var sentAt = 0L

    @VisibleForTesting
    internal var sinceBoot: () -> Long = SystemClock::elapsedRealtime

    /** BioKey has just opened Android's Accessibility settings for the owner. */
    fun expect() {
        sentAt = sinceBoot()
    }

    /** Whether the service coming on now answers a trip BioKey sent the owner on; asked once. */
    @VisibleForTesting
    internal fun take(): Boolean {
        val at = sentAt
        sentAt = 0L
        return at != 0L && sinceBoot() - at in 0..WINDOW_MS
    }

    /** The service has just come on: back to BioKey, if that is where the owner came from. */
    fun comeBack(context: Context) {
        if (!take()) return
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }
}
