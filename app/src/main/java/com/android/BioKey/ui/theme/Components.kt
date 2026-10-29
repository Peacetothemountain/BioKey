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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
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

/**
 * A size that grows with the user's font size, up to 1.5×, so an icon keeps up with the text
 * beside it at large text; exactly itself at 100%.
 */
@Composable
fun Dp.scaledWithText(): Dp = this * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)

/** Between the parts of a line ("Finance · unlocked now"): the dot stays with the word before it. */
const val SEPARATOR = " · "

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
fun SectionHeader(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
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
 *
 * [stateDescription] is what screen readers say of the row's state ("Done") where only its look
 * shows it; the row is then one stop, tappable or not.
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
    stateDescription: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) = GroupRow(
    index = index, count = count, title = title, modifier = modifier,
    subtitle = subtitle?.let(::AnnotatedString),
    icon = icon, iconTint = iconTint, iconContainer = iconContainer, leading = leading, onClick = onClick,
    interaction = interaction, subtitleColor = subtitleColor, stateDescription = stateDescription, trailing = trailing
)

/**
 * [GroupRow] with a styled [subtitle]: a part of it in a colour of its own ("unlocked now") over
 * [subtitleColor] for the rest.
 */
@Composable
fun GroupRow(
    index: Int,
    count: Int,
    title: String,
    subtitle: AnnotatedString?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    iconContainer: Color = MaterialTheme.colorScheme.secondaryContainer,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    interaction: Modifier = Modifier,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    stateDescription: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val shape = groupShape(index, count)
    val described = if (stateDescription != null) {
        modifier.semantics(mergeDescendants = true) { this.stateDescription = stateDescription }
    } else modifier
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
                    IconBadge(icon, iconTint, iconContainer, size = 40.dp.scaledWithText())
                    Spacer(Modifier.width(16.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // In full, however large the text: its end is often what to do ("· tap to grant").
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = subtitleColor)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = groupColor, modifier = described.fillMaxWidth(), content = content)
    } else {
        Surface(shape = shape, color = groupColor, modifier = described.fillMaxWidth(), content = content)
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
