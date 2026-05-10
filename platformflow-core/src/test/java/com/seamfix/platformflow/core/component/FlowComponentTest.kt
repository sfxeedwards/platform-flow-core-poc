package com.seamfix.platformflow.core.component

import com.seamfix.platformflow.core.ui.ComponentHost
import com.seamfix.platformflow.core.ui.TestComponentHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [FlowComponent] + [ComponentResult] against SDK
 * Architecture §7.1–7.2.
 *
 * The runtime dispatch (registry lookup, call site) is covered by
 * later tasks (20: ComponentRegistry, 17: WorkflowEngine). This file is
 * focused on the contract surface itself: ensuring Success/Failure
 * factories work as expected, that a component implementing the
 * interface compiles + runs end-to-end, and that the suspending
 * `execute(...)` is exercised through `runBlocking`.
 */
class FlowComponentTest {

    /** No-op host stub for tests that don't exercise UI / Activity surface. */
    private val noopHost: ComponentHost = TestComponentHost()

    // ── ComponentResult.Success ───────────────────────────────────────

    @Test
    fun success_holds_outputs_verbatim() {
        val outputs = mapOf("matched" to true, "confidence" to 0.91)
        val result = ComponentResult.Success(outputs)
        assertEquals(outputs, result.outputs)
    }

    @Test
    fun success_equality_is_structural() {
        val a = ComponentResult.Success(mapOf("k" to "v"))
        val b = ComponentResult.Success(mapOf("k" to "v"))
        val c = ComponentResult.Success(mapOf("k" to "different"))
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test
    fun success_supports_empty_outputs() {
        // STATUS_AGGREGATOR-style component might return Success with very
        // sparse outputs; an empty map should still be valid.
        val result = ComponentResult.Success(emptyMap())
        assertTrue(result.outputs.isEmpty())
    }

    // ── ComponentResult.Failure ───────────────────────────────────────

    @Test
    fun failure_with_only_reason_uses_safe_defaults() {
        val failure = ComponentResult.Failure("Camera permission denied")
        assertEquals("Camera permission denied", failure.reason)
        assertNull(failure.code)
        assertFalse("retryable defaults to false (terminal)", failure.retryable)
    }

    @Test
    fun failure_with_full_metadata() {
        val failure = ComponentResult.Failure(
            reason = "NIMC API timeout",
            code = "NIMC_TIMEOUT",
            retryable = true,
        )
        assertEquals("NIMC API timeout", failure.reason)
        assertEquals("NIMC_TIMEOUT", failure.code)
        assertTrue(failure.retryable)
    }

    @Test
    fun failure_equality_is_structural() {
        val a = ComponentResult.Failure("x", code = "X", retryable = true)
        val b = ComponentResult.Failure("x", code = "X", retryable = true)
        assertEquals(a, b)
    }

    // ── Sealed-class semantics ────────────────────────────────────────

    @Test
    fun result_is_a_sealed_hierarchy_exhaustive_when() {
        // The compiler enforces exhaustiveness — this test pins the
        // expected branches. If a third subtype is ever added the `when`
        // here will fail to compile, surfacing the change at every call
        // site.
        val results: List<ComponentResult> = listOf(
            ComponentResult.Success(mapOf("k" to "v")),
            ComponentResult.Failure("oops"),
        )
        val labels = results.map {
            when (it) {
                is ComponentResult.Success -> "ok"
                is ComponentResult.Failure -> "fail"
            }
        }
        assertEquals(listOf("ok", "fail"), labels)
    }

    // ── FlowComponent contract ────────────────────────────────────────

    @Test
    fun component_exposes_type_and_runs_execute_to_completion() = runBlocking {
        val component: FlowComponent = StubFaceMatchComponent()
        assertEquals("FACE_MATCH", component.type)

        val result = component.execute(
            config = mapOf("matchThreshold" to 0.85),
            inputs = mapOf(
                "idPhoto" to "id-bytes",
                "selfie" to "selfie-bytes",
            ),
            host = noopHost,
        )
        assertTrue(result is ComponentResult.Success)
        val outputs = (result as ComponentResult.Success).outputs
        assertEquals(true, outputs["matched"])
        assertEquals(0.91, outputs["confidence"] as Double, 1e-9)
        assertEquals("VALID", outputs["verdict"])
    }

    @Test
    fun component_can_signal_terminal_failure() = runBlocking {
        val component: FlowComponent = StubFaceMatchComponent()
        val result = component.execute(
            config = emptyMap(),
            inputs = mapOf("selfie" to "selfie-bytes"),  // missing idPhoto
            host = noopHost,
        )
        assertTrue(result is ComponentResult.Failure)
        val failure = result as ComponentResult.Failure
        assertEquals("Missing idPhoto input", failure.reason)
        assertFalse("missing-input failures aren't retryable", failure.retryable)
    }

    @Test
    fun host_is_passed_through_to_component_unchanged() = runBlocking {
        val seen = mutableListOf<ComponentHost>()
        val capturing = object : FlowComponent {
            override val type = "X"
            override suspend fun execute(
                config: Map<String, Any>,
                inputs: Map<String, Any?>,
                host: ComponentHost,
            ): ComponentResult {
                seen.add(host)
                return ComponentResult.Success(emptyMap())
            }
        }
        capturing.execute(emptyMap(), emptyMap(), noopHost)
        assertEquals(1, seen.size)
        assertSame(noopHost, seen[0])
    }

    // ── Reference impl ────────────────────────────────────────────────

    /**
     * Mirrors the §7.5 example: validates two image inputs, applies the
     * configured threshold, returns a verdict + confidence. No network /
     * UI side effects — keeps the test purely exercising the contract.
     */
    private class StubFaceMatchComponent : FlowComponent {
        override val type = "FACE_MATCH"

        override suspend fun execute(
            config: Map<String, Any>,
            inputs: Map<String, Any?>,
            host: ComponentHost,
        ): ComponentResult {
            val idPhoto = inputs["idPhoto"] as? String
                ?: return ComponentResult.Failure(
                    reason = "Missing idPhoto input",
                    code = "MISSING_INPUT",
                    retryable = false,
                )
            val selfie = inputs["selfie"] as? String
                ?: return ComponentResult.Failure(
                    reason = "Missing selfie input",
                    code = "MISSING_INPUT",
                    retryable = false,
                )
            val threshold = (config["matchThreshold"] as? Number)?.toDouble() ?: 0.85
            // Simulate a 0.91 confidence — above threshold.
            val confidence = 0.91
            val matched = confidence >= threshold
            return ComponentResult.Success(
                mapOf(
                    "matched" to matched,
                    "confidence" to confidence,
                    "verdict" to if (matched) "VALID" else "INVALID",
                    // Surface inputs to silence the unused-warning reviewer.
                    "echoIdPhoto" to idPhoto,
                    "echoSelfie" to selfie,
                ),
            )
        }
    }
}
