package com.android.BioKey.feature.main

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.BioKey.R
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.security.ProtectionEngine
import com.android.BioKey.ui.brand.BioKeyMarkSideways
import com.android.BioKey.ui.brand.HeaderMatrixDotsExpressive
import com.android.BioKey.ui.brand.KeyMark
import com.android.BioKey.ui.theme.SEPARATOR
import com.android.BioKey.ui.theme.scaledWithText
import com.android.BioKey.ui.theme.springPress

/**
 * Home's header: BioKey's mark and its two tiles over the constellation, which runs edge to edge
 * ([sidePadPx] past the list's column on each side) and thins out round them.
 */
@Composable
internal fun HomeHeader(
    look: ProtectionLook,
    animated: Boolean,
    sidePadPx: Int,
    onOpenStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Settings open beside home (tablets): its tile shows it's selected. Null where it opens over home. */
    settingsSelected: Boolean? = null
) {
    // Where the header, its logo and its two labelled tiles are on screen: the
    // constellation thins out round them, so no link cuts through the mark or a label.
    var headerOrigin by remember { mutableStateOf(Offset.Zero) }
    val quietZones = remember { mutableStateMapOf<Int, Rect>() }
    Box(Modifier.onGloballyPositioned { headerOrigin = it.positionInRoot() }) {
        HeaderMatrixDotsExpressive(
            Modifier
                .matchParentSize()
                .horizontalBleed(sidePadPx),
            animated = animated,
            // Where the header is, read as the field is drawn: read here, every frame of a
            // scroll (which moves it) would compose the whole header again.
            quiet = {
                val shift = headerOrigin - Offset(sidePadPx.toFloat(), 0f)
                quietZones.values.map { it.translate(-shift) }
            }
        )
        Column(Modifier.statusBarsPadding()) {
            MainHeader(
                status = look,
                animated = animated,
                compact = shortWindow(),
                onOpenStatus = onOpenStatus,
                onOpenSettings = onOpenSettings,
                settingsSelected = settingsSelected,
                onPlaced = { part, bounds -> quietZones[part] = bounds }
            )
        }
    }
}

/**
 * Material's compact height: a phone in landscape, or half of a split screen. There the header
 * takes a row's height rather than a third of the window, so the list keeps the room. A phone
 * held upright is never under it.
 */
internal const val COMPACT_HEIGHT_DP = 480f

/** Whether a window [heightDp] tall gets the compact header (and sheets and dialogs that use its height). */
internal fun isShortWindow(heightDp: Float): Boolean = heightDp < COMPACT_HEIGHT_DP

/**
 * Whether home's search bar and profile chips stay pinned at the top as the list scrolls. Not in
 * a short window ([isShortWindow]): pinned there they'd take half the height, so they scroll away
 * with the list instead, which gets the whole window, and are one flick back up.
 */
internal fun pinsControls(heightDp: Float): Boolean = !isShortWindow(heightDp)

/** [isShortWindow] for the window this is composed in (read it outside a dialog: a dialog's own window is only as big as it is). */
@Composable
internal fun shortWindow(): Boolean {
    val density = LocalDensity.current
    val height = LocalWindowInfo.current.containerSize.height
    return height > 0 && isShortWindow(with(density) { height.toDp().value })
}

/**
 * Status and Settings as equal-width tiles at the edges, BioKey's mark centred between them,
 * moving unless [animated] is false (the header covered). [compact] (a short window): the same
 * three, each tile's label beside its circle, the mark smaller, a row's height in all.
 */
@Composable
private fun MainHeader(
    status: ProtectionLook,
    animated: Boolean,
    onOpenStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    settingsSelected: Boolean? = null,
    compact: Boolean = false,
    /** Where each part (0 status, 1 logo, 2 settings) is on screen. */
    onPlaced: (part: Int, bounds: Rect) -> Unit = { _, _ -> }
) {
    val settings = stringResource(R.string.settings_title)
    if (compact) {
        CompactHeader(status, animated, settings, onOpenStatus, onOpenSettings, settingsSelected, onPlaced)
        return
    }
    val labelStyle = headerLabelStyle(status.shortLabel, settings)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderTile(
            modifier = Modifier.onGloballyPositioned { onPlaced(0, it.boundsInRoot()) },
            icon = status.icon,
            label = status.shortLabel,
            labelStyle = labelStyle,
            container = status.container,
            content = status.content,
            contentDescription = stringResource(R.string.home_status_description, status.title),
            onClick = onOpenStatus
        )
        // Centred between the two equal-width tiles, with breathing room on both sides.
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // The mark alone, on its side (the fingerprint on the right, the key on the left), as
            // large as the header holds; its print swirls while the header is uncovered.
            BioKeyMarkSideways(
                Modifier
                    .height(104.dp)
                    .aspectRatio(1f / KeyMark.ASPECT)
                    .onGloballyPositioned { onPlaced(1, it.boundsInRoot()) },
                animated = animated
            )
        }
        HeaderTile(
            modifier = Modifier.onGloballyPositioned { onPlaced(2, it.boundsInRoot()) },
            icon = Icons.Default.Settings,
            label = settings,
            labelStyle = labelStyle,
            container = if (settingsSelected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            content = if (settingsSelected == true) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            contentDescription = settings,
            selected = settingsSelected,
            onClick = onOpenSettings
        )
    }
}

/**
 * The header in a short window: status at the start, the mark centred, Settings at the end, each
 * tile a circle with its label beside it, so the whole header is about a row high.
 */
@Composable
private fun CompactHeader(
    status: ProtectionLook,
    animated: Boolean,
    settings: String,
    onOpenStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    settingsSelected: Boolean?,
    onPlaced: (part: Int, bounds: Rect) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Equal shares either side, so the mark stays centred whatever the labels' lengths.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            CompactHeaderTile(
                modifier = Modifier.onGloballyPositioned { onPlaced(0, it.boundsInRoot()) },
                icon = status.icon,
                label = status.shortLabel,
                container = status.container,
                content = status.content,
                contentDescription = stringResource(R.string.home_status_description, status.title),
                onClick = onOpenStatus
            )
        }
        BioKeyMarkSideways(
            Modifier
                .padding(horizontal = 16.dp)
                .height(64.dp)
                .aspectRatio(1f / KeyMark.ASPECT)
                .onGloballyPositioned { onPlaced(1, it.boundsInRoot()) },
            animated = animated
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            CompactHeaderTile(
                modifier = Modifier.onGloballyPositioned { onPlaced(2, it.boundsInRoot()) },
                icon = Icons.Default.Settings,
                label = settings,
                container = if (settingsSelected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                content = if (settingsSelected == true) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                contentDescription = settings,
                selected = settingsSelected,
                onClick = onOpenSettings
            )
        }
    }
}

/** [HeaderTile] laid out across: the circle, its label beside it; the whole pill is the touch target. */
@Composable
private fun CompactHeaderTile(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 48.dp)
            .springPress()
            .clip(RoundedCornerShape(32.dp))
            .clickable(onClick = onClick, role = Role.Button)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                if (selected != null) this.selected = selected
            }
            .padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp.scaledWithText())
                .background(container, CircleShape)
                .then(if (selected == true) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp.scaledWithText()))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

/**
 * The header tiles' label style: labelLarge, made smaller only as far as both [labels] need to
 * fit a tile on one line (large text would otherwise break "Protected" mid-word), and the same
 * for both, so they never look mismatched.
 */
@Composable
private fun headerLabelStyle(vararg labels: String): TextStyle {
    val base = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val width = with(density) { (HeaderTileWidth - 8.dp).roundToPx() }
    return remember(labels.toList(), base, density) {
        var size = base.fontSize
        while (size.value > 9f && labels.any { measurer.measure(it, base.copy(fontSize = size), maxLines = 1, softWrap = false).size.width > width }) {
            size = (size.value - 0.5f).sp
        }
        base.copy(fontSize = size)
    }
}

private val HeaderTileBaseWidth = 84.dp

/** Wider with large text (up to 1.4×), so labels shrink less to fit. */
private val HeaderTileWidth: Dp
    @Composable get() = HeaderTileBaseWidth * LocalDensity.current.fontScale.coerceIn(1f, 1.4f)

/** A circle around an icon, with its label underneath; the whole column is the touch target. */
@Composable
private fun HeaderTile(
    icon: ImageVector,
    label: String,
    /** Shared by both tiles ([headerLabelStyle]), so their labels match. */
    labelStyle: TextStyle,
    container: Color,
    content: Color,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** A tile that toggles something (Settings' pane): announced as selected or not. */
    selected: Boolean? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(HeaderTileWidth)
            .springPress()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick, role = Role.Button)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                if (selected != null) this.selected = selected
            }
            .padding(vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp.scaledWithText())
                .background(container, CircleShape)
                .then(if (selected == true) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(32.dp.scaledWithText()))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** How each protection state looks, in the header tile and its sheet. */
internal data class ProtectionLook(
    val container: Color,
    val content: Color,
    val icon: ImageVector,
    val shortLabel: String,
    val title: String,
    val body: String,
    /** Primary action, or null when protecting normally (then the sheet offers Pause). */
    val action: String?
)

@Composable
internal fun protectionLook(
    mode: ProtectionEngine.Mode,
    enabled: Boolean,
    pausedUntil: Long,
    lockedCount: Int,
    /** Locked apps with a copy in another profile, which compatibility mode can't see. */
    unwatchedCount: Int,
    defaultTimeout: LockTimeoutMode
): ProtectionLook {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val apps = pluralStringResource(R.plurals.home_apps_locked, lockedCount, lockedCount)
    return when {
        // Locked apps open without a check here too: the same colours as off.
        mode == ProtectionEngine.Mode.NEEDS_COMPATIBILITY_SETUP -> ProtectionLook(
            // Off in effect, and said as off: the body names the cause and the fix.
            colors.errorContainer, colors.onErrorContainer, Icons.Default.GppMaybe, stringResource(R.string.home_off_short),
            stringResource(R.string.alert_off_title),
            stringResource(R.string.home_needs_compat_body),
            stringResource(R.string.home_needs_compat_action)
        )
        mode == ProtectionEngine.Mode.OFF -> ProtectionLook(
            colors.errorContainer, colors.onErrorContainer, Icons.Default.Warning, stringResource(R.string.home_off_short),
            stringResource(R.string.alert_off_title), stringResource(R.string.home_off_body), stringResource(R.string.common_turn_on)
        )
        // Switched on, but not running (it crashed): saying "Protected" here would be the one lie that matters.
        mode == ProtectionEngine.Mode.ACCESSIBILITY_NOT_RUNNING -> ProtectionLook(
            colors.errorContainer, colors.onErrorContainer, Icons.Default.Warning, stringResource(R.string.home_not_running_short),
            stringResource(R.string.home_not_running_title),
            stringResource(R.string.home_not_running_body),
            stringResource(R.string.home_not_running_action)
        )
        !enabled -> ProtectionLook(
            colors.tertiaryContainer, colors.onTertiaryContainer, Icons.Default.PauseCircle, stringResource(R.string.common_paused),
            // One family with "On" and "Protection is on": until when is the body's to say.
            stringResource(R.string.home_protection_paused),
            pausedBody(context, pausedUntil),
            stringResource(R.string.home_resume_protection)
        )
        else -> ProtectionLook(
            colors.primaryContainer, colors.onPrimaryContainer, Icons.Default.VerifiedUser, stringResource(R.string.home_protected),
            stringResource(R.string.home_protected_title),
            apps + SEPARATOR + stringResource(defaultTimeout.relockPhraseRes).lowercase() +
                (if (mode == ProtectionEngine.Mode.COMPATIBILITY) "\n" + stringResource(R.string.home_protected_compat) else "") +
                (if (unwatchedCount == 0) "" else "\n" + pluralStringResource(R.plurals.home_unwatched, unwatchedCount, unwatchedCount)),
            null
        )
    }
}

/** When a timed pause ends, in the phone's own time format ("7:15 PM", "19:15"), never split across lines. */
internal fun pauseEndTime(context: Context, pausedUntil: Long): String = com.android.BioKey.ui.format.clockTime(context, pausedUntil)

/**
 * What a pause means: until the owner resumes, or, for a timed one (still running at [now]), until
 * it ends by itself or they resume first, whichever comes sooner.
 */
internal fun pausedBody(context: Context, pausedUntil: Long, now: Long = System.currentTimeMillis()): String =
    if (pausedUntil > now) context.getString(R.string.home_paused_body_timed, pauseEndTime(context, pausedUntil))
    else context.getString(R.string.home_paused_body)

/** Draws [px] wider on each side than it is laid out: over the list's side margins on wide screens. */
internal fun Modifier.horizontalBleed(px: Int): Modifier = if (px <= 0) this else layout { measurable, constraints ->
    val width = constraints.maxWidth + 2 * px
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-px, 0) }
}
