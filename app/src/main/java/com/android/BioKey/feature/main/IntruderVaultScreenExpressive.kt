package com.android.BioKey.feature.main

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.BioKey.core.intruder.IntruderManager
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.ui.theme.ExpressiveTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object VaultThumbnailCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    private val memoryCache = object : androidx.collection.LruCache<String, ImageBitmap>(cacheSize) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return (value.width * value.height * 4) / 1024
        }
    }
    fun get(path: String): ImageBitmap? = memoryCache.get(path)
    fun put(path: String, bitmap: ImageBitmap) { memoryCache.put(path, bitmap) }
    fun remove(path: String) { memoryCache.remove(path) }
    fun clear() { memoryCache.evictAll() }
}

private const val THUMBNAIL_PX = 192
private const val PREVIEW_PX = 1440

private class IntruderEntry(val record: IntruderManager.IntruderRecord, val label: String)

private fun Context.sourceLabel(record: IntruderManager.IntruderRecord): String = when (record.packageName) {
    IntruderManager.DEVICE_LOCK_SOURCE -> "Device lock screen"
    BioKeyAccessibilityService.SELF_PROTECTION_PENDING_KEY, packageName -> "BioKey settings"
    BioKeyAccessibilityService.VIRTUAL_PKG_GOOGLE_PASSWORDS -> "Google Password Manager"
    else -> runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(record.packageName, 0)).toString()
    }.getOrDefault(record.packageName)
}

/**
 * Encrypted intruder photos. Everything that touches the Keystore or decodes images runs off the
 * main thread, and deleting evidence requires a fresh authentication.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntruderVaultSheet(onDismiss: () -> Unit, onChanged: (count: Int) -> Unit = {}) {
    val context = LocalContext.current
    val stepUp = LocalStepUpAuth.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var reloadKey by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<IntruderEntry>?>(null) }
    var preview by remember { mutableStateOf<IntruderEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        val loaded = withContext(Dispatchers.IO) {
            IntruderManager.getIntruderRecords(context).map { IntruderEntry(it, context.sourceLabel(it)) }
        }
        entries = loaded
        onChanged(loaded.size)
    }

    fun delete(entry: IntruderEntry) {
        stepUp.require("Delete intruder photo") {
            VaultThumbnailCache.remove(entry.record.file.absolutePath)
            entries = entries?.filterNot { it.record.file == entry.record.file }
            IntruderManager.deleteIntruderRecord(entry.record)
            preview = null
            reloadKey++
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.reportsInteraction(),
        shape = ExpressiveTokens.Shapes.BentoCardLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Intruder photos",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Encrypted on this device with a hardware-backed key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!entries.isNullOrEmpty()) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text("Delete all", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val current = entries
            when {
                current == null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                current.isEmpty() -> EmptyVault()

                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 36.dp)
                ) {
                    items(current, key = { it.record.file.absolutePath }) { entry ->
                        IntruderTile(
                            entry = entry,
                            onClick = { preview = entry },
                            onDelete = { delete(entry) }
                        )
                    }
                }
            }
        }
    }

    preview?.let { entry ->
        val image by produceState<ImageBitmap?>(null, entry.record.file) {
            value = withContext(Dispatchers.IO) {
                IntruderManager.loadDecryptedBitmap(entry.record.file, PREVIEW_PX)?.asImageBitmap()
            }
        }
        AlertDialog(
            onDismissRequest = { preview = null },
            shape = ExpressiveTokens.Shapes.BentoCardLarge,
            title = { Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .clip(ExpressiveTokens.Shapes.BentoCardMedium),
                        contentAlignment = Alignment.Center
                    ) {
                        val bitmap = image
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Intruder photo",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = entry.record.formattedDate,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { delete(entry) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = CircleShape
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Close") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            shape = ExpressiveTokens.Shapes.BentoCardLarge,
            title = { Text("Delete all intruder photos?") },
            text = { Text("This permanently deletes every captured photo. It can't be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        stepUp.require("Delete all intruder photos") {
                            IntruderManager.clearAllIntruders(context)
                            reloadKey++
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = CircleShape
                ) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

@Composable
private fun EmptyVault() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No intruder photos",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "When intruder photos are on and someone fails to unlock a locked app twice, " +
                "BioKey takes a front-camera photo and stores it here, encrypted.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun IntruderTile(entry: IntruderEntry, onClick: () -> Unit, onDelete: () -> Unit) {
    val filePath = entry.record.file.absolutePath
    val thumbnail by produceState<ImageBitmap?>(VaultThumbnailCache.get(filePath), filePath) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                val bmp = IntruderManager.loadDecryptedBitmap(entry.record.file, THUMBNAIL_PX)?.asImageBitmap()
                if (bmp != null) VaultThumbnailCache.put(filePath, bmp)
                bmp
            }
        }
    }

    Surface(
        onClick = onClick,
        shape = ExpressiveTokens.Shapes.BentoCardMedium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val bitmap = thumbnail
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.record.formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
