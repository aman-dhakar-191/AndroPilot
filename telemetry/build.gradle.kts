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

// Depends on :andropilot-core, and core must never depend on this.
//
// That direction is the whole design. CLAUDE.md says the SDK has no network code, and this
// module is nothing but network code -- so it lives outside, consumes the public
// AgentEventListener seam, and an app that does not want telemetry simply does not add the
// dependency. It is a plain JVM module rather than an Android one because it needs only a
// file and an HTTP POST, both of which exist on Android; that also makes every test here
// run without a device.
dependencies {
    api(project(":andropilot-core"))

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
            artifactId = "andropilot-telemetry"
            from(components["java"])
        }
    }
}
