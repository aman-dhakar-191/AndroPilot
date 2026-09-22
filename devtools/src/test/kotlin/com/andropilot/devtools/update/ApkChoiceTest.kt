package com.andropilot.devtools.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The regression these exist for: a release grew a second APK and the updater, which took
 * the first one ending in `.apk`, quietly started installing the wrong application.
 */
class ApkChoiceTest {

    private fun asset(name: String) = ApkCandidate(name, "https://example.test/$name", 1)

    /** The assets in the order the GitHub API actually returns them: sorted by name. */
    private val release = listOf(
        asset("andropilot-agent-1.0.0.apk"),
        asset("andropilot-android-1.0.0.aar"),
        asset("andropilot-core-1.0.0.jar"),
        asset("andropilot-demo-1.0.0.apk"),
        asset("andropilot-devtools-1.0.0.aar"),
        asset("andropilot-host-1.0.0.zip"),
    )

    @Test
    fun `picks the app's own apk rather than whichever sorts first`() {
        val chosen = chooseApk(release, hint = "andropilot-demo")
        assertEquals(ApkChoice.Chosen(asset("andropilot-demo-1.0.0.apk")), chosen)

        // The bug, stated as a test: without the hint the first .apk is the agent's, and
        // the inspector installed it over itself for several releases.
        assertEquals("andropilot-agent-1.0.0.apk", release.first { it.name.endsWith(".apk") }.name)
    }

    @Test
    fun `refuses to choose between two apks rather than guessing`() {
        // Guessing here installs a different application. Both apps share a signing key, so
        // the wrong install succeeds and nothing anywhere reports a problem.
        val choice = chooseApk(release, hint = null)
        assertTrue(choice is ApkChoice.Ambiguous, "expected a refusal, got $choice")
        assertEquals(
            listOf("andropilot-agent-1.0.0.apk", "andropilot-demo-1.0.0.apk"),
            (choice as ApkChoice.Ambiguous).candidates,
        )
    }

    @Test
    fun `a single apk still needs no configuration`() {
        // The case the original rule was written for, and it has to keep working: a
        // one-app repository should not have to configure anything.
        val single = listOf(asset("andropilot-demo-1.0.0.apk"), asset("andropilot-core-1.0.0.jar"))
        assertEquals(ApkChoice.Chosen(asset("andropilot-demo-1.0.0.apk")), chooseApk(single, hint = null))
    }

    @Test
    fun `reports nothing when the release has no apk at all`() {
        val jarsOnly = listOf(asset("andropilot-core-1.0.0.jar"), asset("andropilot-host-1.0.0.zip"))
        assertEquals(ApkChoice.None, chooseApk(jarsOnly, hint = null))
        assertEquals(ApkChoice.None, chooseApk(jarsOnly, hint = "andropilot-demo"))
    }

    @Test
    fun `reports nothing when the hint matches no apk`() {
        // A typo in the configured name must not silently fall back to some other app's APK.
        assertEquals(ApkChoice.None, chooseApk(release, hint = "andropilot-inspector"))
    }

    @Test
    fun `a hint that matches several is still a refusal`() {
        val many = listOf(asset("andropilot-demo-1.0.0.apk"), asset("andropilot-demo-beta-1.0.0.apk"))
        val choice = chooseApk(many, hint = "andropilot-demo")
        assertTrue(choice is ApkChoice.Ambiguous, "expected a refusal, got $choice")
    }

    @Test
    fun `matches case-insensitively, since asset names are not a contract`() {
        val odd = listOf(asset("AndroPilot-Demo-1.0.0.APK"), asset("andropilot-agent-1.0.0.apk"))
        assertEquals(
            ApkChoice.Chosen(asset("AndroPilot-Demo-1.0.0.APK")),
            chooseApk(odd, hint = "andropilot-demo"),
        )
    }
}
