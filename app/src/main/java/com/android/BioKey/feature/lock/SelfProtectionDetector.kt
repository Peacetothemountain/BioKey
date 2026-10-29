package com.android.BioKey.feature.lock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
 *
 * Every answer is about one window of the host: the event's own, or the host's window on screen.
 * Never the active window when that belongs to another app (the other half of a split screen, or
 * the launcher a new window is still opening from), and never an answer kept from another window.
 */
internal class SelfProtectionDetector(private val service: BioKeyAccessibilityService) {

    companion object {
        private val INSTALLER_PACKAGES = setOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.coloros.packageinstaller",
            "com.oppo.packageinstaller",
            "com.vivo.packageinstaller"
        )

        private val HOST_PACKAGES = INSTALLER_PACKAGES + setOf(
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

        /**
         * Settings, installers, permission and security apps: where BioKey's own pages are, and
         * where a settings trip BioKey started may lead
         * ([com.android.BioKey.core.security.LockStateTracker.beginExternalNavigation]).
         * For both engines.
         */
        fun isHostPackage(pkg: String) = pkg in HOST_PACKAGES

        /** Android's own confirm-your-PIN and biometric screens (also used outside self-protection). */
        fun isCredentialConfirmationClass(className: String?): Boolean {
            if (className.isNullOrBlank()) return false
            return className.contains("ConfirmDeviceCredential", ignoreCase = true) ||
                className.contains("ConfirmLockPattern", ignoreCase = true) ||
                className.contains("ConfirmLockPassword", ignoreCase = true) ||
                className.contains("ConfirmCommonPassword", ignoreCase = true) ||
                className.contains("BiometricPrompt", ignoreCase = true) ||
                className.contains("AuthContainerView", ignoreCase = true)
        }

        private const val SETTINGS_PKG = "com.android.settings"
        private const val PLAY_STORE_PKG = "com.android.vending"

        /** AOSP Settings strings for App info actions; most manufacturers keep these names. */
        private val SETTINGS_ACTION_STRINGS = listOf(
            "force_stop", "uninstall_text", "uninstall_all_users_text", "clear_user_data_text",
            "clear_cache_btn_text"
        )

        /** AOSP Settings strings that name one accessibility service: its switch and its stop dialog. */
        private val SETTINGS_SERVICE_PROMPTS = listOf(
            "accessibility_service_primary_switch_title", "accessibility_service_primary_open_title",
            "disable_service_title"
        )

        private const val CONTENT_CHECK_INTERVAL_MS = 120L

        /**
         * The Play Store redraws all the time (banners, carousels, download progress), and each
         * look is a round trip run on its UI thread, so its pages are read less often. Its
         * dialogs, uninstalling among them, open with a state event, which is always read at once.
         */
        private const val PLAY_STORE_CHECK_INTERVAL_MS = 500L

        /** How long [pkg]'s last answer stands for a burst of content events. */
        internal fun contentCheckIntervalMs(pkg: String): Long =
            if (pkg == PLAY_STORE_PKG) PLAY_STORE_CHECK_INTERVAL_MS else CONTENT_CHECK_INTERVAL_MS

        private const val MAX_NODES_SCANNED = 400

        /** A host window that can't be read yet (it is still opening) is looked at again this often, this many times. */
        private const val UNREADABLE_RETRY_MS = 180L
        private const val UNREADABLE_RETRIES = 3

        /**
         * English fallback: exact standalone action labels that only appear on an App Info or App
         * Storage detail screen (never on Settings -> Apps, See all apps, or Accessibility lists).
         */
        private val EXACT_APP_DETAIL_ACTIONS = setOf(
            "force stop",
            "uninstall",
            "clear storage",
            "clear data",
            "clear cache"
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

        /** English control verbs a single node starts with, e.g. "Use BioKey App Lock Service". */
        private val SAME_NODE_PROMPT_VERBS = listOf(
            "use ", "stop ", "turn off", "disable ", "uninstall", "deny ", "allow "
        )

        /** Where Android keeps what the accessibility shortcuts (volume keys, button, gesture) switch. */
        private val SHORTCUT_TARGET_SETTINGS = listOf(
            "accessibility_shortcut_target_service",
            "accessibility_button_targets",
            "accessibility_gesture_targets"
        )

        /**
         * Whether an accessibility shortcut is assigned to BioKey's service. A shortcut switches the
         * service off straight away, without any of the screens self-protection guards, so the
         * owner should be told to remove it.
         */
        fun isShortcutAssigned(context: Context): Boolean = SHORTCUT_TARGET_SETTINGS.any { key ->
            val targets = runCatching { Settings.Secure.getString(context.contentResolver, key) }.getOrNull()
            shortcutTargetsInclude(targets, context.packageName, BioKeyAccessibilityService::class.java.name)
        }

        /** [targets] is a ':'-separated list of flattened component names, full or short ("pkg/.Cls"). */
        internal fun shortcutTargetsInclude(targets: String?, pkg: String, cls: String): Boolean =
            !targets.isNullOrBlank() && targets.split(':').any { entry ->
                val targetPkg = entry.substringBefore('/', "").trim()
                val targetCls = entry.substringAfter('/', "").trim()
                targetPkg == pkg && (targetCls == cls || (targetCls.startsWith('.') && pkg + targetCls == cls))
            }

        /**
         * Matches any of [labels] as a whole name: "BioKey" and "BioKey's", but not the start of
         * "BioKeyboard". Only Latin letters and digits count as part of a word, so a name written
         * right next to Chinese or Japanese text still matches.
         */
        internal fun namePattern(labels: Collection<String>): Regex = Regex(
            labels.filter { it.isNotBlank() }.sortedByDescending { it.length }
                .joinToString("|", prefix = "(?<![A-Za-z0-9])(?:", postfix = ")(?![A-Za-z0-9])") { Regex.escape(it) },
            RegexOption.IGNORE_CASE
        )

        /**
         * A single node that is itself a control or prompt for one service: "Use BioKey App Lock
         * Service", "Stop BioKey App Lock Service?", "BioKey App Lock Service shortcut", "Turn
         * off notification access for BioKey notification shield?". [togglePrompts] are the
         * device's own Settings strings for them, in the phone's language.
         */
        internal fun isControlPrompt(text: String, togglePrompts: Set<String>): Boolean {
            val lower = text.trim().lowercase()
            return lower in togglePrompts || SAME_NODE_PROMPT_VERBS.any { lower.startsWith(it) } ||
                lower.endsWith(" shortcut") ||
                lower.endsWith("?") || lower.endsWith("？")
        }

        /**
         * The [labels] worth a text search: Android's finds any text that contains the label,
         * ignoring case, so a label that contains a shorter one ("BioKey App Lock Service",
         * "BioKey") finds nothing the shorter one doesn't. Only exact containment counts, so no
         * language's rules for upper and lower case can make the two searches differ.
         */
        internal fun textSearchLabels(labels: Collection<String>): List<String> {
            val kept = ArrayList<String>()
            for (label in labels.filter { it.isNotBlank() }.sortedBy { it.length }) {
                if (kept.none { label.contains(it) }) kept += label
            }
            return kept
        }

        /**
         * The [ids] a [host]'s window can actually contain: its own, the framework's and AndroidX's.
         * Asking a window for another app's IDs is a wasted round trip to that app. Google's builds
         * of AOSP apps (com.google.android.permissioncontroller) keep AOSP's resource package.
         */
        internal fun viewIdsFor(host: String, ids: Collection<String>): List<String> {
            val own = setOf(host, host.replace("com.google.android.", "com.android."))
            return ids.filter { id ->
                val resourcePkg = id.substringBefore(':')
                resourcePkg == "android" || resourcePkg.startsWith("androidx.") || resourcePkg in own
            }
        }
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

    private val names = namePattern(labels)
    private val searchLabels = textSearchLabels(labels)

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

    private val headerIdsByHost = HashMap<String, List<String>>()
    private val actionBarIdsByHost = HashMap<String, List<String>>()

    private data class WindowKey(val pkg: String, val windowId: Int)

    /** The last answer and the window it was about: a burst of content events reuses it briefly. */
    private var lastKey: WindowKey? = null
    private var lastCheckAt = 0L
    private var lastResult = false

    /** How the service asks for another look later, kept from its last event. */
    private var recheck: ((Long) -> Unit)? = null
    private var unreadableRetries = 0

    fun isHost(pkg: String) = isHostPackage(pkg)


    /**
     * Whether [pkg]'s window is one of BioKey's guarded screens: [event]'s window, or without an
     * event the host's window on screen. [onTrailingCheckNeeded] schedules another look (with no
     * event) after the given delay; it is called when this answer may be about to change.
     */
    fun isGuardedScreen(
        pkg: String,
        event: AccessibilityEvent? = null,
        onTrailingCheckNeeded: ((Long) -> Unit)? = null
    ): Boolean {
        if (pkg !in HOST_PACKAGES) return false
        if (onTrailingCheckNeeded != null) {
            recheck = onTrailingCheckNeeded
            unreadableRetries = 0
        }
        if (isCredentialConfirmationClass(event?.className?.toString())) return false
        refreshSettingsLabels()

        val isWindowStateEvent = event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isContentEvent = event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (event != null && isContentEvent && answeredMomentsAgo(WindowKey(pkg, event.windowId))) return lastResult

        val eventNamesBioKey = event != null &&
            (event.text.any { mentionsBioKey(it) } || mentionsBioKey(event.contentDescription))

        val root = hostRoot(pkg, event)
        if (root == null) {
            if (pkg in INSTALLER_PACKAGES && eventNamesBioKey) return true
            // Not readable yet: never fall back to an answer about some other window.
            if (unreadableRetries < UNREADABLE_RETRIES) {
                unreadableRetries++
                recheck?.invoke(UNREADABLE_RETRY_MS)
            }
            return false
        }
        unreadableRetries = 0
        try {
            val key = WindowKey(pkg, root.windowId)
            if (event == null && answeredMomentsAgo(key)) return lastResult
            val result = judge(root, pkg, isContentEvent, isWindowStateEvent && eventNamesBioKey, eventNamesBioKey)
            lastKey = key
            lastCheckAt = SystemClock.elapsedRealtime()
            lastResult = result
            return result
        } finally {
            root.release()
        }
    }

    /**
     * [key]'s window was read less than [contentCheckIntervalMs] ago: its answer stands, and
     * another look is scheduled for when the interval is over, so the last state is always read.
     */
    private fun answeredMomentsAgo(key: WindowKey): Boolean {
        val later = recheck ?: return false
        val elapsed = SystemClock.elapsedRealtime() - lastCheckAt
        val interval = contentCheckIntervalMs(key.pkg)
        if (key != lastKey || elapsed >= interval) return false
        later(interval - elapsed + 20L)
        return true
    }

    /**
     * The root of the window to judge, or null while it can't be read: [event]'s own window, or
     * without an event the host's window on screen (the active one first).
     */
    private fun hostRoot(pkg: String, event: AccessibilityEvent?): AccessibilityNodeInfo? {
        val windowId = event?.windowId ?: -1
        val active = runCatching { service.rootInActiveWindow }.getOrNull()
        if (active != null) {
            if (active.packageName?.toString() == pkg && (windowId < 0 || active.windowId == windowId)) return active
            active.release()
        }
        // Split screen, or a window that isn't the active one yet: look it up in the window list.
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        val candidates = if (windowId >= 0) {
            windows.filter { it.id == windowId }
        } else {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.isActive }
        }
        for (window in candidates) {
            runCatching { window.root }.getOrNull()?.ofPackage(pkg)?.let { return it }
        }
        return null
    }

    /** This node if it belongs to [pkg]; otherwise it is released. */
    private fun AccessibilityNodeInfo.ofPackage(pkg: String): AccessibilityNodeInfo? {
        if (packageName?.toString() == pkg) return this
        release()
        return null
    }

    private fun judge(
        root: AccessibilityNodeInfo,
        pkg: String,
        isContentEvent: Boolean,
        windowTitleNamesBioKey: Boolean,
        eventNamesBioKey: Boolean
    ): Boolean {
        // 1. Package installer dialogs only ever target a single app.
        if (pkg in INSTALLER_PACKAGES) {
            return eventNamesBioKey || root.hasNodeNamingBioKey()
        }

        // Steps 2 and 3 cost a round trip to the host per ID (up to 9 in Settings), run on its UI
        // thread, and content events come many times a second (the Play Store redraws all the
        // time). For those the scan goes first, and the IDs are only asked for when the window
        // names BioKey somewhere.
        if (isContentEvent) {
            val scan = scanWindowStructure(root, pkg, windowTitleNamesBioKey)
            return scan == Scan.GUARDED || (scan == Scan.NAMES_BIOKEY && hasBioKeyTitle(root, pkg))
        }
        // A window that has just opened (a state event, once per screen), and the looks without an
        // event that follow a burst of content events, ask for the IDs first: they also find a title
        // an app has hidden from accessibility, which no scan reads. Self-protection must never miss
        // a guarded page, so these looks pay the round trips.
        return hasBioKeyTitle(root, pkg) || scanWindowStructure(root, pkg, windowTitleNamesBioKey) == Scan.GUARDED
    }

    /** Steps 2 and 3: a header, toolbar or dialog title (found by view ID) that names BioKey. */
    private fun hasBioKeyTitle(root: AccessibilityNodeInfo, pkg: String): Boolean {
        // 2. Explicit single-app header, collapsing toolbar, or dialog title/message IDs.
        for (id in headerIdsByHost.getOrPut(pkg) { viewIdsFor(pkg, SUBJECT_HEADER_AND_DIALOG_IDS) }) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id).orEmpty()
            val match = nodes.any { node ->
                headerNamesBioKey(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())
            }
            nodes.forEach { it.release() }
            if (match) return true
        }

        // 3. Check ActionBar title children (for devices not using collapsing_toolbar).
        for (barId in actionBarIdsByHost.getOrPut(pkg) { viewIdsFor(pkg, ACTION_BAR_IDS) }) {
            val bars = root.findAccessibilityNodeInfosByViewId(barId).orEmpty()
            var barMatches = false
            for (bar in bars) {
                if (!barMatches && subtreeMentionsBioKey(bar, maxDepth = 3)) {
                    barMatches = true
                }
                bar.release()
            }
            if (barMatches) return true
        }
        return false
    }

    /** What a scan found. [UNFINISHED]: nothing named BioKey, but the window was too big to read whole. */
    private enum class Scan { GUARDED, NAMES_BIOKEY, OTHER, UNFINISHED }

    /**
     * 4. Single-pass structural scan of the window for Compose SPA App Info, Accessibility detail
     * page, Special App Access detail page, or Compose dialogs.
     */
    private fun scanWindowStructure(root: AccessibilityNodeInfo, pkg: String, windowTitleNamesBioKey: Boolean): Scan {
        // Play Store pages are full of other people's sentences (reviews, descriptions), so there
        // only the page's structure counts, never a single sentence that names BioKey.
        val trustSentences = pkg != PLAY_STORE_PKG
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        var scanned = 0
        var unfinished = false

        // Each child is a round trip to the host, so only those that will be read are fetched:
        // no more than MAX_NODES_SCANNED in all, counting those already waiting. The nodes read
        // are the same first ones as when every child was fetched.
        fun queueChildrenOf(node: AccessibilityNodeInfo) {
            for (i in 0 until node.childCount) {
                if (scanned + queue.size >= MAX_NODES_SCANNED) {
                    unfinished = true
                    return
                }
                node.getChild(i)?.let(queue::addLast)
            }
        }
        queueChildrenOf(root)

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
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val textNamesBioKey = mentionsBioKey(text)
                val descNamesBioKey = mentionsBioKey(desc)

                if (textNamesBioKey || descNamesBioKey) {
                    mentionsBioKeyNode = true
                    if (text.equals(pkgName, ignoreCase = true)) {
                        hasExactPackageName = true
                    }
                    if (node.viewIdResourceName in SUBJECT_HEADER_AND_DIALOG_IDS && headerNamesBioKey(text, desc)) {
                        matchedDirectBioKeyControl = true
                    } else if (trustSentences &&
                        ((textNamesBioKey && isControlPrompt(text, serviceTogglePrompts)) ||
                            (descNamesBioKey && isControlPrompt(desc, serviceTogglePrompts)))
                    ) {
                        // Single node that names BioKey AND is a control or prompt for it.
                        matchedDirectBioKeyControl = true
                    }
                }
                if (!matchedDirectBioKeyControl && a11yDescriptionSnippet.isNotEmpty() &&
                    (text.contains(a11yDescriptionSnippet, ignoreCase = true) || desc.contains(a11yDescriptionSnippet, ignoreCase = true))
                ) {
                    // BioKey's unique accessibility service description on the A11y detail page
                    matchedDirectBioKeyControl = true
                }

                if (!matchedDirectBioKeyControl) {
                    if (text.lowercase() in appDetailActions || desc.lowercase() in appDetailActions) {
                        hasAppDetailAction = true
                    }
                    if (text.startsWith(versionPrefix, ignoreCase = true) || text.startsWith("version ", ignoreCase = true)) {
                        hasVersionHeader = true
                    }
                    val cls = node.className?.toString().orEmpty()
                    if (node.isCheckable || cls.contains("Switch") || cls.contains("CheckBox")) {
                        hasToggle = true
                    }
                    queueChildrenOf(node)
                }
            }
            node.release()
        }

        if (matchedDirectBioKeyControl) return Scan.GUARDED
        if (!mentionsBioKeyNode) return if (unfinished) Scan.UNFINISHED else Scan.OTHER

        // BioKey App Info / Storage page (XML or Android 14-17 Compose SpaActivity):
        // Names BioKey AND has an exact App Info action ("Force stop", "Uninstall", "Clear storage", etc.)
        if (hasAppDetailAction) return Scan.GUARDED

        // Android 14-17 Compose SpaActivity single-app detail page (AppInfoHeader renders
        // "BioKey" + "Version X.Y" or "com.android.BioKey", plus a permission toggle or detail body).
        if ((hasVersionHeader || hasExactPackageName) && (hasToggle || windowTitleNamesBioKey)) {
            return Scan.GUARDED
        }

        return Scan.NAMES_BIOKEY
    }

    /** A header or dialog title that names BioKey, other than an "Unlock …" prompt. */
    private fun headerNamesBioKey(text: String, desc: String): Boolean {
        val isUnlockPrompt = text.contains("Unlock ", ignoreCase = true) || desc.contains("Unlock ", ignoreCase = true)
        return !isUnlockPrompt && (mentionsBioKey(text) || mentionsBioKey(desc))
    }

    private fun subtreeMentionsBioKey(root: AccessibilityNodeInfo, maxDepth: Int): Boolean {
        if (mentionsBioKey(root.text) || mentionsBioKey(root.contentDescription)) return true
        if (maxDepth <= 0) return false
        var found = false
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            if (!found && subtreeMentionsBioKey(child, maxDepth - 1)) {
                found = true
            }
            child.release()
        }
        return found
    }

    private fun mentionsBioKey(text: CharSequence?): Boolean =
        !text.isNullOrEmpty() && names.containsMatchIn(text)

    /** Android's text search matches parts of words ("BioKeyboard"): keep whole-name matches only. */
    private fun AccessibilityNodeInfo.hasNodeNamingBioKey(): Boolean = searchLabels.any { label ->
        val nodes = findAccessibilityNodeInfosByText(label).orEmpty()
        val found = nodes.any { mentionsBioKey(it.text) || mentionsBioKey(it.contentDescription) }
        nodes.forEach { it.release() }
        found
    }
}
