package com.android.BioKey.feature.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.android.BioKey.R
import com.android.BioKey.core.security.FrontApp
import com.android.BioKey.feature.lock.ForegroundRules
import com.android.BioKey.feature.lock.UsageWatchService
import com.android.BioKey.feature.main.MainActivity
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Quick Settings "Lock this app": locks the app the shade was pulled down over, without opening
 * BioKey: the nearest an app lock gets to the launcher's own long-press "App lock". It only ever
 * adds a lock (or ends the app's session, if it's locked already), so like Lock all it needs no
 * authentication. Never for a home screen, BioKey itself, or the system's own screens.
 *
 * Over the phone's lock screen it never names the app: whoever holds the locked phone mustn't
 * learn what was last open.
 */
class LockThisAppTileService : TileService() {

    private companion object {
        /** Where the app in front is asked for ([appInFront]). */
        val lookups: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "biokey-lock-app-tile").apply { isDaemon = true } }
    }

    private val main = Handler(Looper.getMainLooper())
    /** The showing of the tile a name is being looked up for: an answer for an earlier one is dropped. */
    private var shown = 0

    override fun onStartListening() {
        super.onStartListening()
        val showing = ++shown
        render(subtitle = null)
        // Over the phone's lock screen it names no app: nothing to look up.
        if (!isLocked) appInFront { app -> if (showing == shown) render(subtitle = null, app = app) }
    }

    override fun onStopListening() {
        shown++
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // A tap is always acted on, whatever the tile is showing by the time the answer comes.
        appInFront(::lock)
    }

    /**
     * The app in front ([UsageWatchService.appInFrontForTile]), then [then] on the main thread.
     * Asked off it: with compatibility mode reading nothing, that is a look through Android's
     * usage events, which can take a while.
     */
    private fun appInFront(then: (String?) -> Unit) {
        val app = applicationContext
        lookups.execute {
            val pkg = runCatching { UsageWatchService.appInFrontForTile(app) }.getOrNull()
            main.post { then(pkg) }
        }
    }

    private fun lock(app: String?) {
        val outcome = FrontApp.lock(this, app) { ForegroundRules.isLauncher(this, it, isLocked = { false }) }
        val named = !isLocked
        render(
            subtitle = when (outcome) {
                FrontApp.Outcome.NotSetUp -> return openBioKey()
                FrontApp.Outcome.NothingToLock -> getString(R.string.lock_app_tile_nothing)
                is FrontApp.Outcome.ProfileOff ->
                    if (named) getString(R.string.lock_app_tile_profile_off, outcome.profileName) else getString(R.string.lock_app_tile_not_locked)
                is FrontApp.Outcome.Locked -> {
                    val label = FrontApp.label(this, outcome.pkg)
                    when {
                        !outcome.protecting -> getString(R.string.lock_app_tile_not_protecting)
                        outcome.paused -> getString(R.string.lock_app_tile_paused)
                        named -> getString(R.string.lock_app_tile_locked, label)
                        else -> getString(R.string.lock_app_tile_locked_unnamed)
                    }
                }
            },
            done = outcome is FrontApp.Outcome.Locked
        )
    }

    /** Not set up: BioKey opens, to set it up (after the phone's own unlock, from the lock screen). */
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

    /**
     * An action, not a switch: it rests inactive. Its subtitle says which app a tap locks ([app],
     * the one in front; not over the lock screen), or, after a tap, what happened ([done]: shown
     * as active a moment).
     */
    private fun render(subtitle: String?, done: Boolean = false, app: String? = null) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.lock_app_tile_label)
        tile.state = if (done) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = subtitle ?: app?.takeIf { !isLocked && canLock(it) }?.let { FrontApp.label(this, it) }
        tile.updateTile()
    }

    /** Whether a tap could lock [pkg]: an app with a launcher icon, not BioKey or a home screen ([FrontApp.lock] decides). */
    private fun canLock(pkg: String): Boolean = pkg != packageName && packageManager.getLaunchIntentForPackage(pkg) != null &&
        !ForegroundRules.isLauncher(this, pkg, isLocked = { false })
}
