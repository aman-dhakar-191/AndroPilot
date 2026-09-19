package com.andropilot.host

import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * The PC-side host: the thing the phone dials into and the thing an MCP client talks to.
 *
 * One process rather than three, because all three parts share a single fact -- which
 * device is currently connected -- and splitting them would mean inventing an IPC channel
 * to share it.
 *
 * Everything is off by default and every port binds to loopback unless told otherwise. A
 * socket into this process can drive somebody's phone.
 */
public fun main(args: Array<String>) {
    val options = parse(args)
    if (options.help) {
        System.err.println(USAGE)
        return
    }

    val token = options.token ?: generateToken().also {
        // stderr, not stdout: in MCP mode stdout carries JSON-RPC and a stray line there
        // corrupts the stream.
        System.err.println("[host] No --token given, generated one for this run: $it")
    }

    val bridge = AgentBridge(
        port = options.port,
        token = token,
        bindAddress = options.bind,
        onEvent = { payload -> if (options.verbose) System.err.println("[event] $payload") },
    ).start()

    System.err.println("[host] Waiting for a device on ws://${options.bind}:${bridge.port}/agent")
    System.err.println("[host] Token: $token")

    val ingest = options.ingestPort?.let { port ->
        IngestServer(port, options.telemetryDirectory, token, options.bind).start().also {
            System.err.println("[host] Telemetry ingest on http://${options.bind}:${it.port}/ingest -> ${options.telemetryDirectory}")
        }
    }

    val skills = Skills(options.skillsDirectory)
    System.err.println("[host] ${skills.all().size} app skill(s) loaded from ${options.skillsDirectory}")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { bridge.close() }
            runCatching { ingest?.close() }
        },
    )

    if (options.mcp) {
        System.err.println("[host] Serving MCP on stdio.")
        McpServer(bridge, skills).serve(System.`in`.bufferedReader(), System.out.writer())
    } else {
        System.err.println("[host] Bridge only (pass --mcp to serve MCP on stdio). Ctrl-C to stop.")
        Thread.currentThread().join()
    }
}

internal data class Options(
    val port: Int = 8765,
    val bind: String = "127.0.0.1",
    val token: String? = null,
    val mcp: Boolean = false,
    val verbose: Boolean = false,
    val ingestPort: Int? = null,
    val skillsDirectory: File = File("skills"),
    val telemetryDirectory: File = File("telemetry-data"),
    val help: Boolean = false,
)

internal fun parse(args: Array<String>): Options {
    var options = Options()
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--port" -> options = options.copy(port = args[++i].toInt())
            "--bind" -> options = options.copy(bind = args[++i])
            "--token" -> options = options.copy(token = args[++i])
            "--mcp" -> options = options.copy(mcp = true)
            "--verbose" -> options = options.copy(verbose = true)
            "--ingest-port" -> options = options.copy(ingestPort = args[++i].toInt())
            "--skills" -> options = options.copy(skillsDirectory = File(args[++i]))
            "--telemetry-dir" -> options = options.copy(telemetryDirectory = File(args[++i]))
            "--help", "-h" -> options = options.copy(help = true)
            else -> throw IllegalArgumentException("Unknown option '$arg'.\n$USAGE")
        }
        i++
    }
    return options
}

private fun generateToken(): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))

internal const val USAGE: String = """
andropilot-host -- the PC side of the AndroPilot agent bridge.

  --port <n>             Port the phone dials into (default 8765).
  --bind <addr>          Interface to bind (default 127.0.0.1). Binding wider exposes a
                         socket that can drive a phone; do it deliberately.
  --token <secret>       Shared secret the phone must present. Generated if omitted.
  --mcp                  Serve MCP on stdio, so an MCP client can drive the device.
  --ingest-port <n>      Also accept telemetry batches on this port.
  --telemetry-dir <dir>  Where ingested records are written (default ./telemetry-data).
  --skills <dir>         Per-app notes, as <dir>/<package>/SKILL.md (default ./skills).
  --verbose              Print forwarded device events to stderr.
"""
