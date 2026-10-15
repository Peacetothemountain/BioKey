package com.android.BioKey.core.data

/** How soon an unlocked app locks again after it leaves the screen. */
enum class LockTimeoutMode(val durationMillis: Long) {
    IMMEDIATE(0L),
    ON_SCREEN_OFF(-1L),
    GRACE_30_SECONDS(30_000L),
    GRACE_1_MINUTE(60_000L),
    GRACE_5_MINUTES(300_000L),
    GRACE_15_MINUTES(900_000L);

    companion object {
        fun fromName(name: String?): LockTimeoutMode = entries.find { it.name == name } ?: IMMEDIATE
    }
}
