package com.android.BioKey.core.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named group of apps that share a re-lock delay, with its own on/off switch. An app is in at
 * most one profile. An app is locked when its own switch is on and, if it is in a profile, that
 * profile is [enabled]: turning a profile off keeps each app's own choice for when it's back on.
 */
data class SecurityProfile(
    val id: String,
    val name: String,
    val iconKey: String = "shield",
    val packages: Set<String> = emptySet(),
    val timeoutMode: LockTimeoutMode = LockTimeoutMode.IMMEDIATE,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("iconKey", iconKey)
        .put("timeoutMode", timeoutMode.name)
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
            SecurityProfile(PRESET_WORK, "Work", "work", timeoutMode = LockTimeoutMode.GRACE_5_MINUTES)
        )

        fun fromJson(json: JSONObject): SecurityProfile {
            val pkgArray = json.optJSONArray("packages")
            val packages = buildSet { for (i in 0 until (pkgArray?.length() ?: 0)) add(pkgArray!!.getString(i)) }
            return SecurityProfile(
                id = json.optString("id"),
                name = json.optString("name", "Profile"),
                iconKey = json.optString("iconKey", "shield"),
                packages = packages,
                timeoutMode = LockTimeoutMode.fromName(json.optString("timeoutMode")),
                enabled = json.optBoolean("enabled", true)
            )
        }
    }
}
