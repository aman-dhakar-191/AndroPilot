package com.andropilot.core.session

import com.andropilot.core.observe.AgentLogger
import com.andropilot.core.observe.LogLevel
import com.andropilot.core.observe.Redactor
import com.andropilot.core.safety.DefaultSafetyPolicy
import com.andropilot.core.safety.SafetyPolicy
import com.andropilot.core.vision.VisionConfig
import com.andropilot.core.vision.VisionProvider

/**
 * Everything tunable about a session, with defaults chosen so the common case needs none
 * of it.
 *
 * The timing numbers are the ones that matter in practice. They are expressed as policy
 * rather than sprinkled through the code so that a host targeting slow emulators or
 * animation-heavy apps can raise them in one place.
 */
public data class SessionConfig(
    /** Maximum time to wait for the UI to stop changing after an action. */
    public val settleTimeoutMs: Long = 2_000,
    /** The structural signature must hold steady this long for the UI to count as settled. */
    public val settleQuietPeriodMs: Long = 250,
    /** Interval between settle / condition polls. */
    public val pollIntervalMs: Long = 100,
    /** Grace period after a click before the post-action snapshot is taken. */
    public val postActionDelayMs: Long = 120,
    /** Default timeout for [com.andropilot.core.action.AgentAction.WaitFor]. */
    public val defaultWaitTimeoutMs: Long = 5_000,
    /**
     * How long an approval stays usable after a human grants it.
     *
     * An approval authorises one action on one target, and a human answering a prompt is
     * authorising what they were shown *then*. Without a bound, an approval nobody redeemed
     * would sit indefinitely and silently let an identical action through much later.
     */
    public val confirmationValidityMs: Long = 120_000,
    /** How many times an element-targeting action retries after a transient failure. */
    public val maxRetries: Int = 2,
    /** Delay before each retry; doubles per attempt. */
    public val retryBackoffMs: Long = 250,
    /**
     * Whether an action that dispatched cleanly but produced no observable UI change should
     * be reported as [com.andropilot.core.action.FailureReason.NO_EFFECT].
     *
     * On by default: silent no-ops are the single most common way an agent loop gets stuck,
     * and surfacing them is the point of a verification-first design. Hosts driving UIs that
     * legitimately do nothing visible (a toggle that only mutates a backend) can turn it off.
     */
    public val treatNoEffectAsFailure: Boolean = true,
    public val policy: SafetyPolicy = DefaultSafetyPolicy(),
    public val visionProvider: VisionProvider? = null,
    public val vision: VisionConfig = VisionConfig.DEFAULT,
    public val logger: AgentLogger = AgentLogger.NONE,
    public val logLevel: LogLevel = LogLevel.INFO,
    /**
     * When false (the default), screen text and typed values never appear in logs or traces.
     * Turn it on only for local debugging.
     */
    public val allowTextInLogs: Boolean = false,
    /** Size of the in-memory action trace ring. */
    public val traceCapacity: Int = 200,
    /**
     * Listeners notified of every [com.andropilot.core.observe.AgentEvent].
     *
     * Registered here rather than subscribed to later so that nothing is missed: a listener
     * attached after the session starts would not see what already happened. These are
     * called synchronously and in order, so a recorder or a live inspector gets every event;
     * see [com.andropilot.core.observe.AgentEventListener] for the cost of that guarantee.
     */
    public val listeners: List<com.andropilot.core.observe.AgentEventListener> = emptyList(),
    public val maxElements: Int = 400,
) {
    public val redactor: Redactor get() = Redactor(allowTextContent = allowTextInLogs)

    public companion object {
        public val DEFAULT: SessionConfig = SessionConfig()

        /** Verbose, unredacted, permissive. For local development only. */
        public fun debug(): SessionConfig = SessionConfig(
            policy = DefaultSafetyPolicy.permissive(),
            logger = AgentLogger.console(LogLevel.DEBUG),
            logLevel = LogLevel.DEBUG,
            allowTextInLogs = true,
        )
    }
}
