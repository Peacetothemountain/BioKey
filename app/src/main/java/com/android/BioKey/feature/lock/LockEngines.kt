package com.android.BioKey.feature.lock

import android.content.Context
import com.android.BioKey.core.Engines

/** The two engines, as core/ asks for them ([com.android.BioKey.core.FeatureLinks]). */
internal object LockEngines : Engines {
    override fun accessibilityEnabled(context: Context) = BioKeyAccessibilityService.isServiceEnabled(context)
    override val accessibilityRunning get() = BioKeyAccessibilityService.isRunning
    override val compatibilityRunning get() = UsageWatchService.isRunning
    override val compatibilityStartPending get() = UsageWatchService.startPending
    override fun startCompatibility(context: Context) = UsageWatchService.start(context)
    override fun stopCompatibility(context: Context) = UsageWatchService.stop(context)
}
