package com.andropilot.core.testing

import com.andropilot.core.action.Direction
import com.andropilot.core.action.SystemKey
import com.andropilot.core.driver.DriverErrorKind
import com.andropilot.core.driver.DriverOutcome
import com.andropilot.core.model.Bounds
import com.andropilot.core.model.ElementRole
import com.andropilot.core.model.ScreenMetrics
import com.andropilot.core.model.UiElement
import com.andropilot.core.model.UiSnapshot

/**
 * A scripted screen: a mutable element list plus handlers describing how it reacts.
 *
 * Building one is deliberately terse so a test can express a representative Android screen
 * in a few lines. See [FakeScreens] for ready-made examples covering the structures that
 * break naive automation.
 */
public open class FakeScreen(
    public var packageName: String,
    public var windowTitle: String? = null,
    public val metrics: ScreenMetrics = ScreenMetrics.DEFAULT,
    elements: List<UiElement> = emptyList(),
    public var hasDialog: Boolean = false,
    public var keyboardVisible: Boolean = false,
) {
    private val _elements = elements.toMutableList()
    public val elements: List<UiElement> get() = _elements

    /** Invoked when a click lands on [element]; default flips checkable state. */
    public var clickHandler: (FakeScreen, UiElement) -> Unit = { screen, element ->
        if (element.checkable) screen.update(element.id) { it.copy(checked = !it.checked) }
    }

    public var longClickHandler: (FakeScreen, UiElement) -> Unit = { screen, element ->
        screen.update(element.id) { it.copy(selected = !it.selected) }
    }

    public var keyHandler: (FakeScreen, SystemKey) -> Unit = { _, _ -> }

    public var launchHandler: (FakeScreen, String) -> DriverOutcome = { _, pkg ->
        DriverOutcome.Rejected(DriverErrorKind.APP_UNAVAILABLE, "'$pkg' is not installed.")
    }

    /** Pixels each scroll step moves content. Set to 0 to simulate a list at its end. */
    public var scrollStepPx: Int = 300

    public fun replaceAll(next: List<UiElement>) {
        _elements.clear()
        _elements.addAll(next)
    }

    public fun update(id: String, transform: (UiElement) -> UiElement) {
        val idx = _elements.indexOfFirst { it.id == id }
        if (idx >= 0) _elements[idx] = transform(_elements[idx])
    }

    public fun add(element: UiElement) {
        _elements += element
    }

    public fun remove(id: String) {
        _elements.removeAll { it.id == id }
    }

    /** Applies this screen's reaction to a click, as the driver would. */
    public fun simulateClick(element: UiElement) {
        clickHandler(this, element)
    }

    public fun simulateLongClick(element: UiElement) {
        longClickHandler(this, element)
    }

    public fun simulateFocus(element: UiElement) {
        _elements.replaceAll { it.copy(focused = it.id == element.id) }
        if (element.editable) keyboardVisible = true
    }

    public fun simulateSetText(element: UiElement, text: String) {
        update(element.id) { it.copy(text = text) }
    }

    public fun simulateScroll(container: UiElement, direction: Direction) {
        if (scrollStepPx == 0) return
        val (dx, dy) = when (direction) {
            Direction.DOWN -> 0 to -scrollStepPx
            Direction.UP -> 0 to scrollStepPx
            Direction.LEFT -> scrollStepPx to 0
            Direction.RIGHT -> -scrollStepPx to 0
        }
        val frame = metrics.frame
        _elements.replaceAll { e ->
            if (e.id == container.id || !container.childIds.contains(e.id)) {
                e
            } else {
                val moved = Bounds(
                    e.bounds.left + dx, e.bounds.top + dy,
                    e.bounds.right + dx, e.bounds.bottom + dy,
                )
                e.copy(bounds = moved, visible = moved.intersects(frame))
            }
        }
    }

    public fun simulateKey(key: SystemKey) {
        keyHandler(this, key)
    }

    public fun simulateLaunch(packageName: String): DriverOutcome = launchHandler(this, packageName)

    public fun toSnapshot(): UiSnapshot = UiSnapshot(
        snapshotId = "fake",
        capturedAt = 0L,
        packageName = packageName,
        windowTitle = windowTitle,
        metrics = metrics,
        elements = elements.toList(),
        hasDialog = hasDialog,
        keyboardVisible = keyboardVisible,
    )
}

/** Terse builders for elements, so test screens read like the UI they describe. */
public object Ui {

    public fun root(
        packageName: String,
        metrics: ScreenMetrics = ScreenMetrics.DEFAULT,
        childIds: List<String> = emptyList(),
    ): UiElement = UiElement(
        id = "root",
        role = ElementRole.CONTAINER,
        bounds = metrics.frame,
        className = "android.widget.FrameLayout",
        resourceId = "$packageName:id/content",
        childIds = childIds,
        depth = 0,
    )

    public fun button(
        id: String,
        text: String,
        bounds: Bounds,
        enabled: Boolean = true,
        resourceId: String? = null,
        parentId: String = "root",
        depth: Int = 1,
    ): UiElement = UiElement(
        id = id,
        role = ElementRole.BUTTON,
        bounds = bounds,
        text = text,
        resourceId = resourceId,
        className = "android.widget.Button",
        clickable = true,
        longClickable = true,
        focusable = true,
        enabled = enabled,
        actions = setOf(
            com.andropilot.core.model.UiAction.CLICK,
            com.andropilot.core.model.UiAction.LONG_CLICK,
        ),
        parentId = parentId,
        depth = depth,
    )

    public fun field(
        id: String,
        hint: String,
        bounds: Bounds,
        text: String? = null,
        password: Boolean = false,
        resourceId: String? = null,
        parentId: String = "root",
        depth: Int = 1,
    ): UiElement = UiElement(
        id = id,
        role = ElementRole.TEXT_FIELD,
        bounds = bounds,
        text = text,
        hint = hint,
        resourceId = resourceId,
        className = "android.widget.EditText",
        clickable = true,
        focusable = true,
        editable = true,
        password = password,
        actions = setOf(
            com.andropilot.core.model.UiAction.CLICK,
            com.andropilot.core.model.UiAction.SET_TEXT,
            com.andropilot.core.model.UiAction.FOCUS,
        ),
        parentId = parentId,
        depth = depth,
    )

    public fun label(
        id: String,
        text: String,
        bounds: Bounds,
        parentId: String = "root",
        depth: Int = 1,
    ): UiElement = UiElement(
        id = id,
        role = ElementRole.TEXT,
        bounds = bounds,
        text = text,
        className = "android.widget.TextView",
        parentId = parentId,
        depth = depth,
    )

    public fun list(
        id: String,
        bounds: Bounds,
        childIds: List<String>,
        parentId: String = "root",
        depth: Int = 1,
    ): UiElement = UiElement(
        id = id,
        role = ElementRole.LIST,
        bounds = bounds,
        className = "androidx.recyclerview.widget.RecyclerView",
        scrollable = true,
        actions = setOf(
            com.andropilot.core.model.UiAction.SCROLL_FORWARD,
            com.andropilot.core.model.UiAction.SCROLL_BACKWARD,
        ),
        childIds = childIds,
        parentId = parentId,
        depth = depth,
    )

    public fun listItem(
        id: String,
        text: String,
        bounds: Bounds,
        parentId: String,
        depth: Int = 2,
    ): UiElement = UiElement(
        id = id,
        role = ElementRole.LIST_ITEM,
        bounds = bounds,
        text = text,
        className = "android.widget.LinearLayout",
        clickable = true,
        parentId = parentId,
        depth = depth,
        actions = setOf(com.andropilot.core.model.UiAction.CLICK),
    )

    /** An unlabelled icon button: the case that motivates visual fallback. */
    public fun unlabelledIcon(
        id: String,
        bounds: Bounds,
        parentId: String = "root",
        depth: Int = 1,
    ): UiElement = UiElement(
        id = id,
        role = ElementRole.IMAGE,
        bounds = bounds,
        className = "android.widget.ImageButton",
        clickable = true,
        parentId = parentId,
        depth = depth,
        actions = setOf(com.andropilot.core.model.UiAction.CLICK),
    )

    public fun toggle(
        id: String,
        text: String,
        bounds: Bounds,
        checked: Boolean = false,
        parentId: String = "root",
        depth: Int = 1,
    ): UiElement = UiElement(
        id = id,
        role = ElementRole.SWITCH,
        bounds = bounds,
        text = text,
        className = "android.widget.Switch",
        clickable = true,
        focusable = true,
        checkable = true,
        checked = checked,
        parentId = parentId,
        depth = depth,
        actions = setOf(com.andropilot.core.model.UiAction.CLICK),
    )
}
