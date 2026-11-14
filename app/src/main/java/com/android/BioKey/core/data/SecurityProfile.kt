package com.android.BioKey.core.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named group of apps with its own on/off switch, and optionally its own re-lock delay
 * ([timeoutMode]; null follows the default in Settings). An app is in at most one profile. An app is locked when its own switch is on and, if it is in a profile, that
 * profile is [enabled]: turning a profile off keeps each app's own choice for when it's back on.
 */
data class SecurityProfile(
    val id: String,
    val name: String,
    val iconKey: String = "shield",
    val packages: Set<String> = emptySet(),
    val timeoutMode: LockTimeoutMode? = null,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("iconKey", iconKey)
        .apply { if (timeoutMode != null) put("timeoutMode", timeoutMode.name) }
        .put("packages", JSONArray(packages.sorted()))
        .put("enabled", enabled)

    companion object {
        const val PRESET_PERSONAL = "profile_personal"
        const val PRESET_FINANCE = "profile_finance"
        const val PRESET_SOCIAL = "profile_social"
        const val PRESET_WORK = "profile_work"

        val DEFAULTS = listOf(
            SecurityProfile(PRESET_PERSONAL, "Personal", "shield"),
            SecurityProfile(PRESET_FINANCE, "Finance", "finance"),
            SecurityProfile(PRESET_SOCIAL, "Social", "social"),
            SecurityProfile(PRESET_WORK, "Work", "work")
        )

        /**
         * The re-lock delays built-in profiles had before they followed Settings (model version 3):
         * one still on its own is taken to be untouched, and follows Settings from then on.
         */
        val FORMER_PRESET_TIMEOUTS = mapOf(
            PRESET_PERSONAL to LockTimeoutMode.IMMEDIATE,
            PRESET_FINANCE to LockTimeoutMode.IMMEDIATE,
            PRESET_SOCIAL to LockTimeoutMode.IMMEDIATE,
            PRESET_WORK to LockTimeoutMode.GRACE_5_MINUTES
        )

        fun fromJson(json: JSONObject): SecurityProfile {
            val pkgArray = json.optJSONArray("packages")
            val limit = minOf(pkgArray?.length() ?: 0, 4_000)
            val packages = buildSet {
                for (i in 0 until limit) {
                    val pkg = pkgArray?.opt(i) as? String ?: throw IllegalArgumentException("Expected package string in profile")
                    add(pkg)
                }
            }
            return SecurityProfile(
                id = json.optString("id"),
                name = AppText.cleanLabel(json.optString("name", "Profile"), 40).ifEmpty { "Profile" },
                iconKey = AppText.cleanLabel(json.optString("iconKey", "shield"), 32).ifEmpty { "shield" },
                packages = packages,
                timeoutMode = json.optString("timeoutMode").takeIf { it.isNotEmpty() }?.let(LockTimeoutMode::fromName),
                enabled = json.optBoolean("enabled", true)
            )
        }
    }
}
