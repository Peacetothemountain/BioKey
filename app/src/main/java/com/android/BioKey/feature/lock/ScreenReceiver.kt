package com.android.BioKey.feature.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.android.BioKey.core.diagnostics.EngineTrace
import com.android.BioKey.core.security.BiometricEnrollment
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectionAlerts
import com.android.BioKey.core.security.UnlockAttempts
import com.android.BioKey.feature.notification.ShieldAccessWatch

/**
 * Ends every unlock session the moment the display turns off or a screensaver starts, so a
 * phone that's picked up again always starts locked. Registered dynamically and not exported,
 * so other apps can't spoof these broadcasts.
 */
class ScreenReceiver private constructor() : BroadcastReceiver() {

    companion object {
        fun register(context: Context): ScreenReceiver {
            val receiver = ScreenReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_SHUTDOWN)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
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
                        UnlockAttempts.onUserPresent(app)
                        ProtectionAlerts.remindIfOff(app)
                        BiometricEnrollment.checkSoon(app)
                        ShieldAccessWatch.check(app)
                    } finally {
                        pending?.finish()
                    }
                }.start()
            }
        }
    }
}
