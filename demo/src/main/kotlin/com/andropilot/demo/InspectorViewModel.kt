package com.andropilot.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andropilot.android.AndroPilot
import com.andropilot.core.action.ActionData
import com.andropilot.core.action.ActionResult
import com.andropilot.core.action.AgentAction
import com.andropilot.core.action.Direction
import com.andropilot.core.action.PendingConfirmation
import com.andropilot.core.action.SystemKey
import com.andropilot.core.action.UiCondition
import com.andropilot.core.model.UiElement
import com.andropilot.core.model.UiSnapshot
import com.andropilot.core.observe.TraceEntry
import com.andropilot.core.safety.ConfirmationOutcome
import com.andropilot.core.selector.Selector
import com.andropilot.core.session.ToolCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InspectorState(
    val busy: Boolean = false,
    val snapshot: UiSnapshot? = null,
    val lastSummary: String = "Grant the accessibility permission, then press Observe.",
    val trace: List<TraceEntry> = emptyList(),
    val pending: List<PendingConfirmation> = emptyList(),
    val selectorText: String = "",
    val typeText: String = "",
    val matches: List<UiElement> = emptyList(),
)

/**
 * Drives the SDK from the demo UI.
 *
 * Every button maps to exactly one [AgentAction], and the result is rendered through
 * [ToolCodec.summarizeForModel] -- the same compact text an AI agent would receive. That is
 * the point of the demo: it validates the automation layer and shows an integrator what an
 * agent actually sees, without pretending to be an agent.
 */
class InspectorViewModel : ViewModel() {

    private val session = AndroPilot.session()

    private val _state = MutableStateFlow(InspectorState())
    val state: StateFlow<InspectorState> = _state.asStateFlow()

    val serviceConnected: StateFlow<Boolean> get() = AndroPilot.serviceConnected

    fun onSelectorChanged(value: String) {
        _state.value = _state.value.copy(selectorText = value)
    }

    fun onTypeTextChanged(value: String) {
        _state.value = _state.value.copy(typeText = value)
    }

    fun observe() = dispatch(AgentAction.Observe())

    fun observeWithVision() = dispatch(AgentAction.Observe(includeVisual = true))

    fun screenshot() = dispatch(AgentAction.Screenshot())

    fun back() = dispatch(AgentAction.PressKey(SystemKey.BACK))

    fun home() = dispatch(AgentAction.PressKey(SystemKey.HOME))

    fun scrollDown() = dispatch(AgentAction.Scroll(Direction.DOWN))

    fun scrollUp() = dispatch(AgentAction.Scroll(Direction.UP))

    fun findElement() = selector()?.let { dispatch(AgentAction.FindElement(it)) }

    fun click() = selector()?.let { dispatch(AgentAction.Click(it)) }

    fun longPress() = selector()?.let { dispatch(AgentAction.LongPress(it)) }

    fun typeText() = dispatch(
        AgentAction.TypeText(selector(), _state.value.typeText),
    )

    fun clearText() = dispatch(AgentAction.ClearText(selector()))

    fun waitForSelector() = selector()?.let {
        dispatch(AgentAction.WaitFor(UiCondition.ElementPresent(it), timeoutMs = 5_000))
    }

    fun launch(packageName: String) = dispatch(AgentAction.LaunchApp(packageName))

    /** A multi-step sequence, to demonstrate that failures stop the plan. */
    fun runSequence(packageName: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val results = session.executeAll(
                listOf(
                    AgentAction.LaunchApp(packageName),
                    AgentAction.Observe(),
                    AgentAction.Scroll(Direction.DOWN),
                    AgentAction.Observe(),
                ),
            )
            publish(results.last(), header = "Sequence: ${results.size} step(s) ran")
        }
    }

    fun resolve(id: String, outcome: ConfirmationOutcome) {
        viewModelScope.launch {
            session.resolveConfirmation(id, outcome)
            _state.value = _state.value.copy(
                pending = session.pendingConfirmations(),
                lastSummary = "Confirmation $id: ${outcome.name.lowercase()}. " +
                    if (outcome == ConfirmationOutcome.APPROVED) {
                        "Repeat the action to run it."
                    } else {
                        "The action stays blocked."
                    },
            )
        }
    }

    private fun selector(): Selector? =
        _state.value.selectorText.takeIf { it.isNotBlank() }?.let(Selector::text)

    private fun dispatch(action: AgentAction) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            publish(session.execute(action))
        }
    }

    private fun publish(result: ActionResult, header: String? = null) {
        val snapshot = when (result) {
            is ActionResult.Success ->
                (result.data as? ActionData.SnapshotData)?.snapshot ?: result.snapshot
            is ActionResult.Failure -> result.snapshot
        } ?: _state.value.snapshot

        val matches = (result as? ActionResult.Success)
            ?.let { (it.data as? ActionData.Element)?.let { d -> listOf(d.element) } }
            ?: (result as? ActionResult.Failure)?.candidates?.map { it.element }
            ?: emptyList()

        _state.value = _state.value.copy(
            busy = false,
            snapshot = snapshot,
            matches = matches,
            trace = session.trace().reversed(),
            pending = session.pendingConfirmations(),
            lastSummary = buildString {
                header?.let { appendLine(it) }
                append(ToolCodec.summarizeForModel(result, maxElements = 200))
            },
        )
    }
}
