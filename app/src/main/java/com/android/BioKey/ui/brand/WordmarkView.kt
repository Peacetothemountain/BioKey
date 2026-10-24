package com.android.BioKey.ui.brand

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View

/**
 * BioKey's mark ([KeyMark]) for plain-View screens (the lock cover), upright, fitted and centred,
 * as [BioKeyLogo] draws it. Still.
 *
 * Lives as long as the protection service, inside the shield's cover, and draws straight from the
 * mark's paths: nothing to decode, so the cover's first frame never waits for it.
 */
@SuppressLint("ViewConstructor")
class WordmarkView(context: Context) : View(context) {

    private var color = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The mark in [color], one of the theme's (its primary). */
    fun setColor(color: Int) {
        this.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        KeyMark.draw(canvas, 0f, 0f, width.toFloat(), height.toFloat(), color)
    }
}
