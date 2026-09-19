package com.andropilot.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

class IngestServerTest {

    private fun post(port: Int, body: String, token: String): Int {
        val connection = (URI("http://127.0.0.1:$port/ingest").toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-AndroPilot-Run", "run-1")
            setRequestProperty("X-AndroPilot-Device", "device-1")
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        return connection.responseCode
    }

    @Test
    fun `stores a batch as json lines tagged with its run`(@TempDir dir: File) {
        IngestServer(0, dir, "secret").start().use { server ->
            assertEquals(200, post(server.port, "{\"kind\":\"note\"}\n{\"kind\":\"action\"}\n", "secret"))
            val written = dir.listFiles()!!.single().readLines()
            assertEquals(2, written.size)
            assertTrue(written[0].contains("\"run\":\"run-1\""))
            assertTrue(written[0].contains("\"device\":\"device-1\""))
            assertTrue(written[0].contains("\"record\":{\"kind\":\"note\"}"))
            assertEquals(1, server.accepted)
        }
    }

    @Test
    fun `rejects a batch with the wrong token and writes nothing`(@TempDir dir: File) {
        IngestServer(0, dir, "secret").start().use { server ->
            assertEquals(401, post(server.port, "{}\n", "wrong"))
            assertTrue(dir.listFiles()!!.isEmpty())
        }
    }
}
