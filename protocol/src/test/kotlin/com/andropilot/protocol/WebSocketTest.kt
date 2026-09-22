package com.andropilot.protocol

import com.andropilot.protocol.ws.ProtocolException
import com.andropilot.protocol.ws.WebSocketConnection
import com.andropilot.protocol.ws.WsClient
import com.andropilot.protocol.ws.WsServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The client and the server are tested against each other rather than against a mock.
 *
 * Both halves are hand-written, and the failure mode that matters -- a masking or length
 * bug that only appears above 125 bytes -- is invisible unless real bytes cross a real
 * socket. These tests do that on loopback, so they still need no device and no network.
 */
class WebSocketTest {

    private fun echoServer(token: String = "secret"): WsServer =
        WsServer(
            port = 0,
            authorize = { it.bearerToken == token },
            onConnection = { connection, _ ->
                while (true) {
                    val message = connection.receive() ?: break
                    connection.send(message)
                }
            },
        ).also { it.serveInBackground() }

    @Test
    fun `round-trips a text message`() {
        echoServer().use { server ->
            client(server).use { connection ->
                connection.send("hello")
                assertEquals("hello", connection.receive())
            }
        }
    }

    @Test
    fun `round-trips payloads across every length encoding`() {
        // 125 is the last single-byte length, 126 switches to a 16-bit length, and 70000
        // switches to the 64-bit one. Each boundary has its own off-by-one to get wrong.
        echoServer().use { server ->
            client(server).use { connection ->
                for (size in listOf(0, 1, 125, 126, 127, 65_535, 65_536, 70_000)) {
                    val payload = "x".repeat(size)
                    connection.send(payload)
                    assertEquals(payload, connection.receive(), "failed at length $size")
                }
            }
        }
    }

    @Test
    fun `carries non-ascii text intact`() {
        // Length is counted in bytes, not characters. Emoji catch a codec that confuses them.
        echoServer().use { server ->
            client(server).use { connection ->
                val payload = "settings → Wi-Fi 📶 éè"
                connection.send(payload)
                assertEquals(payload, connection.receive())
            }
        }
    }

    @Test
    fun `refuses a connection without the token`() {
        echoServer().use { server ->
            val failure = assertThrows<ProtocolException> {
                WsClient.connect("ws://127.0.0.1:${server.port}/agent")
            }
            assertTrue(failure.message!!.contains("401"), "expected a 401, got: ${failure.message}")
        }
    }

    @Test
    fun `refuses a connection with the wrong token`() {
        echoServer(token = "right").use { server ->
            assertThrows<ProtocolException> {
                WsClient.connect(
                    "ws://127.0.0.1:${server.port}/agent",
                    headers = mapOf("Authorization" to "Bearer wrong"),
                )
            }
        }
    }

    @Test
    fun `accepts a token from the query string`() {
        echoServer().use { server ->
            WsClient.connect("ws://127.0.0.1:${server.port}/agent?token=secret").use { connection ->
                connection.send("ping")
                assertEquals("ping", connection.receive())
            }
        }
    }

    @Test
    fun `reports a clean close as an end of stream rather than an error`() {
        val connections = ArrayBlockingQueue<WebSocketConnection>(1)
        WsServer(
            port = 0,
            authorize = { true },
            onConnection = { connection, _ ->
                connections.put(connection)
                // Hold the handler open; the test closes the connection explicitly.
                Thread.sleep(2_000)
            },
        ).use { server ->
            server.serveInBackground()
            val client = WsClient.connect("ws://127.0.0.1:${server.port}/agent")
            val serverSide = connections.poll(5, TimeUnit.SECONDS)!!
            serverSide.close()
            assertNull(client.receive())
        }
    }

    @Test
    fun `a keep-alive ping is answered and disturbs nothing`() {
        // The agent sends one of these every 25 seconds so an idle socket is not reclaimed
        // by a router or the phone's radio. It must be invisible to the message flow: a
        // pong surfacing as a message, or a ping interrupting a read, would be worse than
        // the dropped connection it exists to prevent.
        echoServer().use { server ->
            client(server).use { connection ->
                connection.send("before")
                assertEquals("before", connection.receive())

                repeat(3) { connection.ping() }

                connection.send("after")
                assertEquals("after", connection.receive())
            }
        }
    }

    @Test
    fun `a ping arriving mid-conversation is answered by the peer`() {
        // The server answers a ping itself, inside receive(), without the handler above it
        // ever being told. Proven by the echo server -- which only ever handles text --
        // continuing to work while pings cross in both directions.
        val received = java.util.concurrent.ArrayBlockingQueue<String>(4)
        WsServer(
            port = 0,
            authorize = { true },
            onConnection = { connection, _ ->
                while (true) {
                    val message = connection.receive() ?: break
                    received.put(message)
                    connection.ping()
                    connection.send(message)
                }
            },
        ).use { server ->
            server.serveInBackground()
            WsClient.connect("ws://127.0.0.1:${server.port}/agent", readTimeoutMs = 10_000).use { client ->
                client.ping()
                client.send("hello")
                assertEquals("hello", client.receive())
                assertEquals("hello", received.poll(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `frames round-trip through the protocol codec`() {
        val frames = listOf(
            Frame.Hello(PROTOCOL_VERSION, "pixel", "0.1.0"),
            Frame.ActionRequest("7", """{"type":"observe"}"""),
            Frame.ActionResponse("7", """{"type":"success"}"""),
            Frame.Event("""{"kind":"note"}"""),
            Frame.Error(null, "boom"),
        )
        for (frame in frames) {
            assertEquals(frame, ProtocolJson.decode(ProtocolJson.encode(frame)))
        }
    }

    @Test
    fun `carries frames over the socket`() {
        echoServer().use { server ->
            client(server).use { connection ->
                connection.send(ProtocolJson.encode(Frame.ActionRequest("1", """{"type":"observe"}""")))
                val echoed = ProtocolJson.decode(connection.receive()!!)
                assertEquals(Frame.ActionRequest("1", """{"type":"observe"}"""), echoed)
            }
        }
    }

    private fun client(server: WsServer): WebSocketConnection = WsClient.connect(
        "ws://127.0.0.1:${server.port}/agent",
        headers = mapOf("Authorization" to "Bearer secret"),
        readTimeoutMs = 10_000,
    )
}
