package com.android.BioKey.feature.lock

import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.android.BioKey.R

/**
 * Recognises system screens that specifically target BioKey and could switch it off:
 * - BioKey's App info / Storage pages (XML and Android 14-17 Jetpack Compose SPA)
 * - BioKey's Accessibility service detail page and stop confirmation dialog
 * - BioKey's Notification access / Special app access detail page and revoke dialog
 * - Package installer uninstall confirmation dialog for BioKey
 *
 * Multi-app list screens (Settings -> Apps, See all apps, Accessibility list, Special app access
 * lists) where BioKey is merely one row among other apps must NEVER trigger.
 */
internal class SelfProtectionDetector(private val service: BioKeyAccessibilityService) {

    companion object {
        val INSTALLER_PACKAGES = setOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.coloros.packageinstaller",
            "com.oppo.packageinstaller",
            "com.vivo.packageinstaller"
        )

        val HOST_PACKAGES = INSTALLER_PACKAGES + setOf(
            "com.android.settings",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.android.vending",
            "com.samsung.android.lool",
            "com.miui.securitycenter",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.oplus.battery",
            // One UI keeps its Accessibility menu in an app of its own.
            "com.samsung.accessibility",
            "com.vivo.permissionmanager",
            "com.iqoo.secure",
            "com.huawei.systemmanager"
        )

        private const val SETTINGS_PKG = "com.android.settings"

        /** AOSP Settings strings for App info actions; most manufacturers keep these names. */
        private val SETTINGS_ACTION_STRINGS = listOf(
            "force_stop", "uninstall_text", "uninstall_all_users_text", "clear_user_data_text",
            "clear_cache_btn_text", "remove_device_admin"
        )

        /** AOSP Settings strings that name one accessibility service: its switch and its stop dialog. */
        private val SETTINGS_SERVICE_PROMPTS = listOf(
            "accessibility_service_primary_switch_title", "accessibility_service_primary_open_title",
            "disable_service_title"
        )

        private const val CONTENT_CHECK_INTERVAL_MS = 120L
        private const val MAX_NODES_SCANNED = 400

        /**
         * English fallback: exact standalone action labels that only appear on an App Info, App
         * Storage, or Device Admin detail screen (never on Settings -> Apps, See all apps, or Accessibility lists).
         */
        private val EXACT_APP_DETAIL_ACTIONS = setOf(
            "force stop",
            "uninstall",
            "clear storage",
            "clear data",
            "clear cache",
            "deactivate",
            "deactivate this device admin app"
        )

        /**
         * View IDs that exclusively represent the single-app subject header, screen toolbar title,
         * or confirmation dialog title/message. NEVER include "android:id/title" (used by every
         * Preference list row in Settings).
         */
        private val SUBJECT_HEADER_AND_DIALOG_IDS = setOf(
            "com.android.settings:id/entity_header_title",
            "com.android.settings:id/app_detail_title",
            "com.google.android.permissioncontroller:id/entity_header_title",
            "com.android.permissioncontroller:id/entity_header_title",
            "com.miui.securitycenter:id/app_manager_details_applabel",
            "com.android.settings:id/collapsing_toolbar",
            "android:id/alertTitle",
            "androidx.appcompat:id/alertTitle",
            "com.android.settings:id/alertTitle",
            "com.android.permissioncontroller:id/alertTitle",
            "com.google.android.permissioncontroller:id/alertTitle",
            "android:id/message"
        )

        private val ACTION_BAR_IDS = setOf(
            "com.android.settings:id/action_bar",
            "android:id/action_bar"
        )

        private val SAME_NODE_PROMPT_VERBS = listOf(
            "use ", "stop ", "turn off", "disable ", "uninstall", "deactivate", "deny ", "allow "
        )
    }

    private val appName = service.getString(R.string.app_name)
    private val a11yLabel = service.getString(R.string.accessibility_service_label)
    private val notifLabel = service.getString(R.string.notification_shield_label)
    private val pkgName = service.packageName

    private val labels: List<String> = listOf(
        appName,
        "BioKey",
        pkgName,
        a11yLabel,
        notifLabel
    ).filter { it.isNotBlank() }.distinct()

    /**
     * The opening of BioKey's own accessibility description, shown only on its Accessibility
     * detail page. Taken from the description itself, in BioKey's language, so it can't drift.
     */
    private val a11yDescriptionSnippet = service.getString(R.string.accessibility_service_description)
        .take(48).trim().lowercase()

    // The device's own Settings labels, in the phone's language (English kept as a fallback),
    // re-read when the language changes.
    private var labelsLocale: java.util.Locale? = null
    private var appDetailActions: Set<String> = EXACT_APP_DETAIL_ACTIONS
    private var serviceTogglePrompts: Set<String> = emptySet()
    private var versionPrefix = "version "

    private fun refreshSettingsLabels() {
        val locale = service.resources.configuration.locales[0]
        if (locale == labelsLocale) return
        labelsLocale = locale
        val res = runCatching { service.packageManager.getResourcesForApplication(SETTINGS_PKG) }.getOrNull()
        fun string(name: String, vararg args: Any): String? = runCatching {
            @Suppress("DiscouragedApi") // Another app's resources: only reachable by name.
            val id = res?.getIdentifier(name, "string", SETTINGS_PKG) ?: 0
            if (id == 0) null else res?.getString(id, *args)?.trim()?.lowercase()
        }.getOrNull()?.takeIf { it.isNotBlank() }
        appDetailActions = EXACT_APP_DETAIL_ACTIONS + SETTINGS_ACTION_STRINGS.mapNotNull { string(it) }
        serviceTogglePrompts = SETTINGS_SERVICE_PROMPTS.mapNotNull { string(it, a11yLabel) }.toSet()
        // "Version %1$s" -> "version ": whatever comes before the number.
        versionPrefix = string("version_text", "§§")?.substringBefore("§§")?.takeIf { it.isNotBlank() } ?: "version "
    }

    private var lastContentCheck = 0L
    private var lastGuardedResult = false

    fun isHost(pkg: String) = pkg in HOST_PACKAGES

    fun isCredentialConfirmationClass(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        return className.contains("ConfirmDeviceCredential", ignoreCase = true) ||
            className.contains("ConfirmLockPattern", ignoreCase = true) ||
            className.contains("ConfirmLockPassword", ignoreCase = true) ||
            className.contains("BiometricPrompt", ignoreCase = true) ||
            className.contains("AuthContainerView", ignoreCase = true)
    }

    fun isGuardedScreen(
        pkg: String,
        event: AccessibilityEvent? = null,
        onTrailingCheckNeeded: ((Long) -> Unit)? = null
    ): Boolean {
        refreshSettingsLabels()
        if (pkg !in HOST_PACKAGES) {
            lastGuardedResult = false
            return false
        }
        val eventCls = event?.className?.toString()
        if (isCredentialConfirmationClass(eventCls)) {
            lastGuardedResult = false
            return false
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastContentCheck = 0L
        } else if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastContentCheck
            if (elapsed < CONTENT_CHECK_INTERVAL_MS) {
                onTrailingCheckNeeded?.invoke(CONTENT_CHECK_INTERVAL_MS - elapsed + 20L)
                return lastGuardedResult
            }
            lastContentCheck = now
        }

        val isWindowStateEvent = event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val eventText = if (event != null) {
            buildString {
                event.text.forEach { append(it).append(' ') }
                append(event.contentDescription ?: "")
            }.trim()
        } else ""
        if (eventText.contains("Unlock BioKey", ignoreCase = true) ||
            eventText.contains("Unlock $appName", ignoreCase = true) ||
            eventText.contains("Verify it's you", ignoreCase = true)
        ) {
            lastGuardedResult = false
            return false
        }
        val eventNamesBioKey = eventText.isNotEmpty() && mentionsBioKey(eventText)

        val root = service.rootInActiveWindow
        if (root == null) {
            if (pkg in INSTALLER_PACKAGES && eventNamesBioKey) {
                lastGuardedResult = true
                return true
            }
            onTrailingCheckNeeded?.invoke(180L)
            return lastGuardedResult
        }
        try {
            if (root.packageName?.toString() != pkg) {
                onTrailingCheckNeeded?.invoke(180L)
                return lastGuardedResult
            }

            // 1. Package installer dialogs only ever target a single app.
            if (pkg in INSTALLER_PACKAGES) {
                val res = eventNamesBioKey || labels.any { root.hasNodeWithText(it) }
                lastGuardedResult = res
                return res
            }

            // 2. Explicit single-app header, collapsing toolbar, or dialog title/message IDs.
            for (id in SUBJECT_HEADER_AND_DIALOG_IDS) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id).orEmpty()
                val match = nodes.any { node ->
                    val t = node.text?.toString().orEmpty()
                    val d = node.contentDescription?.toString().orEmpty()
                    val isUnlockPrompt = t.contains("Unlock ", ignoreCase = true) || d.contains("Unlock ", ignoreCase = true)
                    !isUnlockPrompt && (mentionsBioKey(t) || mentionsBioKey(d))
                }
                nodes.forEach(::release)
                if (match) {
                    lastGuardedResult = true
                    return true
                }
            }

            // 3. Check ActionBar title children (for devices not using collapsing_toolbar).
            for (barId in ACTION_BAR_IDS) {
                val bars = root.findAccessibilityNodeInfosByViewId(barId).orEmpty()
                var barMatches = false
                for (bar in bars) {
                    if (!barMatches && subtreeMentionsBioKey(bar, maxDepth = 3)) {
                        barMatches = true
                    }
                    release(bar)
                }
                if (barMatches) {
                    lastGuardedResult = true
                    return true
                }
            }

            // 4. Single-pass structural scan of the window for Compose SPA App Info,
            //    Accessibility detail page, Special App Access detail page, or Compose dialogs.
            val res = scanWindowStructure(root, isWindowStateEvent && eventNamesBioKey)
            lastGuardedResult = res
            return res
        } finally {
            release(root)
        }
    }

    private fun scanWindowStructure(root: AccessibilityNodeInfo, windowTitleNamesBioKey: Boolean): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until root.childCount) root.getChild(i)?.let(queue::addLast)

        var scanned = 0
        var mentionsBioKeyNode = windowTitleNamesBioKey
        var hasExactPackageName = false
        var hasVersionHeader = false
        var hasAppDetailAction = false
        var hasToggle = false
        var matchedDirectBioKeyControl = false

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!matchedDirectBioKeyControl && scanned < MAX_NODES_SCANNED) {
                scanned++
                val rawText = node.text?.toString()?.trim().orEmpty()
                val rawDesc = node.contentDescription?.toString()?.trim().orEmpty()
                val combined = if (rawDesc.isNotEmpty() && rawDesc != rawText) "$rawText $rawDesc" else rawText
                val lower = combined.lowercase()
                val lowerText = rawText.lowercase()
                val viewId = node.viewIdResourceName.orEmpty()

                if (viewId in SUBJECT_HEADER_AND_DIALOG_IDS && mentionsBioKey(combined)) {
                    matchedDirectBioKeyControl = true
                } else if (lower.contains(a11yDescriptionSnippet)) {
                    // BioKey's unique accessibility service description on the A11y detail page
                    matchedDirectBioKeyControl = true
                } else if (mentionsBioKey(combined)) {
                    mentionsBioKeyNode = true
                    if (rawText.equals(pkgName, ignoreCase = true)) {
                        hasExactPackageName = true
                    }
                    // Single node that names BioKey AND has a control/prompt verb
                    // e.g. "Use Bio Key App Lock Service", "Stop Bio Key App Lock Service?",
                    // "Bio Key App Lock Service shortcut", "Turn off notification access for Bio Key?"
                    if (lower in serviceTogglePrompts || SAME_NODE_PROMPT_VERBS.any { lower.contains(it) } ||
                        lower.endsWith(" shortcut") ||
                        lower.endsWith("?")
                    ) {
                        matchedDirectBioKeyControl = true
                    }
                }

                if (!matchedDirectBioKeyControl) {
                    if (lowerText in appDetailActions || rawDesc.lowercase() in appDetailActions) {
                        hasAppDetailAction = true
                    }
                    if (lowerText.startsWith(versionPrefix) || lowerText.startsWith("version ")) {
                        hasVersionHeader = true
                    }
                    val cls = node.className?.toString().orEmpty()
                    if (node.isCheckable || cls.contains("Switch") || cls.contains("CheckBox")) {
                        hasToggle = true
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
                }
            }
            release(node)
        }

        if (matchedDirectBioKeyControl) return true
        if (!mentionsBioKeyNode) return false

        // BioKey App Info / Storage page (XML or Android 14-17 Compose SpaActivity):
        // Names BioKey AND has an exact App Info action ("Force stop", "Uninstall", "Clear storage", etc.)
        if (hasAppDetailAction) return true

        // Android 14-17 Compose SpaActivity single-app detail page (AppInfoHeader renders
        // "Bio Key" + "Version X.Y" or "com.android.BioKey", plus a permission toggle or detail body).
        if ((hasVersionHeader || hasExactPackageName) && (hasToggle || windowTitleNamesBioKey)) {
            return true
        }

        return false
    }

    private fun subtreeMentionsBioKey(root: AccessibilityNodeInfo, maxDepth: Int): Boolean {
        if (mentionsBioKey(root.text?.toString().orEmpty()) ||
            mentionsBioKey(root.contentDescription?.toString().orEmpty())
        ) return true
        if (maxDepth <= 0) return false
        var found = false
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            if (!found && subtreeMentionsBioKey(child, maxDepth - 1)) {
                found = true
            }
            release(child)
        }
        return found
    }

    private fun mentionsBioKey(text: CharSequence): Boolean =
        text.isNotEmpty() && labels.any { text.contains(it, ignoreCase = true) }

    private fun AccessibilityNodeInfo.hasNodeWithText(text: String): Boolean {
        val nodes = findAccessibilityNodeInfosByText(text).orEmpty()
        val found = nodes.isNotEmpty()
        nodes.forEach(::release)
        return found
    }

    private fun release(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }
}
