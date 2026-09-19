package com.andropilot.core.vision

import com.andropilot.core.driver.Screenshot
import com.andropilot.core.model.Bounds
import com.andropilot.core.model.ElementRole
import com.andropilot.core.model.PerceptionSource
import com.andropilot.core.model.ScreenMetrics
import com.andropilot.core.model.UiElement
import com.andropilot.core.model.UiSnapshot

/**
 * Something a vision system found in a screenshot.
 *
 * Deliberately minimal -- a box, optional text, optional guessed role, a confidence. Every
 * plausible backend (a Tesseract binding, ML Kit, a cloud OCR API, a remote VLM, a bespoke
 * template matcher) can produce this much, and nothing more is needed to fuse with the
 * accessibility tree.
 */
public data class VisualElement(
    val bounds: Bounds,
    val text: String? = null,
    val role: ElementRole? = null,
    val confidence: Double = 0.5,
    /** Free-form provider-specific detail, surfaced in traces only. */
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Pluggable visual perception.
 *
 * The SDK ships **no** implementation and depends on no OCR or model library. Apps with an
 * incomplete accessibility tree are the motivating case, and which vision stack is
 * appropriate (on-device latency vs cloud accuracy vs privacy) is a host decision, not the
 * SDK's.
 *
 * Implementations should be cancellation-aware and should return quickly or not at all --
 * [VisionConfig.timeoutMs] bounds how long the session will wait.
 */
public fun interface VisionProvider {

    /**
     * Analyses [screenshot] and returns whatever it can find.
     *
     * @param hint Elements the accessibility tree already knows about, so a provider can
     *   skip regions that are already well described, or use them to calibrate.
     */
    public suspend fun analyze(screenshot: Screenshot, hint: List<UiElement>): List<VisualElement>
}

/** Controls when and how visual perception participates. */
public data class VisionConfig(
    /** Never call the provider when the semantic tree already has at least this many labelled elements. */
    val semanticSufficiencyThreshold: Int = 3,
    /** Upper bound on provider latency before the session gives up and proceeds semantically. */
    val timeoutMs: Long = 4_000,
    /** Drop detections below this confidence. */
    val minConfidence: Double = 0.35,
    /** A visual box overlapping a semantic element by at least this IoU is treated as the same thing. */
    val fusionIouThreshold: Double = 0.5,
) {
    public companion object {
        public val DEFAULT: VisionConfig = VisionConfig()
    }
}

/**
 * Merges visual detections into a semantic snapshot.
 *
 * Two jobs, in priority order:
 *  1. **Enrich** -- a semantic node that exists but has no label (the classic unlabelled
 *     `ImageButton`) adopts overlapping visual text and becomes [PerceptionSource.FUSED].
 *  2. **Add** -- a visual detection that overlaps nothing semantic becomes a new
 *     [PerceptionSource.VISUAL] element, clickable via coordinates only.
 *
 * Semantic information always wins on conflict: it comes from the app itself and is not a
 * guess.
 */
public object PerceptionFusion {

    public fun fuse(
        snapshot: UiSnapshot,
        detections: List<VisualElement>,
        config: VisionConfig = VisionConfig.DEFAULT,
    ): UiSnapshot {
        val usable = detections
            .filter { it.confidence >= config.minConfidence && !it.bounds.isEmpty }
            .sortedByDescending { it.confidence }
        if (usable.isEmpty()) return snapshot

        val consumed = HashSet<Int>()
        val enriched = snapshot.elements.map { element ->
            if (element.label != null || element.bounds.isEmpty) return@map element
            val idx = usable.indices.firstOrNull { i ->
                i !in consumed &&
                    usable[i].text != null &&
                    element.bounds.iou(usable[i].bounds) >= config.fusionIouThreshold
            } ?: return@map element
            consumed += idx
            val detection = usable[idx]
            element.copy(
                contentDescription = detection.text,
                source = PerceptionSource.FUSED,
                confidence = minOf(element.confidence, detection.confidence + 0.2).coerceAtMost(1.0),
            )
        }

        val rootId = snapshot.root?.id
        val additions = usable.withIndex()
            .filterNot { (i, _) -> i in consumed }
            .filterNot { (_, d) -> overlapsSemantic(d, snapshot, config) }
            .mapIndexed { n, (_, d) ->
                UiElement(
                    id = "v$n",
                    role = d.role ?: inferRole(d, snapshot.metrics),
                    bounds = d.bounds,
                    text = d.text,
                    clickable = true, // only reachable by coordinate tap
                    source = PerceptionSource.VISUAL,
                    confidence = d.confidence,
                    parentId = rootId,
                    depth = 1,
                )
            }

        if (additions.isEmpty() && enriched == snapshot.elements) return snapshot

        val warnings = if (additions.isEmpty()) snapshot.warnings else {
            snapshot.warnings + "${additions.size} element(s) were detected visually and can " +
                "only be interacted with by coordinate; verification for them is weaker."
        }
        return snapshot.copy(elements = enriched + additions, warnings = warnings)
    }

    /**
     * Whether the semantic tree is rich enough that vision would add nothing. Used to avoid
     * paying for a screenshot and an analysis pass on every observation.
     */
    public fun isSemanticPerceptionSufficient(
        snapshot: UiSnapshot,
        config: VisionConfig = VisionConfig.DEFAULT,
    ): Boolean {
        val labelledInteractive = snapshot.elements.count { it.isActionable && it.label != null }
        return labelledInteractive >= config.semanticSufficiencyThreshold
    }

    private fun overlapsSemantic(
        detection: VisualElement,
        snapshot: UiSnapshot,
        config: VisionConfig,
    ): Boolean = snapshot.elements.any { e ->
        e.visible && e.label != null && e.bounds.iou(detection.bounds) >= config.fusionIouThreshold
    }

    /** A crude shape heuristic; providers that can do better should set the role themselves. */
    private fun inferRole(detection: VisualElement, metrics: ScreenMetrics): ElementRole {
        val b = detection.bounds
        val wideAndShort = b.width > b.height * 2 && b.height < metrics.heightPx / 10
        return when {
            detection.text == null -> ElementRole.IMAGE
            wideAndShort -> ElementRole.BUTTON
            else -> ElementRole.TEXT
        }
    }
}
