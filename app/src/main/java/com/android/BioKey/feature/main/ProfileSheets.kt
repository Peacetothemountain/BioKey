package com.android.BioKey.feature.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.ProtectDialogWindow
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.BioKeyMessageHost
import com.android.BioKey.ui.theme.Group
import com.android.BioKey.ui.theme.GroupGap
import com.android.BioKey.ui.theme.GroupInset
import com.android.BioKey.ui.theme.GroupRow
import com.android.BioKey.ui.theme.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A profile's icon: [key] is what's saved, [label] what TalkBack reads in the icon picker. */
internal class ProfileIcon(val key: String, val vector: ImageVector, val label: String)

internal val PROFILE_ICONS: List<ProfileIcon> = listOf(
    ProfileIcon("shield", Icons.Default.Shield, "Shield"),
    ProfileIcon("finance", Icons.Default.AccountBalance, "Bank"),
    ProfileIcon("social", Icons.AutoMirrored.Filled.Chat, "Chat"),
    ProfileIcon("work", Icons.Default.Work, "Briefcase"),
    ProfileIcon("privacy", Icons.Default.VisibilityOff, "Hidden"),
    ProfileIcon("photos", Icons.Default.Photo, "Photo"),
    ProfileIcon("health", Icons.Default.Favorite, "Heart"),
    ProfileIcon("lock", Icons.Default.Lock, "Lock")
)

internal fun profileIcon(key: String): ImageVector =
    PROFILE_ICONS.firstOrNull { it.key == key }?.vector ?: Icons.Default.Shield

internal val LockTimeoutMode.shortLabel: String
    get() = when (this) {
        LockTimeoutMode.IMMEDIATE -> "Immediately"
        LockTimeoutMode.ON_SCREEN_OFF -> "When screen turns off"
        LockTimeoutMode.GRACE_30_SECONDS -> "After 30 seconds"
        LockTimeoutMode.GRACE_1_MINUTE -> "After 1 minute"
        LockTimeoutMode.GRACE_5_MINUTES -> "After 5 minutes"
        LockTimeoutMode.GRACE_15_MINUTES -> "After 15 minutes"
    }

/** "Re-locks immediately", "Re-locks after 5 minutes"… */
internal val LockTimeoutMode.relockPhrase: String
    get() = "Re-locks " + shortLabel.replaceFirstChar { it.lowercase() }

private val LockTimeoutMode.explanation: String?
    get() = when (this) {
        LockTimeoutMode.IMMEDIATE -> "As soon as you go Home, open Recents or switch app"
        LockTimeoutMode.ON_SCREEN_OFF -> "Stays unlocked until the screen turns off"
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
        if (icon == null) icon = withContext(Dispatchers.IO) { AppIconCache.load(context, app, sizePx) }
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
            Text(title, style = MaterialTheme.typography.headlineSmall)
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

/** Create a profile, or rename / re-icon / delete an existing one. */
@Composable
internal fun ProfileDetailsDialog(
    existing: SecurityProfile?,
    onDismiss: () -> Unit,
    onSave: (name: String, iconKey: String) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var iconKey by rememberSaveable { mutableStateOf(existing?.iconKey ?: "shield") }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete && existing != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(profileIcon(existing.iconKey), contentDescription = null) },
            title = { Text("Delete “${existing.name}”?") },
            text = {
                ProtectDialogWindow()
                Text("Its apps keep their own lock switches and use your default re-lock delay.")
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(profileIcon(iconKey), contentDescription = null) },
        title = { Text(if (existing == null) "New profile" else "Edit profile") },
        text = {
            ProtectDialogWindow()
            Column(modifier = Modifier.rejectObscuredTouches()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        // Typing on the soft keyboard never passes through BioKey's own touch
                        // handling, so it's counted here: a long edit doesn't hit the idle lock.
                        LockStateTracker.onOwnAppInteraction()
                        if (it.length <= 24) name = it
                    },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
                Text("Icon", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PROFILE_ICONS.forEach { choice ->
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
                                    contentDescription = choice.label,
                                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
                if (existing != null && onDelete != null) {
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { confirmDelete = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("Delete profile", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), iconKey) }, enabled = name.isNotBlank()) {
                Text(if (existing == null) "Create" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Pick how soon apps lock again after leaving them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelockSheet(
    title: String,
    current: LockTimeoutMode,
    onSelect: (LockTimeoutMode) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ProtectDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .rejectObscuredTouches()
                .padding(bottom = 24.dp)
        ) {
            SheetTitle(title, "Apps stay unlocked while you use them. This sets when they lock again after you leave.")
            Spacer(Modifier.height(12.dp))
            val modes = LockTimeoutMode.entries
            Group(count = modes.size) { i ->
                val mode = modes[i]
                GroupRow(
                    index = i,
                    count = modes.size,
                    title = mode.shortLabel,
                    subtitle = mode.explanation,
                    interaction = Modifier.selectable(selected = mode == current, role = Role.RadioButton) { onSelect(mode) }
                ) {
                    RadioButton(selected = mode == current, onClick = null)
                }
            }
        }
    }
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
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AppIcon(app, 64.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(app.label, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = when {
                                isHomeScreen -> HOME_SCREEN_NOTE
                                !isLocked -> "Not locked"
                                currentProfile?.enabled == false -> "Not locked while ${currentProfile.name} is off"
                                else -> (currentProfile?.timeoutMode ?: defaultTimeout).relockPhrase
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Group(count = 1) {
                        val canChange = !isHomeScreen || isLocked
                        GroupRow(
                            index = 0,
                            count = 1,
                            title = "Lock app",
                            subtitle = when {
                                isHomeScreen -> "BioKey never covers the home screen you use or your phone's own, so you can always get Home"
                                isLocked -> "Needs your face, fingerprint or screen lock to open"
                                else -> "Opens without a check"
                            },
                            icon = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            interaction = Modifier.toggleable(value = isLocked, enabled = canChange, role = Role.Switch, onValueChange = onLockedChange)
                        ) {
                            Switch(checked = isLocked, onCheckedChange = null, enabled = canChange)
                        }
                    }
                    if (!isHomeScreen || currentProfile != null) SectionHeader("Profile")
                }
                if (currentProfile != null) {
                    item(key = "remove") {
                        Group(count = 1) {
                            GroupRow(
                                index = 0,
                                count = 1,
                                title = "Remove from ${currentProfile.name}",
                                subtitle = if (isLocked) "Stays locked, with your default re-lock delay" else "Stays unlocked",
                                icon = Icons.Default.RemoveCircleOutline,
                                onClick = onRemoveFromProfile
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                if (!isHomeScreen) profileChoices(profiles, currentProfile, defaultTimeout, onMoveToProfile, onNewProfile)
            }
            BioKeyMessageHost(Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** The app's profile: none, one of [profiles], or a new one. */
private fun LazyListScope.profileChoices(
    profiles: List<SecurityProfile>,
    currentProfile: SecurityProfile?,
    defaultTimeout: LockTimeoutMode,
    onMoveToProfile: (String?) -> Unit,
    onNewProfile: () -> Unit
) {
    val count = profiles.size + 2
    item {
        ProfileChoiceRow(
            index = 0,
            count = count,
            title = "No profile",
            subtitle = defaultTimeout.relockPhrase + " (default)",
            iconKey = "lock",
            selected = currentProfile == null,
            onClick = { onMoveToProfile(null) }
        )
    }
    itemsIndexed(profiles, key = { _, p -> p.id }) { i, profile ->
        ProfileChoiceRow(
            index = i + 1,
            count = count,
            title = profile.name,
            subtitle = if (profile.enabled) profile.timeoutMode.relockPhrase else "Off · its apps aren't locked",
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
                title = "New profile",
                icon = Icons.Default.Add,
                iconTint = MaterialTheme.colorScheme.onPrimary,
                iconContainer = MaterialTheme.colorScheme.primary,
                onClick = onNewProfile
            )
        }
        Text(
            text = "Choosing a profile turns the app's lock on and uses that profile's re-lock delay. Turning the lock off keeps it in its profile.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = GroupInset + 16.dp, end = GroupInset + 16.dp, top = 12.dp)
        )
    }
}

@Composable
private fun ProfileChoiceRow(index: Int, count: Int, title: String, subtitle: String, iconKey: String, selected: Boolean, onClick: () -> Unit) {
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
                    title = { Text("Add to ${profile.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    },
                    actions = {
                        Button(
                            onClick = { onAdd(chosen) },
                            enabled = chosen.isNotEmpty(),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(if (chosen.isEmpty()) "Add" else "Add ${chosen.size}")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
                Text(
                    text = "Apps already in a profile, and home screens, aren't listed",
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
                                text = if (query.isBlank()) "Every app is already in a profile. To move one here, tap it in the app list and pick ${profile.name}."
                                else "No apps outside a profile match “${query.trim()}”",
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
                            subtitle = if (app.packageName in lockedApps) "Locked" else app.category.label,
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
    TextField(
        value = query,
        onValueChange = {
            LockStateTracker.onOwnAppInteraction()
            onChange(it)
        },
        placeholder = { Text("Search apps") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            { TextButton(onClick = { onChange("") }) { Text("Clear") } }
        } else null,
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
    )
}
