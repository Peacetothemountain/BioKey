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
 * sized to the mark ([LogoLayer]) rather than the full-size artwork. With a fixed size, those
 * copies are made in the background once the view is attached and visible, so the cover's first
 * frame, the moment a locked app is covered, doesn't wait for a decode.
 */
@SuppressLint("ViewConstructor")
class WordmarkView(context: Context) : View(context) {

    private val letters = LogoLayer(resources, R.drawable.ic_title_logo_letters)
    private val vines = VineSlither(resources)

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

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) prepare()
    }

    private fun prepare() {
        val lp = layoutParams ?: return
        if (!isAttachedToWindow || visibility != VISIBLE || lp.width <= 0 || lp.height <= 0) return
        letters.prepare(lp.width, lp.height)
        vines.prepare(lp.width, lp.height)
    }

    override fun onDraw(canvas: Canvas) {
        letters.draw(canvas, width.toFloat(), height.toFloat())
        vines.draw(canvas, width.toFloat(), height.toFloat(), null)
    }
}
