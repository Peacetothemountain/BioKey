package com.android.BioKey.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A note above what it's about, as a tonal callout: tinted towards the error colours as a
 * [warning] (as an urgent card is: Review during a hold, setup after a reset), else the calm
 * secondary ones. Read whole by a screen reader, its list in order.
 */
@Composable
fun Callout(
    text: String,
    warning: Boolean,
    modifier: Modifier = Modifier,
    headline: String? = null,
    /** A list after [text], each item's wrapped lines hanging after its bullet. */
    items: List<String> = emptyList(),
    footer: String? = null
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (warning) lerp(colors.surfaceContainerHighest, colors.errorContainer, if (dark) 0.45f else 0.7f) else colors.secondaryContainer,
        contentColor = if (warning) colors.onErrorContainer else colors.onSecondaryContainer,
        // One stop for a screen reader: the note is read whole, its list in order.
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { }
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Icon(Icons.Default.GppMaybe, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(20.dp.scaledWithText()))
            Spacer(Modifier.width(12.dp))
            Column {
                if (headline != null) Text(headline, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodyMedium)
                items.forEach { item ->
                    Row(Modifier.padding(start = 4.dp, top = 2.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clearAndSetSemantics { })
                        Spacer(Modifier.width(8.dp))
                        Text(item, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (footer != null) Text(footer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
