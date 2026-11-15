package com.android.BioKey.feature.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.android.BioKey.R
import com.android.BioKey.core.security.LockAllNow
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.main.MainActivity

/**
 * Quick Settings "Re-lock now": from inside any app, every locked app asks again the next time it
 * opens, BioKey included. It only ever locks, so it needs no authentication, even on the lock
 * screen. Paused, it resumes protection ([LockAllNow]); with nothing protecting, it opens BioKey.
 *
 * A tap shows: the tile lights up for [FlashMillis], then rests again. A compact tile (the shade's
 * first row, most phones' default) doesn't show a subtitle, so the outcome written there alone
 * would go unseen.
 */
class LockAllTileService : TileService() {

    private val vault by lazy { SecurityVault.getInstance(this) }
    private val handler = Handler(Looper.getMainLooper())

    /** The outcome of the last tap, while the tile is lit for it; null when resting. */
    private var flash: String? = null
    private var flashing = false

    private val endFlash = Runnable {
        flashing = false
        flash = null
        render()
    }

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onDestroy() {
        handler.removeCallbacks(endFlash)
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val outcome = LockAllNow.run(this, includeOwnApp = true, reason = "Re-lock now tile")
        // Over the phone's lock screen a tap looks the same whatever it did: asking to unlock
        // there, only when protection is off, would tell whoever holds the phone that it is.
        if (outcome == LockAllNow.Outcome.NOT_PROTECTING && !isLocked) {
            openBioKey()
            return
        }
        handler.removeCallbacks(endFlash)
        flashing = true
        flash = LockAllNow.message(this, outcome)
        render()
        handler.postDelayed(endFlash, FlashMillis)
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

    private fun render() {
        val tile = qsTile ?: return
        val locked = isLocked
        val look = if (locked) {
            appearance(locked = true, flashing = flashing, outcome = null, resting = null)
        } else {
            val protecting = vault.isSetupCompleted() && ProtectionEngine.isProtecting(this)
            val resting = when {
                !protecting -> notProtectingLabel(this, ProtectionEngine.mode(this) == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING)
                !vault.isProtectionActive() -> getString(R.string.lock_all_tile_paused)
                else -> null
            }
            appearance(locked = false, flashing = flashing, outcome = flash, resting = resting)
        }
        tile.label = getString(R.string.lock_all_tile_label)
        tile.state = look.state
        tile.subtitle = look.subtitle
        tile.updateTile()
    }

    /** What the tile shows: its state and subtitle. */
    internal data class Look(val state: Int, val subtitle: String?)

    companion object {
        /** How long the tile stays lit after a tap. */
        internal const val FlashMillis = 2_000L

        /**
         * The tile's look. An action, not a switch: it rests inactive, and is never unavailable (a
         * tap with nothing protecting opens BioKey to fix it). A tap lights it ([flashing]) with
         * what it did as its subtitle ([outcome]); resting, the subtitle says how protection
         * stands ([resting]). Over the lock screen ([locked]) it says nothing at all: no subtitle,
         * and the same brief light after a tap whatever the tap did.
         */
        internal fun appearance(locked: Boolean, flashing: Boolean, outcome: String?, resting: String?): Look = when {
            locked -> Look(if (flashing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE, null)
            flashing -> Look(Tile.STATE_ACTIVE, outcome)
            else -> Look(Tile.STATE_INACTIVE, resting)
        }
    }
}
