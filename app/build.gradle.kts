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
        versionCode = 466
        versionName = "4.5"
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile")
            if (store != null) {
                storeFile = file(store)
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
            signingConfig = when {
                release.storeFile != null -> release
                allowDebugSignedRelease -> signingConfigs.getByName("debug")
                else -> null // Produces an unsigned APK; configure key.properties to sign.
            }
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
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

    // Don't embed the (unencrypted, Google-signed) dependency metadata block in release artifacts.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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

