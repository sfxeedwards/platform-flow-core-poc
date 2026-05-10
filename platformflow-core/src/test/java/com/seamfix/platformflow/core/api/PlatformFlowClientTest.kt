package com.seamfix.platformflow.core.api

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.model.WorkflowDefinition
import com.seamfix.platformflow.core.model.WorkflowEdge
import com.seamfix.platformflow.core.model.WorkflowNode
import com.seamfix.platformflow.core.network.WorkflowClient
import com.seamfix.platformflow.core.network.WorkflowClientException
import com.seamfix.platformflow.core.ui.ComponentHost
import com.seamfix.platformflow.core.ui.TestComponentHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Coverage for [PlatformFlowClient] — the testable backbone of the
 * public API (§11). The singleton façade is a thin pass-through; this
 * test focuses on the orchestration: workflow fetch → engine run →
 * callback dispatch and the error-path adapters.
 */
class PlatformFlowClientTest {

    // ── Fixtures ──────────────────────────────────────────────────────

    private val baseConfig = PlatformFlowConfig(
        apiBaseUrl = "https://api.example.test/",
        apiKey = "test-key",
    )

    private val noopHost: ComponentHost = TestComponentHost()

    /** Programmable [WorkflowClient] for tests. */
    private class FakeWorkflowClient(
        var fetchResponder: suspend (String) -> WorkflowDefinition = {
            error("fetchResponder not configured")
        },
        var reportResponder: suspend (SessionResult) -> Unit = { /* no-op */ },
    ) : WorkflowClient {
        val fetchCalls: MutableList<String> = mutableListOf()
        val reportCalls: MutableList<SessionResult> = mutableListOf()

        override suspend fun fetchWorkflow(workflowId: String): WorkflowDefinition {
            fetchCalls.add(workflowId)
            return fetchResponder(workflowId)
        }

        override suspend fun reportResult(result: SessionResult) {
            reportCalls.add(result)
            reportResponder(result)
        }
    }

    private class StubComponent(
        override val type: String,
        private val outputs: Map<String, Any> = emptyMap(),
        private val failure: ComponentResult.Failure? = null,
    ) : FlowComponent {
        override suspend fun execute(
            config: Map<String, Any>,
            inputs: Map<String, Any?>,
            host: ComponentHost,
        ): ComponentResult = failure ?: ComponentResult.Success(outputs)
    }

    /** Records every callback the client fires. */
    private class RecordingCallbacks : PlatformFlowCallbacks {
        val steps: MutableList<Triple<String, Int, Int>> = mutableListOf()
        var completed: SessionResult? = null
        var errored: PlatformFlowError? = null
        var cancelled: Boolean = false

        override fun onStepComplete(nodeId: String, stepIndex: Int, totalSteps: Int) {
            steps.add(Triple(nodeId, stepIndex, totalSteps))
        }

        override fun onComplete(result: SessionResult) { completed = result }
        override fun onError(error: PlatformFlowError) { errored = error }
        override fun onCancelled() { cancelled = true }
    }

    private fun linearWorkflow(): WorkflowDefinition = WorkflowDefinition(
        workflowId = "wf-1",
        version = 7,
        tenantId = "tenant-A",
        name = "Linear",
        entryNode = "a",
        nodes = listOf(
            WorkflowNode(id = "a", componentType = "A"),
            WorkflowNode(id = "b", componentType = "B"),
        ),
        edges = listOf(
            WorkflowEdge(id = "ab", from = "a", to = "b", default = true),
        ),
    )

    // ── Successful run ────────────────────────────────────────────────

    @Test
    fun start_runs_workflow_and_dispatches_onComplete_with_full_SessionResult() = runBlocking {
        val workflow = linearWorkflow()
        val client = PlatformFlowClient(
            config = baseConfig,
            workflowClient = FakeWorkflowClient(fetchResponder = { workflow }),
            builtIns = listOf(
                StubComponent("A", outputs = mapOf("ka" to "va")),
                StubComponent("B", outputs = mapOf("kb" to "vb")),
            ),
            clock = MonotonicClock(start = 1_000L, step = 250L),
        )
        val callbacks = RecordingCallbacks()

        client.start(noopHost, "wf-1", input = mapOf("seed" to 1), callbacks)

        val result = callbacks.completed
        assertNotNull("onComplete must fire", result)
        result!!
        assertEquals("wf-1", result.workflowId)
        assertEquals(7, result.workflowVersion)
        assertEquals("tenant-A", result.tenantId)
        assertEquals(SessionStatus.COMPLETED, result.status)
        assertEquals(listOf("a", "b"), result.executionPath)
        assertEquals(
            mapOf(
                "a" to mapOf("ka" to "va"),
                "b" to mapOf("kb" to "vb"),
            ),
            result.nodeOutputs,
        )
        // MonotonicClock: started at 1000, advanced once per call. start
        // reads `clock()` once before run, once at the end → 250ms apart.
        assertEquals(250L, result.durationMs)
        assertNull("onError must not fire", callbacks.errored)
        assertEquals(false, callbacks.cancelled)
    }

    @Test
    fun start_streams_onStepComplete_per_node() = runBlocking {
        val workflow = linearWorkflow()
        val client = PlatformFlowClient(
            config = baseConfig,
            workflowClient = FakeWorkflowClient(fetchResponder = { workflow }),
            builtIns = listOf(StubComponent("A"), StubComponent("B")),
        )
        val callbacks = RecordingCallbacks()

        client.start(noopHost, "wf-1", emptyMap(), callbacks)

        assertEquals(
            listOf(
                Triple("a", 1, 2),
                Triple("b", 2, 2),
            ),
            callbacks.steps,
        )
    }

    @Test
    fun start_passes_workflowId_to_workflowClient() = runBlocking {
        val workflow = linearWorkflow()
        val fake = FakeWorkflowClient(fetchResponder = { workflow })
        val client = PlatformFlowClient(
            config = baseConfig,
            workflowClient = fake,
            builtIns = listOf(StubComponent("A"), StubComponent("B")),
        )

        client.start(noopHost, "wf-42", emptyMap(), RecordingCallbacks())

        assertEquals(listOf("wf-42"), fake.fetchCalls)
    }

    // ── Custom component registration ─────────────────────────────────

    @Test
    fun registerComponent_overrides_builtIn_with_same_type() = runBlocking {
        val workflow = linearWorkflow()
        // Built-in "A" returns one value; the custom override returns another.
        val builtInA = StubComponent("A", outputs = mapOf("from" to "builtin"))
        val customA = StubComponent("A", outputs = mapOf("from" to "custom"))
        val client = PlatformFlowClient(
            config = baseConfig,
            workflowClient = FakeWorkflowClient(fetchResponder = { workflow }),
            builtIns = listOf(builtInA, StubComponent("B", outputs = emptyMap())),
        )
        client.registerComponent(customA)

        val callbacks = RecordingCallbacks()
        client.start(noopHost, "wf-1", emptyMap(), callbacks)

        val result = assertNotNullOrFail(callbacks.completed)
        assertEquals(mapOf("from" to "custom"), result.nodeOutputs["a"])
    }

    // ── Error: workflow client failure → NetworkError ────────────────

    @Test
    fun network_failure_during_fetch_maps_to_NetworkError() = runBlocking {
        val cause = RuntimeException("transport down")
        val client = PlatformFlowClient(
            config = baseConfig,
            workflowClient = FakeWorkflowClient(fetchResponder = {
                throw WorkflowClientException("offline", cause)
            }),
            builtIns = emptyList(),
        )
        val callbacks = RecordingCallbacks()

        client.start(noopHost, "wf-1", emptyMap(), callbacks)

        val error = callbacks.errored
        assertTrue("expected NetworkError, got $error", error is PlatformFlowError.NetworkError)
        error as PlatformFlowError.NetworkError
        assertEquals("offline", error.message)
        assertSame(cause, error.cause)
        assertNull(callbacks.completed)
    }

    // ── Error: validation failure → ValidationError ──────────────────

    @Test
    fun engine_validation_failure_maps_to_ValidationError() = runBlocking {
        // Entry node points at a non-existent id — engine throws WorkflowException
        // which the adapter maps to ValidationError.
        val workflow = WorkflowDefinition(
            workflowId = "wf-2",
            version = 1,
            tenantId = "t",
            name = "Bad",
            entryNode = "missing",
            nodes = listOf(WorkflowNode(id = "a", componentType = "A")),
            edges = emptyList(),
        )
        val client = PlatformFlowClient(
            config = baseConfig,
            workflowClient = FakeWorkflowClient(fetchResponder = { workflow }),
            builtIns = listOf(StubComponent("A")),
        )
        val callbacks = RecordingCallbacks()

        client.start(noopHost, "wf-2", emptyMap(), callbacks)

        val error = callbacks.errored
        assertTrue(
            "expected ValidationError, got $error",
            error is PlatformFlowError.ValidationError,
        )
    }

    // ── Error: component failure → ComponentError ────────────────────

    @Test
    fun component_failure_maps_to_ComponentError_with_partials() = runBlocking {
        val workflow = linearWorkflow()
        val client = PlatformFlowClient(
            config = baseConfig,
            workflowClient = FakeWorkflowClient(fetchResponder = { workflow }),
            builtIns = listOf(
                StubComponent("A", outputs = mapOf("ka" to "va")),
                StubComponent(
                    type = "B",
                    failure = ComponentResult.Failure(
                        reason = "Camera permission denied",
                        retryable = true,
                    ),
                ),
            ),
        )
        val callbacks = RecordingCallbacks()

        client.start(noopHost, "wf-1", emptyMap(), callbacks)

        val error = callbacks.errored
        assertTrue(error is PlatformFlowError.ComponentError)
        error as PlatformFlowError.ComponentError
        assertEquals("b", error.nodeId)
        assertEquals("B", error.componentType)
        assertEquals("Camera permission denied", error.reason)
        assertTrue(error.retryable)
        assertEquals(mapOf("a" to mapOf("ka" to "va")), error.partialResults)
    }

    // ── Error: arbitrary throwable → InternalError ───────────────────

    @Test
    fun unexpected_throwable_outside_engine_maps_to_InternalError() = runBlocking {
        val cause = IllegalStateException("invariant broken")
        val client = PlatformFlowClient(
            config = baseConfig,
            // Throwing a non-WorkflowClientException from the workflow
            // client jumps to the catch-all InternalError arm.
            workflowClient = FakeWorkflowClient(fetchResponder = { throw cause }),
            builtIns = emptyList(),
        )
        val callbacks = RecordingCallbacks()

        client.start(noopHost, "wf-1", emptyMap(), callbacks)

        val error = callbacks.errored
        assertTrue(
            "expected InternalError, got $error",
            error is PlatformFlowError.InternalError,
        )
        error as PlatformFlowError.InternalError
        assertEquals("invariant broken", error.message)
        assertSame(cause, error.cause)
    }

    // ── Cancellation honours structured concurrency ───────────────────

    @Test
    fun coroutine_cancellation_fires_onCancelled_and_rethrows() {
        val client = PlatformFlowClient(
            config = baseConfig,
            workflowClient = FakeWorkflowClient(fetchResponder = {
                throw CancellationException("user backed out")
            }),
            builtIns = emptyList(),
        )
        val callbacks = RecordingCallbacks()

        var rethrown: CancellationException? = null
        try {
            runBlocking {
                client.start(noopHost, "wf-1", emptyMap(), callbacks)
            }
            fail("CancellationException must propagate up to honour structured concurrency")
        } catch (e: CancellationException) {
            rethrown = e
        }

        assertNotNull(rethrown)
        assertTrue("onCancelled must fire", callbacks.cancelled)
        assertNull("onError must not fire on cancellation", callbacks.errored)
        assertNull("onComplete must not fire on cancellation", callbacks.completed)
    }

    // ── reportResult delegates ────────────────────────────────────────

    @Test
    fun reportResult_delegates_to_workflowClient() = runBlocking {
        val fake = FakeWorkflowClient()
        val client = PlatformFlowClient(
            config = baseConfig,
            workflowClient = fake,
            builtIns = emptyList(),
        )
        val result = SessionResult(
            workflowId = "wf",
            workflowVersion = 1,
            tenantId = "t",
            status = SessionStatus.COMPLETED,
            nodeOutputs = mapOf("a" to mapOf("k" to "v")),
            executionPath = listOf("a"),
            durationMs = 99L,
        )

        client.reportResult(result)

        assertEquals(listOf(result), fake.reportCalls)
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun <T> assertNotNullOrFail(value: T?): T {
        if (value == null) {
            fail("expected non-null value")
            error("unreachable")
        }
        return value
    }

    /** Clock that returns successive values [start], [start]+step, [start]+2·step, … */
    private class MonotonicClock(start: Long, private val step: Long) : () -> Long {
        private var next: Long = start
        override fun invoke(): Long {
            val v = next
            next += step
            return v
        }
    }
}
