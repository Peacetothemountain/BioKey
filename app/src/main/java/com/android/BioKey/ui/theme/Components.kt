package com.android.BioKey.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * BioKey's building blocks, modelled on Android 16/17 Settings: content sits in rounded groups
 * whose rows are separated by thin gaps (large outer corners, small inner corners), with a
 * coloured section header above each group.
 */

/** Horizontal inset of groups from the screen edge. */
val GroupInset = 16.dp

/** Widest a list or page gets: phones use their full width, tablets and landscape stay readable. */
val MaxContentWidth = 720.dp
private val OuterCorner = 24.dp
private val InnerCorner = 6.dp
/** Gap between rows inside a group. */
val GroupGap = 2.dp

/** Shape of row [index] of [count] in a group: round outer corners, tight inner ones. */
fun groupShape(index: Int, count: Int): Shape = RoundedCornerShape(
    topStart = if (index == 0) OuterCorner else InnerCorner,
    topEnd = if (index == 0) OuterCorner else InnerCorner,
    bottomStart = if (index == count - 1) OuterCorner else InnerCorner,
    bottomEnd = if (index == count - 1) OuterCorner else InnerCorner
)

/** Colour of group rows: one step up from the page. */
val groupColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainer

/** Section title above a group; a heading for screen readers, so they can jump between sections. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .semantics { heading() }
            .padding(start = GroupInset + 16.dp, end = GroupInset + 16.dp, top = 24.dp, bottom = 10.dp)
    )
}

/** A static group of rows; each row receives its position for [GroupRow]. */
@Composable
fun Group(
    count: Int,
    modifier: Modifier = Modifier,
    row: @Composable (index: Int) -> Unit
) {
    Column(
        modifier = modifier.padding(horizontal = GroupInset),
        verticalArrangement = Arrangement.spacedBy(GroupGap)
    ) {
        repeat(count) { row(it) }
    }
}

/**
 * One row of a group: optional leading icon badge, title and supporting text, trailing content.
 * Pass [onClick] for a tappable row, or a [Modifier.toggleable]/[Modifier.selectable] via
 * [interaction] for switches and choices (so the whole row is the touch target).
 */
@Composable
fun GroupRow(
    index: Int,
    count: Int,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    iconContainer: Color = MaterialTheme.colorScheme.secondaryContainer,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    interaction: Modifier = Modifier,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val shape = groupShape(index, count)
    val content: @Composable () -> Unit = {
        Row(
            modifier = interaction
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                leading != null -> {
                    leading()
                    Spacer(Modifier.width(16.dp))
                }
                icon != null -> {
                    IconBadge(icon, iconTint, iconContainer)
                    Spacer(Modifier.width(16.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = subtitleColor,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = groupColor, modifier = modifier.fillMaxWidth(), content = content)
    } else {
        Surface(shape = shape, color = groupColor, modifier = modifier.fillMaxWidth(), content = content)
    }
}

/** Icon in a tonal circle, as used at the start of group rows. */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    size: Dp = 40.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(container, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}
