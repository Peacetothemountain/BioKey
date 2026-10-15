package com.android.BioKey.feature.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.core.security.rejectObscuredTouches

internal val PROFILE_ICONS: List<Pair<String, ImageVector>> = listOf(
    "shield" to Icons.Default.Shield,
    "finance" to Icons.Default.AccountBalance,
    "social" to Icons.AutoMirrored.Filled.Chat,
    "work" to Icons.Default.Work,
    "privacy" to Icons.Default.VisibilityOff,
    "photos" to Icons.Default.Photo,
    "health" to Icons.Default.Favorite,
    "lock" to Icons.Default.Lock
)

internal fun profileIcon(key: String): ImageVector =
    PROFILE_ICONS.firstOrNull { it.first == key }?.second ?: Icons.Default.Shield

internal val LockTimeoutMode.shortLabel: String
    get() = when (this) {
        LockTimeoutMode.IMMEDIATE -> "Immediately"
        LockTimeoutMode.ON_SCREEN_OFF -> "When screen turns off"
        LockTimeoutMode.GRACE_30_SECONDS -> "After 30 seconds"
        LockTimeoutMode.GRACE_1_MINUTE -> "After 1 minute"
        LockTimeoutMode.GRACE_5_MINUTES -> "After 5 minutes"
        LockTimeoutMode.GRACE_15_MINUTES -> "After 15 minutes"
    }

private val LockTimeoutMode.explanation: String
    get() = when (this) {
        LockTimeoutMode.IMMEDIATE -> "As soon as you go Home, open Recents, or switch to another app"
        LockTimeoutMode.ON_SCREEN_OFF -> "Stays unlocked until the screen turns off"
        else -> "Once the app has been out of sight this long"
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

@Composable
internal fun AppIcon(app: AppItem, size: Dp = 40.dp) {
    if (app.iconBitmap != null) {
        Image(bitmap = app.iconBitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(size)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = app.label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
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
            title = { Text("Delete “${existing.name}”?") },
            text = { Text("Its apps stay locked and will use your default re-lock delay.") },
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
        title = { Text(if (existing == null) "New profile" else "Edit profile") },
        text = {
            Column(modifier = Modifier.rejectObscuredTouches()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 24) name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
                Text("Icon", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(PROFILE_ICONS, key = { it.first }) { (key, vector) ->
                        val selected = key == iconKey
                        Surface(
                            onClick = { iconKey = key },
                            shape = CircleShape,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = vector,
                                    contentDescription = key,
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .rejectObscuredTouches()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Apps stay unlocked while you're using them. This sets when they lock again after you leave.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(12.dp))
            LockTimeoutMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = mode == current, role = Role.RadioButton, onClick = { onSelect(mode) })
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(mode.shortLabel, style = MaterialTheme.typography.bodyLarge)
                        if (mode == LockTimeoutMode.IMMEDIATE || mode == LockTimeoutMode.ON_SCREEN_OFF) {
                            Text(mode.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    RadioButton(selected = mode == current, onClick = null)
                }
            }
        }
    }
}

/**
 * Per-app settings, opened by tapping an app anywhere: lock it, and choose its profile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppOptionsSheet(
    app: AppItem,
    isLocked: Boolean,
    currentProfile: SecurityProfile?,
    profiles: List<SecurityProfile>,
    defaultTimeout: LockTimeoutMode,
    onLockedChange: (Boolean) -> Unit,
    onMoveToProfile: (String?) -> Unit,
    onNewProfile: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .rejectObscuredTouches(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(app, 48.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val relock = (currentProfile?.timeoutMode ?: defaultTimeout).shortLabel
                        Text(
                            text = if (isLocked) "Locked · re-locks ${relock.replaceFirstChar { it.lowercase() }}" else "Not locked",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = isLocked, role = Role.Switch, onValueChange = onLockedChange)
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Lock app", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = isLocked, onCheckedChange = null)
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Profile",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp)
                )
                Text(
                    text = "A profile groups apps and sets how soon they lock again. Choosing one locks the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(4.dp))
                ProfileChoiceRow(
                    title = "No profile",
                    subtitle = "Re-locks ${defaultTimeout.shortLabel.replaceFirstChar { it.lowercase() }} (default)",
                    iconKey = "lock",
                    selected = isLocked && currentProfile == null,
                    onClick = { onMoveToProfile(null) }
                )
            }
            items(profiles, key = { it.id }) { profile ->
                ProfileChoiceRow(
                    title = profile.name,
                    subtitle = "Re-locks ${profile.timeoutMode.shortLabel.replaceFirstChar { it.lowercase() }}",
                    iconKey = profile.iconKey,
                    selected = isLocked && currentProfile?.id == profile.id,
                    onClick = { onMoveToProfile(profile.id) }
                )
            }
            item {
                TextButton(onClick = onNewProfile, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("+ New profile")
                }
            }
        }
    }
}

@Composable
private fun ProfileChoiceRow(title: String, subtitle: String, iconKey: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfileBadge(iconKey, 36.dp, active = selected)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null)
    }
}

/** Multi-select picker for adding apps to [profile]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddAppsSheet(
    profile: SecurityProfile,
    installedApps: List<AppItem>,
    profileOfApp: (String) -> SecurityProfile?,
    lockedApps: Set<String>,
    onAdd: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    // Only apps that aren't in any profile yet: an app lives in exactly one profile, and moving
    // it between profiles is done from the app's own options.
    val candidates = remember(installedApps, profile.packages, query) {
        installedApps
            .filter { app -> profileOfApp(app.packageName) == null }
            .filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .rejectObscuredTouches()
        ) {
            Row(
                modifier = Modifier.padding(start = 24.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Add apps", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "to ${profile.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = { onAdd(selected) }, enabled = selected.isNotEmpty()) {
                    Text(if (selected.isEmpty()) "Add" else "Add ${selected.size}")
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                if (candidates.isEmpty()) {
                    item(key = "none") {
                        Text(
                            text = if (query.isBlank()) "Every app is already in a profile. To move one here, tap it in the app list and pick ${profile.name}."
                            else "No apps outside a profile match “${query.trim()}”",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
                        )
                    }
                }
                items(candidates, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox) {
                                selected = if (it) selected + app.packageName else selected - app.packageName
                            }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val note = when {
                                app.packageName in lockedApps -> "Locked"
                                else -> app.category.label
                            }
                            Text(
                                note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Checkbox(checked = checked, onCheckedChange = null)
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp)
    )
}
