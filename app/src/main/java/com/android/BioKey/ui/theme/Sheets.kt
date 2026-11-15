package com.android.BioKey.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.android.BioKey.ui.security.ProtectDialogWindow
import com.android.BioKey.ui.security.rejectObscuredTouches

/** How deep the dark theme's scrim is behind a sheet (Material's is 32%). */
private const val DarkScrimAlpha = 0.6f

/** A sheet's drag handle, pressed or focused: a circle round it rather than its whole box lit. */
private val HandleIndication = ripple(bounded = false, radius = 24.dp)

/**
 * BioKey's bottom sheets. As Material's, with two differences.
 *
 * The drag handle: it is a button (a tap expands or closes the sheet) drawn with the indication
 * around it, which by default lights up its whole box, a grey slab behind the handle when it has
 * keyboard focus or a pointer over it. Here it lights up as a circle. The sheet's contents keep
 * the indication they would have had.
 *
 * Its top: Material lets a tall sheet run up under the status bar, its rounded corners cut by it
 * (the clock and battery drawn over the sheet). Here a sheet is never taller than the room below
 * the status bar: a full-height sheet stops just under it, corners whole, and scrolls within.
 *
 * Its surface in the dark theme: Material's (the lowest container) is almost the shade of the
 * dimmed page behind, so the sheet hardly reads as a surface of its own. There it's two steps up
 * ([ColorScheme.surfaceContainerHigh]) over a deeper scrim, and the groups of rows on it a step
 * higher still ([LocalGroupColor]), for every sheet alike.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BioKeySheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit
) {
    val indication = LocalIndication.current
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    CompositionLocalProvider(LocalIndication provides HandleIndication) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            containerColor = if (dark) colors.surfaceContainerHigh else BottomSheetDefaults.ContainerColor,
            scrimColor = if (dark) colors.scrim.copy(alpha = DarkScrimAlpha) else BottomSheetDefaults.ScrimColor,
            // The bottom (navigation bar, keyboard) and sides only: the top is kept clear below.
            contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal) }
        ) {
            CompositionLocalProvider(
                LocalIndication provides indication,
                LocalGroupColor provides if (dark) colors.surfaceContainerHighest else Color.Unspecified
            ) {
                val statusBar = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // What's left under the drag handle, less the status bar's height: the sheet's
                    // top then never reaches above the status bar's bottom edge.
                    val cap = if (constraints.hasBoundedHeight) Modifier.heightIn(max = (maxHeight - statusBar).coerceAtLeast(0.dp)) else Modifier
                    Column(cap.fillMaxWidth()) { content() }
                }
            }
        }
    }
}

/**
 * For a dialog with a text field in it. Android no longer shrinks a dialog's window for the
 * keyboard (apps draw edge to edge from Android 15), so a tall dialog would keep its buttons
 * under the keyboard. With these, the dialog is told about the keyboard itself and takes only the
 * room above it ([aboveKeyboard]). [KeyboardSafeDialog] uses both.
 */
val KeyboardAwareDialog = DialogProperties(decorFitsSystemWindows = false)

/** Clear of the status and navigation bars and of the keyboard, with a little air between: with [KeyboardAwareDialog]. */
fun Modifier.aboveKeyboard(): Modifier = systemBarsPadding().imePadding().padding(vertical = 16.dp)

/**
 * The one set of spaces between a dialog's parts, the same in every dialog ([KeyboardSafeDialog]
 * puts the icon, title, content and buttons apart as Material's dialogs do; these are for
 * what the content holds).
 */
object DialogSpacing {
    /** Between the content's parts: the text and a field, a field and a section after it, that and a red action. */
    val Part = 16.dp

    /** Between two fields, each with its one-line help under it (the line is already air of its own). */
    val Field = 8.dp

    /** Between a small label and what it names ("Icon" and the icons). */
    val Label = 12.dp

    /** Between a field and the line of help or error under it, as Material's own supporting text. */
    val FieldLine = 4.dp
}

/** Material's dialog padding, and the smaller one for a window too short for it (landscape with the keyboard up). */
private val DialogPadding = 24.dp
private val CompactDialogPadding = 16.dp

/** A dialog shorter than this uses [CompactDialogPadding]. */
private val CompactDialogHeight = 360.dp

/** How far the content fades out where there's more of it to scroll to. */
private val FadeLength = 24.dp

/** The room taken by the buttons' row (one line of 48-dp targets), until they've been laid out. */
private val ButtonRowHeight = 48.dp

/** Tag of the scrolling part of a [KeyboardSafeDialog], for tests. */
const val DialogScrollTag = "dialog_scroll"

/**
 * BioKey's dialog, for every dialog with a text field (and the confirmations that come with them,
 * so all look alike). Material's own lays out its icon, title and text one above the other with
 * only the text scrolling: at a large font, with the keyboard up, the title alone can take the
 * room, leaving a sliver of the field. Here:
 *
 * - The icon, title and content scroll as one, and only the buttons stay put. Short of room, the
 *   icon, title and text go up out of sight first: the field being typed in is brought fully into
 *   view, with its line of help under it ([keepInViewWhileFocused]).
 * - Where there's more to scroll to, the content fades out at that edge, and a divider sits
 *   between it and the buttons, as in Material's scrolling dialogs.
 * - With so little room that even a field couldn't show above pinned buttons (landscape, with the
 *   keyboard up, at a large font), the buttons scroll with the rest, the field coming first. The
 *   keyboard's own action key (Next, Done) still goes on from it.
 * - [busy]: a thin bar between the content and the buttons, which takes no room of its own, so
 *   nothing moves as it comes and goes.
 *
 * It sits above the keyboard ([KeyboardAwareDialog], [aboveKeyboard]), its window protected as the
 * app's ([ProtectDialogWindow]), and refuses touches through another app's window over it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSafeDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    busy: Boolean = false,
    startButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = Modifier.aboveKeyboard(), properties = KeyboardAwareDialog) {
        ProtectDialogWindow()
        KeyboardSafeDialogLayout(confirmButton, dismissButton, icon, title, busy, startButton, content)
    }
}

/** [KeyboardSafeDialog]'s surface and layout, without the window: on its own for tests. */
@OptIn(ExperimentalFoundationApi::class) // The scrolling's bring-into-view rule.
@Composable
fun KeyboardSafeDialogLayout(
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    busy: Boolean = false,
    startButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
        modifier = Modifier.rejectObscuredTouches()
    ) {
        BoxWithConstraints {
            val bounded = constraints.hasBoundedHeight
            val pad = if (bounded && maxHeight < CompactDialogHeight) CompactDialogPadding else DialogPadding
            // The least the scrolling part may have before the buttons give up their place: a
            // field and its line, at the font size in use.
            val minContent = 72.dp + with(LocalDensity.current) { 32.sp.toDp() }
            // The buttons' height as last laid out (stacked, they're taller than one row).
            var buttonsHeight by remember { mutableStateOf(ButtonRowHeight) }
            val pinned = !bounded || maxHeight - pad - buttonsHeight - pad - pad >= minContent
            val scroll = rememberScrollState()
            val density = LocalDensity.current
            // The icon's own height (without the air under it).
            var iconHeight by remember { mutableIntStateOf(0) }
            val padPx by rememberUpdatedState(with(density) { pad.roundToPx() })
            // How far the content must scroll for the icon to be wholly out of sight: the padding
            // above it as well as its height. (Stopping at the title instead, as before, left the
            // icon's foot showing in the padding at the dialog's top: a sliver.)
            val iconBlock: () -> Int = remember { { if (iconHeight == 0) 0 else iconHeight + padPx } }
            // The room [iconWhole] added at the foot, as last laid out: nothing to scroll to, so no
            // sign of more below for it.
            val footRoom = remember { mutableIntStateOf(0) }
            val iconWhole = remember(scroll) { Modifier.iconWhole(scroll, iconBlock, footRoom) }
            val moreBelow = { scroll.value < scroll.maxValue - footRoom.intValue }
            // The dialog's own scrolling (keeping a field in view) never stops part of the way into
            // the icon, cut by the top edge: where it would, it goes on until the icon is out of
            // sight. What's below the title only moves up by less than the icon's height, so a field
            // kept in view stays in view. (Any other stop, a drag let go included, settles below.)
            val baseSpec = LocalBringIntoViewSpec.current
            val iconSpec = remember(baseSpec, scroll) {
                object : BringIntoViewSpec {
                    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                        val distance = baseSpec.calculateScrollDistance(offset, size, containerSize)
                        val from = scroll.value
                        val to = (from + distance).roundToInt()
                        if (iconClear(to, iconBlock(), scroll.maxValue) == to) return distance
                        // Past the icon, or else back to the top, whichever leaves all that was
                        // asked for in view (else the scrolling would never settle); failing both, as asked.
                        return listOf(iconBlock(), 0)
                            .map { (it - from).toFloat() }
                            .firstOrNull { offset - it >= -0.5f && offset - it + size <= containerSize + 0.5f }
                            ?: distance
                    }
                }
            }
            // Whatever else moves it (the keyboard coming or going and the dialog's height with
            // it, a drag let go), it never comes to rest part of the way into the icon either: it
            // settles at the nearer of the icon's two edges.
            LaunchedEffect(scroll) {
                snapshotFlow { Triple(scroll.isScrollInProgress, scroll.value, iconBlock()) }.collect { (moving, at, block) ->
                    val rest = iconRest(at, block, scroll.maxValue)
                    if (!moving && rest != at) scroll.animateScrollTo(rest)
                }
            }
            val buttons = @Composable { modifier: Modifier ->
                DialogButtons(
                    confirmButton,
                    dismissButton,
                    startButton,
                    modifier.onSizeChanged { size ->
                        val h = with(density) { size.height.toDp() }
                        if (h > 0.dp && h != buttonsHeight) buttonsHeight = h
                    }
                )
            }
            Column {
                Box(Modifier.weight(1f, fill = false)) {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides iconSpec) {
                    Column(
                        Modifier
                            .fadingEdges(scroll, FadeLength, moreBelow)
                            .verticalScroll(scroll)
                            .testTag(DialogScrollTag)
                            .then(iconWhole)
                            .padding(horizontal = pad)
                            .padding(top = pad, bottom = pad)
                    ) {
                        // What's inside (a field's own scrolling) as everywhere else.
                        CompositionLocalProvider(LocalBringIntoViewSpec provides baseSpec) {
                        if (icon != null) {
                            CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.iconContentColor) {
                                Box(
                                    Modifier.fillMaxWidth().padding(bottom = 16.dp).onSizeChanged { iconHeight = it.height },
                                    contentAlignment = Alignment.Center
                                ) { icon() }
                            }
                        }
                        if (title != null) {
                            CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                                ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                                    Box(
                                        Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                        contentAlignment = if (icon != null) Alignment.Center else Alignment.CenterStart
                                    ) { title() }
                                }
                            }
                        }
                        CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { content() }
                        }
                        if (!pinned) buttons(Modifier.padding(top = pad))
                        }
                    }
                    }
                    // More below: a divider at the viewport's foot too (with the buttons scrolling,
                    // the field kept in view can fill it, its line empty, so the fade alone may not show).
                    if (moreBelow()) {
                        HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (busy) {
                        LinearProgressIndicator(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = pad)
                                .padding(bottom = (pad - 4.dp) / 2)
                        )
                    }
                }
                if (pinned) buttons(Modifier.padding(start = pad, end = pad, bottom = pad))
            }
        }
    }
}

/**
 * The dialog's buttons at its end, as Material lays them out: side by side at the end when they
 * fit, the main one last. When they don't (a large font, a long label), they're stacked as
 * Material stacks them: the main one on top, each at the dialog's end, never one on a line and the
 * other wrapped beside or under it. A label too long for one line even then wraps with its lines
 * set to the end too, so every button lines up on the same edge.
 */
@Composable
private fun DialogButtons(
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)?,
    startButton: (@Composable () -> Unit)?,
    modifier: Modifier
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(textAlign = TextAlign.End)) {
            Layout(
                content = {
                    Box(Modifier.layoutId(ConfirmId), propagateMinConstraints = true) { confirmButton() }
                    if (dismissButton != null) Box(Modifier.layoutId(DismissId), propagateMinConstraints = true) { dismissButton() }
                    if (startButton != null) Box(Modifier.layoutId(StartId), propagateMinConstraints = true) { startButton() }
                },
                modifier = modifier.fillMaxWidth()
            ) { measurables, constraints ->
                val width = constraints.maxWidth
                val confirm = measurables.first { it.layoutId == ConfirmId }
                val dismiss = measurables.firstOrNull { it.layoutId == DismissId }
                val start = measurables.firstOrNull { it.layoutId == StartId }
                val gap = ButtonGap.roundToPx()
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val sideBySide = dialogButtonsFit(
                    listOfNotNull(start, dismiss, confirm).map { it.maxIntrinsicWidth(Constraints.Infinity) },
                    gap,
                    width
                )
                val c = confirm.measure(loose)
                val d = dismiss?.measure(loose)
                val st = start?.measure(loose)
                if (sideBySide) {
                    val height = maxOf(c.height, d?.height ?: 0, st?.height ?: 0)
                    layout(width, height) {
                        c.place(width - c.width, (height - c.height) / 2)
                        if (d != null) d.place(width - c.width - gap - d.width, (height - d.height) / 2)
                        st?.place(0, (height - st.height) / 2)
                    }
                } else {
                    // Stacked: the main action on top, then the other, then the one at the start
                    // (apart from the two by a little more air), each at the end.
                    val stackGap = StackGap.roundToPx()
                    val startGap = ButtonGap.roundToPx()
                    val height = c.height + (d?.let { stackGap + it.height } ?: 0) + (st?.let { startGap + it.height } ?: 0)
                    layout(width, height) {
                        c.place(width - c.width, 0)
                        var y = c.height
                        if (d != null) {
                            d.place(width - d.width, y + stackGap)
                            y += stackGap + d.height
                        }
                        st?.place(width - st.width, y + startGap)
                    }
                }
            }
        }
    }
}

private const val ConfirmId = "confirm"
private const val DismissId = "dismiss"
private const val StartId = "start"

/** Between buttons side by side, and between stacked ones, as Material's dialogs. */
private val ButtonGap = 8.dp
private val StackGap = 4.dp

/**
 * Where a dialog's own scrolling should stop, having stopped at [offset]: never part of the way
 * into the icon at its top ([iconBlock], how far it must scroll for the icon to be out of sight), but past it, where it
 * can scroll that far ([max]).
 */
internal fun iconClear(offset: Int, iconBlock: Int, max: Int): Int =
    if (offset in 1 until iconBlock && iconBlock <= max) iconBlock else offset

/**
 * Where a dialog's scrolling comes to rest, having stopped at [offset] by any means: never part of
 * the way into the icon ([iconBlock]), but at the nearer of its two edges. [max]: how far it can
 * scroll ([iconWhole] makes that at least the icon's height whenever it scrolls at all).
 */
internal fun iconRest(offset: Int, iconBlock: Int, max: Int): Int = when {
    offset !in 1 until iconBlock -> offset
    iconBlock > max -> 0
    offset * 2 >= iconBlock -> iconBlock
    else -> 0
}

/**
 * How much room to add at the foot of a dialog's scrolling content ([overflow]: by how much it's
 * taller than its room) so that it either doesn't scroll or can scroll the whole icon
 * ([iconBlock]) out of sight. Without it, content a little taller than its room (the keyboard up on
 * a phone) could scroll only part of the way into the icon, leaving a sliver of it at the top.
 */
internal fun iconRoom(overflow: Int, iconBlock: Int): Int = if (overflow in 1 until iconBlock) iconBlock - overflow else 0

/**
 * The scrolling content with [iconRoom] added at its foot. The room it's given is [scroll]'s
 * viewport as last measured: while the content is taller than its room that's the room itself,
 * which the added foot doesn't change.
 */
private fun Modifier.iconWhole(scroll: ScrollState, iconBlock: () -> Int, footRoom: androidx.compose.runtime.MutableIntState): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val room = scroll.viewportSize
    val extra = if (room <= 0) 0 else iconRoom(placeable.height - room, iconBlock())
    if (footRoom.intValue != extra) footRoom.intValue = extra
    layout(placeable.width, placeable.height + extra) { placeable.place(0, 0) }
}

/**
 * Whether a dialog's buttons sit side by side in [width] (else they're stacked): each at its full
 * one-line width ([widths]), [gap] between each two.
 */
internal fun dialogButtonsFit(widths: List<Int>, gap: Int, width: Int): Boolean =
    widths.sum() + gap * (widths.size - 1).coerceAtLeast(0) <= width

/** Fades the content out at an edge it can still be scrolled past: a sign there's more that way. */
private fun Modifier.fadingEdges(scroll: ScrollState, length: Dp, moreBelow: () -> Boolean = { scroll.canScrollForward }): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val px = length.toPx().coerceAtMost(size.height / 3f)
        if (scroll.canScrollBackward) {
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = px),
                size = Size(size.width, px),
                blendMode = BlendMode.DstIn
            )
        }
        if (moreBelow()) {
            drawRect(
                Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = size.height - px, endY = size.height),
                topLeft = Offset(0f, size.height - px),
                size = Size(size.width, px),
                blendMode = BlendMode.DstIn
            )
        }
    }

/**
 * On what holds a field and its line of help: while the field has focus, the whole of it is kept
 * in view in the dialog's scrolling content, on gaining focus and as the keyboard comes up (which
 * makes the dialog shorter). The scrolling content alone would keep only the field's text in view,
 * not its outline's top or the help under it.
 *
 * [above], [below]: how much of what's next to it (in pixels) is kept in view with it where there's
 * room, so two fields filled in together ("Type it again" under the first) show together, the text
 * over them giving way first. Short of room for both, the focused one alone is kept in view.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.keepInViewWhileFocused(above: () -> Int = { 0 }, below: () -> Int = { 0 }): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        // Each change of the keyboard's height or of its own (a message under the field coming,
        // perhaps on two lines at a large font), and once at first; a frame later, once the dialog
        // has its new size.
        snapshotFlow { ime.getBottom(density) to size }.collect {
            withFrameNanos { }
            val up = above()
            val down = below()
            if (up > 0 || down > 0) {
                requester.bringIntoView(Rect(0f, -up.toFloat(), size.width.toFloat(), (size.height + down).toFloat()))
            }
            requester.bringIntoView()
        }
    }
    onSizeChanged { size = it }.bringIntoViewRequester(requester).onFocusChanged { focused = it.hasFocus }
}

/** A gap of [height] in a dialog's content: [DialogSpacing]'s values. */
@Composable
fun DialogGap(height: Dp) = Spacer(Modifier.height(height))
