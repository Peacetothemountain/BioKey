package com.android.BioKey.feature.main

import androidx.compose.ui.graphics.ImageBitmap
import com.android.BioKey.core.data.AppCategory

/** A launchable app as shown in BioKey's lists. */
data class AppItem(
    val packageName: String,
    val label: String,
    val iconBitmap: ImageBitmap?,
    val category: AppCategory,
    val isWorkProfile: Boolean = false
)
