package com.andropilot.host

import com.andropilot.host.agent.AgentLoop
import com.andropilot.host.agent.OpenAiCompatibleClient
import com.andropilot.host.ui.ControlServer
import com.andropilot.host.ui.RunEventBus
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

    val bus = RunEventBus()
    val modelClient = options.modelEndpoint?.let { endpoint ->
        // The key comes from the environment by preference: a --model-key lands in the
        // process list, where anything on the machine can read it.
        val key = System.getenv("ANDROPILOT_MODEL_KEY") ?: options.modelKey.orEmpty()
        if (options.modelKey != null) {
            System.err.println(
                "[host] Warning: --model-key is visible in the process list. " +
                    "Prefer ANDROPILOT_MODEL_KEY in the environment.",
            )
        }
        OpenAiCompatibleClient(
            baseUrl = endpoint,
            apiKey = key,
            model = options.model,
            temperature = options.temperature,
        ).also { System.err.println("[host] Model: ${it.describe}") }
    }

    val ui = options.uiPort?.let { port ->
        ControlServer(
            port = port,
            bridge = bridge,
            bus = bus,
            loopFactory = modelClient?.let {
                { AgentLoop(bridge, it, skills, maxSteps = options.maxSteps, emit = bus::emit) }
            },
        ).start().also {
            // Loopback regardless of --bind. That flag is there so a phone on the LAN can
            // reach the agent socket; it must not also publish a start-a-run button.
            System.err.println("[host] Control UI on http://127.0.0.1:${it.port}")
            if (modelClient == null) {
                System.err.println("[host] (no --model-endpoint, so the UI can watch but not start a run)")
            }
        }
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { bridge.close() }
            runCatching { ingest?.close() }
            runCatching { ui?.close() }
        },
    )

    // With a UI open, the process stays up serving it rather than running one task and
    // exiting: closing the browser tab should not end the session.
    if (modelClient != null && ui == null) {
        val client = modelClient
        System.err.println("[host] Waiting for the device before the first run...")
        if (!bridge.awaitDevice(options.waitForDeviceMs)) {
            System.err.println("[host] No device connected within ${options.waitForDeviceMs}ms. Exiting.")
            bridge.close()
            ingest?.close()
            return
        }

        val loop = AgentLoop(bridge, client, skills, maxSteps = options.maxSteps, emit = bus::emit)

        val goals: Sequence<String> = options.goal?.let { sequenceOf(it) }
            // No --goal means an interactive session: type a task, watch it run, type
            // another. The device connection is held across all of them.
            ?: generateSequence {
                System.err.print("\ngoal> ")
                System.err.flush()
                readlnOrNull()?.takeIf { it.isNotBlank() }
            }

        for (goal in goals) {
            try {
                val outcome = loop.run(goal.trim())
                System.err.println(
                    if (outcome.finished) {
                        "[host] Done in ${outcome.steps} step(s), ${outcome.actions} action(s)."
                    } else {
                        "[host] Stopped at the step limit after ${outcome.actions} action(s)."
                    },
                )
                outcome.message?.let { System.err.println("[host] $it") }
            } catch (e: Exception) {
                System.err.println("[host] Run failed: ${e.message}")
            }
        }
        bridge.close()
        ingest?.close()
        ui?.close()
        return
    }

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
    /** Set to run the agent loop against an OpenAI-compatible endpoint. */
    val modelEndpoint: String? = null,
    val model: String = "gpt-4o-mini",
    val modelKey: String? = null,
    val temperature: Double? = null,
    val goal: String? = null,
    val maxSteps: Int = 40,
    val waitForDeviceMs: Long = 120_000,
    /** Serves the control page. Loopback only, whatever --bind says. */
    val uiPort: Int? = null,
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
            "--model-endpoint" -> options = options.copy(modelEndpoint = args[++i])
            "--model" -> options = options.copy(model = args[++i])
            "--model-key" -> options = options.copy(modelKey = args[++i])
            "--temperature" -> options = options.copy(temperature = args[++i].toDouble())
            "--goal" -> options = options.copy(goal = args[++i])
            "--max-steps" -> options = options.copy(maxSteps = args[++i].toInt())
            "--wait-ms" -> options = options.copy(waitForDeviceMs = args[++i].toLong())
            "--ui-port" -> options = options.copy(uiPort = args[++i].toInt())
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

Driving the device from your own model (an OpenAI-compatible endpoint):

  --model-endpoint <url> Base URL, e.g. http://localhost:4000/v1. Enables the agent loop.
  --model <name>         Model to ask for, as your endpoint names it.
  --model-key <secret>   API key. Prefer the ANDROPILOT_MODEL_KEY environment variable:
                         an argument is visible to anything that can list processes.
  --goal "<task>"        Run one task and exit. Omit for an interactive prompt.
  --max-steps <n>        Ceiling on model turns per run (default 40). This drives a real
                         phone; a loop with no ceiling keeps going when it is lost.
  --temperature <n>      Passed through when your endpoint accepts it.
  --wait-ms <n>          How long to wait for the phone before giving up (default 120000).
  --ui-port <n>          Serve the control page there: type a goal, watch each step, stop a
                         run. Always bound to 127.0.0.1, whatever --bind says, because it
                         needs no password and starting a run is not something to publish.
"""
