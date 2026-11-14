// Apps that open the way real apps do (launch screens, sign-in hops, permission requests, apps
// that keep reopening themselves), for tools/device_gantlet.py to lock and open on a test device or
// an emulator. A build of its own, not part of BioKey's:
//   ./gradlew -p tools/gantlet-targets assembleDebug
pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "gantlet-targets"
include(":app")
