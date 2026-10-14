package com.android.BioKey.feature.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile. Turning protection back on is instant. Pausing it opens BioKey, which
 * requires authentication before offering the pause options; from the lock screen shade the
 * device must be unlocked first.
 */
class BioKeyTileService : TileService() {

    companion object {
        fun requestTileUpdate(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, BioKeyTileService::class.java))
            }
        }
    }

    private var listeningScope: CoroutineScope? = null
    private var pauseTimerJob: kotlinx.coroutines.Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningScope?.cancel()
        val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        listeningScope = scope
        scope.launch {
            val vault = SecurityVault.getInstance(this@BioKeyTileService)
            combine(
                vault.setupCompletedFlow,
                vault.globalLockEnabledFlow,
                vault.pausedUntilFlow
            ) { setupCompleted, enabled, pausedUntil ->
                Triple(setupCompleted, enabled, pausedUntil)
            }.collect { (setupCompleted, _, pausedUntil) ->
                updateTile()
                pauseTimerJob?.cancel()
                if (setupCompleted && pausedUntil > 0L) {
                    val remaining = pausedUntil - System.currentTimeMillis()
                    if (remaining > 0L) {
                        pauseTimerJob = launch {
                            delay(remaining + 50L)
                            vault.isGlobalLockEnabled()
                            updateTile()
                        }
                    }
                }
            }
        }
    }

    override fun onStopListening() {
        pauseTimerJob?.cancel()
        pauseTimerJob = null
        listeningScope?.cancel()
        listeningScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val vault = SecurityVault.getInstance(this)
        if (!vault.isSetupCompleted()) {
            if (isLocked) unlockAndRun { openApp() } else openApp()
            return
        }
        if (vault.isGlobalLockEnabled()) {
            if (isLocked) unlockAndRun { openPauseScreen() } else openPauseScreen()
        } else {
            vault.resumeProtection("Quick Settings tile")
            updateTile()
        }
    }

    private fun openPauseScreen() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_PAUSE_PROTECTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        launchActivityAndCollapse(intent)
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        launchActivityAndCollapse(intent)
    }

    private fun launchActivityAndCollapse(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val vault = SecurityVault.getInstance(this)
        val setupCompleted = vault.isSetupCompleted()
        val enabled = vault.isGlobalLockEnabled()

        tile.label = "BioKey"
        when {
            !setupCompleted -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = "Setup required"
            }
            enabled -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Protecting"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Paused"
            }
        }
        tile.updateTile()
    }
}
