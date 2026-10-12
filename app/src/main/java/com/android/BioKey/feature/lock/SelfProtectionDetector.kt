package com.android.BioKey.feature.lock

import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.android.BioKey.R

/**
 * Recognises system screens that could switch BioKey off: its App info page (force stop,
 * uninstall, clear storage), its accessibility-service toggle, the device-admin page, notification
 * access, and the uninstall confirmation in the package installer or Play Store.
 *
 * Heuristic by necessity (there is no API for this): a screen in one of [HOST_PACKAGES] that
 * names BioKey *and* offers a destructive control (a toggle, or an uninstall/force-stop style
 * action). Lists that merely mention BioKey (battery usage, storage) don't trigger.
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
            // OEM security/device-care apps that can force-stop or disable apps.
            "com.samsung.android.lool",
            "com.miui.securitycenter",
            "com.coloros.safecenter",
            "com.oplus.battery"
        )

        private const val CONTENT_CHECK_INTERVAL_MS = 400L
        private const val MAX_NODES_SCANNED = 400

        private val DESTRUCTIVE_ACTIONS = listOf("uninstall", "force stop", "deactivate", "clear storage", "clear data", "turn off")

        private val APP_HEADER_IDS = listOf(
            "com.android.settings:id/entity_header_title",
            "com.android.settings:id/app_detail_title"
        )
    }

    private val labels: List<String> = listOf(
        service.getString(R.string.app_name),
        "BioKey",
        service.getString(R.string.accessibility_service_label)
    ).distinct()

    private var lastContentCheck = 0L

    fun isHost(pkg: String) = pkg in HOST_PACKAGES

    fun isGuardedScreen(pkg: String, event: AccessibilityEvent): Boolean {
        if (pkg !in HOST_PACKAGES) return false
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastContentCheck < CONTENT_CHECK_INTERVAL_MS) return false
            lastContentCheck = now
        }

        val eventText = buildString {
            event.text.forEach { append(it).append(' ') }
            append(event.contentDescription ?: "")
        }
        val titleNamesBioKey = mentionsBioKey(eventText)

        val root = service.rootInActiveWindow ?: return titleNamesBioKey && pkg in INSTALLER_PACKAGES
        try {
            if (root.packageName?.toString() != pkg) return false
            val pageNamesBioKey = titleNamesBioKey || labels.any { label -> root.hasNodeWithText(label) }
            if (!pageNamesBioKey) return false

            // The uninstall confirmation dialog: naming BioKey is enough.
            if (pkg in INSTALLER_PACKAGES) return true

            // AOSP/Pixel App info header showing BioKey's name.
            for (id in APP_HEADER_IDS) {
                val headers = root.findAccessibilityNodeInfosByViewId(id).orEmpty()
                val match = headers.any { node -> mentionsBioKey(node.text?.toString().orEmpty()) }
                headers.forEach(::release)
                if (match) return true
            }

            if (DESTRUCTIVE_ACTIONS.any { root.hasNodeWithText(it) }) return true
            return root.containsToggle()
        } finally {
            release(root)
        }
    }

    private fun mentionsBioKey(text: CharSequence): Boolean = labels.any { text.contains(it, ignoreCase = true) }

    private fun AccessibilityNodeInfo.hasNodeWithText(text: String): Boolean {
        val nodes = findAccessibilityNodeInfosByText(text).orEmpty()
        val found = nodes.isNotEmpty()
        nodes.forEach(::release)
        return found
    }

    /** Breadth-first search for a switch/checkbox, bounded so huge lists stay cheap. */
    private fun AccessibilityNodeInfo.containsToggle(): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until childCount) getChild(i)?.let(queue::addLast)
        var scanned = 0
        var found = false
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!found && scanned < MAX_NODES_SCANNED) {
                scanned++
                val cls = node.className?.toString().orEmpty()
                if (node.isCheckable || cls.contains("Switch") || cls.contains("CheckBox")) {
                    found = true
                } else {
                    for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
                }
            }
            release(node)
        }
        return found
    }

    private fun release(node: AccessibilityNodeInfo) {
        // Node recycling is a no-op from API 33; it still returns nodes to the pool on 31-32.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }
}
