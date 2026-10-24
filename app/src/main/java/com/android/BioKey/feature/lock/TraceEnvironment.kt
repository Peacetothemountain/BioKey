package com.android.BioKey.feature.lock

import android.content.Context
import com.android.BioKey.core.diagnostics.EngineTrace
import com.android.BioKey.core.security.SecurityVault

/**
 * What an engine trace starts with ([EngineTrace.start]): which engine is running, whether
 * protection is on, each locked app with its re-lock delay and name, and the phone's home
 * screens, helper apps and keyboards ([ForegroundRules.traceEnvironment]). A replay sets the
 * same up before it feeds the engine anything. Debug builds only (it asks for app names).
 */
internal fun startEngineTrace(context: Context, vault: SecurityVault, engine: String) {
    if (!EngineTrace.enabled) return
    val env = buildList {
        add(EngineTrace.line("env", "engine", engine))
        add(EngineTrace.line("env", "protection", if (vault.isProtectionActive()) 1 else 0))
        for (pkg in vault.getLockedPackages()) {
            add(EngineTrace.line("env", "locked", pkg, vault.getTimeoutMode(pkg).name, AppInfoCache.quickLabel(context, pkg)))
        }
        addAll(ForegroundRules.traceEnvironment(context))
    }
    EngineTrace.start(context, env)
}
