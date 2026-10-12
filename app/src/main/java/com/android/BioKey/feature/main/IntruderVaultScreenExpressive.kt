package com.android.BioKey.feature.main

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.BioKey.R
import com.android.BioKey.core.intruder.IntruderManager
import com.android.BioKey.ui.theme.ExpressiveTokens
import com.android.BioKey.ui.theme.expressiveClickable
import com.android.BioKey.ui.theme.springPress
import kotlin.math.cos
import kotlin.math.sin

/**
 * Modern Material 3 Expressive Intruder Vault Screen.
 * Features:
 * - Dynamic Canvas Radar Sweep Sentinel
 * - Tonal Bento Evidence Cards with high-contrast badge metadata
 * - Spring-compressed touch interactions
 * - Fluid preview dialog with cryptographic evidence tags
 */
@Composable
fun IntruderVaultScreenExpressive() {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<IntruderManager.IntruderRecord>>(emptyList()) }
    var selectedRecordForPreview by remember { mutableStateOf<IntruderManager.IntruderRecord?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun refreshRecords() {
        records = IntruderManager.getIntruderRecords(context)
    }

    LaunchedEffect(Unit) {
        refreshRecords()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Living Dynamic Radar Sentinel Bento Hero
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = ExpressiveTokens.Shapes.BentoCardLarge,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    // Mini Active Canvas Radar Scanner
                    RadarScannerIndicator(
                        modifier = Modifier.size(52.dp),
                        radarColor = MaterialTheme.colorScheme.tertiary
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = "INTRUSION RADAR SENTINEL",
                            style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 1.2.sp,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            ),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = "${records.size} Intercepts Preserved",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Silent Camera2 trap armed on 2+ failed attempts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (records.isNotEmpty()) {
                    Surface(
                        onClick = { showClearConfirm = true },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .size(40.dp)
                            .springPress()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_trashcan_outline),
                                contentDescription = "Clear All",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        if (records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 64.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(92.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "Vault Empty",
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "No Breaches Detected",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your device security is active.\nIf an unauthorized intruder fails authentication repeatedly,\na stealth front-facing capture will be logged here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = {
                            ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CONFIRM_SUCCESS)
                            IntruderManager.captureIntruderPhoto(context, "Test Security Trigger")
                            Toast.makeText(context, "Triggered Camera2 silent capture test", Toast.LENGTH_SHORT).show()
                            refreshRecords()
                        },
                        shape = CircleShape,
                        modifier = Modifier.springPress()
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Test Camera2 Silent Trap",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp)
            ) {
                items(records, key = { it.file.absolutePath }) { record ->
                    ExpressiveIntruderTile(
                        record = record,
                        onDelete = {
                            ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.REJECT_ERROR)
                            IntruderManager.deleteIntruderRecord(record)
                            refreshRecords()
                        },
                        onClick = {
                            ExpressiveTokens.triggerHaptic(context, ExpressiveTokens.HapticFeedbackStyle.CLICK_SUBTLE)
                            selectedRecordForPreview = record
                        }
                    )
                }
            }
        }
    }

    // Detail Evidence Dialog
    selectedRecordForPreview?.let { record ->
        val bitmap = remember(record.file) {
            IntruderManager.loadDecryptedBitmap(context, record.file)
        }

        AlertDialog(
            onDismissRequest = { selectedRecordForPreview = null },
            shape = ExpressiveTokens.Shapes.BentoCardLarge,
            title = {
                Text(
                    text = "Breach Evidence Record",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Intruder Evidence",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .clip(ExpressiveTokens.Shapes.BentoCardMedium),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        shape = ExpressiveTokens.Shapes.BentoCardSmall,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "TARGET PACKAGE: ${record.packageName}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "TIMESTAMP: ${record.formattedDate}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        IntruderManager.deleteIntruderRecord(record)
                        selectedRecordForPreview = null
                        refreshRecords()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = CircleShape,
                    modifier = Modifier.springPress()
                ) {
                    Text("Delete Record")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { selectedRecordForPreview = null },
                    modifier = Modifier.springPress()
                ) {
                    Text("Close", color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    // Clear All Confirmation
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            shape = ExpressiveTokens.Shapes.BentoCardLarge,
            title = { Text("Purge Evidence Vault?", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Text(
                    "This will permanently delete all captured intruder photos and telemetry records from secure storage.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        IntruderManager.clearAllIntruders(context)
                        showClearConfirm = false
                        refreshRecords()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = CircleShape,
                    modifier = Modifier.springPress()
                ) {
                    Text("Purge All")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirm = false },
                    modifier = Modifier.springPress()
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

/**
 * Animated Canvas Radar Scanner Component.
 */
@Composable
private fun RadarScannerIndicator(
    modifier: Modifier = Modifier,
    radarColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar_spin")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_angle"
    )

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Concentric Range Rings
        drawCircle(
            color = radarColor.copy(alpha = 0.15f),
            radius = radius * 0.95f,
            center = center,
            style = Stroke(width = 1.5f)
        )
        drawCircle(
            color = radarColor.copy(alpha = 0.20f),
            radius = radius * 0.60f,
            center = center,
            style = Stroke(width = 1.2f)
        )
        drawCircle(
            color = radarColor.copy(alpha = 0.25f),
            radius = radius * 0.25f,
            center = center,
            style = Stroke(width = 1f)
        )

        // Radar Sweep Line
        val rad = Math.toRadians(angle.toDouble())
        val endX = center.x + radius * cos(rad).toFloat()
        val endY = center.y + radius * sin(rad).toFloat()

        drawLine(
            color = radarColor.copy(alpha = 0.85f),
            start = center,
            end = Offset(endX, endY),
            strokeWidth = 2f
        )

        // Blip dot at center
        drawCircle(
            color = radarColor,
            radius = 3.5f,
            center = center
        )
    }
}

/**
 * Expressive Evidence Tile Component.
 */
@Composable
private fun ExpressiveIntruderTile(
    record: IntruderManager.IntruderRecord,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember(record.file) {
        IntruderManager.loadDecryptedBitmap(context, record.file)
    }

    Surface(
        onClick = onClick,
        shape = ExpressiveTokens.Shapes.BentoCardMedium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .springPress(pressScale = 0.98f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Intruder Thumbnail",
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
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.packageName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = record.formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(36.dp)
                    .springPress()
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
