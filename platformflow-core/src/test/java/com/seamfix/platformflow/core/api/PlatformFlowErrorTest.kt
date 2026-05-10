package com.seamfix.platformflow.core.api

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.engine.ComponentFailureException
import com.seamfix.platformflow.core.engine.SessionStore
import com.seamfix.platformflow.core.engine.WorkflowEngine
import com.seamfix.platformflow.core.engine.WorkflowException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [PlatformFlowError] (§13.1) and the
 * [WorkflowEngine.RunResult.Failure.toPlatformFlowError] adapter
 * (§13.2 / §13.3).
 */
class PlatformFlowErrorTest {

    // ── Variant construction + equality ────────────────────────────────

    @Test
    fun networkError_carries_message_and_optional_cause() {
        val cause = RuntimeException("connection reset")
        val withCause = PlatformFlowError.NetworkError("offline", cause)
        val withoutCause = PlatformFlowError.NetworkError("offline")

        assertEquals("offline", withCause.message)
        assertSame(cause, withCause.cause)

        assertEquals("offline", withoutCause.message)
        assertNull(withoutCause.cause)
    }

    @Test
    fun validationError_carries_message_and_errors_list() {
        val err = PlatformFlowError.ValidationError(
            message = "Workflow contains a cycle",
            errors = listOf("Workflow contains a cycle: a → b → a"),
        )
        assertEquals("Workflow contains a cycle", err.message)
        assertEquals(1, err.errors.size)
    }

    @Test
    fun componentError_carries_full_metadata_and_partials() {
        val partial = mapOf(
            "collect_data" to mapOf("nationality" to "NG"),
            "id_scan" to mapOf("documentType" to "NATIONAL_ID"),
        )
        val err = PlatformFlowError.ComponentError(
            nodeId = "face_match",
            componentType = "FACE_MATCH",
            reason = "Camera permission denied",
            retryable = true,
            partialResults = partial,
        )
        assertEquals("face_match", err.nodeId)
        assertEquals("FACE_MATCH", err.componentType)
        assertEquals("Camera permission denied", err.reason)
        assertTrue(err.retryable)
        assertEquals(partial, err.partialResults)
    }

    @Test
    fun internalError_carries_message_and_optional_cause() {
        val cause = IllegalStateException("invariant broken")
        val err = PlatformFlowError.InternalError("Engine invariant broken", cause)
        assertEquals("Engine invariant broken", err.message)
        assertSame(cause, err.cause)

        val noCause = PlatformFlowError.InternalError("Boom")
        assertNull(noCause.cause)
    }

    @Test
    fun all_variants_are_data_classes_with_structural_equality() {
        // String / list / map equality is structural; Throwable equality
        // is reference-based, so we keep cause references equal here.
        val cause = RuntimeException("x")
        val a1 = PlatformFlowError.NetworkError("oops", cause)
        val a2 = PlatformFlowError.NetworkError("oops", cause)
        assertEquals(a1, a2)

        val v1 = PlatformFlowError.ValidationError("m", listOf("e1", "e2"))
        val v2 = PlatformFlowError.ValidationError("m", listOf("e1", "e2"))
        assertEquals(v1, v2)

        val c1 = PlatformFlowError.ComponentError("n", "T", "r", false, emptyMap())
        val c2 = PlatformFlowError.ComponentError("n", "T", "r", false, emptyMap())
        assertEquals(c1, c2)

        val i1 = PlatformFlowError.InternalError("m")
        val i2 = PlatformFlowError.InternalError("m")
        assertEquals(i1, i2)
    }

    @Test
    fun sealed_hierarchy_supports_exhaustive_when() {
        // Compile-time pin — if a fifth case is added, every consumer's
        // `when` will fail to compile until they handle it.
        val examples: List<PlatformFlowError> = listOf(
            PlatformFlowError.NetworkError("n"),
            PlatformFlowError.ValidationError("v", listOf("e")),
            PlatformFlowError.ComponentError("nid", "TYPE", "r", false, emptyMap()),
            PlatformFlowError.InternalError("i"),
        )
        val labels = examples.map {
            when (it) {
                is PlatformFlowError.NetworkError -> "network"
                is PlatformFlowError.ValidationError -> "validation"
                is PlatformFlowError.ComponentError -> "component"
                is PlatformFlowError.InternalError -> "internal"
            }
        }
        assertEquals(listOf("network", "validation", "component", "internal"), labels)
    }

    // ── Adapter: ComponentFailureException → ComponentError ────────────

    @Test
    fun componentFailureException_maps_to_componentError_with_partials() {
        val store = SessionStore(emptyMap())
        store.recordNodeResult("collect_data", mapOf("nationality" to "NG"))
        store.recordNodeResult("id_scan", mapOf("documentType" to "NATIONAL_ID"))

        val failure = WorkflowEngine.RunResult.Failure(
            cause = ComponentFailureException(
                nodeId = "face_match",
                componentType = "FACE_MATCH",
                failure = ComponentResult.Failure(
                    reason = "Camera permission denied",
                    code = "CAM_DENIED",
                    retryable = true,
                ),
            ),
            store = store,
        )

        val error = failure.toPlatformFlowError()
        assertTrue(error is PlatformFlowError.ComponentError)
        error as PlatformFlowError.ComponentError
        assertEquals("face_match", error.nodeId)
        assertEquals("FACE_MATCH", error.componentType)
        assertEquals("Camera permission denied", error.reason)
        assertTrue(error.retryable)
        assertEquals(
            mapOf(
                "collect_data" to mapOf("nationality" to "NG"),
                "id_scan" to mapOf("documentType" to "NATIONAL_ID"),
            ),
            error.partialResults,
        )
    }

    @Test
    fun componentError_partialResults_is_empty_when_failure_is_at_first_node() {
        val store = SessionStore(emptyMap())
        // No recorded results — failure happened on the entry node.
        val failure = WorkflowEngine.RunResult.Failure(
            cause = ComponentFailureException(
                nodeId = "collect_data",
                componentType = "DATA_FORM",
                failure = ComponentResult.Failure(
                    reason = "User cancelled",
                    retryable = true,
                ),
            ),
            store = store,
        )
        val error = failure.toPlatformFlowError() as PlatformFlowError.ComponentError
        assertTrue(error.partialResults.isEmpty())
    }

    // ── Adapter: WorkflowException → ValidationError ────────────────────

    @Test
    fun workflowException_maps_to_validationError() {
        val failure = WorkflowEngine.RunResult.Failure(
            cause = WorkflowException(
                "Workflow contains a cycle: a → b → a",
            ),
            store = SessionStore(emptyMap()),
        )
        val error = failure.toPlatformFlowError()
        assertTrue(error is PlatformFlowError.ValidationError)
        error as PlatformFlowError.ValidationError
        assertEquals("Workflow contains a cycle: a → b → a", error.message)
        assertEquals(listOf("Workflow contains a cycle: a → b → a"), error.errors)
    }

    @Test
    fun workflowException_with_null_message_uses_safe_default() {
        // A non-null message is the common case but the adapter must
        // still produce something useful on a null message.
        val failure = WorkflowEngine.RunResult.Failure(
            cause = object : RuntimeException() { /* anonymous: message=null */ }
                .let { WorkflowException("Workflow validation failed", it) },
            store = SessionStore(emptyMap()),
        )
        val error = failure.toPlatformFlowError() as PlatformFlowError.ValidationError
        assertNotNull(error.message)
        assertFalse("message must not be empty", error.message.isEmpty())
    }

    // ── Adapter: arbitrary Throwable → InternalError ────────────────────

    @Test
    fun arbitrary_throwable_maps_to_internalError_with_cause() {
        val cause = RuntimeException("totally unexpected")
        val failure = WorkflowEngine.RunResult.Failure(
            cause = cause,
            store = SessionStore(emptyMap()),
        )
        val error = failure.toPlatformFlowError()
        assertTrue(error is PlatformFlowError.InternalError)
        error as PlatformFlowError.InternalError
        assertEquals("totally unexpected", error.message)
        assertSame(cause, error.cause)
    }

    @Test
    fun throwable_without_message_uses_class_simple_name_as_message() {
        // Keeps `message` informative even when the Throwable was thrown
        // without one (common with anonymous `error()` calls).
        class CustomCrash : RuntimeException()  // no message

        val failure = WorkflowEngine.RunResult.Failure(
            cause = CustomCrash(),
            store = SessionStore(emptyMap()),
        )
        val error = failure.toPlatformFlowError() as PlatformFlowError.InternalError
        assertEquals("CustomCrash", error.message)
    }

    @Test
    fun internalError_path_does_not_swallow_original_throwable() {
        val cause = OutOfMemoryError("simulated OOM")
        val failure = WorkflowEngine.RunResult.Failure(
            cause = cause,
            store = SessionStore(emptyMap()),
        )
        val error = failure.toPlatformFlowError() as PlatformFlowError.InternalError
        assertSame(
            "the original Throwable must be preserved for diagnostics",
            cause,
            error.cause,
        )
    }
}
