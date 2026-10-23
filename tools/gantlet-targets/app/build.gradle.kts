plugins {
    id("com.android.application")
}

/**
 * One app per way of opening, each its own package so BioKey locks (and cools down) each apart:
 * com.biokey.gantlet.<flavor>. The launch screen reads [SCENARIO] to know what to do.
 */
android {
    namespace = "com.biokey.gantlet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.biokey.gantlet"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    flavorDimensions += "scenario"
    productFlavors {
        // Opens its main screen at once: nothing but the launch screen ever comes up first.
        create("plain") { scenario("plain", "Gantlet Plain") }
        // A launch screen that opens the main one after 300 ms, as X does: over the lock screen.
        create("splash") { scenario("splash", "Gantlet Splash") }
        // The same after 1.5 s: the lock screen and its prompt are up by then.
        create("late") { scenario("late", "Gantlet Late") }
        // Launch screen, then a sign-in screen, then the main one, 500 ms apart.
        create("chain") { scenario("chain", "Gantlet Chain") }
        // The main screen in a task of its own (another task affinity).
        create("newtask") { scenario("newtask", "Gantlet NewTask") }
        // Asks for a permission as it starts: the system's dialog comes over the lock screen.
        create("permission") { scenario("permission", "Gantlet Permission") }
        // Reopens itself every 700 ms, for ever: never shown, and given up on (Home) in the end.
        create("loop") { scenario("loop", "Gantlet Loop") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

fun com.android.build.api.dsl.ApplicationProductFlavor.scenario(name: String, label: String) {
    dimension = "scenario"
    applicationIdSuffix = ".$name"
    buildConfigField("String", "SCENARIO", "\"$name\"")
    resValue("string", "app_name", label)
}
