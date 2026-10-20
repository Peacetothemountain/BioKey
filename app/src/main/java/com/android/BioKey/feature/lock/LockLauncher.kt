package com.android.BioKey.feature.lock

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Starts [LockScreenActivity] from a background component (either detection engine). */
object LockLauncher {
    private const val TAG = "LockLauncher"

    /**
     * Returns false if the lock screen could not be started (the caller must fail closed).
     * [displayId], when known, is the display the locked app is on: the lock screen opens there.
     */
    fun launch(context: Context, targetPkg: String, isSelfProtection: Boolean, displayId: Int? = null): Boolean {
        val intent = Intent(context, LockScreenActivity::class.java).apply {
            putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, targetPkg)
            putExtra(LockScreenActivity.EXTRA_SELF_PROTECTION, isSelfProtection)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }

        // Primary path: a PendingIntent with an explicit background-start grant and no animation
        // (a plain startActivity from a service can be silently blocked on Android 14+). BioKey
        // holds a background-start privilege here: a bound accessibility service, or a visible
        // overlay window in compatibility mode.
        try {
            val creatorOptions = ActivityOptions.makeCustomAnimation(context, 0, 0)
            val sendOptions = ActivityOptions.makeCustomAnimation(context, 0, 0)
            if (displayId != null) {
                creatorOptions.launchDisplayId = displayId
                sendOptions.launchDisplayId = displayId
            }
            if (Build.VERSION.SDK_INT >= 36) {
                creatorOptions.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
                sendOptions.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                creatorOptions.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                @Suppress("DEPRECATION")
                sendOptions.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            PendingIntent.getActivity(
                context,
                targetPkg.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                creatorOptions.toBundle()
            ).send(context, 0, null, null, null, null, sendOptions.toBundle())
            return true
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent lock screen start failed, trying startActivity: ${e.message}")
        }
        return try {
            val options = ActivityOptions.makeCustomAnimation(context, 0, 0)
            if (displayId != null) options.launchDisplayId = displayId
            context.startActivity(intent, options.toBundle())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Lock screen launch failed", e)
            false
        }
    }
}
