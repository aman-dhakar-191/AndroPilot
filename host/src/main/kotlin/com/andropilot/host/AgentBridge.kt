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

/**
 * What came back from running one action.
 *
 * Two renderings of the same outcome: [payload] is the SDK's full result document, which is
 * what an MCP client or a script should get, and [summary] is the compact form meant for a
 * model's context. The device produces both, because it is the side that understands them.
 */
public data class DeviceResult(
    val payload: String,
    val summary: String?,
) {
    /** What to put in front of a model: the summary when there is one, else the raw payload. */
    public val forModel: String get() = summary ?: payload
}

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

    private val pending = ConcurrentHashMap<String, SynchronousQueue<DeviceResult>>()
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
    public fun execute(actionPayload: String, timeoutMs: Long = 120_000): DeviceResult {
        val socket = connection ?: throw IllegalStateException(
            "No device is connected. Open the AndroPilot agent app and connect it to this host.",
        )
        val id = nextId.getAndIncrement().toString()
        val mailbox = SynchronousQueue<DeviceResult>()
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
        // A phone that drops and immediately redials arrives while the previous session is
        // still unwinding: its socket is dead but this thread has not reached the `finally`
        // that clears the slot yet. Refusing on that is not a safety check, it is a race --
        // the device is turned away with "another device is already connected" when the
        // other device is itself, and nothing retries. So give a closing session a moment
        // to finish before deciding. A genuinely concurrent second phone is still refused,
        // because its predecessor's socket stays open and the wait expires.
        val until = System.currentTimeMillis() + HANDOVER_GRACE_MS
        while (connection?.isOpen == true && System.currentTimeMillis() < until) {
            Thread.sleep(25)
        }
        if (connection?.isOpen == true) {
            socket.send(ProtocolJson.encode(Frame.Error(null, "Another device is already connected to this host.")))
            return
        }
        connection = socket
        try {
            loop(socket, request)
        } catch (e: java.io.IOException) {
            // A phone that walks out of Wi-Fi range, loses its process, or is switched off
            // resets the socket rather than closing it politely. That is an ordinary end to
            // a connection, not a fault: letting it escape killed the worker thread and put
            // a stack trace on the console that read like a host crash.
            log("Device disconnected: ${e.message ?: e::class.java.simpleName}")
        } finally {
            device?.let { log("Device gone: ${it.name}. Waiting for it to dial back in.") }
            connection = null
            device = null
            tools = emptyList()
            // Unblock anything still waiting rather than letting it sit until its timeout.
            pending.values.forEach {
                it.offer(DeviceResult(DISCONNECTED_RESULT, "FAILED [not_connected] The device disconnected."))
            }
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
                    log("Device connected: ${frame.device} (SDK ${frame.sdkVersion}) from ${request.remoteAddress}")
                    socket.send(ProtocolJson.encode(Frame.Welcome(PROTOCOL_VERSION, "andropilot-host")))
                    socket.send(ProtocolJson.encode(Frame.ToolsRequest(nextId.getAndIncrement().toString())))
                }
                is Frame.ToolsResponse -> {
                    tools = frame.tools
                    log("Device reported ${frame.tools.size} tools. Ready.")
                    firstConnection.countDown()
                }
                is Frame.ActionResponse -> {
                    // offer, not put: a caller that already timed out must not wedge the
                    // reader thread and take the whole connection down with it.
                    pending[frame.id]?.offer(
                        DeviceResult(frame.payload, frame.summary),
                        5,
                        TimeUnit.SECONDS,
                    )
                }
                is Frame.Event -> runCatching { onEvent(frame.payload) }
                is Frame.Error -> runCatching { onEvent(text) }
                is Frame.ActionRequest, is Frame.ToolsRequest, is Frame.Welcome, is Frame.Note ->
                    socket.send(ProtocolJson.encode(Frame.Error(null, "A device may not send ${frame::class.simpleName}.")))
            }
        }
    }

    override fun close() {
        runCatching { connection?.close() }
        server.close()
    }

    /**
     * Asks the device to record something in its own event stream.
     *
     * Used for the model's reasoning. Best-effort and deliberately so: a note that does not
     * arrive costs a line of analysis later, while an exception here would end a run that
     * was otherwise going fine.
     */
    /**
     * Says what the connection is doing, on stderr.
     *
     * stderr and not stdout: in MCP mode stdout carries JSON-RPC and one stray line
     * corrupts the stream. It is not optional chatter either -- with nothing printed
     * between "waiting for a device" and the first action, a host with a phone attached
     * and a host with none look exactly alike.
     */
    private fun log(message: String) {
        System.err.println("[host] $message")
    }

    public fun note(message: String, data: Map<String, String> = emptyMap()) {
        val socket = connection ?: return
        runCatching { socket.send(ProtocolJson.encode(Frame.Note(message, data))) }
    }

    private companion object {
        /**
         * How long a new connection waits for a closing one to release the slot.
         *
         * Long enough for a socket already torn down at the peer to be noticed here, short
         * enough that a real second phone is refused promptly rather than left hanging.
         */
        private const val HANDOVER_GRACE_MS = 2_000L

        /** Shaped like a real failure so a caller never has to special-case a disconnect. */
        const val DISCONNECTED_RESULT =
            """{"type":"failure","action":{"type":"observe"},"duration_ms":0,""" +
                """"reason":"not_connected","message":"The device disconnected before it answered."}"""
    }
}
