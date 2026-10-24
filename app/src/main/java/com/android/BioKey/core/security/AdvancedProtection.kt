package com.android.BioKey.core.security

import android.content.Context
import android.os.Build
import android.security.advancedprotection.AdvancedProtectionManager
import androidx.annotation.ChecksSdkIntAtLeast

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
    fun isEnabled(context: Context): Boolean = isEnabled(Build.VERSION.SDK_INT) {
        context.getSystemService(AdvancedProtectionManager::class.java)?.isAdvancedProtectionEnabled == true
    }

    /** Whether Advanced Protection on this Android version blocks BioKey's accessibility service. */
    fun blocksBioKey(context: Context): Boolean = blocksBioKey(Build.VERSION.SDK_INT) { isEnabled(context) }

    /**
     * [isEnabled] on Android [sdk], where [ask] asks Android itself. Apart from Android so tests
     * can run it on versions Robolectric can't (16 and 17 need Java 21). Android 15 and older have
     * no Advanced Protection to ask about; a question that fails counts as off.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.BAKLAVA, lambda = 1)
    internal fun isEnabled(sdk: Int, ask: () -> Boolean): Boolean {
        if (sdk < Build.VERSION_CODES.BAKLAVA) return false
        return runCatching(ask).getOrDefault(false)
    }

    /** [blocksBioKey] on Android [sdk], where [enabled] says whether Advanced Protection is on. */
    internal fun blocksBioKey(sdk: Int, enabled: () -> Boolean): Boolean = sdk >= 37 && enabled()
}
