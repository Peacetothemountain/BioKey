package com.android.BioKey.feature.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.android.BioKey.core.diagnostics.DiagnosticLog
import com.android.BioKey.core.diagnostics.EngineTrace
import com.android.BioKey.core.security.BiometricEnrollment
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.feature.notification.ShieldAccessWatch

/**
 * Ends every unlock session the moment the display turns off or a screensaver starts, so a
 * phone that's picked up again always starts locked. Registered dynamically and not exported,
 * so other apps can't spoof these broadcasts; the phone being unlocked, which SystemUI sends, see
 * [registerForUnlocks].
 */
class ScreenReceiver private constructor() : BroadcastReceiver() {

    companion object {
        fun register(context: Context): ScreenReceiver {
            val receiver = ScreenReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_SHUTDOWN)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            context.registerForUnlocks(receiver)
            return receiver
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        EngineTrace.record("d.bc", intent.action)
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_DREAMING_STARTED,
            Intent.ACTION_SHUTDOWN -> {
                // Always: ending sessions twice is harmless, missing a lock is not (a screensaver
                // never turns the screen off, so the engines' own screen-off handling misses it).
                LockStateTracker.onScreenTurnedOff()
            }
            // The phone was unlocked: whoever had it has likely handed it back, so this is when
            // failed unlocks and times app locking was off are told.
            Intent.ACTION_USER_PRESENT -> {
                // Off the main thread: right after a restart, this may be the first read of BioKey's storage.
                val pending: PendingResult? = goAsync()
                val app = context.applicationContext
                Thread {
                    try {
                        // Each on its own: one failing mustn't skip the others (each is looked at
                        // again at the next unlock).
                        listOf(
                            { UnlockAttempts.onUserPresent(app) },
                            { ProtectionAlerts.remindIfOff(app) },
                            { BiometricEnrollment.checkSoon(app) },
                            { ShieldAccessWatch.checkSoon(app).get() }
                        ).forEach { step ->
                            runCatching(step).onFailure { DiagnosticLog.log("Unlock check failed: ${it.javaClass.simpleName}") }
                        }
                    } finally {
                        pending?.finish()
                    }
                }.start()
            }
        }
    }
}

/**
 * Registers [receiver] for the phone being unlocked (ACTION_USER_PRESENT). The keyguard sends it,
 * and on a phone the keyguard is SystemUI: an app of its own, not the system, so a receiver that
 * isn't exported never hears it. It's a protected broadcast, which only the system and SystemUI
 * may send: exporting the receiver for it alone lets no other app in.
 */
internal fun Context.registerForUnlocks(receiver: BroadcastReceiver) {
    ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
}
