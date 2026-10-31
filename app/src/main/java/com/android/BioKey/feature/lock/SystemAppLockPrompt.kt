package com.android.BioKey.feature.lock

import android.content.res.Resources

/**
 * The words of Android's own App lock prompt ([SystemAppLock]), as Android has them in the phone's
 * language: "This app is protected with app lock" ([oneApp]), beside the app's name and icon, or,
 * with several locked apps on screen at once, "X and Y are protected with app lock" ([twoApps],
 * [threeApps]). Read from Android's own resources ([fromSystem]), so they match the prompt in any
 * language; a prompt with none of them isn't Android's App lock prompt.
 */
internal class SystemAppLockPrompt(
    private val oneApp: String,
    twoApps: String? = null,
    threeApps: String? = null,
    /** The prompt's own title ("Verify it's you"): what Android calls its window too. */
    private val title: String? = null
) {

    companion object {
        /** What SystemUI names the prompt's window itself, whatever its title. */
        private const val PROMPT_WINDOW = "BiometricPrompt"

        /** Android's own words, or null where it has none (before Android 17 QPR3, or another build). */
        fun fromSystem(): SystemAppLockPrompt? = runCatching {
            val res = Resources.getSystem()
            fun words(name: String): String? = res.getIdentifier(name, "string", SystemAppLock.PACKAGE).takeIf { it != 0 }?.let(res::getString)
            val one = words("locked_app_biometric_prompt_desc_one_app") ?: return null
            SystemAppLockPrompt(
                one,
                words("locked_app_biometric_prompt_desc_two_apps"),
                words("locked_app_biometric_prompt_desc_three_apps"),
                words("biometric_dialog_default_title")
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
     * Whether a window titled [windowTitle] is a biometric prompt's (SystemUI's own name for it,
     * or the title Android's App lock gives its prompt): never the notification shade or a
     * heads-up, which can show any words at all.
     */
    fun isPromptWindow(windowTitle: CharSequence?): Boolean {
        val t = windowTitle?.toString()?.trim() ?: return false
        return t == PROMPT_WINDOW || (title != null && t == title)
    }

    /**
     * The names on a prompt whose texts are [texts], or null if it isn't Android's App lock prompt.
     * One app: every other text there but its title, its name among them (beside the buttons, which
     * name no app); several: the names in its sentence, in their order.
     */
    fun namesIn(texts: List<String>): List<String>? {
        if (oneApp in texts) return texts.filter { it != oneApp && it != title }
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
