plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Same version packing as the demo. See demo/build.gradle.kts for why the scheme matters.
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
    namespace = "com.andropilot.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.andropilot.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersion
    }

    signingConfigs {
        create("dev") {
            // The same stable key the demo uses, for the same reason: a per-machine debug
            // key makes every CI build refuse to install over the last one.
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

    // buildConfig is needed for BuildConfig.VERSION_NAME, which the agent reports to the
    // host so a version mismatch is visible from the desk rather than guessed at.
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":andropilot-android"))
    implementation(project(":andropilot-protocol"))
    implementation(project(":andropilot-telemetry"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}
