package com.seamfix.platformflow.core.model

/**
 * The top-level workflow JSON — the exact contract the Admin UI publishes
 * and the SDK consumes.
 *
 * Per SDK Architecture §3.1; canonical schema in Platform Architecture §10.
 */
data class WorkflowDefinition(
    val workflowId: String,
    val version: Int,
    val tenantId: String,
    val name: String,
    val entryNode: String,
    val nodes: List<WorkflowNode>,
    val edges: List<WorkflowEdge>,
)
