package com.android.BioKey.feature.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.BioKey.R
import com.android.BioKey.core.data.LockTimeoutMode
import com.android.BioKey.core.data.SecurityProfile
import com.android.BioKey.ui.theme.GroupInset
import com.android.BioKey.ui.theme.SEPARATOR
import com.android.BioKey.ui.theme.scaledWithText

/** [description]: what screen readers say in place of a short [label] ("Finance profile, 2 apps" for "Finance · 2"). */
@Composable
internal fun FilterChoice(label: String, icon: ImageVector, selected: Boolean, description: String? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        // Chosen, it shows a tick where its icon was: colour alone doesn't say so to everyone.
        leadingIcon = { Icon(if (selected) Icons.Default.Check else icon, contentDescription = null, modifier = Modifier.size(18.dp.scaledWithText())) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    )
}

internal const val PROFILE_CARD_TAG = "profile_card"

/** Everything about a profile, at the top of its own page. */
@Composable
internal fun ProfileCard(
    profile: SecurityProfile,
    appCount: Int,
    defaultTimeout: LockTimeoutMode,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRelock: () -> Unit,
    onAddApps: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GroupInset, vertical = 8.dp)
            .testTag(PROFILE_CARD_TAG)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileBadge(profile.iconKey, 56.dp, active = profile.enabled)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    // The whole name, as the switch below says it: any allowed name wraps, never cut.
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        text = pluralStringResource(R.plurals.home_app_count, appCount, appCount) + SEPARATOR +
                            if (profile.enabled) stringResource((profile.timeoutMode ?: defaultTimeout).relockPhraseRes).lowercase() else stringResource(R.string.home_off),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.home_profile_edit_description, profile.name))
                }
            }
            Spacer(Modifier.height(12.dp))
            // The profile's own switch: off pauses every app in it; each keeps its own setting.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .toggleable(value = profile.enabled, role = Role.Switch, onValueChange = onEnabledChange)
                    .padding(horizontal = 4.dp, vertical = 8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_profile_lock_apps, profile.name), style = MaterialTheme.typography.titleMedium)
                    // On, the title and the switch say it all; off, a line says what that means.
                    if (!profile.enabled) {
                        Text(text = stringResource(R.string.home_profile_off_text), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = profile.enabled, onCheckedChange = null)
            }
            Spacer(Modifier.height(8.dp))
            // Side by side, each half the card; at a large font, one above the other at full width,
            // so neither label wraps inside its button.
            val stacked = LocalDensity.current.fontScale > 1.3f
            val addApps = @Composable { modifier: Modifier ->
                Button(onClick = onAddApps, modifier = modifier) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp.scaledWithText()))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_add_apps))
                }
            }
            val relock = @Composable { modifier: Modifier ->
                // Chooses when its apps lock again, not a "lock now": named for the delay. The
                // second of the pair: outlined, in the card's own colour, so it reads as a button
                // beside "Add apps" without competing with it.
                val onCard = MaterialTheme.colorScheme.onSecondaryContainer
                OutlinedButton(
                    onClick = onRelock,
                    modifier = modifier,
                    border = BorderStroke(1.dp, onCard.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = onCard)
                ) {
                    Icon(Icons.Default.LockClock, contentDescription = null, modifier = Modifier.size(18.dp.scaledWithText()))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_relock))
                }
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    addApps(Modifier.fillMaxWidth())
                    relock(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    addApps(Modifier.weight(1f))
                    relock(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun EmptyState(text: String, actionLabel: String?, onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(actionLabel)
            }
        }
    }
}
