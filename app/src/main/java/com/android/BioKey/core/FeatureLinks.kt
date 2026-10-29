package com.android.BioKey.core

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * The two engines that lock apps, as core/ needs them: whether each runs, and compatibility mode
 * started or stopped. Implemented in feature/lock ([FeatureLinks.install]).
 */
interface Engines {
    /** The accessibility service is switched on in Android's settings. */
    fun accessibilityEnabled(context: Context): Boolean
    /** ... and connected, running. */
    val accessibilityRunning: Boolean
    val compatibilityRunning: Boolean
    /** Compatibility mode was asked to start and hasn't yet. */
    val compatibilityStartPending: Boolean
    fun startCompatibility(context: Context)
    fun stopCompatibility(context: Context)
}

/**
 * What core/ needs of the features, without depending on them: the engines, and BioKey's main
 * screen to open from an alert. Installed first thing as BioKey starts (BioKeyApplication), before
 * anything here can ask.
 */
object FeatureLinks {
    /** The main screen opened at an app's name: what to search for. */
    const val EXTRA_SEARCH = "com.android.BioKey.extra.SEARCH"

    @Volatile private var installedEngines: Engines? = null
    @Volatile private var mainScreen: Class<out Activity>? = null

    fun install(engines: Engines, mainScreen: Class<out Activity>) {
        installedEngines = engines
        this.mainScreen = mainScreen
    }

    val engines: Engines
        get() = checkNotNull(installedEngines) { "FeatureLinks.install wasn't called (BioKeyApplication.onCreate)" }

    /** BioKey's main screen, opened from an alert: a new task, or the one already open brought forward. */
    fun openMainScreen(context: Context): Intent =
        Intent(context, checkNotNull(mainScreen) { "FeatureLinks.install wasn't called (BioKeyApplication.onCreate)" })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
