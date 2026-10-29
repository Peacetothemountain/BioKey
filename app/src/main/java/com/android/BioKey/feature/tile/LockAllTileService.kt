package com.android.BioKey.feature.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.android.BioKey.R
import com.android.BioKey.core.security.LockAllNow
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.MainActivity

/**
 * Quick Settings "Lock all": from inside any app, every locked app asks again the next time it
 * opens, BioKey included. It only ever locks, so it needs no authentication, even on the lock
 * screen. Paused, it resumes protection ([LockAllNow]); with nothing protecting, it opens BioKey.
 */
class LockAllTileService : TileService() {

    private val vault by lazy { SecurityVault.getInstance(this) }

    override fun onStartListening() {
        super.onStartListening()
        render(subtitle = null)
    }

    override fun onClick() {
        super.onClick()
        when (LockAllNow.run(this, includeOwnApp = true, reason = "lock-all tile")) {
            LockAllNow.Outcome.NOT_PROTECTING -> openBioKey()
            else -> render(subtitle = getString(R.string.lock_all_done))
        }
    }

    private fun openBioKey() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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

    private fun render(subtitle: String?) {
        val tile = qsTile ?: return
        val protecting = vault.isSetupCompleted() && ProtectionEngine.isProtecting(this)
        tile.label = getString(R.string.lock_all_tile_label)
        // Inactive rather than unavailable when nothing protects: a tap opens BioKey to fix it.
        tile.state = if (protecting) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = subtitle ?: when {
            !protecting -> getString(R.string.lock_all_tile_not_protecting)
            !vault.isProtectionActive() -> getString(R.string.lock_all_tile_paused)
            else -> null
        }
        tile.updateTile()
    }
}
