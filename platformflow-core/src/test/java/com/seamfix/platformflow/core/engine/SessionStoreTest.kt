package com.seamfix.platformflow.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [SessionStore] against SDK Architecture §10.
 */
class SessionStoreTest {

    // ── Initial state ─────────────────────────────────────────────────────

    @Test
    fun new_store_exposes_input_and_empty_mutable_scopes() {
        val store = SessionStore(
            mapOf("firstName" to "Chinedu", "phoneNumber" to "+2348012345678"),
        )
        assertEquals("Chinedu", store.input["firstName"])
        assertEquals("+2348012345678", store.input["phoneNumber"])
        assertTrue(store.nodeResults.isEmpty())
        assertTrue(store.context.isEmpty())
        assertTrue(store.executionHistory.isEmpty())
    }

    @Test
    fun input_is_a_defensive_copy() {
        val source = mutableMapOf<String, Any>("firstName" to "Chinedu")
        val store = SessionStore(source)
        // Caller mutates their own map after construction.
        source["firstName"] = "Adesanya"
        source["dob"] = "1990-01-01"
        assertEquals("Chinedu", store.input["firstName"])
        assertNull(store.input["dob"])
        assertNotSame("input must be a separate instance", source, store.input)
    }

    // ── recordNodeResult ──────────────────────────────────────────────────

    @Test
    fun recordNodeResult_stores_outputs_and_appends_to_history() {
        val store = SessionStore(emptyMap())
        store.recordNodeResult("collect_data", mapOf("idNumber" to "A12345"))
        store.recordNodeResult("face_match", mapOf("matched" to true, "confidence" to 0.91))

        assertEquals(2, store.nodeResults.size)
        assertEquals(mapOf("idNumber" to "A12345"), store.nodeResults["collect_data"])
        assertEquals(true, store.nodeResults["face_match"]?.get("matched"))
        assertEquals(0.91, store.nodeResults["face_match"]?.get("confidence"))

        assertEquals(listOf("collect_data", "face_match"), store.executionHistory)
    }

    @Test
    fun recordNodeResult_defensively_copies_outputs() {
        val store = SessionStore(emptyMap())
        val outputs = mutableMapOf<String, Any>("idNumber" to "A12345")
        store.recordNodeResult("collect_data", outputs)
        // Caller keeps mutating its outputs map after returning.
        outputs["idNumber"] = "TAMPERED"
        outputs["leak"] = "should-not-appear"

        val stored = store.nodeResults.getValue("collect_data")
        assertEquals("A12345", stored["idNumber"])
        assertNull(stored["leak"])
    }

    @Test
    fun recordNodeResult_overwrites_value_but_history_keeps_both_entries() {
        val store = SessionStore(emptyMap())
        store.recordNodeResult("retryable", mapOf("attempt" to 1))
        store.recordNodeResult("retryable", mapOf("attempt" to 2))

        assertEquals(mapOf("attempt" to 2), store.nodeResults["retryable"])
        assertEquals(listOf("retryable", "retryable"), store.executionHistory)
    }

    // ── applyDataMapping ──────────────────────────────────────────────────

    @Test
    fun applyDataMapping_writes_resolved_values_into_context() {
        val store = SessionStore(emptyMap())
        store.recordNodeResult("nin_verify", mapOf("extractedPhoto" to "photo-bytes"))

        // Synthetic resolver that mirrors what DataResolver will do for $node.path.
        val resolve: (String) -> Any? = { ref ->
            when (ref) {
                "\$nin_verify.extractedPhoto" -> store.nodeResults["nin_verify"]?.get("extractedPhoto")
                else -> null
            }
        }
        store.applyDataMapping(
            mapping = mapOf("idPhoto" to "\$nin_verify.extractedPhoto"),
            resolve = resolve,
        )

        assertEquals("photo-bytes", store.context["idPhoto"])
        assertEquals(1, store.context.size)
    }

    @Test
    fun applyDataMapping_skips_unresolved_keys() {
        val store = SessionStore(emptyMap())
        val resolve: (String) -> Any? = { ref ->
            if (ref == "\$x.found") "got-it" else null
        }
        store.applyDataMapping(
            mapping = mapOf(
                "present" to "\$x.found",
                "missing" to "\$x.notFound",
            ),
            resolve = resolve,
        )

        assertEquals("got-it", store.context["present"])
        assertNull("missing keys must not be written", store.context["missing"])
        assertEquals(1, store.context.size)
    }

    @Test
    fun applyDataMapping_with_empty_mapping_is_a_noop() {
        val store = SessionStore(emptyMap())
        store.applyDataMapping(emptyMap()) { fail("resolver should not be called") }
        assertTrue(store.context.isEmpty())
    }

    @Test
    fun applyDataMapping_does_not_evaluate_unmapped_refs() {
        val store = SessionStore(emptyMap())
        var calls = 0
        store.applyDataMapping(
            mapping = mapOf("k" to "\$x.y"),
            resolve = {
                calls++
                "v"
            },
        )
        assertEquals("resolver should be called exactly once per mapping entry", 1, calls)
    }

    // ── clear ─────────────────────────────────────────────────────────────

    @Test
    fun clear_wipes_mutable_scopes_but_preserves_input() {
        val store = SessionStore(mapOf("firstName" to "Chinedu"))
        store.recordNodeResult("a", mapOf("k" to "v"))
        store.applyDataMapping(mapOf("ctx" to "\$x.y")) { "value" }

        store.clear()

        assertTrue(store.nodeResults.isEmpty())
        assertTrue(store.context.isEmpty())
        assertTrue(store.executionHistory.isEmpty())
        assertEquals("Chinedu", store.input["firstName"])
    }

    @Test
    fun clear_is_idempotent() {
        val store = SessionStore(emptyMap())
        store.clear()
        store.clear()
        assertTrue(store.nodeResults.isEmpty())
    }

    // ── Read-only view semantics ──────────────────────────────────────────

    @Test
    fun nodeResults_view_reflects_subsequent_writes() {
        // The view returned by `nodeResults` is a live read of the underlying
        // mutable map, so a recorded result becomes visible without
        // re-fetching.
        val store = SessionStore(emptyMap())
        val view = store.nodeResults
        assertTrue(view.isEmpty())
        store.recordNodeResult("a", mapOf("k" to "v"))
        assertEquals(1, view.size)
        assertEquals("v", view["a"]?.get("k"))
    }

    private fun fail(message: String): Nothing = org.junit.Assert.fail(message).let { error("unreachable") }
}
