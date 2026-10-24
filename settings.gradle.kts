pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
