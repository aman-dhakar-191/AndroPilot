package com.andropilot.host

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.util.zip.GZIPInputStream

/**
 * Receives telemetry batches and appends them to disk.
 *
 * Deliberately the smallest thing that works. The records arrive as JSON Lines and are
 * written as JSON Lines, partitioned by day, because that is the same format the on-device
 * trace recorder produces -- so every `jq` one-liner and analysis script already written
 * against a device trace works on the server's data with no adaptation. A database can be
 * built from these files at any point; the reverse is not true, which is why the files are
 * the source of truth.
 *
 * Bound to loopback by default. This service accepts records describing what somebody did
 * on their phone; exposing it to a network is a decision, not a default.
 */
public class IngestServer(
    port: Int,
    private val directory: File,
    private val token: String,
    bindAddress: String = "127.0.0.1",
    /**
     * Where this server's own diagnostics go.
     *
     * Every request is reported, accepted or not. An ingest that is silent is
     * indistinguishable from one that is not running, and "is anything arriving at all" is
     * the first question anybody debugging telemetry asks.
     */
    private val log: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {

    private val server = HttpServer.create(InetSocketAddress(bindAddress, port), 16)

    /** Batches accepted since start. */
    public var accepted: Long = 0
        private set

    /** Records written to disk since start. */
    public var records: Long = 0
        private set

    /** Requests refused, for any reason. */
    public var rejected: Long = 0
        private set

    init {
        directory.mkdirs()
        server.createContext("/ingest", ::handle)
        server.executor = null
    }

    public val port: Int get() = server.address.port

    public fun start(): IngestServer = apply { server.start() }

    private fun handle(exchange: HttpExchange) {
        val from = exchange.remoteAddress
        try {
            if (exchange.requestMethod != "POST") {
                rejected++
                log("[ingest] ${exchange.requestMethod} from $from refused: this endpoint takes POST")
                return respond(exchange, 405, "POST only")
            }
            val presented = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()
            if (presented != token) {
                rejected++
                log(
                    "[ingest] refused a batch from $from: " +
                        if (presented == null) "no Authorization header" else "the token does not match this host's",
                )
                return respond(exchange, 401, "bad token")
            }

            val gzipped = exchange.requestHeaders.getFirst("Content-Encoding")?.contains("gzip") == true
            val body = exchange.requestBody.let { if (gzipped) GZIPInputStream(it) else it }
                .readBytes().toString(Charsets.UTF_8)

            val run = exchange.requestHeaders.getFirst("X-AndroPilot-Run") ?: "unknown"
            val device = exchange.requestHeaders.getFirst("X-AndroPilot-Device") ?: "unknown"
            val lines = body.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty()) {
                log("[ingest] empty batch from $from (device $device, run $run) -- nothing written")
                return respond(exchange, 204, "")
            }
            log("[ingest] ${lines.size} record(s) from $from (device $device, run $run)${if (gzipped) ", gzipped" else ""}")

            // The run and device are added here rather than inside every record: they are
            // constant for a batch, and repeating them per line would inflate the upload
            // for information the transport already carries.
            val envelope = lines.joinToString("\n", postfix = "\n") { line ->
                """{"run":"${escape(run)}","device":"${escape(device)}","record":$line}"""
            }
            val file = target()
            file.appendText(envelope, Charsets.UTF_8)
            accepted++
            records += lines.size
            log("[ingest] wrote ${lines.size} record(s) to ${file.path} (${records} total this session)")
            respond(exchange, 200, "ok")
        } catch (e: Exception) {
            // 500 rather than a drop: the client keeps the batch and retries, which is the
            // behaviour that makes an ingest outage cost nothing.
            rejected++
            log("[ingest] failed to store a batch from $from: ${e::class.java.simpleName}: ${e.message}")
            respond(exchange, 500, e.message ?: "error")
        } finally {
            exchange.close()
        }
    }

    private fun target(): File {
        val day = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        return File(directory, "events-$day.jsonl")
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        runCatching {
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        }
    }

    override fun close() {
        server.stop(0)
    }
}
