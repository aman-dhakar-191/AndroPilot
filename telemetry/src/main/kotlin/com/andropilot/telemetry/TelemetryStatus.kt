package com.andropilot.telemetry

/** Where telemetry has got to. */
public enum class TelemetryState {
    /** Constructed, nothing attempted yet. */
    INITIALIZING,

    /**
     * The local sink exists: a spool directory, a worker, a validated endpoint.
     *
     * This says nothing about whether the server can be reached, and reporting it as
     * "connected" is the mistake this enum exists to prevent. Everything up to here happens
     * without a single packet leaving the device.
     */
    INITIALIZED,

    /** A POST was accepted. This is the only state that means records are arriving. */
    CONNECTED,

    /** An upload failed in a way worth retrying, and records are waiting in the spool. */
    RETRYING,

    /** An upload was refused for a reason retrying will not fix -- usually a bad token. */
    FAILED,
}

/**
 * Everything known about the telemetry pipeline, for a screen or a log line.
 *
 * Deliberately a snapshot of plain values rather than a live object: it crosses from the
 * uploader thread to whatever is displaying it, and it is read far more often than it
 * changes.
 *
 * It carries no screen content and no record text. What it describes is the *transport* --
 * how many records, how big, which status code -- so it can be logged and displayed freely,
 * including when `includeText` is off.
 */
public data class TelemetryStatus(
    val state: TelemetryState,
    val endpoint: String,
    /** Records handed to the sink by the SDK. */
    val recorded: Long = 0,
    /** Records the server has accepted. */
    val uploaded: Long = 0,
    /** Batches the server has accepted. */
    val batches: Long = 0,
    /** Records the spool discarded because it filled up. A gap in the data, not a retry. */
    val dropped: Long = 0,
    /** Bytes waiting in the spool right now. */
    val pendingBytes: Long = 0,
    /** Upload attempts that failed since the last success. Resets to zero on any success. */
    val consecutiveFailures: Int = 0,
    val lastSuccessAtMs: Long? = null,
    val lastFailureAtMs: Long? = null,
    /** The HTTP status of the last attempt, when the transport reported one. */
    val lastStatus: Int? = null,
    /** Why the last attempt failed, in words. */
    val lastError: String? = null,
) {
    /** One line for a log or a status row. */
    public fun describe(): String = buildString {
        append(
            when (state) {
                TelemetryState.INITIALIZING -> "initializing"
                TelemetryState.INITIALIZED -> "initialized (not yet reached the server)"
                TelemetryState.CONNECTED -> "connected"
                TelemetryState.RETRYING -> "retrying"
                TelemetryState.FAILED -> "failed"
            },
        )
        append(" -> ").append(endpoint)
        append(" | recorded ").append(recorded)
        append(", uploaded ").append(uploaded)
        append(" in ").append(batches).append(" batch(es)")
        if (dropped > 0) append(", dropped ").append(dropped)
        if (pendingBytes > 0) append(", ").append(pendingBytes).append("B pending")
        if (consecutiveFailures > 0) append(", ").append(consecutiveFailures).append(" failure(s) in a row")
        lastStatus?.let { append(", last HTTP ").append(it) }
        lastError?.let { append(", last error: ").append(it) }
    }
}
