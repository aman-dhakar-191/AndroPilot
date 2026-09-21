package com.andropilot.host.ui

import com.andropilot.host.AgentBridge
import com.andropilot.host.agent.AgentLoop
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * A page for giving the phone something to do, and watching it happen.
 *
 * The gap this fills: with a device attached and no UI, the only way to start a task was to
 * kill the host and restart it with `--goal`. That makes the interesting part of the system
 * -- watching a model read a screen and decide -- the part you cannot see.
 *
 * **Always bound to loopback, whatever `--bind` says.** That flag exists so a phone on the
 * LAN can reach the agent socket; it must not also put a start-a-run button on the network.
 * The page carries no authentication precisely because nothing but this machine can reach
 * it, and the two decisions have to stay joined.
 */
public class ControlServer(
    port: Int,
    private val bridge: AgentBridge,
    private val bus: RunEventBus,
    /** Builds a loop per run. Null when no model endpoint was configured. */
    private val loopFactory: (() -> AgentLoop)?,
) : AutoCloseable {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val current = AtomicReference<AgentLoop?>()
    private val runner = Executors.newSingleThreadExecutor { r ->
        Thread(r, "andropilot-run").apply { isDaemon = true }
    }

    public val port: Int get() = server.address.port

    init {
        server.createContext("/", ::page)
        server.createContext("/events", ::events)
        server.createContext("/run", ::run)
        server.createContext("/stop", ::stop)
        server.createContext("/status", ::status)
        // A cached pool, because an SSE request occupies its thread for as long as the page
        // is open. The default executor is single-threaded and one open page would block
        // every other request.
        server.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "andropilot-ui").apply { isDaemon = true }
        }
    }

    public fun start(): ControlServer = apply { server.start() }

    private fun page(exchange: HttpExchange) {
        val body = javaClass.getResourceAsStream("/ui/index.html")?.readBytes()
            ?: return respond(exchange, 500, "text/plain", "The UI resource is missing from this build.")
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    /**
     * Server-sent events rather than a WebSocket.
     *
     * The browser reconnects on its own, it is one long GET, and the data only ever travels
     * one way. A second WebSocket implementation here would be all cost.
     */
    private fun events(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.responseBody
        val lock = Any()

        fun write(event: RunEvent) {
            synchronized(lock) {
                out.write("data: ${json.encodeToString(RunEvent.serializer(), event)}\n\n".toByteArray())
                out.flush()
            }
        }

        val subscription = bus.subscribe { event -> runCatching { write(event) } }
        try {
            var lastDevice = deviceState()
            write(lastDevice)
            // Polled rather than pushed from the bridge: the bridge would have to know
            // about this UI to notify it, and a device line that updates within a couple of
            // seconds is worth nothing compared to that coupling.
            var ticks = 0
            while (true) {
                Thread.sleep(2_000)
                val now = deviceState()
                if (now != lastDevice) {
                    lastDevice = now
                    write(now)
                }
                // A proxy or an idle browser will drop a stream that says nothing at all.
                if (++ticks % 8 == 0) {
                    synchronized(lock) {
                        out.write(": keep-alive\n\n".toByteArray())
                        out.flush()
                    }
                }
            }
        } catch (e: Exception) {
            // The page closed, or the connection dropped. Ordinary.
        } finally {
            subscription.close()
            runCatching { exchange.close() }
        }
    }

    private fun run(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return respond(exchange, 405, "text/plain", "POST only")
        val factory = loopFactory
            ?: return respond(exchange, 409, "application/json", error("No model endpoint is configured. Restart the host with --model-endpoint."))
        if (current.get() != null) {
            return respond(exchange, 409, "application/json", error("A run is already in progress."))
        }
        if (!bridge.isConnected) {
            return respond(exchange, 409, "application/json", error("No device is connected."))
        }

        val goal = runCatching {
            json.parseToJsonElement(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                .jsonObject["goal"]?.jsonPrimitive?.content
        }.getOrNull()?.trim()
        if (goal.isNullOrEmpty()) {
            return respond(exchange, 400, "application/json", error("Say what the phone should do."))
        }

        val loop = factory()
        current.set(loop)
        runner.execute {
            try {
                loop.run(goal)
            } catch (e: Exception) {
                bus.emit(RunEvent.Failed(e.message ?: e::class.java.simpleName))
            } finally {
                current.set(null)
            }
        }
        respond(exchange, 202, "application/json", """{"started":true}""")
    }

    private fun stop(exchange: HttpExchange) {
        current.get()?.stop()
        respond(exchange, 200, "application/json", """{"stopping":true}""")
    }

    private fun status(exchange: HttpExchange) {
        respond(exchange, 200, "application/json", json.encodeToString(RunEvent.serializer(), deviceState()))
    }

    private fun deviceState(): RunEvent.Device = RunEvent.Device(
        connected = bridge.isConnected,
        name = bridge.device?.name,
        tools = bridge.tools().size,
    )

    private fun error(message: String): String {
        val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        return """{"error":"$escaped"}"""
    }

    private fun respond(exchange: HttpExchange, status: Int, type: String, body: String) {
        runCatching {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "$type; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        runCatching { exchange.close() }
    }

    override fun close() {
        current.get()?.stop()
        runner.shutdownNow()
        server.stop(0)
    }
}
