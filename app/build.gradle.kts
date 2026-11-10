import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
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

// Self-protection (asking for authentication before BioKey can be switched off or uninstalled)
// is left out of release builds: Google Play's Accessibility API policy forbids preventing users
// from disabling or uninstalling an app outside parental-control and enterprise apps. A release
// for sideloading can opt back in with: ./gradlew assembleRelease -Pbiokey.selfProtection=true
val selfProtectionInRelease = providers.gradleProperty("biokey.selfProtection")
    .map { it.toBoolean() }
    .getOrElse(false)

android {
    namespace = "com.android.BioKey"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "com.nbdesigns.biokey"
        minSdk = 31
        targetSdk = 37
        versionCode = 6
        versionName = "1.0.4"
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
        debug {
            // Self-protection's tests run on debug builds, so they always have it.
            buildConfigField("boolean", "SELF_PROTECTION", "true")
        }
        release {
            buildConfigField("boolean", "SELF_PROTECTION", "$selfProtectionInRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            // Symbol tables for native code in the bundle, so Play can read native crash reports.
            // AndroidX's graphics library comes stripped, so for it there are none to take: Play
            // gets a zip of the libraries themselves (see README, Release builds).
            ndk { debugSymbolLevel = "FULL" }
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
            // Marked too, so a build with self-protection is never mistaken for the Play build.
            if (selfProtectionInRelease) versionNameSuffix = (versionNameSuffix ?: "") + "-selfprotection"
        }
    }

    // What only self-protection needs (the accessibility config's view IDs): in the builds that have it.
    sourceSets.getByName("debug").res.srcDir("src/selfProtection/res")
    if (selfProtectionInRelease) sourceSets.getByName("release").res.srcDir("src/selfProtection/res")

    testOptions {
        unitTests.isIncludeAndroidResources = true

        // The lock screen's gantlet: the unit tests that must pass before anything reaches main.
        // This is the only list of them. -Pbiokey.tests=gantlet runs just these (tools/gantlet_tests.sh,
        // for CI and the pre-push hook), -Pbiokey.tests=others runs everything else (CI, after the
        // gantlet), so a test added here moves from one run to the other and can't run in neither
        // or both.
        val gantlet = listOf(
            "com.android.BioKey.Gantlet*",
            "com.android.BioKey.LockStateTrackerTest",
            "com.android.BioKey.CompatibilityEngineTest",
            "com.android.BioKey.AccessibilityOwnTripTest",
            "com.android.BioKey.ForegroundRulesTest",
            "com.android.BioKey.LockLauncherTest",
            "com.android.BioKey.SessionRulesTest",
            "com.android.BioKey.RecentsGestureTest",
            "com.android.BioKey.OwnSessionTest",
            "com.android.BioKey.EngineReplayTest",
        )
        val testSet = providers.gradleProperty("biokey.tests").getOrElse("all")
        require(testSet in setOf("all", "gantlet", "others")) { "biokey.tests is all, gantlet or others, not $testSet" }
        // The design screenshots (DesignScreenshotTest) are recorded for review, never compared, and
        // render every screen, which is slow: they run only when asked for, with
        // -Pbiokey.screenshots=true (CI does, and keeps the PNGs from app/build/screenshots).
        val screenshots = providers.gradleProperty("biokey.screenshots").map { it.toBoolean() }.getOrElse(false)
        val gantletResults = layout.buildDirectory.dir("test-results/gantlet")
        val gantletReport = layout.buildDirectory.dir("reports/tests/gantlet")

        unitTests.all { test ->
            // Test classes run in parallel JVMs, each with its own copy of BioKey's singletons
            // (SecurityVault, LockStateTracker); classes in one JVM run one after another, so each
            // test resets what it relies on (BioKeyApplication starts every one afresh too).
            // Half the cores, at most four: two on GitHub's four-core runners, where two of these
            // and the Gradle daemon fit in 16 GB, and a desktop isn't swamped. Robolectric keeps a
            // sandbox per Android version in each JVM, which 2 GB holds.
            test.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            // Java 21 runs the tests (the app itself is built for 17): Robolectric needs it for
            // Android 16 and 17, the versions BioKey's owners run.
            test.javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
            // Robolectric reaches into FileDescriptor, through the JDK's own internals, to fake the
            // shared memory of Android 16 and 17: Java 21 allows that only when asked.
            test.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
            test.maxHeapSize = "2g"

            if (screenshots) {
                test.systemProperty("roborazzi.test.record", "true")
            } else {
                test.filter.excludeTestsMatching("com.android.BioKey.DesignScreenshotTest")
            }

            when (testSet) {
                "gantlet" -> {
                    gantlet.forEach(test.filter::includeTestsMatching)
                    // Reports of their own, so the run after the gantlet's doesn't replace them.
                    test.reports.junitXml.outputLocation.set(gantletResults)
                    test.reports.html.outputLocation.set(gantletReport)
                    // Gradle only fails a filter when nothing at all matches, so a renamed or
                    // deleted class would quietly drop out of the gantlet: each entry must have run.
                    test.doLast {
                        val ran = gantletResults.get().asFile.list().orEmpty()
                        val missing = gantlet.filter { entry ->
                            val name = Regex("TEST-" + entry.split("*").joinToString(".*") { Regex.escape(it) } + "\\.xml")
                            ran.none(name::matches)
                        }
                        if (missing.isNotEmpty()) {
                            throw GradleException(
                                "The gantlet lists tests that don't exist (renamed or deleted?): " +
                                    "${missing.joinToString()}. Update the list in app/build.gradle.kts."
                            )
                        }
                    }
                }
                "others" -> gantlet.forEach(test.filter::excludeTestsMatching)
            }
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG keeps the gantlet's latency marks (core/diagnostics/LatencyMarks) out
        // of release builds.
        buildConfig = true
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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    // Installs the baseline profile (src/main/baseline-prof.txt) on installs that don't come from
    // Google Play, which installs it itself.
    implementation(libs.androidx.profileinstaller)
    // Generates the baseline and startup profiles, by hand: ./gradlew :app:generateBaselineProfile
    baselineProfile(project(":baselineprofile"))

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

// The baseline profile plugin's build types (nonMinifiedRelease, benchmarkRelease) are copies of
// release that only ever go to a test device or emulator: signed with the debug key, as the debug
// build the device was set up with is, they install over it and keep its settings and locked apps.
androidComponents {
    onVariants(selector().withBuildType("nonMinifiedRelease")) { it.signingConfig.setConfig(android.signingConfigs.getByName("debug")) }
    onVariants(selector().withBuildType("benchmarkRelease")) { it.signingConfig.setConfig(android.signingConfigs.getByName("debug")) }
}

