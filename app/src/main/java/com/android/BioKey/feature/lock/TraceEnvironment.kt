package com.android.BioKey.feature.lock

import android.content.Context
import android.provider.Settings
import com.android.BioKey.core.diagnostics.EngineTrace
import com.android.BioKey.core.security.SecurityVault

/**
 * What an engine trace starts with ([EngineTrace.start]): which engine is running, whether
 * protection is on, whether the lock-screen camera opens over the keyguard, each locked app with
 * its re-lock delay and name, the phone's home screens, helper apps, keyboards and lock-screen
 * camera and emergency apps ([ForegroundRules.traceEnvironment]), and its screensaver settings
 * (whether they're on, the chosen one, Android's default). A replay sets the same up before it
 * feeds the engine anything. Debug builds only (it asks for app names).
 */
internal fun startEngineTrace(context: Context, vault: SecurityVault, engine: String) {
    if (!EngineTrace.enabled) return
    val env = buildList {
        add(EngineTrace.line("env", "engine", engine))
        add(EngineTrace.line("env", "protection", if (vault.isProtectionActive()) 1 else 0))
        add(EngineTrace.line("env", "keyguardCamera", if (vault.isKeyguardCameraAllowed()) 1 else 0))
        for (pkg in vault.getLockedPackages()) {
            add(EngineTrace.line("env", "locked", pkg, vault.getTimeoutMode(pkg).name, AppInfoCache.quickLabel(context, pkg)))
        }
        addAll(ForegroundRules.traceEnvironment(context))
        val settings = context.contentResolver
        add(
            EngineTrace.line(
                "env", "screensaver",
                Settings.Secure.getInt(settings, "screensaver_enabled", 0),
                Settings.Secure.getString(settings, "screensaver_components").orEmpty(),
                Settings.Secure.getString(settings, "screensaver_default_component").orEmpty()
            )
        )
    }
    EngineTrace.start(context, env)
}
