package com.android.BioKey.feature.main

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.android.BioKey.R
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.ui.security.ProtectDialogWindow
import com.android.BioKey.ui.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.BioKeyMessageHost
import com.android.BioKey.ui.theme.Group
import com.android.BioKey.ui.theme.GroupGap
import com.android.BioKey.ui.theme.GroupInset
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.SectionHeader
import com.android.BioKey.ui.theme.scaledWithText
import com.android.BioKey.ui.theme.BioKeySheet
import com.android.BioKey.ui.theme.DialogGap
import com.android.BioKey.ui.theme.DialogSpacing
import com.android.BioKey.ui.theme.KeyboardSafeDialog
import com.android.BioKey.ui.theme.keepInViewWhileFocused
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A profile's icon: [key] is what's saved, [label] what TalkBack reads in the icon picker. */
internal class ProfileIcon(val key: String, val vector: ImageVector, @param:StringRes val label: Int)

internal val PROFILE_ICONS: List<ProfileIcon> = listOf(
    // Saved as "shield" (it once was one): a person now, so no profile wears the shield that says
    // protection is on. Personal's icon.
    ProfileIcon("shield", Icons.Default.Person, R.string.profile_icon_shield),
    ProfileIcon("finance", Icons.Default.AccountBalance, R.string.profile_icon_bank),
    ProfileIcon("social", Icons.AutoMirrored.Filled.Chat, R.string.profile_icon_chat),
    ProfileIcon("work", Icons.Default.Work, R.string.profile_icon_briefcase),
    ProfileIcon("privacy", Icons.Default.VisibilityOff, R.string.profile_icon_hidden),
    ProfileIcon("photos", Icons.Default.Photo, R.string.profile_icon_photo),
    ProfileIcon("health", Icons.Default.Favorite, R.string.profile_icon_heart),
    ProfileIcon("lock", Icons.Default.Lock, R.string.profile_icon_lock),
    // A new profile's: a plain folder of apps, saying nothing about them.
    ProfileIcon("folder", Icons.Default.Folder, R.string.profile_icon_folder)
)

/**
 * What a new profile starts with: one no built-in profile wears (Personal, Finance, Social and
 * Work have theirs), and neutral: a folder, not the crossed-out eye, which reads as hidden.
 */
internal const val NEW_PROFILE_ICON = "folder"

/** "No profile"'s icon among the profiles: no folder, never the lock (that's "Lock app"'s). Not in the picker. */
internal const val NO_PROFILE_ICON = "none"

internal fun profileIcon(key: String): ImageVector =
    if (key == NO_PROFILE_ICON) Icons.Default.FolderOff
    else PROFILE_ICONS.firstOrNull { it.key == key }?.vector ?: Icons.Default.Person

/** A delay's short name ("After 5 minutes"), from the app's strings. */
@get:StringRes
internal val LockTimeoutMode.shortLabelRes: Int
    get() = when (this) {
        LockTimeoutMode.IMMEDIATE -> R.string.profile_relock_immediately
        LockTimeoutMode.ON_SCREEN_OFF -> R.string.profile_relock_screen_off
        LockTimeoutMode.GRACE_30_SECONDS -> R.string.profile_relock_30_seconds
        LockTimeoutMode.GRACE_1_MINUTE -> R.string.profile_relock_1_minute
        LockTimeoutMode.GRACE_5_MINUTES -> R.string.profile_relock_5_minutes
        LockTimeoutMode.GRACE_15_MINUTES -> R.string.profile_relock_15_minutes
    }

/** "Re-locks immediately", "Re-locks after 5 minutes"… */
@get:StringRes
internal val LockTimeoutMode.relockPhraseRes: Int
    get() = when (this) {
        LockTimeoutMode.IMMEDIATE -> R.string.profile_relocks_immediately
        LockTimeoutMode.ON_SCREEN_OFF -> R.string.profile_relocks_screen_off
        LockTimeoutMode.GRACE_30_SECONDS -> R.string.profile_relocks_30_seconds
        LockTimeoutMode.GRACE_1_MINUTE -> R.string.profile_relocks_1_minute
        LockTimeoutMode.GRACE_5_MINUTES -> R.string.profile_relocks_5_minutes
        LockTimeoutMode.GRACE_15_MINUTES -> R.string.profile_relocks_15_minutes
    }

@get:StringRes
private val LockTimeoutMode.explanation: Int?
    get() = when (this) {
        LockTimeoutMode.IMMEDIATE -> R.string.profile_relock_immediately_explanation
        // What the title ("When the phone locks") doesn't already say.
        LockTimeoutMode.ON_SCREEN_OFF -> R.string.profile_relock_screen_off_detail
        else -> null
    }

@Composable
internal fun ProfileBadge(iconKey: String, size: Dp = 48.dp, active: Boolean = true) {
    Surface(
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = profileIcon(iconKey),
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}

/** The app's icon, drawn off the main thread the first time; its initial until then, or if it has none. */
@Composable
internal fun AppIcon(app: AppItem, size: Dp = 40.dp) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val key = AppIconCache.key(app, sizePx)
    var icon by remember(key) { mutableStateOf(AppIconCache.peek(key)) }
    LaunchedEffect(key) {
        if (icon == null) icon = withContext(AppIconCache.loading) { AppIconCache.load(context, app, sizePx) }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(size)) {
            Box(contentAlignment = Alignment.Center) {
                Text(app.label.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/** Title block at the top of every sheet. */
@Composable
private fun SheetTitle(title: String, subtitle: String? = null, action: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null) {
            Spacer(Modifier.width(12.dp))
            action()
        }
    }
}

/** The most characters a profile's name may have: it has to fit a chip and the profile's card. */
internal const val PROFILE_NAME_MAX = 24

/** The counter under the name field shows from this many characters on: quiet until it matters. */
internal const val PROFILE_NAME_COUNT_FROM = PROFILE_NAME_MAX - 6

/**
 * What the name field holds after an edit from [old] to [new]. Within [PROFILE_NAME_MAX] it is
 * taken as it is. Longer, typed into a name already full, the keystroke is refused (so typing in
 * the middle never pushes letters off the end); anything else (a paste, mostly) is cut to fit,
 * never through the middle of a character made of two.
 */
internal fun fitProfileName(old: String, new: String): String {
    if (new.length <= PROFILE_NAME_MAX) return new
    if (old.length >= PROFILE_NAME_MAX) return old
    var end = PROFILE_NAME_MAX
    if (new[end - 1].isHighSurrogate()) end--
    return new.substring(0, end)
}

/** The red-letter button of a dialog that deletes or removes: dims like any other when it can't be used. */
@Composable
internal fun DestructiveTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        contentPadding = contentPadding,
        modifier = modifier
    ) { Text(text) }
}

/**
 * Whether the profile dialog's Save (Create) can be pressed: a name that isn't blank and, for an
 * [existing] profile, something changed (its name, once trimmed, or its icon).
 */
internal fun profileDetailsCanSave(existing: SecurityProfile?, name: String, iconKey: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return false
    return existing == null || trimmed != existing.name || iconKey != existing.iconKey
}

/** Create a profile, or rename / re-icon / delete an existing one. */
@Composable
internal fun ProfileDetailsDialog(
    existing: SecurityProfile?,
    onDismiss: () -> Unit,
    onSave: (name: String, iconKey: String) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var iconKey by rememberSaveable { mutableStateOf(existing?.iconKey ?: NEW_PROFILE_ICON) }
    var confirmDelete by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val canSave = profileDetailsCanSave(existing, name, iconKey)

    // A new profile starts with the cursor in its name, the keyboard up; an existing one opens as
    // it is, its icon as likely to be what's changed as its name.
    LaunchedEffect(Unit) {
        if (existing == null) {
            runCatching { nameFocus.requestFocus() }
            keyboard?.show()
        }
    }

    if (confirmDelete && existing != null && onDelete != null) {
        KeyboardSafeDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(profileIcon(existing.iconKey), contentDescription = null) },
            title = { PassphraseDialogTitle(stringResource(R.string.profile_delete_title, existing.name)) },
            confirmButton = { DestructiveTextButton(stringResource(R.string.common_delete), onClick = { confirmDelete = false; onDelete() }) },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.prompt_cancel)) } }
        ) {
            // An empty profile unlocks nothing: said so, not "its apps".
            Text(stringResource(if (existing.packages.isEmpty()) R.string.profile_delete_text_empty else R.string.profile_delete_text))
        }
        return
    }

    KeyboardSafeDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(profileIcon(iconKey), contentDescription = null) },
        title = { PassphraseDialogTitle(stringResource(if (existing == null) R.string.profile_new else R.string.profile_edit)) },
        confirmButton = {
            TextButton(onClick = { if (canSave) onSave(name.trim(), iconKey) }, enabled = canSave) {
                Text(stringResource(if (existing == null) R.string.profile_create else R.string.profile_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_cancel)) } },
        // With the dialog's buttons, at their start: as "Remove passphrase" is in its dialog.
        startButton = if (existing != null && onDelete != null) {
            { DestructiveTextButton(stringResource(R.string.profile_delete_profile), onClick = { confirmDelete = true }) }
        } else null
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                // Typing on the soft keyboard never passes through BioKey's own touch
                // handling, so it's counted here: a long edit doesn't hit the idle lock.
                LockStateTracker.onOwnAppInteraction()
                name = fitProfileName(name, it)
            },
            label = { Text(stringResource(R.string.profile_name)) },
            // Always one line high, empty or not, so the counter coming never moves what's below.
            supportingText = {
                val count = name.length
                if (count >= PROFILE_NAME_COUNT_FROM) {
                    val spoken = pluralStringResource(R.plurals.profile_name_count_description, PROFILE_NAME_MAX, count, PROFILE_NAME_MAX)
                    Text(
                        stringResource(R.string.profile_name_count, count, PROFILE_NAME_MAX),
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken }
                    )
                } else {
                    Text("")
                }
            },
            singleLine = true,
            // A pill, as every other field in the app (the passphrase's, the backup's, search).
            shape = CircleShape,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            // Done saves where Save would; otherwise it only puts the keyboard away.
            keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(name.trim(), iconKey) else keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(nameFocus)
                .keepInViewWhileFocused()
        )
        DialogGap(DialogSpacing.Part)
        Text(stringResource(R.string.profile_icon), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DialogGap(DialogSpacing.Label)
        // Three even rows of three, centred: the same space on either side however wide the dialog.
        // (Rows of their own, not a flowing row: three 48-dp circles always fit a dialog's width.)
        Column(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PROFILE_ICONS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { choice ->
                        val selected = choice.key == iconKey
                        // A radio button to TalkBack: it hears the icon's name and which one is chosen.
                        Surface(
                            shape = CircleShape,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .selectable(selected = selected, role = Role.RadioButton) { iconKey = choice.key }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = choice.vector,
                                    contentDescription = stringResource(choice.label),
                                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pick how soon apps lock again after leaving them. With [settingsDefault] (a profile's sheet),
 * the first choice is to follow the default in Settings, picked as null; [current] null is that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelockSheet(
    title: String,
    current: LockTimeoutMode?,
    onSelect: (LockTimeoutMode?) -> Unit,
    onDismiss: () -> Unit,
    settingsDefault: LockTimeoutMode? = null
) {
    // Open all the way at once: every choice in sight, none under the navigation bar.
    BioKeySheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ProtectDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .rejectObscuredTouches()
                .padding(bottom = 24.dp)
        ) {
            SheetTitle(title, stringResource(R.string.profile_relock_sheet_text))
            Spacer(Modifier.height(12.dp))
            val modes: List<LockTimeoutMode?> = (if (settingsDefault != null) listOf(null) else emptyList<LockTimeoutMode?>()) + LockTimeoutMode.entries
            // One set of choices for screen readers, not radio buttons each on their own.
            Group(count = modes.size, modifier = Modifier.selectableGroup()) { i ->
                val mode = modes[i]
                GroupRow(
                    index = i,
                    count = modes.size,
                    // The default is called what BioKey's settings call it: "Default re-lock delay".
                    title = stringResource(mode?.shortLabelRes ?: R.string.profile_same_as_settings),
                    // Each choice with an icon, as the pause sheet's are.
                    icon = relockIcon(mode),
                    subtitle = if (mode != null) mode.explanation?.let { stringResource(it) } else stringResource(
                        R.string.profile_follows_default,
                        // settingsDefault is set whenever this row (mode null) is offered.
                        settingsDefault?.let { stringResource(it.shortLabelRes) }.toString()
                    ),
                    interaction = Modifier.selectable(selected = mode == current, role = Role.RadioButton) { onSelect(mode) }
                ) {
                    RadioButton(selected = mode == current, onClick = null)
                }
            }
        }
    }
}

/** A re-lock choice's icon: at once, after a while, with the screen, or as Settings has it (null). */
internal fun relockIcon(mode: LockTimeoutMode?): ImageVector = when (mode) {
    null -> Icons.Default.Settings
    LockTimeoutMode.IMMEDIATE -> Icons.Default.Lock
    LockTimeoutMode.ON_SCREEN_OFF -> Icons.Default.ScreenLockPortrait
    LockTimeoutMode.GRACE_30_SECONDS, LockTimeoutMode.GRACE_1_MINUTE -> Icons.Default.Timer
    LockTimeoutMode.GRACE_5_MINUTES, LockTimeoutMode.GRACE_15_MINUTES -> Icons.Default.Schedule
}

/**
 * Per-app settings, opened by tapping an app anywhere: lock it, and choose its profile.
 *
 * [isHomeScreen]: a home screen BioKey never covers. Its lock can only be turned off, and it
 * isn't offered a profile (choosing one switches the lock on); one it's already in can be left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppOptionsSheet(
    app: AppItem,
    isLocked: Boolean,
    isHomeScreen: Boolean,
    currentProfile: SecurityProfile?,
    profiles: List<SecurityProfile>,
    defaultTimeout: LockTimeoutMode,
    onLockedChange: (Boolean) -> Unit,
    onMoveToProfile: (String?) -> Unit,
    onRemoveFromProfile: () -> Unit,
    onNewProfile: () -> Unit,
    onDismiss: () -> Unit,
    /** Opens what locking this app does ([LockExplainSheet]), locked or not (not for a home screen). */
    onExplain: (() -> Unit)? = null
) {
    // Open all the way at once: dragged open from half-way, a sheet about as tall as the screen
    // (an app in no profile) can stay mid-settle, its rows' taps taken to stop it.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // How a locked app opens, as BioKey is set now: read once as the sheet opens.
    val opensWith = remember { LockExplainer.opensWith(context) }
    BioKeySheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ProtectDialogWindow()
        // The lock switch's "unlocked · Undo" comes up while the sheet stays open: shown here,
        // not behind the sheet's scrim.
        Box {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .rejectObscuredTouches(),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                item {
                    // At the start, as every sheet's title is.
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
                        AppIcon(app, 64.dp)
                        Spacer(Modifier.height(12.dp))
                        // The app the sheet is about: its whole name, wrapped where it's long or the text large.
                        Text(
                            app.label,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = when {
                                isHomeScreen -> stringResource(R.string.home_screen_note)
                                currentProfile?.enabled == false -> stringResource(R.string.profile_not_locked_while_off, currentProfile.name)
                                !isLocked -> stringResource(R.string.common_not_locked)
                                else -> stringResource((currentProfile?.timeoutMode ?: defaultTimeout).relockPhraseRes)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    // While its profile is off the switch shows what happens (off) and waits:
                    // the app's own choice comes back with the profile.
                    val offProfile = currentProfile?.takeIf { !it.enabled }
                    val locked = isLocked && offProfile == null
                    // What a lock does and doesn't cover: before locking it as much as after.
                    val explain = onExplain?.takeIf { !isHomeScreen }
                    val rows = if (explain != null) 2 else 1
                    Group(count = rows) { row ->
                        if (row == 1 && explain != null) {
                            GroupRow(
                                index = 1,
                                count = rows,
                                title = stringResource(R.string.explain_row_title),
                                subtitle = stringResource(R.string.explain_row_text),
                                icon = Icons.Default.Info,
                                // In place of this sheet, not over it: one sheet, one scrim. This
                                // one slides away first, then the explanation comes up.
                                onClick = {
                                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                                        explain()
                                        onDismiss()
                                    }
                                },
                                // It opens a sheet, as the rows with a chevron in BioKey's settings open theirs.
                                trailing = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )
                            return@Group
                        }
                        val canChange = offProfile == null && (!isHomeScreen || isLocked)
                        GroupRow(
                            index = 0,
                            count = rows,
                            title = stringResource(R.string.profile_lock_app),
                            subtitle = when {
                                isHomeScreen -> stringResource(R.string.profile_lock_app_home_screen)
                                offProfile != null -> stringResource(R.string.profile_lock_app_profile_off, offProfile.name)
                                // What it asks for as BioKey is set: never a way in that's refused.
                                locked -> opensWith
                                else -> stringResource(R.string.profile_lock_app_unlocked)
                            },
                            icon = if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                            interaction = Modifier.toggleable(value = locked, enabled = canChange, role = Role.Switch, onValueChange = onLockedChange)
                        ) {
                            Switch(checked = locked, onCheckedChange = null, enabled = canChange)
                        }
                    }
                    if (!isHomeScreen || currentProfile != null) SectionHeader(stringResource(R.string.profile_section))
                }
                // One control for the app's profile: "No profile" is how it leaves one.
                if (!isHomeScreen) {
                    profileChoices(profiles, currentProfile, isLocked, defaultTimeout, onMoveToProfile, onRemoveFromProfile, onNewProfile)
                } else if (currentProfile != null) {
                    // A home screen can't join a profile, only leave the one it's in.
                    item(key = "leave") {
                        Column {
                            ProfileChoiceRow(
                                index = 0,
                                count = 2,
                                title = stringResource(R.string.profile_no_profile),
                                subtitle = noProfileSubtitle(currentProfile, isLocked, defaultTimeout),
                                iconKey = NO_PROFILE_ICON,
                                selected = false,
                                onClick = onRemoveFromProfile
                            )
                            ProfileChoiceRow(
                                index = 1,
                                count = 2,
                                title = currentProfile.name,
                                subtitle = profileSubtitle(currentProfile, defaultTimeout),
                                iconKey = currentProfile.iconKey,
                                selected = true,
                                onClick = {}
                            )
                        }
                    }
                }
            }
            BioKeyMessageHost(Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * "No profile"'s second line. In none: the delay it then follows ("Default re-lock delay ·
 * after 5 minutes"). In [currentProfile]: what leaving it does to the app's lock.
 */
@Composable
private fun noProfileSubtitle(currentProfile: SecurityProfile?, isLocked: Boolean, defaultTimeout: LockTimeoutMode): String = when {
    currentProfile == null -> stringResource(
        R.string.profile_no_profile_default,
        stringResource(defaultTimeout.shortLabelRes).replaceFirstChar { it.lowercase() }
    )
    isLocked -> stringResource(R.string.profile_remove_stays_locked)
    else -> stringResource(R.string.profile_remove_stays_unlocked)
}

/**
 * A profile's second line: that it's off, or a re-lock delay of its own. Nothing where it follows
 * the default: the same words under every profile would say nothing.
 */
@Composable
private fun profileSubtitle(profile: SecurityProfile, defaultTimeout: LockTimeoutMode): String? {
    val own = profile.timeoutMode?.takeIf { it != defaultTimeout }
    return when {
        !profile.enabled -> stringResource(R.string.profile_off)
        own != null -> stringResource(own.relockPhraseRes)
        else -> null
    }
}

/**
 * The app's profile: none, one of [profiles], or a new one. Choosing "No profile" takes it out of
 * the one it's in ([onRemoveFromProfile], with its Undo); chosen already, it does nothing.
 */
private fun LazyListScope.profileChoices(
    profiles: List<SecurityProfile>,
    currentProfile: SecurityProfile?,
    isLocked: Boolean,
    defaultTimeout: LockTimeoutMode,
    onMoveToProfile: (String?) -> Unit,
    onRemoveFromProfile: () -> Unit,
    onNewProfile: () -> Unit
) {
    val count = profiles.size + 2
    item(key = "none") {
        ProfileChoiceRow(
            index = 0,
            count = count,
            title = stringResource(R.string.profile_no_profile),
            subtitle = noProfileSubtitle(currentProfile, isLocked, defaultTimeout),
            iconKey = NO_PROFILE_ICON,
            selected = currentProfile == null,
            onClick = { if (currentProfile != null) onRemoveFromProfile() }
        )
    }
    itemsIndexed(profiles, key = { _, p -> p.id }) { i, profile ->
        ProfileChoiceRow(
            index = i + 1,
            count = count,
            title = profile.name,
            subtitle = profileSubtitle(profile, defaultTimeout),
            iconKey = profile.iconKey,
            selected = currentProfile?.id == profile.id,
            onClick = { onMoveToProfile(profile.id) }
        )
    }
    item {
        Box(Modifier.padding(horizontal = GroupInset).padding(top = GroupGap)) {
            GroupRow(
                index = count - 1,
                count = count,
                title = stringResource(R.string.profile_new),
                icon = Icons.Default.Add,
                iconTint = MaterialTheme.colorScheme.onPrimary,
                iconContainer = MaterialTheme.colorScheme.primary,
                onClick = onNewProfile
            )
        }
        Text(
            text = stringResource(R.string.profile_choose_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = GroupInset + 16.dp, end = GroupInset + 16.dp, top = 12.dp)
        )
    }
}

@Composable
private fun ProfileChoiceRow(index: Int, count: Int, title: String, subtitle: String?, iconKey: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = GroupInset).padding(top = if (index == 0) 0.dp else GroupGap)) {
        GroupRow(
            index = index,
            count = count,
            title = title,
            subtitle = subtitle,
            leading = { ProfileBadge(iconKey, 40.dp, active = selected) },
            interaction = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        ) {
            RadioButton(selected = selected, onClick = null)
        }
    }
}

/**
 * Multi-select picker for adding apps to [profile]: a full-screen page, as Material suggests for
 * a long list with search (and a bottom sheet fights a long list over the overscroll at its end).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddAppsScreen(
    profile: SecurityProfile,
    installedApps: List<AppItem>,
    /** Every profile, so the list follows any of them gaining apps (auto-sort) while it's open. */
    profiles: List<SecurityProfile>,
    profileOfApp: (String) -> SecurityProfile?,
    lockedApps: Set<String>,
    /** Home screens BioKey never covers: adding one would switch on a lock that does nothing. */
    homeScreens: Set<String>,
    onAdd: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    // Only apps that aren't in any profile yet: an app lives in exactly one profile, and moving
    // it between profiles is done from the app's own options.
    val outside = remember(installedApps, profiles, homeScreens) {
        installedApps.filter { app -> profileOfApp(app.packageName) == null && app.packageName !in homeScreens }
    }
    val candidates = remember(outside, query) {
        outside.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
    }
    // An app ticked here that joined another profile meanwhile stays where it is.
    val chosen = remember(outside, selected) { selected.filterTo(HashSet()) { pkg -> outside.any { it.packageName == pkg } } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        ProtectDialogWindow()
        EdgeToEdgeDialogBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
                    .rejectObscuredTouches()
            ) {
                TopAppBar(
                    title = { Text(stringResource(R.string.profile_add_to, profile.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close)) }
                    },
                    actions = {
                        Button(
                            onClick = { onAdd(chosen) },
                            enabled = chosen.isNotEmpty(),
                            // Its end on the page's margin, as the list's and the search's: the
                            // bar's actions already stand 4 dp in from the edge.
                            modifier = Modifier.padding(end = GroupInset - 4.dp)
                        ) {
                            Text(if (chosen.isEmpty()) stringResource(R.string.profile_add) else stringResource(R.string.profile_add_count, chosen.size))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
                Text(
                    text = stringResource(R.string.profile_add_apps_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = GroupInset + 8.dp)
                )
                SearchPill(query, onChange = { query = it }, modifier = Modifier.padding(horizontal = GroupInset, vertical = 12.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = GroupInset, end = GroupInset, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(GroupGap)
                ) {
                    if (candidates.isEmpty()) {
                        item(key = "none") {
                            Text(
                                text = if (query.isBlank()) stringResource(R.string.profile_add_none, profile.name)
                                else stringResource(R.string.profile_add_no_match, query.trim()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                    itemsIndexed(candidates, key = { _, app -> app.packageName }) { i, app ->
                        val checked = app.packageName in selected
                        GroupRow(
                            index = i,
                            count = candidates.size,
                            title = app.label,
                            // Only what differs is said: most aren't locked yet, and none is in a profile.
                            subtitle = if (app.packageName in lockedApps) stringResource(R.string.profile_locked) else null,
                            leading = { AppIcon(app) },
                            interaction = Modifier.toggleable(value = checked, role = Role.Checkbox) {
                                selected = if (it) selected + app.packageName else selected - app.packageName
                            }
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                        }
                    }
                }
            }
        }
    }
}

/** A full-screen dialog draws under the system bars: give their icons the right contrast. */
@Composable
internal fun EdgeToEdgeDialogBars() {
    val view = LocalView.current
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    DisposableEffect(view, light) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
        onDispose { }
    }
}

/**
 * Filled, borderless search field shaped like a pill. Typing counts as using BioKey: the soft
 * keyboard's input never passes through BioKey's own touch handling, so a long search would
 * otherwise hit the idle lock.
 */
@Composable
internal fun SearchPill(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val searchApps = stringResource(R.string.profile_search_apps)
    TextField(
        value = query,
        onValueChange = {
            LockStateTracker.onOwnAppInteraction()
            onChange(it)
        },
        placeholder = { Text(searchApps) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(24.dp.scaledWithText())) },
        trailingIcon = if (query.isNotEmpty()) {
            { TextButton(onClick = { onChange("") }) { Text(stringResource(R.string.card_clear)) } }
        } else null,
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        // Named the way Material's own search field is: the placeholder goes once something is typed.
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .semantics { contentDescription = searchApps }
    )
}
