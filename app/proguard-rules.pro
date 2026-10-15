# BioKey R8 rules

# Strip verbose/debug/info logging from release builds; warnings and errors stay for diagnosis.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Readable crash line numbers without exposing source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Re-lock delays are persisted by enum constant name.
-keepclassmembers enum com.android.BioKey.core.data.LockTimeoutMode {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
