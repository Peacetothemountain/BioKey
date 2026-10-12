package com.android.BioKey.feature.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.MainActivity

import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        listeningScope?.cancel()
        val scope = CoroutineScope(Dispatchers.Main + Job())
        listeningScope = scope
        scope.launch {
            SecurityVault.getInstance(this@BioKeyTileService).globalLockEnabledFlow.collect {
                updateTile()
            }
        }
    }

    override fun onStopListening() {
        listeningScope?.cancel()
        listeningScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val vault = SecurityVault.getInstance(this)
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
        val enabled = SecurityVault.getInstance(this).isGlobalLockEnabled()
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "BioKey"
        tile.subtitle = if (enabled) "Protecting" else "Paused"
        tile.updateTile()
    }
}
