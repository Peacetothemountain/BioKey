package com.android.BioKey.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.android.BioKey.R

/**
 * The ways this phone's owner can actually open a locked app, in Android's own words where it
 * has them: BioKey otherwise says "your face, fingerprint or screen lock" whatever is set up.
 */
object UnlockMethods {

    /**
     * Android's instruction for the prompt locked apps get (Class 3 face or fingerprint, or the
     * screen lock), naming only what's set up and usable: "Use your fingerprint or screen lock to
     * continue". In the phone's language. Null where Android has nothing to say, or can't be asked.
     */
    @VisibleForTesting
    internal var systemPromptMessage: (Context) -> String? = { context ->
        runCatching {
            context.getSystemService(BiometricManager::class.java)
                ?.getStrings(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                ?.promptMessage
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** [systemPromptMessage]: Android's words for how locked apps open here, or null. */
    fun promptMessage(context: Context): String? = systemPromptMessage(context)

    /** [promptMessage], or [fallback] (BioKey's own, which names every method) without one. */
    fun howLockedAppsOpen(context: Context, fallback: String): String = promptMessage(context) ?: fallback

    /** Whether the phone has a strong sensor, no strong face or fingerprint set up on it, and a screen lock to add one under. */
    @VisibleForTesting
    internal var strongBiometricMissing: (Context) -> Boolean = { context ->
        runCatching {
            context.getSystemService(BiometricManager::class.java)?.canAuthenticate(BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        }.getOrDefault(false)
    }

    /**
     * Home offers to add a face or fingerprint: the hardware is there (Android answers "none
     * enrolled", not "no hardware"), none is set up, and there is a screen lock (without one,
     * adding one is the first thing to do, and home says that instead).
     */
    fun canAddStrongBiometric(context: Context): Boolean =
        strongBiometricMissing(context) && ScreenLockWatch.screenLockSet(context)

    /** "Add a fingerprint", "Add your face", or both: what the phone's sensors are. */
    @StringRes
    fun addBiometricTitle(context: Context): Int {
        val pm = context.packageManager
        val fingerprint = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        val face = pm.hasSystemFeature(PackageManager.FEATURE_FACE)
        return when {
            fingerprint && face -> R.string.card_add_biometric_title_both
            face -> R.string.card_add_biometric_title_face
            else -> R.string.card_add_biometric_title_fingerprint
        }
    }

    @VisibleForTesting
    internal fun resetForTests() {
        systemPromptMessage = { null }
        strongBiometricMissing = { false }
    }
}
