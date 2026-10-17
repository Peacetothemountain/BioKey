package com.android.BioKey.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AppSettingsAlt
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.android.BioKey.core.security.ProtectDialogWindow
import com.android.BioKey.core.security.rejectObscuredTouches
import com.android.BioKey.ui.theme.IconBadge

/**
 * Google Play's prominent-disclosure rule: before BioKey sends the user to grant a sensitive
 * permission, it says in the app what it reads, what for, and what it never does, and continues
 * only on an explicit "Agree". Dismissing the sheet or pressing Back is not consent.
 */
enum class Disclosure(
    val icon: ImageVector,
    val title: String,
    val reads: List<String>,
    val usedFor: List<String>
) {
    ACCESSIBILITY(
        Icons.Default.Accessibility,
        "BioKey uses the Accessibility service",
        reads = listOf(
            "Which app is on screen",
            "The name of an app you tap on the home screen, in search or in Recents",
            "The text of system Settings pages about BioKey itself (App info, Accessibility, uninstall)"
        ),
        usedFor = listOf(
            "Covering apps you've locked until you authenticate",
            "Asking for authentication before BioKey can be switched off, if you turn that on"
        )
    ),
    COMPATIBILITY(
        Icons.Default.AppSettingsAlt,
        "BioKey uses usage access and display over other apps",
        reads = listOf("Which app was just opened (app usage events on this phone)"),
        usedFor = listOf(
            "Covering apps you've locked until you authenticate, without the Accessibility service",
            "Drawing BioKey's lock cover over a locked app as it opens"
        )
    ),
    NOTIFICATIONS(
        Icons.Default.Notifications,
        "BioKey uses notification access",
        reads = listOf("Notifications from apps you've locked"),
        usedFor = listOf("Hiding their content until you unlock the app, then putting them back")
    );
}

private val NEVER = listOf(
    "Nothing BioKey sees is stored, shared or sent anywhere. BioKey has no internet access.",
    "BioKey never reads what you type, your messages or your screen's content in other apps."
)

@Stable
class DisclosureGate {
    internal var pending by mutableStateOf<Pair<Disclosure, () -> Unit>?>(null)

    /** Shows [disclosure]; runs [onAgree] only if the user taps Agree. */
    fun request(disclosure: Disclosure, onAgree: () -> Unit) {
        pending = disclosure to onAgree
    }
}

/** A gate whose sheet is shown wherever this is called. */
@Composable
fun rememberDisclosureGate(): DisclosureGate {
    val gate = remember { DisclosureGate() }
    gate.pending?.let { (disclosure, onAgree) ->
        DisclosureSheet(
            disclosure,
            onAgree = {
                gate.pending = null
                onAgree()
            },
            onDecline = { gate.pending = null }
        )
    }
    return gate
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisclosureSheet(disclosure: Disclosure, onAgree: () -> Unit, onDecline: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDecline,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        ProtectDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .rejectObscuredTouches()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            IconBadge(disclosure.icon, size = 52.dp)
            Spacer(Modifier.height(16.dp))
            Text(disclosure.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            DisclosureSection("What BioKey reads", disclosure.reads)
            DisclosureSection("What it's used for", disclosure.usedFor)
            DisclosureSection("What it never does", NEVER)
            Spacer(Modifier.height(8.dp))
            Text(
                "You can turn this off at any time in Android settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) { Text("Not now") }
                Button(onClick = onAgree, modifier = Modifier.weight(1f)) { Text("Agree") }
            }
        }
    }
}

@Composable
private fun DisclosureSection(title: String, lines: List<String>) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
    lines.forEach { line ->
        Row(Modifier.padding(bottom = 6.dp)) {
            Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.size(width = 16.dp, height = 20.dp))
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Spacer(Modifier.height(12.dp))
}
