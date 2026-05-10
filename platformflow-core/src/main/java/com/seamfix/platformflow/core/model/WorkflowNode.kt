package com.seamfix.platformflow.core.model

/**
 * One workflow step. The runtime looks up the component implementation by
 * [componentType], passes [config] and the resolved [inputMapping] values
 * to it, and stores the component's result keyed by [id].
 *
 * The Admin UI may also emit `label` (display override) and `position`
 * (canvas state). Those are runtime-irrelevant and parsed-then-dropped.
 *
 * Per SDK Architecture §3.2.
 */
data class WorkflowNode(
    val id: String,
    val componentType: String,
    /** Component-specific configuration. JSON numbers come back as [Double]. */
    val config: Map<String, Any> = emptyMap(),
    /** Input name → scope reference (e.g. `"idPhoto" → "$context.idPhoto"`). */
    val inputMapping: Map<String, String> = emptyMap(),
)
