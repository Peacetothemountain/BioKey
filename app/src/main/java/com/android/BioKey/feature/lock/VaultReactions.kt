package com.android.BioKey.feature.lock

import android.content.Context
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService

/**
 * How the lock and the notification shield follow the vault ([SecurityVault.Listener]): locked
 * apps' names and icons made ready, the cover taken down on a pause, notifications shown again
 * once they needn't be hidden. Registered as BioKey starts, before the vault first loads.
 */
internal object VaultReactions : SecurityVault.Listener {
    override fun onLocked(context: Context, packages: Collection<String>) = AppInfoCache.preWarm(context, packages)

    override fun onUnlocked(packages: Collection<String>) = BioKeyNotificationShieldService.restoreNotificationsForPackages(packages)

    override fun onPaused() {
        InstantShieldOverlay.dismiss()
        BioKeyNotificationShieldService.restoreAllSnoozedNotifications()
    }

    override fun onNotificationShieldOff() = BioKeyNotificationShieldService.restoreAllSnoozedNotifications()
}
