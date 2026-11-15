package com.android.BioKey.ui.format

import android.content.res.Resources
import com.android.BioKey.R
import kotlin.math.ceil

/**
 * How long until the passphrase can be tried again, for every place that asks for it: "Try again
 * in 28 seconds", "in 4 minutes", "in 2 hours". Always rounded up, so it never says 0.
 */
fun passphraseWaitText(res: Resources, remainingMs: Long): String = when {
    remainingMs <= 60_000L -> ceil(remainingMs / 1_000.0).toInt().coerceAtLeast(1).let { res.getQuantityString(R.plurals.lock_passphrase_wait_seconds, it, it) }
    remainingMs <= 120 * 60_000L -> ceil(remainingMs / 60_000.0).toInt().let { res.getQuantityString(R.plurals.lock_passphrase_wait_minutes, it, it) }
    else -> ceil(remainingMs / 3_600_000.0).toInt().let { res.getQuantityString(R.plurals.lock_passphrase_wait_hours, it, it) }
}
