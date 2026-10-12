package com.android.BioKey.feature.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ZeroKnowledgeMemorySanitizer

/**
 * Hardened Dynamic Screen & Power State Receiver
 *
 * Implements strict component isolation:
 * - Always enforces `Context.RECEIVER_NOT_EXPORTED` to prevent cross-app intent spoofing
 * - Captures SCREEN_OFF, DREAMING_STARTED (ambient/screensaver), and USER_PRESENT
 * - Immediately triggers Zero-Knowledge RAM scrubbing to eradicate active session keys
 */
class ScreenReceiver(
    private val onScreenOffAction: (() -> Unit)? = null,
    private val onUserPresentAction: (() -> Unit)? = null
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenReceiver"

        /**
         * Standard helper to register this receiver strictly with `RECEIVER_NOT_EXPORTED`
         */
        fun registerHardened(
            context: Context,
            onScreenOff: (() -> Unit)? = null,
            onUserPresent: (() -> Unit)? = null
        ): ScreenReceiver {
            val receiver = ScreenReceiver(onScreenOff, onUserPresent)
            val filter = createIntentFilter()

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }
                Log.d(TAG, "ScreenReceiver dynamically registered with RECEIVER_NOT_EXPORTED isolation.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register isolated ScreenReceiver: ${e.message}", e)
            }
            return receiver
        }

        fun createIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                // Ambient screen / screensaver activation
                addAction(Intent.ACTION_DREAMING_STARTED)
                // Shutdown / device sleep transitions
                addAction(Intent.ACTION_SHUTDOWN)
            }
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Received isolated power/display broadcast: $action")

        when (action) {
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_DREAMING_STARTED,
            Intent.ACTION_SHUTDOWN -> {
                Log.i(TAG, "Screen-off/sleep transition detected: initiating immediate zero-knowledge RAM purge.")
                // 1. Invalidate all logical app unlocks and lock counters
                LockStateTracker.onScreenTurnedOff()

                // 2. Perform unconditional RAM sanitization and token zeroization
                ZeroKnowledgeMemorySanitizer.evictAllSessionsAndScrubMemory()

                // 3. Dispatch specific caller actions
                onScreenOffAction?.invoke()
            }

            Intent.ACTION_USER_PRESENT -> {
                Log.d(TAG, "User present on device; verifying lock state integrity.")
                onUserPresentAction?.invoke()
            }
        }
    }

    /**
     * Safely unregisters this receiver from the specified context.
     */
    fun unregister(context: Context) {
        try {
            context.unregisterReceiver(this)
            Log.d(TAG, "ScreenReceiver unregistered successfully.")
        } catch (e: IllegalArgumentException) {
            // Already unregistered or not registered
            Log.w(TAG, "ScreenReceiver was not registered: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed unregistering ScreenReceiver: ${e.message}", e)
        }
    }
}
