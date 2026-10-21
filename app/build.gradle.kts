import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystore = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(key: String): String? = releaseKeystore.getProperty(key)?.takeIf { it.isNotBlank() }

// Release builds must never silently fall back to the public debug key. Opt in explicitly
// (e.g. for local testing) with: ./gradlew assembleRelease -Pbiokey.allowDebugSignedRelease=true
val allowDebugSignedRelease = providers.gradleProperty("biokey.allowDebugSignedRelease")
    .map { it.toBoolean() }
    .getOrElse(false)

android {
    namespace = "com.android.BioKey"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "com.android.BioKey"
        minSdk = 31
        targetSdk = 37
        versionCode = 467
        versionName = "4.6"
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile")
            if (store != null) {
                // Relative to the project root, next to key.properties (absolute paths work too).
                storeFile = rootProject.file(store)
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val release = signingConfigs.getByName("release")
            when {
                release.storeFile != null -> signingConfig = release
                allowDebugSignedRelease -> {
                    signingConfig = signingConfigs.getByName("debug")
                    // Marks the version name, so a test build can't pass for a real release.
                    versionNameSuffix = "-debugsigned"
                }
                else -> signingConfig = null // Produces an unsigned APK; configure key.properties to sign.
            }
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            it.maxHeapSize = "3g"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // The dependency list AGP can embed is encrypted for Google Play, which uses it to flag
    // libraries with known vulnerabilities (SDK Index). Keep it in bundles, which go to Play; leave
    // it out of APKs, which are sideloaded and have no use for it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    lint {
        // Lint errors fail lintDebug (which CI runs); warnings don't. The quick check inside
        // assembleRelease only looks at fatal issues, so these are fatal: they catch crashes on
        // older Android versions and components or intents other apps could reach, and a release
        // built locally, without CI, mustn't ship one either.
        abortOnError = true
        checkReleaseBuilds = true
        fatal += listOf(
            "NewApi",
            "UnspecifiedRegisterReceiverFlag",
            "ExportedService",
            "ExportedReceiver",
            "ExportedContentProvider",
            "MutableImplicitPendingIntent",
            "UnsafeImplicitIntentLaunch",
        )
        // Dependabot proposes updates; these would only repeat them, and need the network.
        disable += listOf("AndroidGradlePluginVersion", "NewerVersionAvailable", "GradleDependency")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    // Installs the baseline profile (src/main/baseline-prof.txt) on installs that don't come from
    // Google Play, which installs it itself.
    implementation(libs.androidx.profileinstaller)

    // Screenshot tests: render screens on the JVM (Robolectric + Roborazzi) for design review.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

kotlin {
    jvmToolchain(17)
}

