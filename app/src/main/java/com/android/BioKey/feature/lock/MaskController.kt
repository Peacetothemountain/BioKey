package com.android.BioKey.feature.lock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.android.BioKey.core.data.PasswordManager
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault

/**
 * The patches ([WindowMask]) over locked apps' windows where a full-screen cover can't stay:
 * picture-in-picture, windows beside the lock screen (split screen, freeform), other displays.
 * Decides which windows need one and puts them up; anything else (whose a window is, covering an
 * app the usual way when a patch can't go up) is the accessibility service's ([Host]).
 */
internal class MaskController(
    /** The accessibility service: patches are its overlay windows. */
    private val context: AccessibilityService,
    private val vault: SecurityVault,
    private val shield: InstantShieldOverlay,
    private val handler: Handler,
    private val host: Host
) {
    /** What the controller asks of the accessibility service. */
    interface Host {
        fun windowList(): List<AccessibilityWindowInfo>?
        /** Whose window this is, as already known (its events, or attributed). */
        fun knownPackage(windowId: Int): String?
        fun packageOf(window: AccessibilityWindowInfo): String?
        /** A window of [pkg] may be covered (not BioKey's, nor a sign-in sheet's host). */
        fun isCoverable(pkg: String): Boolean
        fun isOwnWindow(window: AccessibilityWindowInfo): Boolean
        fun isInteractive(): Boolean
        fun isKeyguardLocked(): Boolean
        /** [pkg] is locked, without a session, and not left on purpose. */
        fun needsCover(pkg: String): Boolean
        fun isOwnScreenInFront(front: String?): Boolean
        fun mayBe(target: String, pkg: String?): Boolean
        fun lock(pkg: String, why: String, displayId: Int? = null)
        /** [pkg]'s lock screen is up under a patch: to the front, asking again. */
        fun bringLockScreenForward(pkg: String, displayId: Int)
        fun guarded(what: String, block: () -> Unit)
        fun describe(t: Throwable): String
    }

    private val masks = WindowMask(context, ::onMaskTapped)

    /** Apps whose every window on screen has a [WindowMask] patch: covered already. */
    var maskedApps: Set<String> = emptySet()
        private set

    private val recheck = Runnable { host.guarded("Mask check") { host.windowList()?.let { update(it, full = false) } } }

    val isEmpty: Boolean get() = masks.isEmpty

    /** [pkg] has a session or isn't locked any more: its patches go. */
    fun forget(pkg: String) = masks.forget(pkg)

    fun refreshPalette() = masks.refreshPalette()

    fun clear() = masks.clear()

    /** The service is going: no look is left pending. */
    fun stop() = handler.removeCallbacks(recheck)

    /**
     * Puts [WindowMask] patches where a full-screen cover can't stay: over picture-in-picture
     * windows of locked apps without a session, and over their windows beside the lock screen
     * (split screen, freeform) or on another display. The latter only on [full] passes: right
     * after a window change, such a window may still be on its way under the lock screen. A patch
     * stays until its app has a session or stops being locked, or its window has gone. While the
     * phone is locked, patches are only hidden (they would sit over the keyguard), never removed;
     * while a prompt is up, likewise, but only on the display(s) it can be on: elsewhere they stay
     * up and take taps, or a locked app on another display would be usable for as long as a
     * prompt waits (a fingerprint prompt has no time limit).
     */
    fun update(list: List<AccessibilityWindowInfo>, full: Boolean) {
        if (!vault.isProtectionActive()) {
            masks.clear()
            maskedApps = emptySet()
            return
        }
        val apps = list.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val targets = apps.mapNotNull { maskTarget(it, list, full) }
        if (targets.isEmpty() && masks.isEmpty) {
            maskedApps = emptySet()
            return
        }
        if (!host.isInteractive() || host.isKeyguardLocked() || apps.isEmpty()) {
            masks.setHidden(true)
            masks.add(targets)
            return
        }
        if (SecurityEngine.INSTANCE.isAuthenticating()) {
            // A patch over the prompt would hide its buttons and fingerprint area: none where it
            // can be. The prompt's own scrim takes touches there meanwhile, and the windows under
            // it drop out of the list, so patches there are kept, not followed.
            val prompt = setOf(Display.DEFAULT_DISPLAY) + listOfNotNull(SecurityEngine.INSTANCE.promptDisplayId())
            masks.setHiddenOn(prompt)
            masks.show(targets, keepOn = prompt)
            handler.removeCallbacks(recheck)
            handler.postDelayed(recheck, MASK_RECHECK_MS)
            return
        }
        val failed = masks.show(targets)
        masks.setHidden(false)
        val unmasked = apps.filterNot { masks.isMasking(it.id) }.mapNotNullTo(HashSet()) { host.knownPackage(it.id) }
        maskedApps = targets.filter { masks.isMasking(it.windowId) }.mapTo(HashSet()) { it.pkg } - unmasked
        // A patch that couldn't be put up: cover the app the usual way instead (fail closed).
        for (target in failed) {
            if (shield.isShowing || LockStateTracker.isRegularLockPending(target.pkg)) continue
            if (target.inPip) coverFromPip(target.pkg, target.windowId) else host.lock(target.pkg, "its window couldn't be masked", target.displayId)
        }
    }

    /** The patch window [w] needs, if any: see [update]. */
    private fun maskTarget(w: AccessibilityWindowInfo, list: List<AccessibilityWindowInfo>, full: Boolean): WindowMask.Target? {
        val pkg = host.knownPackage(w.id) ?: (if (w.isInPictureInPictureMode) host.packageOf(w) else null) ?: return null
        if (pkg == context.packageName || !host.isCoverable(pkg) || !vault.isAppLocked(pkg) ||
            LockStateTracker.isAppUnlocked(pkg, vault.getTimeoutMode(pkg))
        ) return null
        val bounds = Rect().also(w::getBoundsInScreen)
        val elsewhere = w.displayId != Display.DEFAULT_DISPLAY
        // A full-screen window on the phone's own screen is for the shield and the lock screen to cover.
        val fullScreen = !elsewhere && coversScreen(bounds)
        val wanted = w.isInPictureInPictureMode ||
            (!fullScreen && (masks.isMasking(w.id) || (full && (elsewhere || besideLockScreen(w, bounds, list)))))
        if (!wanted) return null
        val overlapped = list.any { other ->
            other.type == AccessibilityWindowInfo.TYPE_SYSTEM && other.displayId == w.displayId && other.layer > w.layer &&
                (other.isActive || other.isFocused) && Rect.intersects(bounds, Rect().also(other::getBoundsInScreen))
        }
        return WindowMask.Target(w.id, pkg, w.displayId, bounds, w.isInPictureInPictureMode, touchable = !overlapped)
    }

    /**
     * While the lock screen is up, a window still listed is beside it, not under it (Android
     * leaves windows that are wholly covered out of the list), unless BioKey's own window over it
     * contains it.
     */
    private fun besideLockScreen(w: AccessibilityWindowInfo, bounds: Rect, list: List<AccessibilityWindowInfo>): Boolean {
        if (!LockScreenActivity.isLockScreenResumed) return false
        return list.none { own ->
            own.type == AccessibilityWindowInfo.TYPE_APPLICATION && own.displayId == w.displayId && own.layer > w.layer &&
                host.isOwnWindow(own) && Rect().also(own::getBoundsInScreen).contains(bounds)
        }
    }

    private fun coversScreen(bounds: Rect): Boolean {
        val screen = runCatching { context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds }.getOrNull() ?: return true
        return bounds.width() >= screen.width() && bounds.height() >= screen.height()
    }

    /**
     * The screen is going off, and every session with it. Locked apps in picture-in-picture are on
     * screen again the moment the phone is unlocked, so their patches go up now, hidden.
     */
    fun arm() {
        masks.setHidden(true)
        if (!vault.isProtectionActive()) return
        val list = host.windowList() ?: return
        val pips = list.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isInPictureInPictureMode }.mapNotNull { w ->
            val pkg = host.packageOf(w) ?: return@mapNotNull null
            if (pkg == context.packageName || !host.isCoverable(pkg) || !vault.isAppLocked(pkg)) return@mapNotNull null
            WindowMask.Target(w.id, pkg, w.displayId, Rect().also(w::getBoundsInScreen), inPip = true)
        }
        masks.add(pips)
    }

    /** A patch was tapped: the user wants that app. */
    private fun onMaskTapped(target: WindowMask.Target) {
        host.guarded("Mask tap") {
            val pkg = target.pkg
            // Asked for on purpose, like a tap on its icon.
            LockStateTracker.clearAbandoned(pkg)
            when {
                // Its lock screen is already up, under the patch (picture-in-picture, or a window
                // over a smaller lock screen, whose buttons the patch can hide): bring it to the
                // front and ask again, even if the user had dismissed the prompt.
                LockScreenActivity.isLockScreenResumed && LockScreenActivity.activeLockedTarget == pkg ->
                    host.bringLockScreenForward(pkg, target.displayId)
                !target.inPip -> host.lock(pkg, "its window was tapped", target.displayId)
                else -> coverFromPip(pkg, target.windowId)
            }
        }
    }

    /**
     * Brings a locked app out of picture-in-picture so it can be locked: the screen is covered
     * first, then the app is expanded (or started, which expands it), and the usual lock flow
     * takes over once it is in front. Its lock screen can't go first: picture-in-picture floats
     * above it, and expanding the app afterwards would push the lock screen away.
     */
    fun coverFromPip(pkg: String, windowId: Int?) {
        if (shield.isShowing) return
        shield.showPredicted(
            targetPackage = pkg,
            waitMs = PIP_EXPAND_WAIT_MS,
            onTapped = { onPipExpandOver(pkg) },
            onExpired = ::onPipExpandOver
        )
        val window = windowId?.let { id -> host.windowList()?.firstOrNull { it.id == id } }
        val root = window?.let { runCatching { it.root }.getOrNull() }
        val expanded = root?.let { node -> runCatching { node.performAction(AccessibilityNodeInfo.ACTION_EXPAND) }.getOrDefault(false).also { node.release() } } == true
        if (!expanded) startApp(pkg)
    }

    /** The app tapped in picture-in-picture had its time to come out of it. */
    private fun onPipExpandOver(pkg: String) {
        try {
            if (!host.needsCover(pkg)) {
                shield.hide()
                return
            }
            val list = host.windowList()
            val stillInPip = list?.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isInPictureInPictureMode && host.packageOf(it) == pkg } == true
            val inFront = list?.let(WindowReader::frontWindow)?.let(host::packageOf)
            when {
                // It wouldn't leave: its lock screen goes up under it, its patch stays over it.
                // Over BioKey's own screen, that lock screen closes back onto BioKey (a trip BioKey
                // starts itself); one that did come out has pushed BioKey off screen itself.
                stillInPip -> {
                    if (host.isOwnScreenInFront(inFront)) LockStateTracker.beginOwnLockScreenHop(pkg)
                    host.lock(pkg, "in picture-in-picture without a session")
                }
                host.mayBe(pkg, inFront) -> host.lock(pkg, "opened from picture-in-picture")
                else -> shield.hide()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Picture-in-picture check failed: ${host.describe(t)}")
            // Nothing would take this cover down again: lock the app (it fails closed from there), or stand down.
            if (vault.isAppLocked(pkg)) host.lock(pkg, "in picture-in-picture") else shield.hide()
        }
    }

    private fun startApp(pkg: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(PasswordManager.launchPackage(pkg)) ?: return
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "Couldn't bring $pkg out of picture-in-picture: ${it.message}") }
    }

    private companion object {
        const val TAG = "BioKeyMasks"
        /** How long a locked app tapped in picture-in-picture has to come out of it before its lock screen goes up anyway. */
        const val PIP_EXPAND_WAIT_MS = 1_000L
        /** How often masks hidden for a prompt look whether it has gone. */
        const val MASK_RECHECK_MS = 400L
    }
}
