# BioKey R8 ProGuard Configuration

# Strip verbose/debug/info logging from release builds.
# Warnings and errors are kept for diagnosing failures.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep line numbers for readable crash reports, but hide original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 1. Enums used in JSON serialization, SharedPreferences, and reflection.
# Prevents R8 from mangling enum names (LockTimeoutMode, Type, AppCategory, HapticFeedbackStyle)
-keepclassmembers enum com.android.BioKey.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public *;
}

# 2. Cryptographic models, Keystore payloads, and Data classes
-keep class com.android.BioKey.core.security.HardwareKeyManager$EncryptedPayload { *; }
-keep class com.android.BioKey.core.security.HardwareKeyManager$ReKeyProof { *; }
-keep class com.android.BioKey.core.security.SecurityEventLog$Event { *; }
-keep class com.android.BioKey.core.data.** { *; }

# 3. AndroidX BiometricPrompt and Crypto
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# 4. CameraX subsystem
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
