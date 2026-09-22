package com.andropilot.telemetry

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPOutputStream

/** The outcome of one upload attempt. */
public enum class UploadOutcome {
    /** Accepted. The batch may be deleted. */
    ACCEPTED,

    /** Refused for a reason retrying will not fix -- a bad token, a malformed batch. */
    REJECTED,

    /** Temporarily unavailable. Keep the batch and back off. */
    RETRY,
}

/**
 * What one upload attempt did, in enough detail to debug it.
 *
 * The outcome alone decides what the sink does next; the status and detail exist so a
 * person can tell a rejected token from a refused connection from a 500, which are three
 * very different problems that otherwise all present as "telemetry is not working".
 */
public data class UploadResult(
    val outcome: UploadOutcome,
    /** The HTTP status, when the attempt got far enough to have one. */
    val status: Int? = null,
    /** What went wrong, for a log. Never record content. */
    val detail: String? = null,
)

/**
 * Where a batch of records goes.
 *
 * An interface so tests never open a socket, and so a host that wants to ship records
 * somewhere other than an HTTP endpoint -- a local collector, a message queue, a file on a
 * NAS -- does not have to reimplement the spooling around it.
 */
public fun interface TelemetryTransport {
    public fun upload(batch: TelemetryBatch): UploadResult
}

/** One upload: the JSON Lines records plus the identity of the run that produced them. */
public data class TelemetryBatch(
    val runId: String,
    val deviceId: String,
    val sdkVersion: String,
    val lines: List<String>,
)

/**
 * Posts batches as gzipped JSON Lines over HTTP.
 *
 * `HttpURLConnection` rather than a client library because this code has to run on Android,
 * which has no `java.net.http`, and adding OkHttp would put a transitive HTTP stack into
 * every app that turns telemetry on. The request is small and the semantics are simple
 * enough that the JDK's own client is not a hardship.
 */
public class HttpTelemetryTransport(
    private val endpoint: String,
    private val token: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : TelemetryTransport {

    init {
        val uri = URI(endpoint)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "Telemetry endpoint must be http(s): '$endpoint'" }
        // Plaintext is allowed only where the traffic cannot leave the machine or the LAN.
        // These records describe what a person did on their phone; shipping them in the
        // clear across the internet would be a worse privacy failure than not redacting.
        if (scheme == "http") {
            require(isPrivate(uri.host)) {
                "Refusing plaintext telemetry to a public host ('${uri.host}'). Use https, " +
                    "or point at localhost or a private address."
            }
        }
    }

    /** The endpoint, for logs and status rows. Carries no token. */
    public val describe: String get() = endpoint

    override fun upload(batch: TelemetryBatch): UploadResult {
        val body = gzip(batch.lines.joinToString("\n", postfix = "\n"))
        val connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/x-ndjson")
            setRequestProperty("Content-Encoding", "gzip")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-AndroPilot-Run", batch.runId)
            setRequestProperty("X-AndroPilot-Device", batch.deviceId)
            setRequestProperty("X-AndroPilot-Sdk", batch.sdkVersion)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            when (code) {
                in 200..299 -> UploadResult(UploadOutcome.ACCEPTED, code)
                408, 429, in 500..599 -> UploadResult(UploadOutcome.RETRY, code, reasonFor(code, connection))
                else -> UploadResult(UploadOutcome.REJECTED, code, reasonFor(code, connection))
            }
        } catch (e: Exception) {
            // Offline, DNS failure, TLS handshake failure: all are "try again later".
            UploadResult(
                UploadOutcome.RETRY,
                status = null,
                detail = "${e::class.java.simpleName}: ${e.message ?: "no detail"}",
            )
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * What the server said, when it said anything.
     *
     * The body is read because an ingest that refuses a batch usually explains why, and
     * that sentence is the whole difference between "telemetry is broken" and "the token
     * is wrong". Capped, because it is a log line and not a payload.
     */
    private fun reasonFor(code: Int, connection: HttpURLConnection): String {
        val body = runCatching {
            connection.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.trim()
        }.getOrNull().orEmpty()
        val hint = when (code) {
            401, 403 -> "the shared token was refused"
            404 -> "no ingest is listening at this path"
            413 -> "the batch was too large"
            else -> "HTTP $code"
        }
        return if (body.isBlank()) hint else "$hint: ${body.take(200)}"
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun isPrivate(host: String?): Boolean {
        if (host == null) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
        if (host.endsWith(".local")) return true
        return host.startsWith("10.") || host.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
    }
}
