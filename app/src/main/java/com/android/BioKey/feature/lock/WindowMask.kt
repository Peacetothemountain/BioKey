package com.android.BioKey.feature.lock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.VisibleForTesting
import androidx.core.view.isVisible
import com.android.BioKey.R
import com.android.BioKey.ui.security.WindowProtection

/**
 * Opaque, screenshot-proof patches over single windows of locked apps, for where the full-screen
 * [InstantShieldOverlay] and the lock screen can't stay on top: a picture-in-picture window (it
 * floats above every app, BioKey's lock screen included), a window left beside the lock screen in
 * split screen or freeform, or a window on another display.
 *
 * Each patch is an accessibility overlay the size of its window and touchable only there, so the
 * window can't be seen, tapped, dragged or expanded through it; tapping the patch means the user
 * wants the app ([onTapped]). Kept apart from the shield on purpose: the lock screen dismissing
 * the shield never takes a patch with it.
 *
 * Which windows get a patch, and when they lose it, is the service's call. Main thread only.
 */
internal class WindowMask(
    private val service: AccessibilityService,
    private val onTapped: (Target) -> Unit
) {

    internal companion object {
        private const val TAG = "WindowMask"
        /** Patches reach a little past their window: picture-in-picture windows have rounded, shadowed edges. */
        const val PADDING_DP = 4

        /** Where a patch goes for a window at [bounds]: the window grown by [padding] on every side. */
        fun patchBounds(bounds: Rect, padding: Int): Rect =
            Rect(bounds.left - padding, bounds.top - padding, bounds.right + padding, bounds.bottom + padding)
    }

    /**
     * A window to mask: [windowId] of [pkg], at [bounds] (screen coordinates) on [displayId].
     * [touchable] is false while a system window (the notification shade, the power menu) sits
     * over it, so the patch doesn't take that window's taps.
     */
    data class Target(
        val windowId: Int,
        val pkg: String,
        val displayId: Int,
        val bounds: Rect,
        val inPip: Boolean,
        val touchable: Boolean = true
    )

    private class Patch(var target: Target, val windowManager: WindowManager, val view: View, val params: WindowManager.LayoutParams) {
        /** Added to the window manager (it is attached to its window a frame later). */
        var added = false
    }

    private val patches = HashMap<Int, Patch>()
    /** Every patch is hidden: the screen is off or the phone locked. */
    private var hiddenEverywhere = false
    /** Displays whose patches are hidden: the ones a prompt can be on. */
    private var hiddenOn: Set<Int> = emptySet()
    private val padding = (PADDING_DP * service.resources.displayMetrics.density).toInt()

    val isEmpty: Boolean get() = patches.isEmpty()

    fun isMasking(windowId: Int): Boolean = windowId in patches

    /** Whether [windowId] has a patch that draws and takes taps (it isn't hidden). */
    @VisibleForTesting
    internal fun isShowing(windowId: Int): Boolean = patches[windowId]?.let { !isHidden(it) && it.view.isVisible } == true

    /**
     * Masks exactly [targets]: new windows get a patch, moved ones are followed, and windows not
     * listed lose theirs, except on the displays in [keepOn] (where windows can be missing from
     * the list for a while, under a prompt). Returns the targets that couldn't be masked (the
     * caller must cover them some other way).
     */
    fun show(targets: Collection<Target>, keepOn: Set<Int> = emptySet()): List<Target> {
        val wanted = targets.associateBy { it.windowId }
        for (id in patches.keys - wanted.keys) {
            if (patches[id]?.target?.displayId !in keepOn) remove(id)
        }
        return add(targets)
    }

    /** Masks [targets] too, leaving other patches as they are. Returns the ones that couldn't be masked. */
    fun add(targets: Collection<Target>): List<Target> {
        val failed = ArrayList<Target>()
        for (target in targets) {
            val patch = patches[target.windowId]
            if (patch == null) {
                if (!attach(target)) failed += target
            } else if (patch.target != target) {
                patch.target = target
                place(patch)
            }
        }
        return failed
    }

    /**
     * Hidden patches keep their windows (showing them again is a visibility flip) but draw nothing
     * and take no taps. [hidden]: every patch, on every display; false shows them all again.
     */
    fun setHidden(hidden: Boolean) = hide(everywhere = hidden, displays = emptySet())

    /** Hides the patches on [displays] only, and shows every other one. */
    fun setHiddenOn(displays: Set<Int>) = hide(everywhere = false, displays = displays)

    private fun hide(everywhere: Boolean, displays: Set<Int>) {
        if (hiddenEverywhere == everywhere && hiddenOn == displays) return
        hiddenEverywhere = everywhere
        hiddenOn = displays
        for (patch in patches.values) place(patch)
    }

    private fun isHidden(patch: Patch) = hiddenEverywhere || patch.target.displayId in hiddenOn

    /** [pkg] has a session now: its windows are the user's again. */
    fun forget(pkg: String) {
        for ((id, patch) in patches.entries.toList()) if (patch.target.pkg == pkg) remove(id)
    }

    fun clear() {
        for (id in patches.keys.toList()) remove(id)
    }

    /** Colours follow the cover's (dark mode, wallpaper). */
    fun refreshPalette() {
        for (patch in patches.values) paint(patch.view)
    }

    private fun attach(target: Target): Boolean {
        val context = contextFor(target.displayId) ?: return false
        return try {
            val windowManager = context.getSystemService(WindowManager::class.java)
            val view = buildView(context)
            val params = WindowManager.LayoutParams(
                0, 0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowProtection.secureFlag(context) or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                windowAnimations = 0
                title = "BioKeyMask"
            }
            val patch = Patch(target, windowManager, view, params)
            view.setOnClickListener { onTapped(patch.target) }
            applyState(patch)
            windowManager.addView(view, params)
            patch.added = true
            patches[target.windowId] = patch
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't mask a window: ${e.message}")
            false
        }
    }

    /** Another display needs a window context of its own for an accessibility overlay. */
    private fun contextFor(displayId: Int): Context? {
        if (displayId == Display.DEFAULT_DISPLAY) return service
        return runCatching {
            val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
            service.createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        }.getOrNull()
    }

    private fun place(patch: Patch) {
        applyState(patch)
        try {
            if (patch.added) patch.windowManager.updateViewLayout(patch.view, patch.params)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't move a window's mask: ${e.message}")
        }
    }

    private fun applyState(patch: Patch) {
        val area = patchBounds(patch.target.bounds, padding)
        patch.params.x = area.left
        patch.params.y = area.top
        patch.params.width = area.width().coerceAtLeast(1)
        patch.params.height = area.height().coerceAtLeast(1)
        val hidden = isHidden(patch)
        val touchable = !hidden && patch.target.touchable
        patch.params.flags = if (touchable) {
            patch.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            patch.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        patch.view.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    private fun remove(windowId: Int) {
        val patch = patches.remove(windowId) ?: return
        runCatching { if (patch.added) patch.windowManager.removeViewImmediate(patch.view) }
    }

    private fun buildView(context: Context): View = FrameLayout(context).apply {
        isClickable = true
        contentDescription = context.getString(R.string.shield_public_title)
        WindowProtection.hideFromNonToolAccessibilityServices(this)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_lock_key)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams((40 * resources.displayMetrics.density).toInt(), (40 * resources.displayMetrics.density).toInt(), Gravity.CENTER))
        paint(this)
    }

    /** The lock cover's colours, on a rounded card the shape of a picture-in-picture window. */
    private fun paint(view: View) {
        val palette = LockCoverView.Palette(view.context)
        view.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(palette.surfaceContainer, palette.surface)
        ).apply { cornerRadius = 16 * view.resources.displayMetrics.density }
        ((view as FrameLayout).getChildAt(0) as ImageView).setColorFilter(palette.primary)
    }
}
