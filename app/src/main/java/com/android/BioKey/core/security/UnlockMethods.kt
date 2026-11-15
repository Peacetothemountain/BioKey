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

    /**
     * What a locked app asks for as BioKey is set right now, as the words that finish a sentence:
     * "your face, fingerprint or screen lock"; under "Face or fingerprint only", "your face or
     * fingerprint", with "or your passphrase" where one is set; and "your passphrase" alone where
     * that is all that can be used. For BioKey's own sentences ("Opening it asks for …"), so that
     * none of them names a way in that would be refused.
     */
    fun asksFor(context: Context): String = context.getString(asksForRes(context))

    @StringRes
    fun asksForRes(context: Context): Int {
        val only = runCatching { SecurityVault.getInstance(context).isBiometricOnly() }.getOrDefault(false)
        val passphrase = only && runCatching { OwnerPassphrase.isSet(context) }.getOrDefault(false)
        val usable = SecurityEngine.hasStrongBiometric(context)
        return when {
            passphrase && usable -> R.string.unlock_asks_biometric_or_passphrase
            passphrase -> R.string.unlock_asks_passphrase
            // With none that can be used and no passphrase, the screen lock is let in (and that is recorded).
            only && usable -> R.string.unlock_asks_biometric
            else -> R.string.unlock_asks_any
        }
    }

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

    private val realHooks: () -> Unit = run {
        val systemPromptMessage = systemPromptMessage
        val strongBiometricMissing = strongBiometricMissing
        return@run {
            this.systemPromptMessage = systemPromptMessage
            this.strongBiometricMissing = strongBiometricMissing
        }
    }

    @VisibleForTesting
    internal fun resetForTests() = realHooks()
}
