plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

// Generates BioKey's baseline and startup profiles (BaselineProfileGenerator) on a device or
// emulator prepared as the gantlet prepares one. By hand only, never in CI or a build:
//   ./gradlew :app:generateBaselineProfile
android {
    namespace = "com.android.BioKey.baselineprofile"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        minSdk = 31
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    // The device or emulator connected now; no Gradle-managed one, which couldn't be set up the way
    // the generator needs (BioKey set up, its engine on, an app locked).
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

kotlin {
    jvmToolchain(17)
}
