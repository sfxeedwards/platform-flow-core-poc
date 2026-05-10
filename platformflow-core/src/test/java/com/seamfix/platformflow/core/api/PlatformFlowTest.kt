package com.seamfix.platformflow.core.api

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Coverage for the [PlatformFlow] singleton façade. The heavy
 * orchestration is exercised in [PlatformFlowClientTest] — here we only
 * verify state transitions: init / reset / unrequired-init guards.
 *
 * Each test calls [PlatformFlow.reset] in teardown so global state
 * doesn't leak.
 */
class PlatformFlowTest {

    @After
    fun tearDown() {
        PlatformFlow.reset()
    }

    @Test
    fun isInitialized_is_false_before_initialize() {
        assertFalse(PlatformFlow.isInitialized)
    }

    @Test
    fun initialize_flips_isInitialized_to_true() {
        PlatformFlow.initialize(
            PlatformFlowConfig(
                apiBaseUrl = "https://api.example.test/",
                apiKey = "k",
            ),
        )
        assertTrue(PlatformFlow.isInitialized)
    }

    @Test
    fun reset_flips_isInitialized_back_to_false() {
        PlatformFlow.initialize(
            PlatformFlowConfig(
                apiBaseUrl = "https://api.example.test/",
                apiKey = "k",
            ),
        )
        PlatformFlow.reset()
        assertFalse(PlatformFlow.isInitialized)
    }

    @Test
    fun initialize_is_idempotent_replacing_previous_client() {
        PlatformFlow.initialize(
            PlatformFlowConfig(apiBaseUrl = "https://a/", apiKey = "k1"),
        )
        // Second init with different config is allowed and supersedes the first.
        PlatformFlow.initialize(
            PlatformFlowConfig(apiBaseUrl = "https://b/", apiKey = "k2"),
        )
        assertTrue(PlatformFlow.isInitialized)
    }

    @Test
    fun registerComponent_before_initialize_throws_IllegalStateException() {
        try {
            PlatformFlow.registerComponent(
                object : com.seamfix.platformflow.core.component.FlowComponent {
                    override val type: String = "X"
                    override suspend fun execute(
                        config: Map<String, Any>,
                        inputs: Map<String, Any?>,
                        host: com.seamfix.platformflow.core.ui.ComponentHost,
                    ) = com.seamfix.platformflow.core.component.ComponentResult.Success(emptyMap())
                },
            )
            fail("expected IllegalStateException when SDK isn't initialized")
        } catch (e: IllegalStateException) {
            assertTrue(
                "message should mention initialize",
                e.message.orEmpty().contains("initialize"),
            )
        }
    }

    // ── Singleton stash semantics (Task 30) ───────────────────────────

    @Test
    fun consumePendingStart_returns_null_when_nothing_stashed() {
        assertNull(PlatformFlow.consumePendingStart())
    }

    @Test
    fun reset_clears_any_pending_stash() {
        // No public path to populate the stash without an Android Context;
        // this test pins the contract that reset() leaves the stash empty
        // regardless of any prior state.
        PlatformFlow.reset()
        assertNull(PlatformFlow.consumePendingStart())
    }

    @Test
    fun pendingStart_data_class_holds_all_three_fields() {
        val callbacks = object : PlatformFlowCallbacks {}
        val p = PlatformFlow.PendingStart(
            workflowId = "wf",
            input = mapOf("k" to "v"),
            callbacks = callbacks,
        )
        assertEquals("wf", p.workflowId)
        assertEquals(mapOf("k" to "v"), p.input)
        assertTrue(p.callbacks === callbacks)
    }

    @Test
    fun reportResult_before_initialize_throws_IllegalStateException() {
        try {
            kotlinx.coroutines.runBlocking {
                PlatformFlow.reportResult(
                    SessionResult(
                        workflowId = "wf",
                        workflowVersion = 1,
                        tenantId = "t",
                        status = SessionStatus.COMPLETED,
                        nodeOutputs = emptyMap(),
                        executionPath = emptyList(),
                        durationMs = 0L,
                    ),
                )
            }
            fail("expected IllegalStateException when SDK isn't initialized")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("initialize"))
        }
    }
}
