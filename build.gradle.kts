// Intentionally no `plugins { ... apply false }` block.
//
// The usual multi-module idiom pins every plugin version at the root, but that would force
// this build to resolve the Android Gradle Plugin even when only :andropilot-core is
// configured -- defeating the point of a core module that builds with no Android SDK.
// Declaring a version at the root *and* in a subproject is also what makes Gradle reject
// the subproject's request ("already on the classpath with an unknown version"), which is
// easy to hit with Kotlin, whose JVM and Android plugins ship in one artifact.
//
// Each module therefore declares its own plugins via the version catalog, which keeps the
// versions in exactly one place: gradle/libs.versions.toml.

group = "com.andropilot"
version = providers.gradleProperty("andropilot.version").getOrElse("0.1.0")

subprojects {
    group = rootProject.group
    version = rootProject.version
}
