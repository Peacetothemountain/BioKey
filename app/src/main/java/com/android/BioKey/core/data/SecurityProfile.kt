package com.android.BioKey.core.data

import org.json.JSONArray
import org.json.JSONObject

data class SecurityProfile(
    val id: String,
    val name: String,
    val iconKey: String = "shield", // "shield", "finance", "social", "lock", "work", "privacy"
    val packages: Set<String> = emptySet(),
    val isPreset: Boolean = false,
    val isStealth: Boolean = false,
    val timeoutMode: LockTimeoutMode = LockTimeoutMode.IMMEDIATE
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("iconKey", iconKey)
            put("isPreset", isPreset)
            put("isStealth", isStealth)
            put("timeoutMode", timeoutMode.name)
            val pkgArray = JSONArray()
            // Deterministic sorting guarantees identical HMAC-SHA256 integrity seal calculation
            packages.sorted().forEach { pkgArray.put(it) }
            put("packages", pkgArray)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): SecurityProfile {
            val id = json.optString("id", "")
            val name = json.optString("name", "Custom Profile")
            val iconKey = json.optString("iconKey", "shield")
            val isPreset = json.optBoolean("isPreset", false)
            val isStealth = json.optBoolean("isStealth", false)
            val timeoutModeStr = json.optString("timeoutMode", LockTimeoutMode.IMMEDIATE.name)
            val timeoutMode = LockTimeoutMode.fromName(timeoutModeStr)

            val packages = mutableSetOf<String>()
            val pkgArray = json.optJSONArray("packages")
            if (pkgArray != null) {
                for (i in 0 until pkgArray.length()) {
                    packages.add(pkgArray.getString(i))
                }
            }

            return SecurityProfile(
                id = id,
                name = name,
                iconKey = iconKey,
                packages = packages,
                isPreset = isPreset,
                isStealth = isStealth,
                timeoutMode = timeoutMode
            )
        }
    }
}
