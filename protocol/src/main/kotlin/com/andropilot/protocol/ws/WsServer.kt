package com.andropilot.protocol.ws

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What a server learned about a connecting client before accepting it. */
public data class HandshakeRequest(
    val path: String,
    val headers: Map<String, String>,
    val remoteAddress: String,
) {
    /** The bearer token, from `Authorization: Bearer ...` or a `?token=` query parameter. */
    public val bearerToken: String?
        get() = headers["authorization"]?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
            ?: path.substringAfter("token=", "").substringBefore('&').takeIf { it.isNotEmpty() }
}

/**
 * Accepts WebSocket connections.
 *
 * Runs on the host machine, never on the phone. Deliberately single-purpose: it upgrades a
 * connection and hands the caller a [WebSocketConnection]; everything about what travels
 * over it belongs to the layer above.
 *
 * [authorize] runs before the upgrade is granted, so a request with a wrong or missing
 * token never becomes a socket. A connection here can drive an accessibility service, which
 * makes it the most dangerous surface in the project -- an unauthenticated bind is not an
 * option, and the constructor refuses one.
 */
public class WsServer(
    port: Int,
    /** Loopback by default. Binding to every interface is an explicit choice, not a default. */
    bindAddress: String = "127.0.0.1",
    private val authorize: (HandshakeRequest) -> Boolean,
    private val onConnection: (WebSocketConnection, HandshakeRequest) -> Unit,
) : AutoCloseable {

    private val serverSocket = ServerSocket(port, 16, InetAddress.getByName(bindAddress))
    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "andropilot-ws").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(true)

    /** The port actually bound. Useful when the caller passed 0 to get an ephemeral one. */
    public val port: Int get() = serverSocket.localPort

    /** Accepts connections until [close]. Blocks the calling thread. */
    public fun serve() {
        while (running.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (e: Exception) {
                if (running.get()) continue else return
            }
            workers.execute { handle(socket) }
        }
    }

    /** Accepts connections on a background thread and returns immediately. */
    public fun serveInBackground(): Thread =
        Thread({ serve() }, "andropilot-ws-accept").apply {
            isDaemon = true
            start()
        }

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        val request = try {
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(' ').getOrElse(1) { "/" }
            HandshakeRequest(
                path = path,
                headers = WsClient.readHeaders(reader),
                remoteAddress = socket.inetAddress?.hostAddress ?: "unknown",
            )
        } catch (e: Exception) {
            runCatching { socket.close() }
            return
        }

        val key = request.headers["sec-websocket-key"]
        if (key == null || !request.headers["upgrade"].equals("websocket", ignoreCase = true)) {
            respond(socket, "400 Bad Request", "Expected a WebSocket upgrade.")
            return
        }
        if (!authorize(request)) {
            respond(socket, "401 Unauthorized", "Bad or missing token.")
            return
        }

        try {
            socket.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: ${acceptFor(key)}\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                )
                flush()
            }
        } catch (e: Exception) {
            runCatching { socket.close() }
            return
        }

        val connection = WebSocketConnection(socket, maskOutgoing = false)
        try {
            onConnection(connection, request)
        } finally {
            runCatching { connection.close() }
        }
    }

    private fun respond(socket: Socket, status: String, body: String) {
        runCatching {
            socket.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 $status\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\n" +
                            "Connection: close\r\n\r\n" + body
                        ).toByteArray(Charsets.US_ASCII),
                )
                flush()
            }
        }
        runCatching { socket.close() }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
        workers.shutdownNow()
    }
}
