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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile. Turning protection back on is instant. Pausing opens BioKey, which asks
 * for authentication before offering pause options (from the lock screen the phone must be
 * unlocked first). The tile mirrors the vault's state while it's visible.
 */
class BioKeyTileService : TileService() {

    companion object {
        fun requestTileUpdate(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, BioKeyTileService::class.java)) }
        }
    }

    private val vault by lazy { SecurityVault.getInstance(this) }
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = CoroutineScope(Dispatchers.Main.immediate).launch {
            combine(vault.setupCompletedFlow, vault.globalLockEnabledFlow, ::Pair).collect { (setupCompleted, enabled) ->
                render(setupCompleted, enabled)
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when {
            !vault.isSetupCompleted() -> launch(Intent(this, MainActivity::class.java))
            !vault.isGlobalLockEnabled() -> vault.resumeProtection()
            else -> launch(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_PAUSE_PROTECTION))
        }
    }

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

    private fun render(setupCompleted: Boolean, enabled: Boolean) {
        val tile = qsTile ?: return
        tile.label = "BioKey"
        tile.state = when {
            !setupCompleted -> Tile.STATE_UNAVAILABLE
            enabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            !setupCompleted -> "Setup required"
            enabled -> "Protecting"
            else -> "Paused"
        }
        tile.updateTile()
    }
}
