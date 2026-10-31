package com.android.BioKey.feature.lock

import android.content.Context
import com.android.BioKey.core.security.SecurityVault

/**
 * How the lock follows the vault ([SecurityVault.Listener]): locked apps' names and icons made
 * ready, the cover taken down on a pause. Registered as BioKey starts, before the vault first loads.
 */
internal object VaultReactions : SecurityVault.Listener {
    override fun onLocked(context: Context, packages: Collection<String>) = AppInfoCache.preWarm(context, packages)

    override fun onPaused() = InstantShieldOverlay.dismiss()
}
