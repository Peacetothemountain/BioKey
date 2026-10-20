package com.android.BioKey.feature.tile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.android.BioKey.R
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile. Turning protection back on is instant. Pausing opens BioKey, which asks
 * for authentication before offering pause options (from the lock screen the phone must be
 * unlocked first). The tile mirrors the vault's state while it's visible, and only says
 * "Protecting" while an engine is actually running.
 */
class BioKeyTileService : TileService() {

    companion object {
        internal fun status(setupCompleted: Boolean, enabled: Boolean, engineRunning: Boolean): Status = when {
            !setupCompleted -> Status.SETUP_REQUIRED
            !enabled -> Status.PAUSED
            !engineRunning -> Status.NOT_PROTECTING
            else -> Status.PROTECTING
        }
    }

    /** What the tile shows, and what a tap on it does. */
    internal enum class Status { SETUP_REQUIRED, NOT_PROTECTING, PROTECTING, PAUSED }

    private val vault by lazy { SecurityVault.getInstance(this) }
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = CoroutineScope(Dispatchers.Main.immediate).launch {
            // The accessibility service connecting or stopping changes the answer too.
            combine(vault.setupCompletedFlow, vault.globalLockEnabledFlow, BioKeyAccessibilityService.running) { setupCompleted, enabled, _ ->
                setupCompleted to enabled
            }.collect { (setupCompleted, enabled) ->
                render(status(setupCompleted, enabled, isEngineRunning()))
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        listening?.cancel()
        listening = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        when (status(vault.isSetupCompleted(), vault.isProtectionActive(), isEngineRunning())) {
            // BioKey shows what's missing (setup, or the switch or permissions an engine needs).
            Status.SETUP_REQUIRED, Status.NOT_PROTECTING -> launch(Intent(this, MainActivity::class.java))
            Status.PAUSED -> vault.resumeProtection()
            // Through BioKey's own, non-exported way in: only it opens the pause sheet.
            Status.PROTECTING -> launch(MainActivity.pauseIntent(this))
        }
    }

    /**
     * An engine is running and still has what it needs, by the same rule as BioKey's home screen
     * and Settings ([ProtectionEngine.mode]). A switch left on in Settings isn't enough: the
     * accessibility service may have crashed, or compatibility mode may not have started.
     */
    private fun isEngineRunning(): Boolean = ProtectionEngine.isProtecting(this)

    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val start = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(start) else start()
    }

    private fun render(status: Status) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        // Inactive rather than unavailable when setup is missing: SystemUI ignores taps on
        // unavailable tiles, and a tap should open BioKey to finish it.
        tile.state = if (status == Status.PROTECTING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when (status) {
            Status.SETUP_REQUIRED -> "Setup required"
            Status.NOT_PROTECTING -> "Not protecting"
            Status.PROTECTING -> "Protecting"
            Status.PAUSED -> "Paused"
        }
        tile.updateTile()
    }
}
