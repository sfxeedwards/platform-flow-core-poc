package com.seamfix.platformflow.core.network

import com.seamfix.platformflow.core.api.SessionResult
import com.seamfix.platformflow.core.model.WorkflowDefinition

/**
 * Network façade over the PlatformFlow control-plane backend. Per SDK
 * Architecture §14.1.
 *
 * **`fetchWorkflow`** returns a [WorkflowDefinition] — typically by
 * combining a cache (§14.2) with the wire call. Concrete impls handle
 * stale-while-revalidate (§14.3) and offline fallback (§14.4)
 * transparently.
 *
 * **`reportResult`** ships a finished [SessionResult] back to the
 * backend for audit / reconciliation.
 *
 * Both methods are `suspend`. Implementations throw
 * [WorkflowClientException] on any unrecoverable failure (no cached
 * fallback, no parseable response, etc.).
 */
interface WorkflowClient {

    /**
     * Fetch the latest published workflow definition for [workflowId].
     *
     * Behavior contract per §14.3 / §14.4:
     *  - Cache hit + fresh → return cached, no network call.
     *  - Cache hit + stale → return cached, fire-and-forget refresh.
     *  - Cache miss + network OK → fetch, cache, return.
     *  - Cache miss + network failure → throw [WorkflowClientException].
     *  - Cache hit (any) + network failure on refresh → no error;
     *    cached value already returned.
     */
    suspend fun fetchWorkflow(workflowId: String): WorkflowDefinition

    /**
     * Post a session result to the backend. Throws
     * [WorkflowClientException] on any network failure — the host
     * should treat this as best-effort and decide whether to retry.
     */
    suspend fun reportResult(result: SessionResult)
}

/**
 * Thrown by [WorkflowClient] on unrecoverable failure: HTTP error
 * status, malformed JSON, transport exception with no cache to fall
 * back on. Adapted to
 * [com.seamfix.platformflow.core.api.PlatformFlowError.NetworkError]
 * by Task 27's public-API layer.
 */
class WorkflowClientException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
