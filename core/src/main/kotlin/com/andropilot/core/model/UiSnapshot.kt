package com.andropilot.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A normalized, immutable description of everything the SDK can currently perceive about
 * the screen.
 *
 * This is the single "eyes" primitive of the SDK. It is deliberately flat: a list of
 * elements plus parent/child id links, rather than a nested tree. A flat list serializes
 * compactly, is cheap to filter, and lets an agent reference any element by id without
 * walking a structure.
 */
@Serializable
public data class UiSnapshot(
    /** Monotonic id for this observation, unique within a session. */
    @SerialName("snapshot_id") val snapshotId: String,
    /** `System.currentTimeMillis()` at capture time. */
    @SerialName("captured_at") val capturedAt: Long,
    /** Package of the app that owns the foreground window, when resolvable. */
    @SerialName("package_name") val packageName: String? = null,
    /** Activity/window title, when the app exposes one. */
    @SerialName("window_title") val windowTitle: String? = null,
    val metrics: ScreenMetrics = ScreenMetrics.DEFAULT,
    val elements: List<UiElement> = emptyList(),
    /** True when a system or app dialog owns input focus. */
    @SerialName("has_dialog") val hasDialog: Boolean = false,
    /** True when the keyboard (an IME window) is showing. */
    @SerialName("keyboard_visible") val keyboardVisible: Boolean = false,
    /** Set when perception was degraded, e.g. the a11y tree was empty or truncated. */
    val warnings: List<String> = emptyList(),
) {
    @Transient
    private val byId: Map<String, UiElement> = elements.associateBy { it.id }

    public val root: UiElement? get() = elements.firstOrNull { it.parentId == null }

    public operator fun get(id: String): UiElement? = byId[id]

    public fun require(id: String): UiElement =
        byId[id] ?: error("No element '$id' in snapshot $snapshotId")

    public fun childrenOf(id: String): List<UiElement> =
        byId[id]?.childIds?.mapNotNull(byId::get).orEmpty()

    public fun parentOf(element: UiElement): UiElement? = element.parentId?.let(byId::get)

    /** Walks from [element] to the root, nearest ancestor first. */
    public fun ancestorsOf(element: UiElement): List<UiElement> {
        val out = ArrayList<UiElement>()
        var current = parentOf(element)
        val seen = HashSet<String>()
        while (current != null && seen.add(current.id)) {
            out += current
            current = parentOf(current)
        }
        return out
    }

    /** All descendants of [element] in depth-first order, excluding [element] itself. */
    public fun descendantsOf(element: UiElement): List<UiElement> {
        val out = ArrayList<UiElement>()
        val stack = ArrayDeque(element.childIds)
        val seen = HashSet<String>()
        while (stack.isNotEmpty()) {
            val next = byId[stack.removeFirst()] ?: continue
            if (!seen.add(next.id)) continue
            out += next
            next.childIds.asReversed().forEach(stack::addFirst)
        }
        return out
    }

    public val interactiveElements: List<UiElement>
        get() = elements.filter { it.isActionable }

    public val scrollableContainers: List<UiElement>
        get() = elements.filter { it.scrollable && it.visible && !it.bounds.isEmpty }

    /** The scrollable container with the largest visible area, the usual scroll target. */
    public val primaryScrollable: UiElement?
        get() = scrollableContainers.maxByOrNull { it.bounds.area }

    public val focusedElement: UiElement? get() = elements.firstOrNull { it.focused }

    public val editableElements: List<UiElement>
        get() = elements.filter { it.editable && it.enabled && it.visible }

    /** All non-blank user-visible strings, de-duplicated, in traversal order. */
    public val visibleText: List<String>
        get() = elements.asSequence()
            .filter { it.visible }
            .mapNotNull { it.text?.takeIf(String::isNotBlank) }
            .distinct()
            .toList()

    /**
     * A structural fingerprint used to answer "did the UI change?" cheaply and without
     * false positives from cursor blinks or timestamp labels.
     *
     * It covers package, dialog/keyboard state, and the role + label + coarse position of
     * every interactive element. Sub-pixel movement and non-interactive text churn (clocks,
     * progress percentages) deliberately do not affect it.
     */
    public fun structuralSignature(): String {
        val cell = maxOf(1, metrics.widthPx / 40)
        return buildString {
            append(packageName).append('|')
            append(if (hasDialog) 'D' else '-')
            append(if (keyboardVisible) 'K' else '-').append('|')
            elements.asSequence()
                .filter { it.isActionable }
                .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }, { it.id }))
                .forEach { e ->
                    append(e.role.name).append(':')
                    append(e.label?.trim()?.take(40) ?: "")
                    append('@').append(e.bounds.left / cell).append(',').append(e.bounds.top / cell)
                    append(if (e.enabled) '+' else '-')
                    append(if (e.checked) '*' else '.')
                    append(';')
                }
        }
    }

    /**
     * A token-efficient rendering for prompting an AI model.
     *
     * Non-interactive containers that contribute nothing (no label, not scrollable) are
     * collapsed away, and indentation encodes nesting. Cap the output with [maxElements]
     * when the screen is unusually dense.
     */
    public fun toCompactText(maxElements: Int = 120, includeBounds: Boolean = true): String {
        val kept = LinkedHashSet<String>()
        elements.forEach { e ->
            val informative = e.isActionable || e.label != null || e.role == ElementRole.DIALOG
            if (informative && e.visible) {
                kept += e.id
                // Keep ancestors so indentation stays meaningful.
                ancestorsOf(e).forEach { kept += it.id }
            }
        }
        val sb = StringBuilder()
        sb.append("screen package=").append(packageName ?: "unknown")
        windowTitle?.let { sb.append(" title=\"").append(it).append('"') }
        sb.append(" size=").append(metrics.widthPx).append('x').append(metrics.heightPx)
        sb.append(' ').append(metrics.orientation.name.lowercase())
        if (hasDialog) sb.append(" dialog")
        if (keyboardVisible) sb.append(" keyboard")
        sb.append('\n')

        var emitted = 0
        for (e in elements) {
            if (e.id !in kept) continue
            if (emitted >= maxElements) {
                sb.append("... ").append(kept.size - emitted).append(" more elements omitted\n")
                break
            }
            repeat(minOf(e.depth, 12)) { sb.append("  ") }
            sb.append('[').append(e.id).append("] ").append(e.role.name.lowercase())
            e.label?.let { sb.append(" \"").append(it.take(80)).append('"') }
            e.resourceId?.let { sb.append(" #").append(it.substringAfterLast('/')) }
            val flags = buildList {
                if (e.clickable) add("clickable")
                if (e.longClickable) add("long-clickable")
                if (e.editable) add("editable")
                if (e.scrollable) add("scrollable")
                if (!e.enabled) add("disabled")
                if (e.checkable) add(if (e.checked) "checked" else "unchecked")
                if (e.selected) add("selected")
                if (e.focused) add("focused")
                if (e.source == PerceptionSource.VISUAL) add("visual")
            }
            if (flags.isNotEmpty()) sb.append(' ').append(flags.joinToString(","))
            if (includeBounds) sb.append(' ').append(e.bounds)
            sb.append('\n')
            emitted++
        }
        warnings.forEach { sb.append("warning: ").append(it).append('\n') }
        return sb.toString()
    }

    public companion object {
        /** An explicit "nothing perceivable" snapshot, used when the a11y tree is empty. */
        public fun empty(
            snapshotId: String,
            capturedAt: Long,
            metrics: ScreenMetrics = ScreenMetrics.DEFAULT,
            warning: String = "No accessibility nodes were available for the active window.",
        ): UiSnapshot = UiSnapshot(
            snapshotId = snapshotId,
            capturedAt = capturedAt,
            metrics = metrics,
            warnings = listOf(warning),
        )
    }
}
