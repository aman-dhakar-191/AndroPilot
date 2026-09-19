package com.andropilot.host

import com.andropilot.protocol.Frame
import com.andropilot.protocol.PROTOCOL_VERSION
import com.andropilot.protocol.ProtocolJson
import com.andropilot.protocol.ToolSpec
import com.andropilot.protocol.ws.HandshakeRequest
import com.andropilot.protocol.ws.WebSocketConnection
import com.andropilot.protocol.ws.WsServer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A device that has connected and identified itself. */
public data class ConnectedDevice(
    val name: String,
    val sdkVersion: String,
    val remoteAddress: String,
)

/**
 * The host side of the agent socket.
 *
 * Listens for a phone to dial in, then lets the rest of the host call actions on it as if
 * the device were a local object. Everything about *what* an action is stays opaque here:
 * the payloads are the SDK's own documents, passed through untouched. That is what lets the
 * phone and the host be updated on different days without a schema negotiation.
 *
 * One device at a time. Two phones sharing a host would need per-device routing throughout
 * the MCP layer for a case nobody has yet; a second connection is refused with a clear
 * message rather than silently displacing the first.
 */
public class AgentBridge(
    port: Int,
    private val token: String,
    bindAddress: String = "127.0.0.1",
    /** Called for every `AgentEvent` the device forwards, in order. */
    private val onEvent: (String) -> Unit = {},
) : AutoCloseable {

    private val pending = ConcurrentHashMap<String, SynchronousQueue<String>>()
    private val nextId = AtomicLong(1)

    @Volatile
    private var connection: WebSocketConnection? = null

    @Volatile
    public var device: ConnectedDevice? = null
        private set

    @Volatile
    private var tools: List<ToolSpec> = emptyList()

    private val firstConnection = CountDownLatch(1)

    private val server = WsServer(
        port = port,
        bindAddress = bindAddress,
        authorize = { it.bearerToken == token },
        onConnection = ::session,
    )

    public val port: Int get() = server.port

    public val isConnected: Boolean get() = connection?.isOpen == true

    public fun start(): AgentBridge = apply { server.serveInBackground() }

    /** Blocks until a device connects, or the timeout passes. */
    public fun awaitDevice(timeoutMs: Long): Boolean =
        firstConnection.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** What the connected device says it can do. Empty when nothing is connected. */
    public fun tools(): List<ToolSpec> = tools

    /**
     * Runs one action on the device and returns its result document.
     *
     * Throws only when there is no device or it stopped answering -- a transport problem.
     * An action that failed comes back as an ordinary result document describing the
     * failure, because that distinction is the SDK's central contract and the wire must not
     * blur it.
     */
    public fun execute(actionPayload: String, timeoutMs: Long = 120_000): String {
        val socket = connection ?: throw IllegalStateException(
            "No device is connected. Open the AndroPilot agent app and connect it to this host.",
        )
        val id = nextId.getAndIncrement().toString()
        val mailbox = SynchronousQueue<String>()
        pending[id] = mailbox
        try {
            socket.send(ProtocolJson.encode(Frame.ActionRequest(id, actionPayload)))
            return mailbox.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw IllegalStateException("The device did not answer within ${timeoutMs}ms.")
        } finally {
            pending.remove(id)
        }
    }

    private fun session(socket: WebSocketConnection, request: HandshakeRequest) {
        if (connection?.isOpen == true) {
            socket.send(ProtocolJson.encode(Frame.Error(null, "Another device is already connected to this host.")))
            return
        }
        connection = socket
        try {
            loop(socket, request)
        } finally {
            connection = null
            device = null
            tools = emptyList()
            // Unblock anything still waiting rather than letting it sit until its timeout.
            pending.values.forEach { it.offer(DISCONNECTED_RESULT) }
        }
    }

    private fun loop(socket: WebSocketConnection, request: HandshakeRequest) {
        while (true) {
            val text = socket.receive() ?: return
            val frame = try {
                ProtocolJson.decode(text)
            } catch (e: Exception) {
                socket.send(ProtocolJson.encode(Frame.Error(null, "Unreadable frame: ${e.message}")))
                continue
            }
            when (frame) {
                is Frame.Hello -> {
                    if (frame.protocolVersion != PROTOCOL_VERSION) {
                        socket.send(
                            ProtocolJson.encode(
                                Frame.Error(
                                    null,
                                    "Protocol mismatch: the device speaks v${frame.protocolVersion}, " +
                                        "this host speaks v$PROTOCOL_VERSION. Update whichever is older.",
                                ),
                            ),
                        )
                        return
                    }
                    device = ConnectedDevice(frame.device, frame.sdkVersion, request.remoteAddress)
                    socket.send(ProtocolJson.encode(Frame.Welcome(PROTOCOL_VERSION, "andropilot-host")))
                    socket.send(ProtocolJson.encode(Frame.ToolsRequest(nextId.getAndIncrement().toString())))
                }
                is Frame.ToolsResponse -> {
                    tools = frame.tools
                    firstConnection.countDown()
                }
                is Frame.ActionResponse -> {
                    // offer, not put: a caller that already timed out must not wedge the
                    // reader thread and take the whole connection down with it.
                    pending[frame.id]?.offer(frame.payload, 5, TimeUnit.SECONDS)
                }
                is Frame.Event -> runCatching { onEvent(frame.payload) }
                is Frame.Error -> runCatching { onEvent(text) }
                is Frame.ActionRequest, is Frame.ToolsRequest, is Frame.Welcome ->
                    socket.send(ProtocolJson.encode(Frame.Error(null, "A device may not send ${frame::class.simpleName}.")))
            }
        }
    }

    override fun close() {
        runCatching { connection?.close() }
        server.close()
    }

    private companion object {
        /** Shaped like a real failure so a caller never has to special-case a disconnect. */
        const val DISCONNECTED_RESULT =
            """{"type":"failure","action":{"type":"observe"},"duration_ms":0,""" +
                """"reason":"not_connected","message":"The device disconnected before it answered."}"""
    }
}
