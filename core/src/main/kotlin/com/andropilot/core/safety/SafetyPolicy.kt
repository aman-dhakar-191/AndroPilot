package com.andropilot.core.safety

import com.andropilot.core.action.AgentAction
import com.andropilot.core.model.ElementRole
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

/**
 * What kind of harm an action risks, as distinct from how much.
 *
 * [RiskLevel] says how bad; this says of what sort. The two are independent, and separating
 * them is what lets a host say "ask me about money, but posting and deleting can proceed" --
 * a perfectly ordinary position that a single severity scale cannot express, because paying
 * and deleting sit at the same level on it.
 */
@Serializable
public enum class RiskCategory {
    /** Money moves: paying, buying, transferring, withdrawing. */
    @SerialName("financial") FINANCIAL,

    /** Something is destroyed: deleting, erasing, uninstalling, closing an account. */
    @SerialName("destructive") DESTRUCTIVE,

    /** Something leaves the device: sending, posting, publishing. */
    @SerialName("communication") COMMUNICATION,

    /** Credentials and identity: passwords, one-time codes, signing out, authorising. */
    @SerialName("credential") CREDENTIAL,

    /** Everything else with a side effect. */
    @SerialName("other") OTHER,
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
 * Risk is decided by structure first and vocabulary second: a password field or a
 * caller-marked credential is decisive, an irreversible keyword escalates on its own, and an
 * ambiguous keyword escalates only when the target sits inside a dialog.
 *
 * The temptation is to flag everything that sounds alarming, on the theory that over-asking
 * is the safe direction. It is not. A policy that interrupts every form submission trains the
 * human to approve without reading, which is precisely when the one prompt that mattered goes
 * through unexamined. Prompting is a budget, and this class spends it on the actions a person
 * would actually want to be stopped for.
 *
 * It is a sane default, not a security guarantee. A host that wants different behaviour
 * supplies its own [SafetyPolicy]; [strict] confirms every side effect for callers who would
 * rather have the noise.
 */
public class DefaultSafetyPolicy(
    /** Actions at or above this level require host confirmation. */
    private val confirmAtOrAbove: RiskLevel = RiskLevel.SENSITIVE,
    /**
     * Actions at or above this level are refused outright, without asking.
     *
     * For a session nobody is watching. Asking and denying differ only when someone is
     * there to answer: with no one at the device a confirmation is a block that never
     * clears, and the agent learns that only by timing out. A denial says so immediately
     * and names why. Null, the default, never denies on risk alone.
     */
    private val denyAtOrAbove: RiskLevel? = null,
    /**
     * The only categories that are ever gated. Anything outside this set is allowed
     * whatever its level, so narrowing it is how a host says which harms it cares about.
     */
    private val gatedCategories: Set<RiskCategory> = RiskCategory.entries.toSet(),
    /** Intent actions the host is willing to let an agent fire. Empty denies all intents. */
    private val allowedIntentActions: Set<String> = DEFAULT_ALLOWED_INTENTS,
    /** Packages the agent may drive. Empty means "any". */
    private val allowedPackages: Set<String> = emptySet(),
    /** Extra label keywords the host wants treated as unconditionally sensitive. */
    extraSensitiveKeywords: Set<String> = emptySet(),
) : SafetyPolicy {

    private val irreversibleKeywords =
        IRREVERSIBLE_KEYWORDS + extraSensitiveKeywords.map { it.lowercase() }
    private val contextualKeywords = CONTEXTUAL_KEYWORDS

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
        val risk = classify(action, snapshot, target, reasons)
        val category = categorize(action, target)
        if (category !in gatedCategories) {
            // Not a harm this host asked to be stopped for.
            return PolicyDecision.Allow(risk)
        }
        return when {
            denyAtOrAbove != null && risk.atLeast(denyAtOrAbove) -> PolicyDecision.Deny(
                "This action is classified ${risk.name.lowercase()} and the policy refuses " +
                    "anything at or above ${denyAtOrAbove.name.lowercase()} without a human " +
                    "present." + reasons.joinToString(prefix = " ", separator = " "),
            )
            risk.atLeast(confirmAtOrAbove) -> PolicyDecision.RequireConfirmation(risk, reasons)
            else -> PolicyDecision.Allow(risk)
        }
    }

    /** Risk classification, exposed so hosts can reuse it inside a custom policy. */
    public fun classify(
        action: AgentAction,
        snapshot: UiSnapshot?,
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
        is AgentAction.ListApps,
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
            labelRisk(snapshot, target, reasons).takeIf { it == RiskLevel.SENSITIVE } ?: RiskLevel.MUTATING
        }

        is AgentAction.ClearText -> RiskLevel.MUTATING

        is AgentAction.Click, is AgentAction.LongPress, is AgentAction.ClickPoint ->
            labelRisk(snapshot, target, reasons)
    }

    /**
     * Risk of acting on [target], in order of how much the signal can be trusted.
     *
     *  1. **Structural signals** -- a password field, or text the caller marked sensitive.
     *     These come from the app or the caller, not from guessing, so they are decisive.
     *  2. **Irreversible keywords** -- words that are rarely anything but the real thing
     *     ("pay", "delete", "withdraw"). These escalate on their own.
     *  3. **Contextual keywords** -- words that appear on ordinary buttons far more often
     *     than on dangerous ones ("submit", "allow", "remove"). These escalate only when
     *     structure corroborates them, which in practice means the target sits inside a
     *     dialog.
     *
     * The third tier is the whole point. "Submit" on a form is a form; "Submit" inside a
     * modal is the commit step of something the app thought worth interrupting the user
     * for. Treating both as sensitive teaches the human to approve without reading, which
     * costs more safety than the extra prompts buy.
     */
    /**
     * What kind of harm [action] on [target] risks.
     *
     * Read off the same label the level is, so the two stay consistent: an action is
     * financial because its button says "Pay", not because of anything the caller declared.
     * Credential markers win, since a password field or a value the caller flagged is
     * structural rather than a guess about wording.
     */
    public fun categorize(action: AgentAction, target: UiElement?): RiskCategory {
        if (action is AgentAction.TypeText && action.sensitive) return RiskCategory.CREDENTIAL
        if (target?.password == true) return RiskCategory.CREDENTIAL
        val label = target?.label?.lowercase() ?: return RiskCategory.OTHER
        return when {
            FINANCIAL_KEYWORDS.any { label.containsWord(it) } -> RiskCategory.FINANCIAL
            DESTRUCTIVE_KEYWORDS.any { label.containsWord(it) } -> RiskCategory.DESTRUCTIVE
            COMMUNICATION_KEYWORDS.any { label.containsWord(it) } -> RiskCategory.COMMUNICATION
            CREDENTIAL_KEYWORDS.any { label.containsWord(it) } -> RiskCategory.CREDENTIAL
            else -> RiskCategory.OTHER
        }
    }

    private fun labelRisk(
        snapshot: UiSnapshot?,
        target: UiElement?,
        reasons: MutableList<String>,
    ): RiskLevel {
        if (target?.password == true) {
            reasons += "The target is a password field."
            return RiskLevel.SENSITIVE
        }
        val label = target?.label?.lowercase() ?: return RiskLevel.MUTATING

        irreversibleKeywords.firstOrNull { label.containsWord(it) }?.let { hit ->
            reasons += "The target is labelled \"${target.label}\", which matches the " +
                "irreversible-action keyword \"$hit\"."
            return RiskLevel.SENSITIVE
        }

        val contextual = contextualKeywords.firstOrNull { label.containsWord(it) }
        if (contextual != null && isCommitPoint(snapshot, target)) {
            reasons += "The target is labelled \"${target.label}\" and sits inside a dialog, " +
                "so \"$contextual\" is being treated as a confirmation step rather than " +
                "ordinary navigation."
            return RiskLevel.SENSITIVE
        }

        return RiskLevel.MUTATING
    }

    /**
     * Whether the target is the commit button of something modal.
     *
     * Ancestry is preferred over the snapshot's `hasDialog` flag: a screen can have a
     * dialog open while the agent acts on something behind it, and only the element's own
     * position in the tree says which side of that it is on. The flag is the fallback for
     * snapshots whose ancestry is unavailable (a truncated tree, or a visually-detected
     * element with no parent links).
     */
    private fun isCommitPoint(snapshot: UiSnapshot?, target: UiElement): Boolean {
        snapshot ?: return false
        val ancestors = snapshot.ancestorsOf(target)
        if (ancestors.isNotEmpty()) {
            return ancestors.any { it.role == ElementRole.DIALOG }
        }
        return snapshot.hasDialog
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
         * Labels that almost always mean money moved, a message left the device, or
         * something was destroyed.
         *
         * Entries earn their place by having a low false-positive rate in real UIs, not
         * just by sounding alarming. "order" is absent because "Order history" and "Sort
         * order" are far more common than an order being placed; the committing phrases
         * are listed instead. Matching is word-bounded, so "Posts", "Resend" and
         * "Addendum" do not match "post", "send" or "send".
         */
        /** Money moves. */
        public val FINANCIAL_KEYWORDS: Set<String> = setOf(
            "pay", "purchase", "buy", "checkout", "place order", "transfer", "withdraw",
            "donate",
        )

        /** Something is destroyed. */
        public val DESTRUCTIVE_KEYWORDS: Set<String> = setOf(
            "delete", "erase", "wipe", "uninstall", "deactivate", "close account",
        )

        /** Something leaves the device. */
        public val COMMUNICATION_KEYWORDS: Set<String> = setOf(
            "send", "publish", "post",
        )

        /** Credentials and identity. */
        public val CREDENTIAL_KEYWORDS: Set<String> = setOf(
            "change password", "change email", "sign out", "log out",
            "authorize", "authorise",
        )

        public val IRREVERSIBLE_KEYWORDS: Set<String> =
            FINANCIAL_KEYWORDS + DESTRUCTIVE_KEYWORDS + COMMUNICATION_KEYWORDS +
                CREDENTIAL_KEYWORDS

        /**
         * Labels that are dangerous in a confirmation dialog and unremarkable anywhere
         * else, so they escalate only when the target sits inside one.
         *
         * Every word here is on a benign button in some app you have used this week:
         * "Submit" on a form, "Apply" on a filter sheet, "Allow" on a cookie banner,
         * "Remove" on a chip, "Reset" on a search. Escalating them unconditionally is what
         * turns confirmation into a reflex.
         */
        public val CONTEXTUAL_KEYWORDS: Set<String> = setOf(
            "confirm", "submit", "apply", "accept", "agree", "allow", "grant", "continue",
            "remove", "clear", "reset", "discard", "revoke", "unlink", "disconnect",
            "install", "update", "share", "subscribe", "unsubscribe", "verify", "format",
        )

        /** Navigation-grade intents only. Anything wider is the host's explicit choice. */
        public val DEFAULT_ALLOWED_INTENTS: Set<String> = setOf(
            "android.intent.action.VIEW",
            "android.intent.action.MAIN",
        )

        /** A policy that never asks, for tests and trusted offline harnesses. */
        public fun permissive(): SafetyPolicy = SafetyPolicy { action, snapshot, target ->
            PolicyDecision.Allow(DefaultSafetyPolicy().classify(action, snapshot, target))
        }

        /** A policy that confirms anything with a side effect. */
        public fun strict(): SafetyPolicy = DefaultSafetyPolicy(confirmAtOrAbove = RiskLevel.MUTATING)

        /**
         * Asks about money and nothing else.
         *
         * For an agent that is trusted to get on with ordinary work but should stop before
         * spending. Everything outside [RiskCategory.FINANCIAL] is allowed whatever its
         * level, including deleting and sending.
         *
         * With nobody at the device a financial action stops and stays pending rather than
         * failing: pending confirmations do not expire, so it can be approved whenever
         * someone next picks the phone up, and approving runs it. Only *granted* approvals
         * age out.
         */
        public fun financialOnly(): SafetyPolicy = DefaultSafetyPolicy(
            confirmAtOrAbove = RiskLevel.SENSITIVE,
            gatedCategories = setOf(RiskCategory.FINANCIAL),
        )

        /**
         * For a session running with nobody at the device.
         *
         * Never asks, because there is no one to ask. Runs navigation and ordinary state
         * changes, and refuses anything at or above [denyAtOrAbove] outright rather than
         * raising a confirmation that will never be answered.
         *
         * The ceiling is the point. [permissive] would also never ask, and would also let an
         * unattended agent tap "Delete account" or "Pay"; this draws a line the agent cannot
         * cross while nobody is watching, and says so in the failure when it tries.
         */
        public fun unattended(
            denyAtOrAbove: RiskLevel = RiskLevel.SENSITIVE,
        ): SafetyPolicy = DefaultSafetyPolicy(
            // Never reached: denial is evaluated first and at the same or a lower
            // threshold, so anything that would have been confirmed is refused instead.
            confirmAtOrAbove = RiskLevel.SENSITIVE,
            denyAtOrAbove = denyAtOrAbove,
        )
    }
}

/** How a host answers a [com.andropilot.core.action.PendingConfirmation]. */
@Serializable
public enum class ConfirmationOutcome { APPROVED, REJECTED }
