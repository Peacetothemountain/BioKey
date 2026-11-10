package com.android.BioKey

import com.android.BioKey.core.data.AppCategory
import com.android.BioKey.feature.main.AppItem
import com.android.BioKey.feature.main.AppOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's list: locked first, then recently used, then A–Z, as Android's own App lock lists apps. */
class AppOrderTest {

    private fun app(pkg: String, label: String) = AppItem(pkg, label, AppCategory.GENERAL)

    private val bank = app("com.example.bank", "Bank")
    private val chat = app("com.example.chat", "chat")
    private val maps = app("com.example.maps", "Maps")
    private val zoo = app("com.example.zoo", "Zoo")
    private val apps = listOf(bank, chat, maps, zoo)

    @Test
    fun withoutUsageItsLockedFirstThenAToZ() {
        val order = AppOrder.order(apps, lockedFirst = { it == zoo.packageName }, lastUsed = emptyMap())
        assertEquals(listOf("Zoo", "Bank", "chat", "Maps"), order.map { it.label })
    }

    @Test
    fun recentlyUsedComesBeforeTheRestInEachGroup() {
        val used = mapOf(maps.packageName to 2_000L, chat.packageName to 3_000L, bank.packageName to 1_000L)
        val order = AppOrder.order(apps, lockedFirst = { it == bank.packageName || it == zoo.packageName }, lastUsed = used)
        // Locked: Bank (used) before Zoo (not). Not locked: chat (most recent), then Maps.
        assertEquals(listOf("Bank", "Zoo", "chat", "Maps"), order.map { it.label })
    }
}
