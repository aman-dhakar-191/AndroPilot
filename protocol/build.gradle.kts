plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

// Deliberately does NOT depend on :andropilot-core.
//
// This module is the wire: frame shapes and a WebSocket implementation. The SDK must stay
// free of network code, so the dependency can only ever point this way -- the agent app
// depends on both and maps between them. Keeping core out also means the PC-side host can
// use this module without pulling in a perception engine it never runs.
//
// It has no third-party dependencies beyond serialization on purpose: it is compiled for
// plain JVM but also runs on Android, which has no `java.net.http`, so the WebSocket
// implementation is built on `java.net.Socket` and works identically in both places.
dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "andropilot-protocol"
            from(components["java"])
        }
    }
}
