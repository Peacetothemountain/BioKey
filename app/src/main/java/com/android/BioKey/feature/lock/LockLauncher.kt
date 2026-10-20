package com.android.BioKey.feature.lock

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display

/** Starts [LockScreenActivity] from a background component (either detection engine). */
object LockLauncher {
    private const val TAG = "LockLauncher"

    /**
     * Returns false if the lock screen could not be started (the caller must fail closed). True
     * only means the start was asked for: Android refuses a background start without an error, so
     * only [LockScreenActivity.isLockScreenResumed] (which the shield's checks watch) confirms it.
     * [displayId], when known, is the display the locked app is on: the lock screen opens there.
     * [reprompt]: the user asked for this lock on purpose, so a lock screen already up for
     * [targetPkg] brings its prompt back (see [LockScreenActivity.EXTRA_REPROMPT]).
     */
    fun launch(
        context: Context,
        targetPkg: String,
        isSelfProtection: Boolean,
        displayId: Int? = null,
        reprompt: Boolean = false
    ): Boolean {
        val intent = Intent(context, LockScreenActivity::class.java).apply {
            putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, targetPkg)
            putExtra(LockScreenActivity.EXTRA_SELF_PROTECTION, isSelfProtection)
            // Always set: the PendingIntent below is reused for the app, and its extras replaced.
            putExtra(LockScreenActivity.EXTRA_REPROMPT, reprompt)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        val bounds = wholeDisplay(context, displayId)

        // Primary path: a PendingIntent with an explicit background-start grant and no animation
        // (a plain startActivity from a service can be silently blocked on Android 14+). BioKey
        // holds a background-start privilege here: a bound accessibility service, or in
        // compatibility mode the user's display-over-other-apps grant (the shield only hides the
        // app). send() returns normally even when the start is refused.
        try {
            val creatorOptions = ActivityOptions.makeCustomAnimation(context, 0, 0)
            val sendOptions = ActivityOptions.makeCustomAnimation(context, 0, 0)
            if (displayId != null) {
                creatorOptions.launchDisplayId = displayId
                sendOptions.launchDisplayId = displayId
            }
            if (bounds != null) {
                creatorOptions.setLaunchBounds(bounds)
                sendOptions.setLaunchBounds(bounds)
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
            if (bounds != null) options.setLaunchBounds(bounds)
            context.startActivity(intent, options.toBundle())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Lock screen launch failed", e)
            false
        }
    }

    /**
     * The whole of [displayId] (the phone's own screen when null), where windows can be freeform
     * (desktop windowing, DeX): the lock screen asks to open that big, not as a small window with
     * the locked app's windows beside it, which it would have to wait out or leave by going Home.
     * Null where windows can't be freeform (Android ignores launch bounds there anyway) or the
     * display can't be read: the lock screen then opens as before.
     */
    private fun wholeDisplay(context: Context, displayId: Int?): Rect? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) return null
        return runCatching {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId ?: Display.DEFAULT_DISPLAY)
                ?: return null
            // The display's own size, in its current rotation: there is no window here to measure.
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            Rect(0, 0, size.x, size.y).takeUnless { it.isEmpty }
        }.getOrNull()
    }
}

/**
 * Sends the user Home: how a cover that can't do its job fails closed (Home takes the locked app
 * off screen). Never throws.
 */
internal fun Context.goHome() {
    runCatching {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
