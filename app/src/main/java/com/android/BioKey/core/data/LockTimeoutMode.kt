package com.android.BioKey.core.data

enum class LockTimeoutMode(val title: String, val description: String, val durationMillis: Long) {
    IMMEDIATE(
        title = "Immediately",
        description = "Re-locks as soon as app is closed or switched away",
        durationMillis = 0L
    ),
    ON_SCREEN_OFF(
        title = "When Screen Turns Off",
        description = "Stays unlocked until your device display sleeps",
        durationMillis = -1L
    ),
    GRACE_30_SECONDS(
        title = "30 Seconds Grace Period",
        description = "Allows switching apps briefly without re-prompting",
        durationMillis = 30_000L
    ),
    GRACE_1_MINUTE(
        title = "1 Minute Grace Period",
        description = "Convenient for multitasking across applications",
        durationMillis = 60_000L
    ),
    GRACE_5_MINUTES(
        title = "5 Minutes Grace Period",
        description = "Locks after 5 minutes of inactivity",
        durationMillis = 300_000L
    ),
    GRACE_15_MINUTES(
        title = "15 Minutes Grace Period",
        description = "Locks after 15 minutes of inactivity",
        durationMillis = 900_000L
    );

    val label: String get() = title
    val isGracePeriod: Boolean get() = durationMillis > 0L

    companion object {
        fun fromName(name: String?): LockTimeoutMode = entries.find { it.name == name } ?: IMMEDIATE
    }
}
