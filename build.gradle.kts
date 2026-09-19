plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = "com.andropilot"
version = providers.gradleProperty("andropilot.version").getOrElse("0.1.0")

subprojects {
    group = rootProject.group
    version = rootProject.version
}
