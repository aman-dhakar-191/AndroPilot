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
 * Where a batch of records goes.
 *
 * An interface so tests never open a socket, and so a host that wants to ship records
 * somewhere other than an HTTP endpoint -- a local collector, a message queue, a file on a
 * NAS -- does not have to reimplement the spooling around it.
 */
public fun interface TelemetryTransport {
    public fun upload(batch: TelemetryBatch): UploadOutcome
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

    override fun upload(batch: TelemetryBatch): UploadOutcome {
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
            when (val code = connection.responseCode) {
                in 200..299 -> UploadOutcome.ACCEPTED
                408, 429, in 500..599 -> UploadOutcome.RETRY
                else -> if (code == 401 || code == 403) UploadOutcome.REJECTED else UploadOutcome.REJECTED
            }
        } catch (e: Exception) {
            // Offline, DNS failure, TLS handshake failure: all are "try again later".
            UploadOutcome.RETRY
        } finally {
            runCatching { connection.disconnect() }
        }
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
