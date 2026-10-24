package com.android.BioKey.ui.brand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.android.BioKey.R
import kotlin.math.min

/** BioKey's name as its logo sets it: Google Sans Flex, bold (res/font, cut down to these letters). */
internal val BioKeyWordmarkFont = FontFamily(Font(R.font.biokey_wordmark, FontWeight.Bold))

/** The name's size, the gap before it and the mark's height, all as parts of the mark's height. */
private const val NAME_SIZE = 0.6f
private const val GAP = 0.2f

/** The size the name is measured at, to work out how wide it is at any other. */
private val MEASURE_SIZE = 100.sp

/**
 * BioKey's logo: its mark ([KeyMark]) beside its name, in the phone's colours (the mark in a
 * gradient across primary and tertiary, the name in the colour of the text around it), as large as
 * fits [modifier]'s size, centred. Still: nothing in it moves.
 */
@Composable
fun BioKeyLogo(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val name = stringResource(R.string.brand_name)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // How wide the name is for each pixel of its size: measured once, at one size.
    val nameWidthPerPx = remember(measurer, density) {
        measurer.measure("BioKey", TextStyle(fontFamily = BioKeyWordmarkFont, fontWeight = FontWeight.Bold, fontSize = MEASURE_SIZE))
            .size.width / with(density) { MEASURE_SIZE.toPx() }
    }
    BoxWithConstraints(
        modifier.clearAndSetSemantics {
            contentDescription = name
            role = Role.Image
        },
        contentAlignment = Alignment.Center
    ) {
        // The mark's height that fits both ways. Per pixel of it, the logo is as wide as the mark,
        // the gap and the name (set at NAME_SIZE of it) together.
        val markHeight: Dp = with(density) {
            val widthPerHeight = KeyMark.ASPECT + GAP + NAME_SIZE * nameWidthPerPx
            min(constraints.maxHeight.toFloat(), constraints.maxWidth / widthPerHeight).toDp()
        }
        val nameSize: TextUnit = with(density) { (markHeight.toPx() * NAME_SIZE).toSp() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Canvas(Modifier.height(markHeight).width(markHeight * KeyMark.ASPECT)) {
                drawIntoCanvas {
                    KeyMark.draw(it.nativeCanvas, 0f, 0f, size.width, size.height, colors.primary.toArgb(), colors.secondary.toArgb(), colors.tertiary.toArgb())
                }
            }
            Spacer(Modifier.width(markHeight * GAP))
            Text(
                "BioKey",
                color = colors.onSurface,
                fontFamily = BioKeyWordmarkFont,
                fontWeight = FontWeight.Bold,
                fontSize = nameSize,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
