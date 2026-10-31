package com.android.BioKey.feature.lock

import android.content.res.Resources

/**
 * The words of Android's own App lock prompt ([SystemAppLock]), as Android has them in the phone's
 * language: "This app is protected with app lock" ([oneApp]), beside the app's name and icon, or,
 * with several locked apps on screen at once, "X and Y are protected with app lock" ([twoApps],
 * [threeApps]). Read from Android's own resources ([fromSystem]), so they match the prompt in any
 * language; a prompt with none of them isn't Android's App lock prompt.
 */
internal class SystemAppLockPrompt(private val oneApp: String, twoApps: String? = null, threeApps: String? = null) {

    companion object {
        /** Android's own words, or null where it has none (before Android 17 QPR3, or another build). */
        fun fromSystem(): SystemAppLockPrompt? = runCatching {
            val res = Resources.getSystem()
            fun words(name: String): String? = res.getIdentifier(name, "string", SystemAppLock.PACKAGE).takeIf { it != 0 }?.let(res::getString)
            val one = words("locked_app_biometric_prompt_desc_one_app") ?: return null
            SystemAppLockPrompt(
                one,
                words("locked_app_biometric_prompt_desc_two_apps"),
                words("locked_app_biometric_prompt_desc_three_apps")
            )
        }.getOrNull()

        /**
         * A pattern matching [format] ("%1$s and %2$s are protected with app lock") with any names
         * in its places, each name in the group its number says (languages reorder them). Null for
         * a format with no places.
         */
        internal fun patternOf(format: String): Pair<Regex, List<Int>>? {
            val place = Regex("%(\\d+)\\\$s")
            val order = place.findAll(format).map { it.groupValues[1].toInt() }.toList()
            if (order.isEmpty()) return null
            val parts = format.split(place).map { Regex.escape(it) }
            return Regex(parts.joinToString("(.+?)")) to order
        }
    }

    private val forms = listOfNotNull(twoApps, threeApps).mapNotNull(::patternOf)

    /**
     * The names on a prompt whose texts are [texts], or null if it isn't Android's App lock prompt.
     * One app: every other text there, its name among them (beside the title and buttons, which
     * name no app); several: the names in its sentence, in their order.
     */
    fun namesIn(texts: List<String>): List<String>? {
        if (oneApp in texts) return texts.filter { it != oneApp }
        for (text in texts) {
            for ((pattern, order) in forms) {
                val match = pattern.matchEntire(text) ?: continue
                val names = match.groupValues.drop(1)
                return order.indices.sortedBy { order[it] }.map { names[it] }
            }
        }
        return null
    }

    /** Whether [texts] come from a prompt naming one app ([oneApp]): then exactly one name must match. */
    fun namesOneApp(texts: List<String>): Boolean = oneApp in texts
}
