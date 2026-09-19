package com.andropilot.protocol.ws

import java.io.BufferedReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocketFactory

/**
 * Dials a WebSocket endpoint.
 *
 * The phone is always the side that dials. Behind carrier NAT a device has no inbound
 * route, its address changes, and Doze will suspend a listening socket -- so a host that
 * tried to connect *to* the phone would work on one Wi-Fi network and nowhere else. The
 * phone holding an outbound connection also makes the endpoint a plain piece of
 * configuration, which is what makes the host location swappable.
 */
public object WsClient {

    /**
     * Opens a connection and completes the handshake.
     *
     * [sslSocketFactory] exists for the common self-hosted case: a model on a home machine
     * behind a self-signed certificate. Passing a factory that trusts exactly that
     * certificate is pinning, and is meaningfully safer than trusting a LAN address; a
     * bearer token on an unverified TLS connection can be handed to whoever answered.
     */
    public fun connect(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 0,
        sslSocketFactory: SSLSocketFactory? = null,
    ): WebSocketConnection {
        val uri = URI(url)
        val secure = when (uri.scheme?.lowercase()) {
            "wss" -> true
            "ws" -> false
            else -> throw IllegalArgumentException("Endpoint must start with ws:// or wss:// but was '$url'")
        }
        val host = uri.host ?: throw IllegalArgumentException("Endpoint has no host: '$url'")
        val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }

        val socket = if (secure) {
            (sslSocketFactory ?: SSLSocketFactory.getDefault() as SSLSocketFactory).let { factory ->
                val plain = Socket()
                plain.connect(InetSocketAddress(host, port), connectTimeoutMs)
                factory.createSocket(plain, host, port, true)
            }
        } else {
            Socket().apply { connect(InetSocketAddress(host, port), connectTimeoutMs) }
        }
        socket.soTimeout = readTimeoutMs
        socket.tcpNoDelay = true

        try {
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append(':').append(port).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }

            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val statusLine = reader.readLine() ?: throw ProtocolException("The host closed the connection during the handshake")
            if (!statusLine.contains(" 101")) {
                // 401 here is the ordinary "wrong token" case, so it has to read as such
                // rather than as a generic handshake failure.
                throw ProtocolException("The host refused the connection: $statusLine")
            }
            val responseHeaders = readHeaders(reader)
            val accept = responseHeaders["sec-websocket-accept"]
            if (accept != acceptFor(key)) {
                throw ProtocolException("The host is not a WebSocket server (bad Sec-WebSocket-Accept)")
            }
            return WebSocketConnection(socket, maskOutgoing = true)
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    internal fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        return headers
    }
}
