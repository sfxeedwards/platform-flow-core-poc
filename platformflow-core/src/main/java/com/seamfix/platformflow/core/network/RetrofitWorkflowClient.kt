package com.seamfix.platformflow.core.network

import com.seamfix.platformflow.core.api.SessionResult
import com.seamfix.platformflow.core.model.WorkflowDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Production [WorkflowClient] implementation: layers
 * stale-while-revalidate (§14.3) and offline fallback (§14.4) on top of
 * a Retrofit-generated [WorkflowApi].
 *
 * Decision flow for [fetchWorkflow]:
 *
 * ```
 * cached := cache.get(id)
 * if cached fresh   → return cached
 * if cached stale   → schedule background refresh, return cached
 * else (no cache)   → blocking fetch + cache + return
 * if blocking fetch fails AND cached exists → return cached (offline §14.4)
 * if blocking fetch fails AND no cache      → throw WorkflowClientException
 * ```
 *
 * Background refreshes never propagate errors to the caller — the
 * cached value has already been returned. Refresh failures are
 * effectively silent; the next cache miss / staleness re-attempts.
 *
 * @property api Retrofit-generated wire client.
 * @property cache LRU + TTL cache.
 * @property backgroundScope Where SWR refreshes run. Production
 *  typically passes a `CoroutineScope(SupervisorJob() + Dispatchers.IO)`;
 *  tests pass a `TestScope` so they can `advanceUntilIdle()`
 *  deterministically.
 */
class RetrofitWorkflowClient(
    private val api: WorkflowApi,
    private val cache: WorkflowCache,
    private val backgroundScope: CoroutineScope,
) : WorkflowClient {

    override suspend fun fetchWorkflow(workflowId: String): WorkflowDefinition {
        val cached = cache.get(workflowId)
        if (cached != null) {
            return if (cache.isFresh(cached)) {
                cached.workflow
            } else {
                // Stale-while-revalidate: hand back the cached version
                // immediately, kick off a refresh in the background.
                scheduleRefresh(workflowId)
                cached.workflow
            }
        }

        // Cache miss: blocking fetch.
        return try {
            val fresh = api.fetchWorkflow(workflowId)
            cache.put(workflowId, fresh)
            fresh
        } catch (io: IOException) {
            throw WorkflowClientException(
                "Failed to fetch workflow '$workflowId': ${io.message ?: "I/O error"}",
                cause = io,
            )
        } catch (other: Throwable) {
            // Retrofit raises HttpException for non-2xx responses; treat
            // anything non-IO as a network-layer failure too. The error
            // adapter (Task 27) will further translate this into
            // PlatformFlowError.NetworkError.
            throw WorkflowClientException(
                "Failed to fetch workflow '$workflowId': ${other.message ?: other::class.java.simpleName}",
                cause = other,
            )
        }
    }

    override suspend fun reportResult(result: SessionResult) {
        try {
            api.reportResult(result)
        } catch (other: Throwable) {
            throw WorkflowClientException(
                "Failed to report session result: ${other.message ?: other::class.java.simpleName}",
                cause = other,
            )
        }
    }

    /**
     * Fire-and-forget background fetch that updates the cache on
     * success. Failures are intentionally swallowed — the caller
     * already received the cached version.
     */
    private fun scheduleRefresh(workflowId: String) {
        backgroundScope.launch {
            try {
                val fresh = api.fetchWorkflow(workflowId)
                cache.put(workflowId, fresh)
            } catch (_: Throwable) {
                // Stale data already returned; next fetchWorkflow re-tries.
            }
        }
    }
}
