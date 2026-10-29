package com.android.BioKey.feature.main

import com.android.BioKey.core.data.LockTimeoutMode

/** How long a delay keeps an app open, for telling a longer one from a shorter: "screen off" is longest. */
internal val LockTimeoutMode.openFor: Long
    get() = if (this == LockTimeoutMode.ON_SCREEN_OFF) Long.MAX_VALUE else durationMillis
