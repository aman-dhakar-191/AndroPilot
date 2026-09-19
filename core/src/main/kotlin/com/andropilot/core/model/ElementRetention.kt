package com.andropilot.core.model

/**
 * Decides which nodes are worth reporting to an agent.
 *
 * Lives in core rather than in the Android tree-walker because it is a pure predicate and
 * the thing most likely to be wrong: it is what stands between an agent and a hierarchy of
 * layout scaffolding. Walking `AccessibilityNodeInfo` needs a device; deciding what to keep
 * does not, and so it is unit-tested.
 */
public object ElementRetention {

    /**
     * Whether [element] earns a place in the snapshot.
     *
     * @param hasSurvivingChildren whether anything beneath it was kept. This is the
     *   *outcome* of filtering the subtree, not the live child count, and the distinction
     *   matters: a container whose children were all dropped is not a container any more,
     *   it is an empty box. Judging it by its live child count keeps exactly the nodes that
     *   convey nothing -- a scrolled-away list reported as `[1220,284][1220,2397]`, or a
     *   collapsed bar as `[0,2712][0,2712]`.
     */
    public fun shouldKeep(element: UiElement, hasSurvivingChildren: Boolean): Boolean {
        if (hasSurvivingChildren) return true
        // Beyond this point the element stands alone, so it has to justify itself.
        if (!element.visible || element.bounds.isEmpty) return false
        return element.label != null || element.isActionable || element.resourceId != null
    }
}
