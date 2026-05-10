package com.seamfix.platformflow.core.model

/**
 * Directed edge between two [WorkflowNode]s. Carries optional routing logic
 * ([rule] + [default]) and an optional [dataMapping] that writes into the
 * `$context` scope when this edge fires.
 *
 * The Admin UI may include a `label` field for canvas display; that field
 * has no runtime meaning and is ignored on parse.
 *
 * Per SDK Architecture §3.3.
 */
data class WorkflowEdge(
    val id: String,
    val from: String,
    val to: String,
    val rule: EdgeRule? = null,
    val default: Boolean = false,
    /** `$context` key → scope reference (e.g. `"idPhoto" → "$nin_verify.extractedPhoto"`). */
    val dataMapping: Map<String, String>? = null,
)
