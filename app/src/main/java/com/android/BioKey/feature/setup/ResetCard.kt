package com.android.BioKey.feature.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.android.BioKey.R
import com.android.BioKey.ui.theme.scaledWithText

/** Test tag of the reset card's "What waits a day" expander. */
internal const val SETUP_RESET_MORE_TAG = "setup_reset_more"

/**
 * Setup's "BioKey was reset" card: a warning callout (as [com.android.BioKey.ui.theme.Callout]
 * draws one) saying in a line or two what happened and that it's recorded, then "What waits a
 * day", collapsed at first, so the steps stay in view: what can't be done for a day, and [wait],
 * from when a backup can be restored or a passphrase set, and why.
 */
@Composable
internal fun ResetCard(headline: String, what: String, wait: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = lerp(colors.surfaceContainerHighest, colors.errorContainer, if (dark) 0.45f else 0.7f),
        contentColor = colors.onErrorContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Icon(Icons.Default.GppMaybe, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(20.dp.scaledWithText()))
            Spacer(Modifier.width(12.dp))
            Column {
                // One stop for a screen reader: what happened, read whole.
                Column(Modifier.semantics(mergeDescendants = true) { }) {
                    Text(headline, style = MaterialTheme.typography.titleSmall)
                    Text(what, style = MaterialTheme.typography.bodyMedium)
                }
                val state = stringResource(if (open) R.string.common_expanded else R.string.common_collapsed)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { open = !open }
                        .semantics(mergeDescendants = true) { stateDescription = state }
                        .testTag(SETUP_RESET_MORE_TAG)
                ) {
                    Text(
                        stringResource(R.string.setup_reset_wait_title),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp.scaledWithText())
                    )
                }
                if (open) {
                    Column(Modifier.padding(bottom = 8.dp).semantics(mergeDescendants = true) { }) {
                        Text(stringResource(R.string.setup_reset_wait_text), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(wait, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
