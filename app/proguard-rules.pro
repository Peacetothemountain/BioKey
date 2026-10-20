# BioKey R8 rules

# Strip verbose/debug/info/warning logging from release builds: those messages can name locked
# apps, and logcat ends up in bug reports and adb sessions. Errors stay for diagnosis, so Log.e
# never names an app.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# Readable crash line numbers without exposing source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
