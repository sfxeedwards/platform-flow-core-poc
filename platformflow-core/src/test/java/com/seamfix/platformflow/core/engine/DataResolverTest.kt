package com.seamfix.platformflow.core.engine

import com.seamfix.platformflow.core.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for [DataResolver] against SDK Architecture §5.
 */
class DataResolverTest {

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun newStoreWithKycSampleState(): SessionStore {
        // Mirrors a snapshot mid-way through the §6.3 KYC example, after
        // collect_data, id_scan, and nin_verify have run and the
        // nin_verify → face_liveness edge has fired its dataMapping.
        val store = SessionStore(
            mapOf(
                "firstName" to "Chinedu",
                "phoneNumber" to "+2348012345678",
            ),
        )
        store.recordNodeResult(
            "collect_data",
            mapOf(
                "formData" to mapOf(
                    "idNumber" to "A12345",
                    "nationality" to "NG",
                ),
                "nationality" to "NG",
            ),
        )
        store.recordNodeResult(
            "id_scan",
            mapOf("documentType" to "NATIONAL_ID"),
        )
        store.recordNodeResult(
            "nin_verify",
            mapOf("extractedPhoto" to "photo-bytes-1"),
        )
        store.applyDataMapping(
            mapping = mapOf("idPhoto" to "\$nin_verify.extractedPhoto"),
            resolve = { ref -> if (ref == "\$nin_verify.extractedPhoto") "photo-bytes-1" else null },
        )
        return store
    }

    // ── Happy paths across the three scopes ─────────────────────────────

    @Test
    fun resolves_input_field() {
        val resolver = DataResolver(newStoreWithKycSampleState())
        assertEquals("Chinedu", resolver.resolve("\$input.firstName"))
    }

    @Test
    fun resolves_context_field() {
        val resolver = DataResolver(newStoreWithKycSampleState())
        assertEquals("photo-bytes-1", resolver.resolve("\$context.idPhoto"))
    }

    @Test
    fun resolves_node_result_top_level_field() {
        val resolver = DataResolver(newStoreWithKycSampleState())
        assertEquals("NATIONAL_ID", resolver.resolve("\$id_scan.documentType"))
    }

    @Test
    fun resolves_through_nested_maps() {
        val resolver = DataResolver(newStoreWithKycSampleState())
        assertEquals(
            "A12345",
            resolver.resolve("\$collect_data.formData.idNumber"),
        )
    }

    // ── Failure modes — all return null, never throw ────────────────────

    @Test
    fun unknown_node_returns_null() {
        val resolver = DataResolver(newStoreWithKycSampleState())
        assertNull(resolver.resolve("\$face_match.matched"))
    }

    @Test
    fun unknown_path_segment_returns_null() {
        val resolver = DataResolver(newStoreWithKycSampleState())
        assertNull(resolver.resolve("\$input.middleName"))
        assertNull(resolver.resolve("\$collect_data.formData.dob"))
    }

    @Test
    fun non_map_intermediate_returns_null() {
        // formData.idNumber is a String; can't traverse further into a String.
        val resolver = DataResolver(newStoreWithKycSampleState())
        assertNull(resolver.resolve("\$collect_data.formData.idNumber.bogus"))
    }

    @Test
    fun null_intermediate_returns_null() {
        val store = SessionStore(emptyMap())
        store.recordNodeResult("a", mapOf("nested" to mapOf<String, Any?>()))
        // Path goes one level deeper than the stored map has.
        val resolver = DataResolver(store)
        assertNull(resolver.resolve("\$a.nested.foo"))
    }

    @Test
    fun bare_scope_without_path_returns_null() {
        // Per §5.2: no dot ⇒ null. Used by the SDK to keep node-root mapping
        // ($face_match alone) explicitly unsupported in V1.
        val resolver = DataResolver(newStoreWithKycSampleState())
        assertNull(resolver.resolve("\$input"))
        assertNull(resolver.resolve("\$nin_verify"))
    }

    @Test
    fun empty_string_returns_null() {
        val resolver = DataResolver(newStoreWithKycSampleState())
        assertNull(resolver.resolve(""))
    }

    @Test
    fun trailing_dot_returns_null() {
        val resolver = DataResolver(newStoreWithKycSampleState())
        assertNull(resolver.resolve("\$input.firstName."))
    }

    @Test
    fun double_dot_returns_null() {
        val resolver = DataResolver(newStoreWithKycSampleState())
        assertNull(resolver.resolve("\$input..firstName"))
    }

    @Test
    fun missing_dollar_prefix_is_lenient() {
        // removePrefix("$") is a no-op when the prefix is missing, so the
        // algorithm runs on the bare scope name. The literal scope words
        // ("input", "context") still route to their bags; node ids that
        // exist still resolve. Names that match nothing return null. The
        // resolver never crashes on unprefixed input.
        val resolver = DataResolver(newStoreWithKycSampleState())
        assertEquals("Chinedu", resolver.resolve("input.firstName"))
        assertEquals("NG", resolver.resolve("collect_data.nationality"))
        assertNull(resolver.resolve("absolutely_no_such_node.field"))
    }

    // ── Edge case: node executed but didn't store the requested key ─────

    @Test
    fun node_without_requested_key_returns_null() {
        val resolver = DataResolver(newStoreWithKycSampleState())
        // id_scan ran but only stored documentType.
        assertNull(resolver.resolve("\$id_scan.confidence"))
    }

    // ── resolveInputMapping ─────────────────────────────────────────────

    @Test
    fun resolveInputMapping_resolves_every_entry() {
        val resolver = DataResolver(newStoreWithKycSampleState())
        val node = WorkflowNode(
            id = "face_match",
            componentType = "FACE_MATCH",
            config = mapOf("matchThreshold" to 0.85),
            inputMapping = mapOf(
                "idNumber" to "\$collect_data.formData.idNumber",
                "scannedDocType" to "\$id_scan.documentType",
                "idPhoto" to "\$context.idPhoto",
                "selfie" to "\$face_liveness.capturedImage", // not yet executed
            ),
        )
        val resolved = resolver.resolveInputMapping(node)

        assertEquals("A12345", resolved["idNumber"])
        assertEquals("NATIONAL_ID", resolved["scannedDocType"])
        assertEquals("photo-bytes-1", resolved["idPhoto"])
        assertNull("selfie source hasn't executed yet", resolved["selfie"])
        // Every input name is present in the result map, even unresolved ones.
        assertEquals(setOf("idNumber", "scannedDocType", "idPhoto", "selfie"), resolved.keys)
    }

    @Test
    fun resolveInputMapping_on_empty_inputs_returns_empty_map() {
        val resolver = DataResolver(newStoreWithKycSampleState())
        val node = WorkflowNode(
            id = "data_form",
            componentType = "DATA_FORM",
        )
        assertEquals(emptyMap<String, Any?>(), resolver.resolveInputMapping(node))
    }

    // ── Type fidelity — non-string values pass through as-is ────────────

    @Test
    fun preserves_value_types() {
        val store = SessionStore(
            mapOf(
                "age" to 30,
                "consent" to true,
                "tags" to listOf("a", "b"),
            ),
        )
        val resolver = DataResolver(store)
        assertEquals(30, resolver.resolve("\$input.age"))
        assertEquals(true, resolver.resolve("\$input.consent"))
        assertEquals(listOf("a", "b"), resolver.resolve("\$input.tags"))
    }

    @Test
    fun returns_intermediate_map_when_path_stops_on_a_container() {
        // resolving $collect_data.formData should yield the full sub-map.
        val resolver = DataResolver(newStoreWithKycSampleState())
        val formData = resolver.resolve("\$collect_data.formData")
        assertEquals(
            mapOf("idNumber" to "A12345", "nationality" to "NG"),
            formData,
        )
    }
}
