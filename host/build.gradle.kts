plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Runs on the developer's machine, never on a phone, and depends on :andropilot-protocol
// alone. It has no dependency on :andropilot-core because it never perceives a screen: it
// forwards opaque action documents to the device that does, which is what keeps the host
// swappable and the SDK's action model the only definition of an action.
dependencies {
    implementation(project(":andropilot-protocol"))

    // Test-only: the end-to-end telemetry test drives a real sink into a real ingest
    // server. The host does not depend on telemetry at runtime and must not start to --
    // it is the receiving end, not a producer.
    testImplementation(project(":andropilot-telemetry"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.andropilot.host.MainKt")
    applicationName = "andropilot-host"
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
