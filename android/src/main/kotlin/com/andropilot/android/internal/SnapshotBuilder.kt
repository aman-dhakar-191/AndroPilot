package com.andropilot.android.internal

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.andropilot.core.model.Bounds
import com.andropilot.core.model.ElementRetention
import com.andropilot.core.model.ElementRole
import com.andropilot.core.model.ScreenMetrics
import com.andropilot.core.model.UiAction
import com.andropilot.core.model.UiElement
import com.andropilot.core.model.UiSnapshot

/**
 * Converts a live `AccessibilityNodeInfo` tree into a [UiSnapshot].
 *
 * Three things make this harder than a naive walk, and each is handled explicitly:
 *
 *  1. **Node recycling.** Every node obtained here is released in a `finally`, including on
 *     the error paths. Leaked nodes exhaust the binder cache and make later traversals
 *     silently return null.
 *  2. **Unbounded and cyclic trees.** Some apps (notably WebViews and badly-behaved custom
 *     views) present very deep or self-referential hierarchies. Depth and node count are
 *     both capped, and visited nodes are tracked.
 *  3. **Noise.** A raw tree carries many nodes that are invisible, zero-sized, or carry
 *     neither text nor behaviour. [ElementRetention] decides which of those to drop, and it
 *     is applied after the subtree has been walked: a container is only worth keeping if
 *     something beneath it survived.
 *
 * The builder is pure with respect to the SDK: it reads the platform and returns core model
 * types, so nothing downstream ever touches an Android class.
 */
internal class SnapshotBuilder(
    private val maxDepth: Int = 40,
    private val maxNodes: Int = 1500,
) {

    fun build(
        rootNode: AccessibilityNodeInfo?,
        metrics: ScreenMetrics,
        packageName: String?,
        windowTitle: String?,
        keyboardVisible: Boolean,
        hasDialog: Boolean,
        snapshotId: String,
        capturedAt: Long,
    ): UiSnapshot {
        if (rootNode == null) {
            return UiSnapshot.empty(snapshotId, capturedAt, metrics)
        }
        val elements = ArrayList<UiElement>(64)
        val warnings = ArrayList<String>(2)
        val counter = IntArray(1)
        val handles = LinkedHashMap<String, String>()

        traverse(
            node = rootNode,
            parentId = null,
            depth = 0,
            metrics = metrics,
            out = elements,
            counter = counter,
            warnings = warnings,
            handles = handles,
        )

        if (elements.isEmpty()) {
            return UiSnapshot.empty(
                snapshotId, capturedAt, metrics,
                "The window reported a root node but no usable children; the app may not " +
                    "implement accessibility, or the screen may be marked secure.",
            )
        }

        return UiSnapshot(
            snapshotId = snapshotId,
            capturedAt = capturedAt,
            packageName = packageName,
            windowTitle = windowTitle,
            metrics = metrics,
            elements = elements,
            hasDialog = hasDialog,
            keyboardVisible = keyboardVisible,
            warnings = warnings,
            nodeHandles = handles,
        )
    }

    /**
     * Depth-first walk, assigning each retained node a short id and recording the tree path
     * the driver needs to find it again.
     *
     * Returns the id it assigned, or null when the node dropped itself. Callers use that to
     * link children, so a dropped node is never referenced by its parent.
     *
     * The path stays index-based ("0.3.1") because that is what re-resolves against a live
     * tree, and re-resolving is what makes a changed screen fail as STALE_ELEMENT instead of
     * acting on whatever moved into the slot. It simply no longer doubles as the id: an
     * agent reads ids on every line and gains nothing from the tree position.
     */
    private fun traverse(
        node: AccessibilityNodeInfo,
        parentId: String?,
        depth: Int,
        metrics: ScreenMetrics,
        out: MutableList<UiElement>,
        counter: IntArray,
        warnings: MutableList<String>,
        handles: MutableMap<String, String>,
        path: String = "0",
    ): String? {
        if (depth > maxDepth) {
            if (warnings.none { it.startsWith("The hierarchy exceeded") }) {
                warnings += "The hierarchy exceeded $maxDepth levels and was truncated."
            }
            return null
        }
        if (counter[0] >= maxNodes) {
            if (warnings.none { it.startsWith("More than") }) {
                warnings += "More than $maxNodes nodes were present; the tree was truncated."
            }
            return null
        }
        counter[0]++

        // Derived from the node budget counter, which only ever increments. Reusing
        // handles.size would also produce dense ids, but only because a dropped node is
        // always the most recently added one -- an invariant a later edit could break
        // silently. Gaps where a node dropped itself cost nothing; a collision would not.
        val id = "e${counter[0]}"
        val childIds = ArrayList<String>(node.childCount.coerceAtMost(64))
        val element = toElement(node, id, parentId, depth, metrics, childIds)

        // Reserve this node's slot before recursing, so children land after it in traversal
        // order. Whether it earns the slot cannot be decided until the subtree is filtered.
        val insertAt = out.size
        out.add(element)
        handles[id] = path

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            try {
                val childId = traverse(
                    node = child,
                    parentId = id,
                    depth = depth + 1,
                    metrics = metrics,
                    out = out,
                    counter = counter,
                    warnings = warnings,
                    handles = handles,
                    path = "$path.$i",
                )
                if (childId != null) childIds += childId
            } finally {
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
            }
        }

        if (!ElementRetention.shouldKeep(element, hasSurvivingChildren = childIds.isNotEmpty())) {
            // Nothing survived below it, so its slot is still the last one: removing it here
            // cannot disturb anything already emitted.
            out.removeAt(insertAt)
            handles.remove(id)
            return null
        }

        // `childIds` is a mutable list that was also handed to `toElement`, so it is copied
        // here rather than aliased: a UiElement is documented as an immutable value and is
        // shared with callers, including across serialization.
        out[insertAt] = element.copy(childIds = childIds.toList())
        return id
    }

    private fun toElement(
        node: AccessibilityNodeInfo,
        id: String,
        parentId: String?,
        depth: Int,
        metrics: ScreenMetrics,
        childIds: List<String>,
    ): UiElement {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
        val className = node.className?.toString()
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val description = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val hint = runCatching { node.hintText?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }

        return UiElement(
            id = id,
            role = roleOf(node, className),
            bounds = bounds,
            text = text,
            contentDescription = description,
            hint = hint,
            resourceId = node.viewIdResourceName?.takeIf { it.isNotBlank() },
            className = className,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            focusable = node.isFocusable,
            focused = node.isFocused,
            editable = node.isEditable,
            enabled = node.isEnabled,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            selected = node.isSelected,
            password = node.isPassword,
            visible = node.isVisibleToUser && bounds.intersects(metrics.frame),
            actions = actionsOf(node),
            parentId = parentId,
            childIds = childIds,
            depth = depth,
        )
    }

    /**
     * Maps a widget class plus its capability flags to a coarse role.
     *
     * Class-name matching is a heuristic and is known to be imperfect -- many apps use
     * custom views with meaningless names. Capability flags are therefore consulted first
     * where they are decisive (`isEditable` beats any class name), and `UNKNOWN` is a valid
     * answer rather than a wrong guess.
     */
    private fun roleOf(node: AccessibilityNodeInfo, className: String?): ElementRole {
        if (node.isEditable) return ElementRole.TEXT_FIELD
        val name = className?.substringAfterLast('.')?.lowercase() ?: return capabilityRole(node)
        return when {
            name.contains("webview") -> ElementRole.WEB_VIEW
            name.contains("switch") -> ElementRole.SWITCH
            name.contains("checkbox") -> ElementRole.CHECKBOX
            name.contains("radio") -> ElementRole.RADIO
            name.contains("seekbar") || name.contains("slider") -> ElementRole.SLIDER
            name.contains("progress") -> ElementRole.PROGRESS
            name.contains("button") -> ElementRole.BUTTON
            name.contains("edittext") -> ElementRole.TEXT_FIELD
            name.contains("tablayout") -> ElementRole.TAB_BAR
            name.contains("tab") -> ElementRole.TAB
            name.contains("image") -> if (node.isClickable) ElementRole.BUTTON else ElementRole.IMAGE
            name.contains("recyclerview") || name.contains("listview") ||
                name.contains("gridview") -> ElementRole.LIST
            name.contains("scrollview") || name.contains("pager") -> ElementRole.SCROLL_CONTAINER
            name.contains("dialog") || name.contains("alertdialog") -> ElementRole.DIALOG
            name.contains("textview") -> if (node.isClickable) ElementRole.LINK else ElementRole.TEXT
            name.contains("layout") || name.contains("viewgroup") ->
                if (node.isScrollable) ElementRole.SCROLL_CONTAINER else capabilityRole(node)
            else -> capabilityRole(node)
        }
    }

    private fun capabilityRole(node: AccessibilityNodeInfo): ElementRole = when {
        node.isScrollable -> ElementRole.SCROLL_CONTAINER
        node.isCheckable -> ElementRole.CHECKBOX
        node.isClickable && node.childCount == 0 -> ElementRole.BUTTON
        node.isClickable -> ElementRole.LIST_ITEM
        node.childCount > 0 -> ElementRole.CONTAINER
        node.text != null -> ElementRole.TEXT
        else -> ElementRole.UNKNOWN
    }

    private fun actionsOf(node: AccessibilityNodeInfo): Set<UiAction> {
        val out = LinkedHashSet<UiAction>(4)
        node.actionList.forEach { action ->
            when (action.id) {
                AccessibilityNodeInfo.ACTION_CLICK -> out += UiAction.CLICK
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> out += UiAction.LONG_CLICK
                AccessibilityNodeInfo.ACTION_FOCUS -> out += UiAction.FOCUS
                AccessibilityNodeInfo.ACTION_SET_TEXT -> out += UiAction.SET_TEXT
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> out += UiAction.SCROLL_FORWARD
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> out += UiAction.SCROLL_BACKWARD
                AccessibilityNodeInfo.ACTION_EXPAND -> out += UiAction.EXPAND
                AccessibilityNodeInfo.ACTION_COLLAPSE -> out += UiAction.COLLAPSE
                AccessibilityNodeInfo.ACTION_DISMISS -> out += UiAction.DISMISS
                else -> Unit // Unrecognised actions are dropped rather than leaked as ints.
            }
        }
        return out
    }

    companion object {
        /** Heuristic for "a dialog is in front", used to set [UiSnapshot.hasDialog]. */
        fun windowsIndicateDialog(windows: List<AccessibilityWindowInfo>): Boolean =
            windows.any { window ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    !window.isFocused &&
                    windows.count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } > 1
            } || windows.any { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.isFocused }
    }
}
