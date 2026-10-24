package com.android.BioKey.ui.brand

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.android.BioKey.R
import kotlin.math.min

/**
 * BioKey's logo for plain-View screens (the lock cover): its mark ([KeyMark]) beside its name, as
 * [BioKeyLogo] sets them, fitted and centred. Still.
 *
 * Lives as long as the protection service, inside the shield's cover, and draws straight from the
 * mark's paths and a 3 KB font: nothing to decode, so the cover's first frame never waits for it.
 */
@SuppressLint("ViewConstructor")
class WordmarkView(context: Context) : View(context) {

    private companion object {
        /** As [BioKeyLogo]: the name's size and the gap before it, as parts of the mark's height. */
        const val NAME_SIZE = 0.6f
        const val GAP = 0.2f

        /** The name's typeface, loaded once for the process (Google Sans Flex, bold, cut down to "BioKey"). */
        @Volatile var typeface: Typeface? = null
    }

    private var primary = 0
    private var secondary = 0
    private var tertiary = 0

    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Companion.typeface ?: (ResourcesCompat.getFont(context, R.font.biokey_wordmark) ?: Typeface.DEFAULT_BOLD)
            .also { Companion.typeface = it }
        textAlign = Paint.Align.LEFT
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The mark in [primary], [secondary] and [tertiary] (the theme's), the name in [text]. */
    fun setColors(primary: Int, secondary: Int, tertiary: Int, text: Int) {
        this.primary = primary
        this.secondary = secondary
        this.tertiary = tertiary
        name.color = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // The name's width for each pixel of its size, then the mark's height that fits both ways.
        name.textSize = 100f
        val namePerPx = name.measureText("BioKey") / 100f
        val markHeight = min(h, w / (KeyMark.ASPECT + GAP + NAME_SIZE * namePerPx))
        val markWidth = markHeight * KeyMark.ASPECT
        name.textSize = markHeight * NAME_SIZE
        val total = markWidth + markHeight * GAP + name.measureText("BioKey")
        val left = (w - total) / 2f
        val top = (h - markHeight) / 2f
        KeyMark.draw(canvas, left, top, markWidth, markHeight, primary, secondary, tertiary)
        // The name centred on the mark: halfway between its cap height and baseline.
        val fm = name.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText("BioKey", left + markWidth + markHeight * GAP, baseline, name)
    }
}
