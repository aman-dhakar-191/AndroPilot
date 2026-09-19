package com.andropilot.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What changed between two snapshots.
 *
 * Verification -- "did my tap actually do anything?" -- is the single most valuable signal
 * the SDK can give an agent, so the diff is a first-class result type rather than an
 * afterthought.
 */
@Serializable
public data class UiDiff(
    @SerialName("app_changed") val appChanged: Boolean,
    @SerialName("window_changed") val windowChanged: Boolean,
    @SerialName("structure_changed") val structureChanged: Boolean,
    @SerialName("dialog_appeared") val dialogAppeared: Boolean,
    @SerialName("dialog_dismissed") val dialogDismissed: Boolean,
    @SerialName("keyboard_toggled") val keyboardToggled: Boolean,
    @SerialName("added_labels") val addedLabels: List<String> = emptyList(),
    @SerialName("removed_labels") val removedLabels: List<String> = emptyList(),
    /** Elements that kept their identity but changed checked/selected/enabled/text. */
    @SerialName("changed_elements") val changedElements: List<ElementChange> = emptyList(),
    /** Fraction of interactive elements that scrolled by a consistent vertical offset. */
    @SerialName("scroll_delta_y") val scrollDeltaY: Int = 0,
) {
    /** The headline answer to "did anything happen?". */
    public val changed: Boolean
        get() = appChanged || windowChanged || structureChanged ||
            dialogAppeared || dialogDismissed || keyboardToggled ||
            addedLabels.isNotEmpty() || removedLabels.isNotEmpty() ||
            changedElements.isNotEmpty() || scrollDeltaY != 0

    public fun summarize(): String = when {
        !changed -> "no observable change"
        appChanged -> "foreground app changed"
        dialogAppeared -> "a dialog appeared"
        dialogDismissed -> "a dialog was dismissed"
        windowChanged -> "the window changed"
        scrollDeltaY != 0 -> "content scrolled by ${scrollDeltaY}px"
        structureChanged -> buildString {
            append("screen content changed")
            if (addedLabels.isNotEmpty()) append("; new: ${addedLabels.take(3).joinToString()}")
            if (removedLabels.isNotEmpty()) append("; gone: ${removedLabels.take(3).joinToString()}")
        }
        changedElements.isNotEmpty() ->
            "element state changed: " + changedElements.take(3).joinToString { it.summary }
        keyboardToggled -> "the keyboard was shown or hidden"
        else -> "minor change"
    }

    public companion object {
        public val NONE: UiDiff = UiDiff(
            appChanged = false,
            windowChanged = false,
            structureChanged = false,
            dialogAppeared = false,
            dialogDismissed = false,
            keyboardToggled = false,
        )

        /**
         * Computes the difference between [before] and [after].
         *
         * Element identity across snapshots is matched on the stable parts of an element:
         * resource id first, then role + label, then geometry overlap. Ids alone are not
         * trusted because a recomposed tree renumbers them.
         */
        public fun between(before: UiSnapshot, after: UiSnapshot): UiDiff {
            val appChanged = before.packageName != after.packageName
            val windowChanged = appChanged || before.windowTitle != after.windowTitle

            val beforeLabels = before.interactiveLabelSet()
            val afterLabels = after.interactiveLabelSet()
            val added = (afterLabels - beforeLabels).take(20)
            val removed = (beforeLabels - afterLabels).take(20)

            val changes = matchElements(before, after)
            val scroll = detectScroll(before, after)

            return UiDiff(
                appChanged = appChanged,
                windowChanged = windowChanged,
                structureChanged = before.structuralSignature() != after.structuralSignature(),
                dialogAppeared = !before.hasDialog && after.hasDialog,
                dialogDismissed = before.hasDialog && !after.hasDialog,
                keyboardToggled = before.keyboardVisible != after.keyboardVisible,
                addedLabels = added.toList(),
                removedLabels = removed.toList(),
                changedElements = changes,
                scrollDeltaY = scroll,
            )
        }

        private fun UiSnapshot.interactiveLabelSet(): Set<String> =
            elements.asSequence()
                .filter { it.visible && it.label != null }
                .mapNotNull { it.label?.trim()?.takeIf(String::isNotEmpty) }
                .toSet()

        /** Identity key that survives tree recomposition, most specific first. */
        private fun identityKeys(e: UiElement): List<String> = buildList {
            e.resourceId?.let { add("r:$it") }
            e.label?.let { add("l:${e.role}:${it.trim()}") }
        }

        private fun matchElements(before: UiSnapshot, after: UiSnapshot): List<ElementChange> {
            val afterByKey = HashMap<String, UiElement>()
            after.elements.forEach { e -> identityKeys(e).forEach { afterByKey.putIfAbsent(it, e) } }

            val out = ArrayList<ElementChange>()
            for (old in before.elements) {
                if (!old.visible) continue
                val new = identityKeys(old).firstNotNullOfOrNull(afterByKey::get) ?: continue
                val deltas = buildList {
                    if (old.checked != new.checked) add("checked ${old.checked}->${new.checked}")
                    if (old.selected != new.selected) add("selected ${old.selected}->${new.selected}")
                    if (old.enabled != new.enabled) add("enabled ${old.enabled}->${new.enabled}")
                    if (old.editable && old.text != new.text) {
                        add("text \"${old.text.orEmpty().take(24)}\"->\"${new.text.orEmpty().take(24)}\"")
                    }
                }
                if (deltas.isNotEmpty()) {
                    out += ElementChange(
                        elementId = new.id,
                        label = new.label,
                        summary = "${new.role.name.lowercase()} ${new.label.orEmpty()}: ${deltas.joinToString("; ")}".trim(),
                    )
                }
                if (out.size >= 20) break
            }
            return out
        }

        /**
         * Detects whole-list movement by finding the most common vertical offset among
         * elements that appear in both snapshots. A single dominant offset means the
         * content scrolled rather than being replaced.
         */
        private fun detectScroll(before: UiSnapshot, after: UiSnapshot): Int {
            if (before.packageName != after.packageName) return 0
            val afterByKey = HashMap<String, UiElement>()
            after.elements.forEach { e -> identityKeys(e).forEach { afterByKey.putIfAbsent(it, e) } }

            val offsets = ArrayList<Int>()
            for (old in before.elements) {
                if (!old.visible || old.bounds.isEmpty) continue
                val new = identityKeys(old).firstNotNullOfOrNull(afterByKey::get) ?: continue
                if (old.bounds.left != new.bounds.left) continue // horizontal move: not a vertical scroll
                offsets += new.bounds.top - old.bounds.top
            }
            if (offsets.size < 3) return 0
            val tolerance = maxOf(2, before.metrics.heightPx / 200)
            val buckets = offsets.groupBy { it / maxOf(1, tolerance) }
            val (_, dominant) = buckets.maxByOrNull { it.value.size } ?: return 0
            if (dominant.size * 2 < offsets.size) return 0 // no clear consensus
            val median = dominant.sorted()[dominant.size / 2]
            return if (kotlin.math.abs(median) <= tolerance) 0 else median
        }
    }
}

@Serializable
public data class ElementChange(
    @SerialName("element_id") val elementId: String,
    val label: String? = null,
    val summary: String,
)
