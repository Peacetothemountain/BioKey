package com.android.BioKey.feature.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.MainActivity

class BioKeyTileService : TileService() {

    companion object {
        private const val TAG = "BioKeyTileService"
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val vault = SecurityVault.getInstance(this)
        val currentlyEnabled = vault.isGlobalLockEnabled()

        if (currentlyEnabled) {
            // Disarming defense requires full authentication!
            // Prevent unauthorized deactivation from the Quick Settings shade without biometric/PIN verification.
            Log.w(TAG, "Tile clicked to disarm BioKey shield. Launching biometric authentication gate.")
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } else {
            // Re-arming is a protective operation: safe to perform immediately
            vault.setGlobalLockEnabled(true)
            applyTileState(true)
            Log.i(TAG, "BioKey shield re-armed via Quick Settings tile.")
        }
    }

    private fun updateTileState() {
        val vault = SecurityVault.getInstance(this)
        applyTileState(vault.isGlobalLockEnabled())
    }

    private fun applyTileState(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "BioKey"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (enabled) "Shield Active" else "Shield Paused"
        }
        tile.updateTile()
    }
}
