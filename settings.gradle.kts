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
val androidSdkDir: String? = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties")
        .takeIf { it.exists() }
        ?.let { f -> java.util.Properties().apply { f.inputStream().use(::load) }.getProperty("sdk.dir") }

if (androidSdkDir != null && file(androidSdkDir).isDirectory) {
    include(":andropilot-android")
    project(":andropilot-android").projectDir = file("android")
    include(":demo")
    project(":demo").projectDir = file("demo")
} else {
    logger.lifecycle(
        "[andropilot] No Android SDK found (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties). " +
            "Configuring :andropilot-core only."
    )
}
