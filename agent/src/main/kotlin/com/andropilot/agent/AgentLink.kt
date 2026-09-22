package com.andropilot.agent

import com.andropilot.core.observe.AgentEvent
import com.andropilot.core.observe.AgentEventListener
import com.andropilot.core.session.AndroPilotSession
import com.andropilot.core.session.ToolCodec
import com.andropilot.core.util.AndroPilotJson
import com.andropilot.protocol.Frame
import com.andropilot.protocol.PROTOCOL_VERSION
import com.andropilot.protocol.ProtocolJson
import com.andropilot.protocol.ToolSpec
import com.andropilot.protocol.ws.WebSocketConnection
import com.andropilot.protocol.ws.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the agent is doing, as the UI and the notification need to show it. */
public sealed interface LinkState {
    public data object Idle : LinkState
    public data class Connecting(val endpoint: String) : LinkState
    public data class Connected(val endpoint: String, val since: Long) : LinkState

    /** Disconnected with a reason. Shown verbatim: a silent retry loop is a support ticket. */
    public data class Failed(val message: String, val retryInMs: Long) : LinkState
}

/**
 * Holds the connection to the host and runs whatever it sends.
 *
 * The phone dials out and keeps the socket open. It has to be this way round: behind
 * carrier NAT a device has no inbound route, its address changes as it moves between
 * networks, and a listening socket would be suspended by Doze. Dialling out also means the
 * host's location is a piece of configuration rather than a property of the network.
 *
 * Actions are executed by handing the payload straight to [ToolCodec.executeJson]. Nothing
 * in this class understands what an action is, which is the point: the wire carries the
 * SDK's own documents, so a host built against an older version still talks to a newer
 * phone as long as the frames match.
 *
 * **The safety policy is not negotiable over this socket.** The session was configured with
 * one when the app started, and every action runs through it. A host can ask for anything;
 * the phone decides what it will do. Anything else would make the policy decorative, since
 * the host is the untrusted end of this connection.
 */
public class AgentLink(
    private val scope: CoroutineScope,
    private val session: AndroPilotSession,
    private val config: AgentConfig,
    private val sdkVersion: String,
) : AgentEventListener {

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    public val state: StateFlow<LinkState> get() = _state.asStateFlow()

    @Volatile
    private var connection: WebSocketConnection? = null

    private var job: Job? = null

    public fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    public fun stop() {
        job?.cancel()
        job = null
        runCatching { connection?.close() }
        connection = null
        _state.value = LinkState.Idle
    }

    /**
     * Forwards the session's events to the host as they happen.
     *
     * Called synchronously on the action path, so it does no work beyond a serialization and
     * a socket write, and a failure here is swallowed: losing the live view of a run is
     * always better than breaking the run.
     */
    override fun onEvent(event: AgentEvent) {
        val socket = connection ?: return
        runCatching {
            val payload = AndroPilotJson.instance.encodeToString(AgentEvent.serializer(), event)
            socket.send(ProtocolJson.encode(Frame.Event(payload)))
        }
    }

    private suspend fun runLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (scope.isActive) {
            try {
                _state.value = LinkState.Connecting(config.endpoint)
                val socket = WsClient.connect(
                    url = config.endpoint,
                    headers = mapOf("Authorization" to "Bearer ${config.token}"),
                )
                connection = socket
                backoffMs = INITIAL_BACKOFF_MS
                _state.value = LinkState.Connected(config.endpoint, System.currentTimeMillis())
                socket.use { serve(it) }
                // A clean close is the host going away, not an error.
                _state.value = LinkState.Failed("The host closed the connection.", backoffMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = LinkState.Failed(e.message ?: e::class.java.simpleName, backoffMs)
            } finally {
                connection = null
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun serve(socket: WebSocketConnection) {
        socket.send(ProtocolJson.encode(Frame.Hello(PROTOCOL_VERSION, config.deviceName, sdkVersion)))

        // A connection with nothing on it is a connection something will reclaim. Between
        // actions this socket is completely silent, and a home router's NAT table or the
        // phone's radio drops an idle one after roughly a minute -- which surfaces as a
        // reset and a reconnect loop, and as a run failing halfway through for no reason
        // the model can understand. The ping costs two bytes and the peer answers it
        // without the application seeing anything.
        val keepAlive = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(KEEP_ALIVE_MS)
                if (!socket.isOpen) return@launch
                runCatching { socket.ping() }.onFailure { return@launch }
            }
        }

        try {
            readFrames(socket)
        } finally {
            keepAlive.cancel()
        }
    }

    private suspend fun readFrames(socket: WebSocketConnection) {
        while (true) {
            scope.ensureActive()
            val text = withContext(Dispatchers.IO) { socket.receive() } ?: return
            val frame = try {
                ProtocolJson.decode(text)
            } catch (e: Exception) {
                socket.send(ProtocolJson.encode(Frame.Error(null, "Unreadable frame: ${e.message}")))
                continue
            }
            when (frame) {
                is Frame.ToolsRequest -> socket.send(
                    ProtocolJson.encode(Frame.ToolsResponse(frame.id, toolSpecs())),
                )
                is Frame.ActionRequest -> scope.launch {
                    // Each action on its own coroutine: a host may pipeline, and a long
                    // `wait_for` must not stop the socket from being read.
                    val result = runCatching { ToolCodec.executeJson(session, frame.payload) }
                    val payload = result.getOrElse { failure(it) }
                    runCatching {
                        socket.send(
                            ProtocolJson.encode(
                                Frame.ActionResponse(frame.id, payload, summarize(payload)),
                            ),
                        )
                    }
                }
                // The host's model narrating what it is about to do. Recorded here rather
                // than there so intent lands in the same ordered stream as the outcome,
                // which is the only place anything downstream can line the two up.
                is Frame.Note -> runCatching { session.note(frame.message, frame.data) }
                is Frame.Welcome, is Frame.Error -> Unit
                is Frame.Hello, is Frame.ToolsResponse, is Frame.ActionResponse, is Frame.Event ->
                    socket.send(ProtocolJson.encode(Frame.Error(null, "A host may not send ${frame::class.simpleName}.")))
            }
        }
    }

    /**
     * Renders a result the way a model should read it.
     *
     * Done here, on the device, because the host deliberately does not parse action
     * documents -- and because a full result carries an entire snapshot, which is far too
     * much to put in a model's context on every single turn. `summarizeForModel` is the
     * SDK's own answer to that, so there is one compaction in the codebase rather than a
     * second one that drifts.
     */
    private fun summarize(payload: String): String? = runCatching {
        ToolCodec.summarizeForModel(ToolCodec.decodeResult(payload))
    }.getOrNull()

    /**
     * Shapes an unexpected exception like a result.
     *
     * `executeJson` turns every foreseeable problem into a Failure already, so reaching
     * here means a programming error -- but the host still has to receive something it can
     * parse rather than a dropped request.
     */
    private fun failure(cause: Throwable): String =
        """{"type":"failure","action":{"type":"observe"},"duration_ms":0,"reason":"internal_error",""" +
            """"message":${quote(cause.message ?: cause::class.java.simpleName)}}"""

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    private fun toolSpecs(): List<ToolSpec> = ToolCodec.toolDescriptors().map { descriptor ->
        ToolSpec(
            name = descriptor.name,
            description = descriptor.description,
            parameterSchema = descriptor.parameterSchema,
            maxRisk = descriptor.maxRisk.name.lowercase(),
        )
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 2_000L

        /**
         * Comfortably under the minute-ish idle timeout a consumer router applies, and far
         * too small a cost to tune: one frame every 25 seconds.
         */
        const val KEEP_ALIVE_MS = 25_000L

        /**
         * Capped at a minute. A phone that woke up on a network where the host is
         * unreachable should not end up retrying once an hour once it comes home.
         */
        const val MAX_BACKOFF_MS = 60_000L
    }
}
