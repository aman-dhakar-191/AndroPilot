plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The release version, shared with the library modules. versionCode has to be a single
// increasing integer, so the three parts are packed into one: 1.2.3 -> 10203. That keeps
// ordering correct as long as minor and patch stay below 100.
//
// AppUpdater.versionCodeOf encodes the same scheme, because the updater has to derive a
// comparable number from a release tag. If these two ever disagree, a newer release looks
// older than what is installed and the update is silently never offered.
val releaseVersion: String = providers.gradleProperty("andropilot.version").getOrElse("0.1.0")
val releaseVersionCode: Int = releaseVersion
    .substringBefore('-')
    .split('.')
    .map { it.toIntOrNull() ?: 0 }
    .let { parts ->
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        major * 10_000 + minor * 100 + patch
    }
    .coerceAtLeast(1)

android {
    namespace = "com.andropilot.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.andropilot.demo"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersion
    }

    signingConfigs {
        create("dev") {
            // A STABLE key, checked into the repository on purpose.
            //
            // AGP's default `debug` config generates ~/.android/debug.keystore on demand, so
            // every fresh CI runner signs with a different key. Android refuses to update an
            // app whose signature changed, which is why installing a newer build over an
            // older one failed with "App not installed as package conflicts with an existing
            // package". A shared key makes builds upgradable over each other.
            //
            // It protects nothing and is not a release key: the password is in this file and
            // Android's own default debug key is public too. Set ANDROPILOT_KEYSTORE and
            // friends to sign with a real one.
            val override = System.getenv("ANDROPILOT_KEYSTORE")
            storeFile = if (override != null) file(override) else
                rootProject.file("keystore/andropilot-dev.keystore")
            storePassword = System.getenv("ANDROPILOT_KEYSTORE_PASSWORD") ?: "andropilot"
            keyAlias = System.getenv("ANDROPILOT_KEY_ALIAS") ?: "andropilot-dev"
            keyPassword = System.getenv("ANDROPILOT_KEY_PASSWORD") ?: "andropilot"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dev")
        }
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":andropilot-android"))
    implementation(project(":andropilot-devtools"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
