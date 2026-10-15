package com.android.BioKey.feature.main

import androidx.compose.ui.graphics.ImageBitmap

enum class AppCategory(val label: String) {
    FINANCE("Banking & Crypto"),
    AUTH("2FA & Security"),
    PRIVACY("Photos & Vault"),
    SOCIAL("Social & Chat"),
    SYSTEM("System"),
    GENERAL("App")
}

data class AppItem(
    val packageName: String,
    val label: String,
    val iconBitmap: ImageBitmap?,
    val category: AppCategory,
    val isWorkProfile: Boolean = false
)
