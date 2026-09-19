package com.andropilot.protocol.ws

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * A minimal RFC 6455 WebSocket, text frames only.
 *
 * Hand-written rather than pulled from a library for one reason: this code has to run
 * unchanged on Android *and* on a desktop JVM. Android has no `java.net.http.WebSocket`,
 * and adding OkHttp to the agent app while the host used something else would mean two
 * implementations of the same protocol and two sets of bugs. `java.net.Socket` exists
 * everywhere.
 *
 * Scope is kept to what the agent protocol needs: text messages, fragmentation, ping/pong
 * and close. There is no compression extension and no binary support. Screenshots travel
 * base64-encoded inside the action result, as they already do everywhere else in the SDK.
 */
public class WebSocketConnection internal constructor(
    private val socket: Socket,
    /** Clients must mask; servers must not. RFC 6455 section 5.3. */
    private val maskOutgoing: Boolean,
) : AutoCloseable {

    private val input: InputStream = socket.getInputStream().buffered()
    private val output: OutputStream = socket.getOutputStream()
    private val random = SecureRandom()
    private val writeLock = Any()

    @Volatile
    private var closed = false

    public val isOpen: Boolean get() = !closed && !socket.isClosed

    /** Sends one text message. Safe to call from any thread. */
    public fun send(text: String) {
        writeFrame(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Blocks until the next text message arrives.
     *
     * Returns null when the peer closed cleanly. Control frames are handled here and never
     * surface to the caller: a ping is answered immediately, because a peer that stops
     * hearing pongs will drop a connection the user believes is live.
     */
    public fun receive(): String? {
        val payload = java.io.ByteArrayOutputStream()
        while (true) {
            val header = readHeader() ?: return null
            when (header.opcode) {
                OPCODE_CLOSE -> {
                    val body = readPayload(header)
                    runCatching { writeFrame(OPCODE_CLOSE, body.copyOfRange(0, minOf(2, body.size))) }
                    closed = true
                    return null
                }
                OPCODE_PING -> writeFrame(OPCODE_PONG, readPayload(header))
                OPCODE_PONG -> readPayload(header)
                OPCODE_TEXT, OPCODE_CONTINUATION -> {
                    payload.write(readPayload(header))
                    if (header.fin) return payload.toString(Charsets.UTF_8)
                }
                else -> throw ProtocolException("Unsupported opcode ${header.opcode}")
            }
        }
    }

    /** Sends a ping. The peer's pong is consumed by [receive] and never surfaces. */
    public fun ping() {
        writeFrame(OPCODE_PING, ByteArray(0))
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { writeFrame(OPCODE_CLOSE, byteArrayOf(0x03, 0xE8.toByte())) }
        runCatching { socket.close() }
    }

    private class Header(val fin: Boolean, val opcode: Int, val length: Int, val mask: ByteArray?)

    private fun readHeader(): Header? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = require(input.read())
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var length = b1 and 0x7F
        if (length == 126) {
            length = (require(input.read()) shl 8) or require(input.read())
        } else if (length == 127) {
            var long = 0L
            repeat(8) { long = (long shl 8) or require(input.read()).toLong() }
            // A frame this large is either a bug or an attempt to exhaust memory. The
            // largest legitimate message is a base64 screenshot, far below this.
            if (long > MAX_MESSAGE_BYTES) throw ProtocolException("Frame of $long bytes exceeds the limit")
            length = long.toInt()
        }
        if (length > MAX_MESSAGE_BYTES) throw ProtocolException("Frame of $length bytes exceeds the limit")
        val mask = if (masked) ByteArray(4).also { readFully(it) } else null
        return Header(fin, opcode, length, mask)
    }

    private fun readPayload(header: Header): ByteArray {
        val bytes = ByteArray(header.length)
        readFully(bytes)
        header.mask?.let { mask ->
            for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return bytes
    }

    private fun readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw EOFException("Connection closed mid-frame")
            offset += read
        }
    }

    private fun require(value: Int): Int =
        if (value < 0) throw EOFException("Connection closed mid-frame") else value

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        synchronized(writeLock) {
            if (socket.isClosed) throw ProtocolException("Socket is closed")
            val out = java.io.ByteArrayOutputStream(payload.size + 14)
            out.write(0x80 or opcode)
            val maskBit = if (maskOutgoing) 0x80 else 0x00
            when {
                payload.size < 126 -> out.write(maskBit or payload.size)
                payload.size <= 0xFFFF -> {
                    out.write(maskBit or 126)
                    out.write((payload.size shr 8) and 0xFF)
                    out.write(payload.size and 0xFF)
                }
                else -> {
                    out.write(maskBit or 127)
                    for (shift in 56 downTo 0 step 8) out.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                }
            }
            if (maskOutgoing) {
                val mask = ByteArray(4).also(random::nextBytes)
                out.write(mask)
                val masked = ByteArray(payload.size)
                for (i in payload.indices) masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                out.write(masked)
            } else {
                out.write(payload)
            }
            output.write(out.toByteArray())
            output.flush()
        }
    }

    internal companion object {
        const val OPCODE_CONTINUATION = 0x0
        const val OPCODE_TEXT = 0x1
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA

        /** 16 MB. Generous for a base64 screenshot, small enough not to be a memory attack. */
        const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024
    }
}

/** A violation of the WebSocket framing rules, or a frame this implementation refuses. */
public class ProtocolException(message: String) : java.io.IOException(message)

/** RFC 6455's handshake accept value. */
internal fun acceptFor(key: String): String {
    val digest = MessageDigest.getInstance("SHA-1")
        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
    return Base64.getEncoder().encodeToString(digest)
}
