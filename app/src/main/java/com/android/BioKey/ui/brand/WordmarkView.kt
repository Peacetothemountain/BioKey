package com.android.BioKey.ui.brand

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.android.BioKey.R

/**
 * BioKey's two-layer wordmark for plain-View screens (the lock cover): letters and vines, held
 * still (the cover is no place for motion), fitted and centred.
 *
 * Lives as long as the protection service, inside the shield's cover, so it draws from copies
 * sized to the mark ([LogoLayer]) rather than the full-size artwork. It never waits for one: with
 * a fixed size, the copies are made in the background as soon as the view is attached and
 * visible, and held here once ready; until then the mark is left out. So the cover's first frame,
 * the moment a locked app is covered, is never held back for a decode.
 *
 * [releaseWhenHidden]: a mark that is rarely shown (the cover's large logo, for BioKey's own
 * settings pages) lets its copies go [RELEASE_AFTER_MS] after it is hidden, rather than keep them
 * for the life of the service.
 */
@SuppressLint("ViewConstructor")
class WordmarkView(context: Context, private val releaseWhenHidden: Boolean = false) : View(context) {

    private companion object {
        const val RELEASE_AFTER_MS = 3 * 60_000L
    }

    private val letters = LogoLayer(resources, R.drawable.ic_title_logo_letters)
    private val vines = VineSlither(resources)

    /** A copy made in the background is ready: taken in now (so nothing else can make it go first), and drawn. */
    private val onCopyReady: () -> Unit = {
        if (isAttachedToWindow) {
            prepare()
            invalidate()
        }
    }

    private val release = Runnable {
        letters.release()
        vines.release()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Letters in [letters] (the theme's primary), vines in [vines] (its tertiary). */
    fun setColors(letters: Int, vines: Int) {
        this.letters.setTint(letters)
        this.vines.setTint(vines)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prepare()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(release)
        release.run()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView !== this) return
        if (visibility == VISIBLE) {
            removeCallbacks(release)
            prepare()
        } else if (releaseWhenHidden) {
            removeCallbacks(release)
            postDelayed(release, RELEASE_AFTER_MS)
        }
    }

    /** Lays both layers out at the view's fixed size, ahead of its first frame (see the class comment). */
    private fun prepare() {
        val lp = layoutParams ?: return
        if (!isAttachedToWindow || visibility != VISIBLE || lp.width <= 0 || lp.height <= 0) return
        letters.layout(lp.width.toFloat(), lp.height.toFloat(), onCopyReady)
        vines.prepare(lp.width.toFloat(), lp.height.toFloat(), onCopyReady)
    }

    override fun onDraw(canvas: Canvas) {
        letters.draw(canvas, width.toFloat(), height.toFloat(), onCopyReady)
        vines.draw(canvas, width.toFloat(), height.toFloat(), null, onCopyReady)
    }
}
