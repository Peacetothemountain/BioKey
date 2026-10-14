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

# 1. Enum persisted by name in SharedPreferences / JSON
-keepclassmembers enum com.android.BioKey.core.data.LockTimeoutMode {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public *;
}

# 2. Data classes used in persistence
-keep class com.android.BioKey.core.data.** { *; }


