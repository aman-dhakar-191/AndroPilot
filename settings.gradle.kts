pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "andropilot"

// The pure-Kotlin core carries all perception/matching/verification logic and has no
// Android dependency, so it always builds -- including on machines (and CI jobs) that
// have no Android SDK installed.
include(":andropilot-core")
project(":andropilot-core").projectDir = file("core")

// The Android library + demo app are only wired in when an Android SDK is actually
// available. This keeps `./gradlew build` usable for core development and for JVM-only
// CI, while the release workflow (which installs the SDK) builds everything.
// `takeIf(String::isNotBlank)` matters: CI sets ANDROID_HOME to the empty string to force
// the core-only configuration, and `file("")` resolves to the project directory, which *is*
// a directory -- so a plain null check would wrongly wire in the Android modules.
val androidSdkDir: String? = System.getenv("ANDROID_HOME")?.takeIf(String::isNotBlank)
    ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf(String::isNotBlank)
    ?: file("local.properties")
        .takeIf { it.exists() }
        ?.let { f -> java.util.Properties().apply { f.inputStream().use(::load) }.getProperty("sdk.dir") }
        ?.takeIf(String::isNotBlank)

if (androidSdkDir != null && file(androidSdkDir).isDirectory) {
    include(":andropilot-android")
    project(":andropilot-android").projectDir = file("android")
    include(":andropilot-devtools")
    project(":andropilot-devtools").projectDir = file("devtools")
    include(":demo")
    project(":demo").projectDir = file("demo")
} else {
    logger.lifecycle(
        "[andropilot] No Android SDK found (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties). " +
            "Configuring :andropilot-core only."
    )
}
