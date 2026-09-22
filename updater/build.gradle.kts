plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Same version packing as the other apps. See demo/build.gradle.kts for why the scheme
// matters: diverge from it and a newer release looks older than what is installed.
val releaseVersion: String = providers.gradleProperty("andropilot.version").getOrElse("0.1.0")
val releaseVersionCode: Int = releaseVersion
    .substringBefore('-')
    .split('.')
    .map { it.toIntOrNull() ?: 0 }
    .let { parts ->
        parts.getOrElse(0) { 0 } * 10_000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
    }
    .coerceAtLeast(1)

android {
    namespace = "com.andropilot.updater"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.andropilot.updater"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersion
    }

    signingConfigs {
        create("dev") {
            // The same stable key as the other apps, and here it is not merely convenient:
            // Android refuses to replace an app whose signing key changed, so an updater
            // signed differently from what it updates could never install anything.
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

// Depends on the updater library and nothing else. It holds no SDK, no accessibility
// service and no automation code at all -- which is the point of it being its own app:
// the permission to install packages lives here, apart from anything that can drive a
// screen.
dependencies {
    implementation(project(":andropilot-devtools"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}
