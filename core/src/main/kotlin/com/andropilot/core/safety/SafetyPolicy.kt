package com.andropilot.core.safety

import com.andropilot.core.action.AgentAction
import com.andropilot.core.model.UiElement
import com.andropilot.core.model.UiSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How consequential an action is.
 *
 * The SDK classifies; the host application decides. Nothing here is a security boundary
 * against the *user* -- it is a guard rail so an integrating app can keep an autonomous
 * agent from doing something irreversible without a human in the loop.
 */
@Serializable
public enum class RiskLevel {
    /** Reading the screen. No side effects at all. */
    @SerialName("read_only") READ_ONLY,

    /** Navigation and inspection: scrolling, back, opening a screen. */
    @SerialName("navigation") NAVIGATION,

    /** Mutates app state in a way the user can usually undo. */
    @SerialName("mutating") MUTATING,

    /** Sends, pays, deletes, changes credentials, or is otherwise hard to undo. */
    @SerialName("sensitive") SENSITIVE,
    ;

    public fun atLeast(other: RiskLevel): Boolean = ordinal >= other.ordinal
}

/** The verdict a [SafetyPolicy] returns for a proposed action. */
@Serializable
public sealed interface PolicyDecision {

    @Serializable
    @SerialName("allow")
    public data class Allow(val risk: RiskLevel) : PolicyDecision

    /** Run it, but only after the host confirms. */
    @Serializable
    @SerialName("confirm")
    public data class RequireConfirmation(
        val risk: RiskLevel,
        val reasons: List<String>,
    ) : PolicyDecision

    @Serializable
    @SerialName("deny")
    public data class Deny(val reason: String) : PolicyDecision
}

/**
 * Decides whether a proposed action may run.
 *
 * Implementations are consulted **before** the action touches the device and see the
 * current [UiSnapshot] plus the resolved [target] when one exists, so a policy can key off
 * what is actually on screen ("this button says Pay £240") rather than only the request.
 */
public fun interface SafetyPolicy {
    public fun evaluate(action: AgentAction, snapshot: UiSnapshot?, target: UiElement?): PolicyDecision
}

/**
 * The shipped default: classifies risk heuristically, allows everything up to
 * [confirmAtOrAbove], and asks for confirmation beyond it.
 *
 * The heuristics are intentionally conservative and keyword-driven. They will produce false
 * positives; that is the correct bias for a component that can spend a user's money. A host
 * that wants different behaviour supplies its own [SafetyPolicy] -- this class is a sane
 * default, not a security guarantee.
 */
public class DefaultSafetyPolicy(
    /** Actions at or above this level require host confirmation. */
    private val confirmAtOrAbove: RiskLevel = RiskLevel.SENSITIVE,
    /** Intent actions the host is willing to let an agent fire. Empty denies all intents. */
    private val allowedIntentActions: Set<String> = DEFAULT_ALLOWED_INTENTS,
    /** Packages the agent may drive. Empty means "any". */
    private val allowedPackages: Set<String> = emptySet(),
    /** Additional label keywords that should be treated as sensitive. */
    extraSensitiveKeywords: Set<String> = emptySet(),
) : SafetyPolicy {

    private val sensitiveKeywords = SENSITIVE_KEYWORDS + extraSensitiveKeywords.map { it.lowercase() }

    override fun evaluate(
        action: AgentAction,
        snapshot: UiSnapshot?,
        target: UiElement?,
    ): PolicyDecision {
        if (action is AgentAction.OpenIntent && action.action !in allowedIntentActions) {
            return PolicyDecision.Deny(
                "Intent action '${action.action}' is not in the host's allow-list.",
            )
        }
        if (action is AgentAction.LaunchApp && allowedPackages.isNotEmpty() &&
            action.packageName !in allowedPackages
        ) {
            return PolicyDecision.Deny(
                "Package '${action.packageName}' is not in the host's allow-list.",
            )
        }
        if (allowedPackages.isNotEmpty() && snapshot?.packageName != null &&
            snapshot.packageName !in allowedPackages && action.mutatesState()
        ) {
            return PolicyDecision.Deny(
                "The foreground app '${snapshot.packageName}' is outside the host's allow-list.",
            )
        }

        val reasons = ArrayList<String>(2)
        val risk = classify(action, target, reasons)
        return if (risk.atLeast(confirmAtOrAbove)) {
            PolicyDecision.RequireConfirmation(risk, reasons)
        } else {
            PolicyDecision.Allow(risk)
        }
    }

    /** Risk classification, exposed so hosts can reuse it inside a custom policy. */
    public fun classify(
        action: AgentAction,
        target: UiElement?,
        reasons: MutableList<String> = ArrayList(),
    ): RiskLevel = when (action) {
        is AgentAction.Observe,
        is AgentAction.Screenshot,
        is AgentAction.FindElement,
        is AgentAction.ElementExists,
        is AgentAction.WaitFor,
        is AgentAction.Verify,
        is AgentAction.Sleep,
        -> RiskLevel.READ_ONLY

        is AgentAction.Scroll,
        is AgentAction.ScrollUntil,
        is AgentAction.Swipe,
        -> RiskLevel.NAVIGATION

        is AgentAction.PressKey -> RiskLevel.NAVIGATION

        is AgentAction.LaunchApp, is AgentAction.OpenIntent -> RiskLevel.NAVIGATION

        is AgentAction.TypeText -> if (action.sensitive) {
            reasons += "The text was marked as a credential or one-time code."
            RiskLevel.SENSITIVE
        } else {
            labelRisk(target, reasons).takeIf { it == RiskLevel.SENSITIVE } ?: RiskLevel.MUTATING
        }

        is AgentAction.ClearText -> RiskLevel.MUTATING

        is AgentAction.Click, is AgentAction.LongPress, is AgentAction.ClickPoint ->
            labelRisk(target, reasons)
    }

    private fun labelRisk(target: UiElement?, reasons: MutableList<String>): RiskLevel {
        if (target?.password == true) {
            reasons += "The target is a password field."
            return RiskLevel.SENSITIVE
        }
        val label = target?.label?.lowercase() ?: return RiskLevel.MUTATING
        val hit = sensitiveKeywords.firstOrNull { keyword -> label.containsWord(keyword) }
        return if (hit != null) {
            reasons += "The target is labelled \"${target.label}\", which matches the sensitive keyword \"$hit\"."
            RiskLevel.SENSITIVE
        } else {
            RiskLevel.MUTATING
        }
    }

    private fun AgentAction.mutatesState(): Boolean = when (this) {
        is AgentAction.Click, is AgentAction.ClickPoint, is AgentAction.LongPress,
        is AgentAction.TypeText, is AgentAction.ClearText, is AgentAction.OpenIntent,
        -> true
        else -> false
    }

    private fun String.containsWord(word: String): Boolean {
        var from = 0
        while (true) {
            val idx = indexOf(word, from)
            if (idx < 0) return false
            val beforeOk = idx == 0 || !this[idx - 1].isLetterOrDigit()
            val endIdx = idx + word.length
            val afterOk = endIdx >= length || !this[endIdx].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            from = idx + 1
        }
    }

    public companion object {
        /**
         * Labels that, on a button, usually mean money moved, a message left the device,
         * or something got destroyed.
         */
        public val SENSITIVE_KEYWORDS: Set<String> = setOf(
            "send", "pay", "purchase", "buy", "order", "checkout", "confirm order",
            "subscribe", "transfer", "withdraw", "donate", "tip",
            "delete", "remove", "erase", "wipe", "clear all", "reset", "format",
            "uninstall", "deactivate", "close account", "unsubscribe",
            "sign out", "log out", "change password", "change email", "verify",
            "authorize", "authorise", "grant", "allow", "accept", "agree",
            "publish", "post", "share", "submit", "apply", "install",
        )

        /** Navigation-grade intents only. Anything wider is the host's explicit choice. */
        public val DEFAULT_ALLOWED_INTENTS: Set<String> = setOf(
            "android.intent.action.VIEW",
            "android.intent.action.MAIN",
        )

        /** A policy that never asks, for tests and trusted offline harnesses. */
        public fun permissive(): SafetyPolicy = SafetyPolicy { action, _, target ->
            PolicyDecision.Allow(DefaultSafetyPolicy().classify(action, target))
        }

        /** A policy that confirms anything with a side effect. */
        public fun strict(): SafetyPolicy = DefaultSafetyPolicy(confirmAtOrAbove = RiskLevel.MUTATING)
    }
}

/** How a host answers a [com.andropilot.core.action.PendingConfirmation]. */
public enum class ConfirmationOutcome { APPROVED, REJECTED }
