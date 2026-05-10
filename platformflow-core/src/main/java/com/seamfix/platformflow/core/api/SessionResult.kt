package com.seamfix.platformflow.core.api

/**
 * Terminal outcome of one workflow session. Per SDK Architecture §11.3.
 *
 * Surfaced to the host via `PlatformFlowCallbacks.onComplete(result)`
 * and shippable to the backend via
 * [com.seamfix.platformflow.core.network.WorkflowClient.reportResult].
 *
 * @property workflowId The id of the workflow that ran.
 * @property workflowVersion The version that ran (relevant when the
 *  backend may have published a newer one mid-session).
 * @property tenantId Owning tenant — useful for multi-tenant audit logs.
 * @property status Canonical session status — see [SessionStatus].
 * @property nodeOutputs Every successfully-executed node's outputs,
 *  keyed by node id. Empty for sessions that failed before any node
 *  ran.
 * @property executionPath Ordered list of node ids actually traversed
 *  (the engine's [com.seamfix.platformflow.core.engine.SessionStore.executionHistory]).
 *  Useful for analytics and resume.
 * @property durationMs Total wall-clock time from `start()` invocation
 *  to terminal status, in milliseconds.
 */
data class SessionResult(
    val workflowId: String,
    val workflowVersion: Int,
    val tenantId: String,
    val status: SessionStatus,
    val nodeOutputs: Map<String, Map<String, Any>>,
    val executionPath: List<String>,
    val durationMs: Long,
)
