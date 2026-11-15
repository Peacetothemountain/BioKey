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
import com.android.BioKey.feature.main.pauseEndTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Nothing protecting, in the words of home's header: "Not running" where BioKey is switched on
 * but has stopped, "Off" otherwise (switched off, or Advanced Protection with compatibility mode
 * not set up). Both tiles say it.
 */
internal fun notProtectingLabel(context: Context, notRunning: Boolean): String =
    context.getString(if (notRunning) R.string.home_not_running_short else R.string.home_off_short)

/**
 * Quick Settings "Protection": on while locked apps are being locked. Turning protection back on
 * is instant. Pausing opens BioKey, which asks the owner to confirm it's them before offering
 * pause options (from the lock screen the phone must be unlocked first). The tile mirrors the
 * vault's state while it's visible, and only says "Protecting" while an engine is actually running.
 */
class BioKeyTileService : TileService() {

    companion object {
        internal fun status(setupCompleted: Boolean, enabled: Boolean, engineRunning: Boolean): Status = when {
            !setupCompleted -> Status.SETUP_REQUIRED
            // As home says: nothing running outranks a pause, which resuming wouldn't fix.
            !engineRunning -> Status.NOT_PROTECTING
            !enabled -> Status.PAUSED
            else -> Status.PROTECTING
        }

        /**
         * The line under the tile's name: what it is, and when paused, until when and that a tap
         * resumes. Nothing beyond "Protecting" over the lock screen, where anyone can see it.
         */
        internal fun subtitle(
            context: Context,
            status: Status,
            pausedUntil: Long,
            locked: Boolean,
            now: Long = System.currentTimeMillis(),
            /** Switched on but not running, as against off: the same words as home's header. */
            notRunning: Boolean = false
        ): String? = when {
            // Over the lock screen, nothing under its name once set up: on, paused and off look the same there.
            locked && status != Status.SETUP_REQUIRED -> null
            status == Status.SETUP_REQUIRED -> context.getString(R.string.tile_setup_required)
            status == Status.NOT_PROTECTING -> notProtectingLabel(context, notRunning)
            status == Status.PROTECTING -> context.getString(R.string.tile_protecting)
            pausedUntil > now -> context.getString(R.string.tile_paused_until, pauseEndTime(context, pausedUntil))
            else -> context.getString(R.string.tile_paused)
        }
    }

    /** What the tile shows, and what a tap on it does. NOT_PROTECTING is "Off" or "Not running" ([notProtectingLabel]). */
    internal enum class Status { SETUP_REQUIRED, NOT_PROTECTING, PROTECTING, PAUSED }

    private val vault by lazy { SecurityVault.getInstance(this) }
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = CoroutineScope(Dispatchers.Main.immediate).launch {
            // The accessibility service connecting or stopping changes the answer too, as does a
            // pause's end time (a pause made longer, or ending).
            combine(vault.setupCompletedFlow, vault.globalLockEnabledFlow, vault.pausedUntilFlow, BioKeyAccessibilityService.running) { setupCompleted, enabled, _, _ ->
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
        // Over the lock screen the tile looks the same whether protection is on or paused: that isn't
        // for whoever picks the phone up to see. (Setup missing still shows, so a tap can finish it.)
        val shownOn = status == Status.PROTECTING || (isLocked && vault.isSetupCompleted())
        tile.state = if (shownOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = subtitle(
            this, status, vault.pausedUntilFlow.value, isLocked,
            notRunning = ProtectionEngine.mode(this) == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING
        )
        tile.updateTile()
    }
}
