package com.android.BioKey.core.security

import android.content.Context
import android.os.Build
import android.security.advancedprotection.AdvancedProtectionManager

/**
 * Android's Advanced Protection mode (Android 16+).
 *
 * On Android 17, turning it on limits the accessibility API to genuine accessibility tools
 * (screen readers, switch and voice input, Braille). BioKey is an app lock, not an accessibility
 * tool, and declares itself honestly, so Android switches its service off and keeps it off while
 * the mode is on. BioKey then switches to compatibility mode (usage access and display over other
 * apps, which Advanced Protection leaves alone: see [ProtectionEngine]), and explains how to set
 * it up while those permissions are missing. Android's own App Lock keeps working under Advanced
 * Protection too.
 */
object AdvancedProtection {

    /** True when Advanced Protection is on (needs the normal QUERY_ADVANCED_PROTECTION_MODE permission). */
    fun isEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        return runCatching {
            context.getSystemService(AdvancedProtectionManager::class.java)?.isAdvancedProtectionEnabled == true
        }.getOrDefault(false)
    }

    /** Whether Advanced Protection on this Android version blocks BioKey's accessibility service. */
    fun blocksBioKey(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 37 && isEnabled(context)
}
