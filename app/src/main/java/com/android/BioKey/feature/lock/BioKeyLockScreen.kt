package com.android.BioKey.feature.lock

import androidx.compose.foundation.layout.ime
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.BioKey.R
import com.android.BioKey.ui.brand.BioKeyLogo
import com.android.BioKey.ui.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.springPress

/** The logo's size, as on [LockCoverView]. */
private val LOGO_WIDTH = 120.dp
private val LOGO_HEIGHT = 136.dp
/**
 * As on [LockCoverView]: the smallest the logo gets in a short window, as a share of its size.
 * Shorter still, it goes and "BioKey is locked" stays.
 */
private const val MIN_LOGO_SCALE = 0.5f
/** Kept above and below the logo and its words. */
private val MESSAGE_PADDING = 16.dp
/** "BioKey is locked", for tests: screen readers hear it as the pane title instead. */
internal const val LOCKED_TITLE_TAG = "lock_screen_title"
/** Between the logo and "BioKey is locked". */
private val TITLE_GAP = 16.dp

/** As on [LockCoverView]: the entry upright under the words (its parts, gaps and margins, and its text), and the least width for one row. */
private val ENTRY_UPRIGHT = (56 + 8 + 12 + 56 + 8 + 48 + 24 + 12 + 12).dp
private val ENTRY_UPRIGHT_TEXT = (20 + 28).sp
private val COMPACT_MIN_WIDTH = 480.dp

/** As on [LockCoverView]: how long the logo glides, and the entry or buttons fade, as the entry opens or closes. */
private const val COVER_MOVE_MS = 220
private const val COVER_FADE_MS = 150

/**
 * BioKey's own lock, drawn in Compose. Laid out like [LockCoverView] showing BioKey (the logo,
 * the same size, in the middle; a primary button and Cancel at the bottom), so every lock in
 * BioKey looks like one product.
 *
 * After a permanent lockout the button locks the phone, so that the PIN can bring biometrics
 * back; only the accessibility service can do that, so without it ([canLockPhone] false) Cancel
 * is the way out and the message says what to do.
 *
 * With [passphrase] offered, "Use passphrase" sits beside Cancel, as on the cover; opened, its
 * entry ([PassphraseEntry]) takes the buttons' place, above the keyboard. Without it (null), the
 * screen is as it always was.
 */
@Composable
fun BioKeyLockScreen(
    onUnlockClicked: () -> Unit,
    onCancelClicked: () -> Unit,
    statusMessage: String? = null,
    isPermanentLockout: Boolean = false,
    canLockPhone: Boolean = true,
    onLockDeviceClicked: () -> Unit = {},
    passphrase: PassphraseGateState? = null,
    onPassphraseSubmit: (CharArray) -> Unit = {}
) {
    val lockedTitle = stringResource(R.string.lock_screen_locked)
    // Clear of the system bars and a camera cutout alike, and by the same on both sides, so
    // everything sits in the middle of the screen, as on the cover.
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val evenInsets = remember(insets) { EvenSides(insets) }
    val entryOpen = passphrase?.open == true
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .rejectObscuredTouches()
            // Announced as it appears; the logo already says "BioKey" when moving through it.
            .semantics { paneTitle = lockedTitle }
            .background(coverWash())
    ) {
        // As on the cover: a wide window with too little height for the entry upright under the
        // words (a phone in landscape with the keyboard up) has it in one row, saying itself what's
        // locked, the logo and words stepping aside.
        val density = LocalDensity.current
        val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
        val roomWide = maxWidth - with(density) { (evenInsets.getLeft(density, layoutDirection) + evenInsets.getRight(density, layoutDirection)).toDp() }
        val roomHigh = maxHeight - with(density) {
            (evenInsets.getTop(density) + maxOf(evenInsets.getBottom(density), WindowInsets.ime.getBottom(density))).toDp()
        }
        val upright = ENTRY_UPRIGHT + with(density) { ENTRY_UPRIGHT_TEXT.toDp() }
        val compact = entryOpen && roomWide >= COMPACT_MIN_WIDTH && roomHigh < upright
        // The logo and its words centred in the room above the buttons. When that room is short
        // (landscape, a split screen, large text, a lockout) the logo shrinks, then goes, so
        // "BioKey is locked" always shows; past that they scroll. Above the keyboard too, while
        // the passphrase has it up.
        Column(Modifier.fillMaxSize().windowInsetsPadding(evenInsets).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val room = (maxHeight - MESSAGE_PADDING * 2).coerceAtLeast(0.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(horizontal = 32.dp, vertical = MESSAGE_PADDING),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (!compact) LockedMessage(lockedTitle, statusMessage, room, stepAside = entryOpen, Modifier.widthIn(max = 420.dp))
                }
            }
            // The buttons, or the passphrase's entry in their place. As on the cover, what leaves
            // goes at once (and what was typed with it) and what comes fades in, while the room
            // they take changes smoothly, so the logo above glides to its new place rather than
            // jumping (all at once with the system's animations off). Scrolling as a last resort,
            // as the cover's entry does: on a short window with the keyboard up the field stays in
            // view and the rest scrolls.
            val openedAtFirst = remember { entryOpen }
            val switched = remember { BooleanArray(1) }
            if (entryOpen != openedAtFirst) switched[0] = true
            Box(
                modifier = Modifier
                    // The same width as LockCoverView's buttons: the screen less 32dp each side, at most
                    // 360dp; in one row, as the cover's, at most 720dp.
                    .padding(horizontal = 32.dp)
                    .widthIn(max = if (compact) 720.dp else 360.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    // In one row, closer to the keyboard, as the cover's: the room is short. The
                    // row keeps its own room above and below it (up to 12dp), giving it up first.
                    .padding(vertical = if (compact) 0.dp else 24.dp)
                    .animateContentSize(tween(COVER_MOVE_MS, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)), Alignment.BottomCenter),
                contentAlignment = Alignment.BottomCenter
            ) {
                key(entryOpen) {
                    // Not on the screen's first showing: only when one takes the other's place.
                    val fade = remember { Animatable(if (switched[0]) 0f else 1f) }
                    LaunchedEffect(Unit) { fade.animateTo(1f, tween(COVER_FADE_MS)) }
                    Column(
                        Modifier.fillMaxWidth().graphicsLayer { alpha = fade.value },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (entryOpen && passphrase != null) {
                            PassphraseEntry(
                                passphrase, onPassphraseSubmit, Modifier.fillMaxWidth(),
                                compactTitle = if (compact) stringResource(R.string.brand_name) else null,
                                // All of it above the keyboard: the line under the field included.
                                roomHeight = if (compact) roomHigh.coerceAtLeast(0.dp) else Dp.Infinity
                            )
                        } else {
                            if (!isPermanentLockout || canLockPhone) {
                                Button(
                                    onClick = if (isPermanentLockout) onLockDeviceClicked else onUnlockClicked,
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .springPress()
                                ) {
                                    if (!isPermanentLockout) {
                                        // In sp, as the cover's: it grows with the text.
                                        val density = LocalDensity.current
                                        Icon(
                                            Icons.Default.Fingerprint,
                                            contentDescription = null,
                                            modifier = Modifier.size(with(density) { LockCoverView.UNLOCK_GLYPH_SP.sp.toDp() })
                                        )
                                        Spacer(Modifier.width(with(density) { LockCoverView.UNLOCK_GLYPH_GAP_SP.sp.toDp() }))
                                    }
                                    Text(stringResource(if (isPermanentLockout) R.string.lock_screen_lock_phone else R.string.lock_screen_unlock), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            if (passphrase != null && passphrase.offered) {
                                PassphraseAndCancel(onUsePassphrase = { passphrase.open = true }, onCancel = onCancelClicked)
                            } else {
                                CoverTextButton(stringResource(R.string.prompt_cancel), onCancelClicked)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The logo, "BioKey is locked", and what the last prompt said (a lockout, say), in [room]: the
 * logo as large as fits there (down to half its size), and gone when even that doesn't, so the
 * words always show; past that they scroll. With [stepAside] (the passphrase's entry open) they
 * step aside altogether instead, as the cover's do: a sliver of them is worth less than the field.
 */
@Composable
private fun LockedMessage(lockedTitle: String, statusMessage: String?, room: Dp, stepAside: Boolean, modifier: Modifier = Modifier) {
    val status = statusMessage?.takeIf { it.isNotBlank() }
    Layout(
        contents = listOf(
            { BioKeyLogo(Modifier.fillMaxSize()) },
            {
                // Said in words too, not only by the mark (screen readers hear the pane title).
                Text(
                    lockedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    // Heard as the pane title, so nothing here for screen readers; tests find it.
                    modifier = Modifier.clearAndSetSemantics { testTag = LOCKED_TITLE_TAG }
                )
            },
            {
                // Always there, empty when there's nothing to say: a live region is read out when
                // its text changes, not when it first appears. Empty, it takes no room: the logo
                // and title stay in the middle, and a short window keeps that room for them.
                Text(
                    text = status.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        ),
        modifier = modifier
    ) { (art, title, said), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val titlePlaced = title.first().measure(loose)
        val saidPlaced = said.first().measure(loose)
        val gap = TITLE_GAP.roundToPx()
        val saidHeight = if (status != null) gap + saidPlaced.height else 0
        val wordsHeight = titlePlaced.height + saidHeight
        val roomPx = room.roundToPx()
        val full = LOGO_HEIGHT.roundToPx()
        val logoHeight = (roomPx - wordsHeight - gap).coerceAtMost(full)
            .let { if (it < full * MIN_LOGO_SCALE) 0 else it }
        val logoWidth = (LOGO_WIDTH.roundToPx().toLong() * logoHeight / full).toInt()
        val logoPlaced = if (logoHeight > 0) art.first().measure(Constraints.fixed(logoWidth, logoHeight)) else null
        val height = (if (logoHeight > 0) logoHeight + gap else 0) + wordsHeight
        val width = maxOf(logoWidth, titlePlaced.width, saidPlaced.width, constraints.minWidth)
            .coerceAtMost(constraints.maxWidth)
        if (stepAside && height > roomPx) {
            layout(width, 0) {}
        } else {
            layout(width, height) {
                var y = 0
                logoPlaced?.placeRelative((width - logoPlaced.width) / 2, y)
                if (logoPlaced != null) y += logoHeight + gap
                titlePlaced.placeRelative((width - titlePlaced.width) / 2, y)
                y += titlePlaced.height + (if (status != null) gap else 0)
                saidPlaced.placeRelative((width - saidPlaced.width) / 2, y)
            }
        }
    }
}

/**
 * [insets] with the larger of its left and right on both sides: content clear of a camera cutout
 * on one side stays in the middle of the screen, as on [LockCoverView].
 */
private data class EvenSides(private val insets: WindowInsets) : WindowInsets {
    override fun getLeft(density: Density, layoutDirection: LayoutDirection) =
        maxOf(insets.getLeft(density, layoutDirection), insets.getRight(density, layoutDirection))
    override fun getRight(density: Density, layoutDirection: LayoutDirection) = getLeft(density, layoutDirection)
    override fun getTop(density: Density) = insets.getTop(density)
    override fun getBottom(density: Density) = insets.getBottom(density)
}

/** The lock cover's background: the wallpaper's colour washing down into the surface, as on [LockCoverView]. */
@Composable
private fun coverWash(): Brush {
    val surface = MaterialTheme.colorScheme.surface
    val wash = MaterialTheme.colorScheme.primaryContainer
    return remember(surface, wash) { Brush.verticalGradient(listOf(lerp(surface, wash, 0.5f), lerp(surface, wash, 0.16f), surface)) }
}
