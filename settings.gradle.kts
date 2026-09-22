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

// The wire format and WebSocket implementation shared by the agent app and the host. Pure
// Kotlin, no Android dependency and no dependency on the core SDK, so it configures here
// alongside core and is testable without a device.
include(":andropilot-protocol")
project(":andropilot-protocol").projectDir = file("protocol")

// The telemetry sink. Also pure Kotlin: it only needs AgentEvent, a file and an HTTP POST.
include(":andropilot-telemetry")
project(":andropilot-telemetry").projectDir = file("telemetry")

// The PC-side host: the bridge the phone dials into, and the MCP server in front of it.
include(":andropilot-host")
project(":andropilot-host").projectDir = file("host")

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
    include(":andropilot-agent")
    project(":andropilot-agent").projectDir = file("agent")
    // Its own app so the permission to install packages lives apart from anything that can
    // read or tap a screen.
    include(":andropilot-updater")
    project(":andropilot-updater").projectDir = file("updater")
} else {
    logger.lifecycle(
        "[andropilot] No Android SDK found (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties). " +
            "Configuring :andropilot-core only."
    )
}
