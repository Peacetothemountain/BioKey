pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Downloads the Java a build asks for when this computer doesn't have it (the unit tests run
    // on Java 21, which Robolectric needs for Android 16 and 17).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BioKey"
include(":app")
// The baseline profile generator: run by hand on a device or emulator, never by a build.
include(":baselineprofile")
