package com.andropilot.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Precedence is the whole value of a settings file, and the only part of it that can be
 * wrong without looking wrong: a file that quietly overrode an explicit flag, or a flag
 * that quietly ignored the file, both start a host nobody asked for.
 */
class SettingsTest {

    private fun file(dir: File, json: String) = File(dir, "settings.json").apply { writeText(json) }

    @Test
    fun `the file supplies what the command line left out`(@TempDir dir: File) {
        val settings = HostSettings.read(
            file(
                dir,
                """
                {
                  "port": 9000,
                  "bind": "0.0.0.0",
                  "token": "from-file",
                  "uiPort": 8080,
                  "modelEndpoint": "http://localhost:4000/v1",
                  "modelId": "some/model",
                  "apiKey": "sk-file"
                }
                """.trimIndent(),
            ),
        )
        val options = parse(emptyArray()).withDefaultsFrom(settings)

        assertEquals(9000, options.port)
        assertEquals("0.0.0.0", options.bind)
        assertEquals("from-file", options.token)
        assertEquals(8080, options.uiPort)
        assertEquals("http://localhost:4000/v1", options.modelEndpoint)
        assertEquals("some/model", options.model)
        assertEquals("sk-file", options.modelKey)
    }

    @Test
    fun `the command line wins over the file`(@TempDir dir: File) {
        val settings = HostSettings.read(
            file(dir, """{"port": 9000, "bind": "0.0.0.0", "token": "from-file", "modelId": "file/model"}"""),
        )
        val options = parse(
            arrayOf("--port", "7000", "--token", "from-flag", "--model", "flag/model"),
        ).withDefaultsFrom(settings)

        assertEquals(7000, options.port)
        assertEquals("from-flag", options.token)
        assertEquals("flag/model", options.model)
        // Not given on the command line, so the file still supplies it.
        assertEquals("0.0.0.0", options.bind)
    }

    @Test
    fun `an absent file changes nothing`(@TempDir dir: File) {
        val settings = HostSettings.read(File(dir, "does-not-exist.json"))
        val options = parse(emptyArray()).withDefaultsFrom(settings)
        val untouched = parse(emptyArray())

        assertEquals(untouched.port, options.port)
        assertEquals(untouched.bind, options.bind)
        assertNull(options.token)
        assertNull(options.modelEndpoint)
    }

    @Test
    fun `a malformed file is refused rather than ignored`(@TempDir dir: File) {
        // Falling back to defaults would start the host on a different port with a freshly
        // invented token, and the only symptom would be a phone that stopped connecting.
        val broken = file(dir, """{"port": "not a number"}""")
        val failure = assertThrows<IllegalStateException> { HostSettings.read(broken) }
        assertTrue(failure.message!!.contains("not valid settings JSON"), failure.message)
    }

    @Test
    fun `unknown keys are tolerated so an older host reads a newer file`(@TempDir dir: File) {
        val settings = HostSettings.read(file(dir, """{"port": 9000, "somethingAddedLater": true}"""))
        assertEquals(9000, settings.port)
    }

    @Test
    fun `an empty file is treated as no settings, not as broken`(@TempDir dir: File) {
        assertEquals(HostSettings(), HostSettings.read(file(dir, "")))
    }

    @Test
    fun `what it writes is what it reads`(@TempDir dir: File) {
        val target = File(dir, "nested/settings.json")
        val original = HostSettings(
            port = 8765,
            bind = "0.0.0.0",
            token = "abc",
            uiPort = 8080,
            modelEndpoint = "http://localhost:4000/v1",
            modelId = "some/model",
            apiKey = "sk-test",
        )
        HostSettings.write(target, original)

        assertTrue(target.isFile, "the parent directory should have been created")
        assertEquals(original, HostSettings.read(target))
    }

    @Test
    fun `a file written before the rename still configures the model`(@TempDir dir: File) {
        val options = Options().withDefaultsFrom(
            HostSettings.read(
                file(dir, """{"model": "old/model", "modelKey": "sk-old"}"""),
            ),
        )

        assertEquals("old/model", options.model)
        assertEquals("sk-old", options.modelKey)
    }
}
