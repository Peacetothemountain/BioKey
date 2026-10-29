package com.android.BioKey.feature.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * For work a screen starts but mustn't stop halfway when the screen goes (clearing records, then
 * offering Undo): lives as long as BioKey's process.
 */
internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
