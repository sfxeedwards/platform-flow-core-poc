package com.seamfix.platformflow.core.network

import com.seamfix.platformflow.core.api.SessionResult
import com.seamfix.platformflow.core.api.SessionStatus
import com.seamfix.platformflow.core.model.WorkflowDefinition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Coverage for [RetrofitWorkflowClient]'s SWR (§14.3) + offline (§14.4)
 * orchestration. The wire interface is faked so the tests stay
 * deterministic and JVM-only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetrofitWorkflowClientTest {

    /** Fake [WorkflowApi] that records calls and returns canned responses. */
    private class FakeApi : WorkflowApi {
        var responder: suspend (String) -> WorkflowDefinition = {
            error("FakeApi.responder not configured for fetchWorkflow")
        }
        var reportResponder: suspend (SessionResult) -> Unit = { /* no-op */ }
        val fetchCalls: MutableList<String> = mutableListOf()
        val reportCalls: MutableList<SessionResult> = mutableListOf()

        override suspend fun fetchWorkflow(workflowId: String): WorkflowDefinition {
            fetchCalls.add(workflowId)
            return responder(workflowId)
        }

        override suspend fun reportResult(result: SessionResult) {
            reportCalls.add(result)
            reportResponder(result)
        }
    }

    /** Mutable clock the cache reads through, so tests can step time. */
    private class MutableClock(var now: Long = 0L) {
        fun read(): Long = now
    }

    private fun wf(id: String, version: Int = 1) = WorkflowDefinition(
        workflowId = id,
        version = version,
        tenantId = "t",
        name = "wf-$id",
        entryNode = "a",
        nodes = emptyList(),
        edges = emptyList(),
    )

    // ── Cache miss + network ─────────────────────────────────────────

    @Test
    fun cache_miss_fetches_caches_returns() = runTest {
        val api = FakeApi().apply {
            responder = { wf("wf_kyc") }
        }
        val cache = WorkflowCache()
        val client = RetrofitWorkflowClient(api, cache, TestScope(testScheduler))

        val result = client.fetchWorkflow("wf_kyc")
        assertEquals("wf_kyc", result.workflowId)
        assertEquals(listOf("wf_kyc"), api.fetchCalls)
        // Now in cache.
        assertEquals("wf_kyc", cache.get("wf_kyc")?.workflow?.workflowId)
    }

    @Test
    fun cache_miss_with_network_failure_throws_WorkflowClientException() = runTest {
        val cause = IOException("connection refused")
        val api = FakeApi().apply { responder = { throw cause } }
        val client = RetrofitWorkflowClient(
            api,
            WorkflowCache(),
            TestScope(testScheduler),
        )

        val thrown = runCatching { client.fetchWorkflow("wf") }.exceptionOrNull()
        assertNotNull("expected WorkflowClientException", thrown)
        assertTrue(thrown is WorkflowClientException)
        assertSame(cause, thrown!!.cause)
        assertTrue(
            "message should mention the workflowId for diagnostics",
            thrown.message?.contains("wf") == true,
        )
    }

    @Test
    fun cache_miss_with_non_io_failure_still_wraps_in_WorkflowClientException() = runTest {
        // Retrofit's HttpException for non-2xx, or any other Throwable —
        // the client wraps everything that isn't IOException uniformly.
        val cause = RuntimeException("HTTP 503")
        val api = FakeApi().apply { responder = { throw cause } }
        val client = RetrofitWorkflowClient(
            api,
            WorkflowCache(),
            TestScope(testScheduler),
        )

        val thrown = runCatching { client.fetchWorkflow("wf") }.exceptionOrNull()
        assertTrue(thrown is WorkflowClientException)
        assertSame(cause, thrown!!.cause)
    }

    // ── Cache hit (fresh): no API call ─────────────────────────────

    @Test
    fun cache_hit_fresh_returns_cached_without_api_call() = runTest {
        val clock = MutableClock(now = 0L)
        val cache = WorkflowCache(clock = clock::read)
        cache.put("wf", wf("wf", version = 5))

        // Caller would never reach the responder; if it does, the test fails.
        val api = FakeApi().apply { responder = { error("should not be called") } }
        val client = RetrofitWorkflowClient(api, cache, TestScope(testScheduler))

        clock.now = 1_000L  // well within default 24h TTL
        val result = client.fetchWorkflow("wf")
        assertEquals(5, result.version)
        assertTrue(api.fetchCalls.isEmpty())
    }

    // ── Cache hit (stale): SWR ─────────────────────────────────────

    @Test
    fun cache_hit_stale_returns_cached_and_refreshes_in_background() = runTest {
        val clock = MutableClock(now = 0L)
        val config = WorkflowCacheConfig(ttlMs = 1_000)
        val cache = WorkflowCache(config = config, clock = clock::read)
        cache.put("wf", wf("wf", version = 1))

        val api = FakeApi().apply { responder = { wf("wf", version = 2) } }
        val backgroundScope = TestScope(testScheduler)
        val client = RetrofitWorkflowClient(api, cache, backgroundScope)

        clock.now = 5_000L  // past TTL → stale
        val result = client.fetchWorkflow("wf")
        assertEquals(
            "stale path returns cached value immediately",
            1,
            result.version,
        )
        // No API call yet — refresh is queued.
        assertTrue(api.fetchCalls.isEmpty())

        // Drain the background scope.
        backgroundScope.advanceUntilIdle()
        assertEquals(listOf("wf"), api.fetchCalls)

        // Cache now holds the refreshed version for the next call.
        assertEquals(2, cache.get("wf")?.workflow?.version)
    }

    @Test
    fun stale_with_failed_background_refresh_does_not_throw() = runTest {
        val clock = MutableClock(now = 0L)
        val cache = WorkflowCache(
            config = WorkflowCacheConfig(ttlMs = 1_000),
            clock = clock::read,
        )
        cache.put("wf", wf("wf", version = 1))

        val api = FakeApi().apply {
            responder = { throw IOException("offline") }
        }
        val backgroundScope = TestScope(testScheduler)
        val client = RetrofitWorkflowClient(api, cache, backgroundScope)

        clock.now = 5_000L
        // Caller still gets the stale value with no exception surfaced.
        val result = client.fetchWorkflow("wf")
        assertEquals(1, result.version)

        backgroundScope.advanceUntilIdle()
        assertEquals(
            "background fetch was attempted",
            listOf("wf"),
            api.fetchCalls,
        )
        // Cache still holds the old version since refresh failed.
        assertEquals(1, cache.get("wf")?.workflow?.version)
    }

    @Test
    fun successive_stale_reads_each_trigger_a_refresh() = runTest {
        val clock = MutableClock(now = 0L)
        val cache = WorkflowCache(
            config = WorkflowCacheConfig(ttlMs = 1_000),
            clock = clock::read,
        )
        cache.put("wf", wf("wf"))

        val api = FakeApi().apply { responder = { wf("wf") } }
        val backgroundScope = TestScope(testScheduler)
        val client = RetrofitWorkflowClient(api, cache, backgroundScope)

        // The first refresh updates fetchedAt to clock.now, so we have to
        // manually re-stale the entry between reads to exercise this case.
        clock.now = 5_000L
        client.fetchWorkflow("wf")
        backgroundScope.advanceUntilIdle()
        assertEquals(1, api.fetchCalls.size)
        // Cache's fetchedAt is now clock.now (5_000). Move time forward
        // past TTL again to re-stale.
        clock.now = 10_000L  // past 5_000 + 1_000 TTL
        client.fetchWorkflow("wf")
        backgroundScope.advanceUntilIdle()
        assertEquals(2, api.fetchCalls.size)
    }

    // ── reportResult ─────────────────────────────────────────────────

    @Test
    fun reportResult_delegates_to_api() = runTest {
        val api = FakeApi()
        val client = RetrofitWorkflowClient(
            api,
            WorkflowCache(),
            TestScope(testScheduler),
        )
        val result = SessionResult(
            workflowId = "wf",
            workflowVersion = 1,
            tenantId = "tenant-A",
            status = SessionStatus.COMPLETED,
            nodeOutputs = mapOf("a" to mapOf("k" to "v")),
            executionPath = listOf("a"),
            durationMs = 1_234L,
        )
        client.reportResult(result)
        assertEquals(listOf(result), api.reportCalls)
    }

    @Test
    fun reportResult_wraps_failures_in_WorkflowClientException() = runTest {
        val cause = IOException("server unavailable")
        val api = FakeApi().apply { reportResponder = { throw cause } }
        val client = RetrofitWorkflowClient(
            api,
            WorkflowCache(),
            TestScope(testScheduler),
        )
        val thrown = runCatching {
            client.reportResult(
                SessionResult(
                    workflowId = "wf",
                    workflowVersion = 1,
                    tenantId = "tenant-A",
                    status = SessionStatus.FAILED,
                    nodeOutputs = emptyMap(),
                    executionPath = emptyList(),
                    durationMs = 0L,
                ),
            )
        }.exceptionOrNull()
        assertTrue(thrown is WorkflowClientException)
        assertSame(cause, thrown!!.cause)
    }
}
