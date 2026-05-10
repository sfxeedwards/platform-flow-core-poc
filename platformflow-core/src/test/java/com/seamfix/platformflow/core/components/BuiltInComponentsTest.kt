package com.seamfix.platformflow.core.components

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.Verdict
import com.seamfix.platformflow.core.engine.DataResolver
import com.seamfix.platformflow.core.engine.RuleEvaluator
import com.seamfix.platformflow.core.engine.SessionStore
import com.seamfix.platformflow.core.engine.WorkflowEngine
import com.seamfix.platformflow.core.model.Comparator
import com.seamfix.platformflow.core.model.EdgeRule
import com.seamfix.platformflow.core.model.RuleItem
import com.seamfix.platformflow.core.model.RuleOperator
import com.seamfix.platformflow.core.model.WorkflowDefinition
import com.seamfix.platformflow.core.model.WorkflowEdge
import com.seamfix.platformflow.core.model.WorkflowNode
import com.seamfix.platformflow.core.ui.TestComponentHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the built-in [BuiltInComponents] catalogue: type-key
 * alignment with the Admin UI registry, scoring vs capture verdict
 * semantics (§9.1), and an end-to-end run of the §10.1 KYC workflow
 * through the stubs to prove the contract holds wire-to-wire.
 */
class BuiltInComponentsTest {

    private val noopHost = TestComponentHost()

    /** Canonical component-type strings expected from the Admin UI registry. */
    private val expectedTypes: Set<String> = setOf(
        "DATA_FORM",
        "FACE_LIVENESS",
        "FACE_MATCH",
        "NIN_VERIFICATION",
        "BVN_VERIFICATION",
        "PASSPORT_SCAN",
        "ID_VERIFICATION",
        "FINGERPRINT",
        "PORTRAIT",
        "STATUS_AGGREGATOR",
    )

    /** Per §9.1: scoring components emit verdict + confidence. */
    private val scoringTypes: Set<String> = setOf(
        "FACE_MATCH",
        "FACE_LIVENESS",
        "NIN_VERIFICATION",
        "BVN_VERIFICATION",
        "ID_VERIFICATION",
        "FINGERPRINT",
    )

    /** Per §9.1: pure capture components do NOT emit a verdict. */
    private val captureTypes: Set<String> = setOf(
        "DATA_FORM",
        "PASSPORT_SCAN",
        "PORTRAIT",
    )

    // ── Catalogue shape ─────────────────────────────────────────────────

    @Test
    fun all_lists_every_built_in_with_unique_types() {
        val types = BuiltInComponents.all.map { it.type }
        assertEquals(
            "Built-ins should list each canonical type exactly once",
            expectedTypes.size,
            types.size,
        )
        assertEquals(expectedTypes, types.toSet())
    }

    @Test
    fun newRegistry_factory_pre_populates_with_all_built_ins() {
        val registry = BuiltInComponents.newRegistry()
        assertEquals(expectedTypes, registry.types())
        for (type in expectedTypes) {
            assertNotNull("$type should be retrievable", registry.get(type))
        }
    }

    @Test
    fun scoring_and_capture_partition_is_complete() {
        // Every built-in is in one of the two buckets; together they cover
        // every canonical type. Catches any future drift between this
        // partition and the actual catalogue.
        val partition = scoringTypes + captureTypes + setOf("STATUS_AGGREGATOR")
        assertEquals(expectedTypes, partition)
    }

    // ── Scoring vs capture verdict semantics (§9.1) ─────────────────────

    @Test
    fun scoring_components_emit_verdict_and_confidence() = runBlocking {
        for (type in scoringTypes) {
            val component = BuiltInComponents.all.first { it.type == type }
            val result = component.execute(emptyMap(), emptyMap(), noopHost)
            assertTrue("$type should Succeed", result is ComponentResult.Success)
            val outputs = (result as ComponentResult.Success).outputs

            assertNotNull("$type missing 'verdict'", outputs["verdict"])
            assertNotNull("$type missing 'confidence'", outputs["confidence"])

            // Stubs all return VALID; real impls vary. The point is the
            // shape is correct.
            assertEquals(Verdict.VALID.name, outputs["verdict"])
            assertTrue(
                "$type confidence should be a Number in [0, 1]",
                outputs["confidence"] is Number &&
                    (outputs["confidence"] as Number).toDouble() in 0.0..1.0,
            )
        }
    }

    @Test
    fun pure_capture_components_do_not_emit_verdict() = runBlocking {
        for (type in captureTypes) {
            val component = BuiltInComponents.all.first { it.type == type }
            val result = component.execute(emptyMap(), emptyMap(), noopHost)
            assertTrue("$type should Succeed", result is ComponentResult.Success)
            val outputs = (result as ComponentResult.Success).outputs
            assertNull("$type should NOT emit verdict", outputs["verdict"])
        }
    }

    // ── Component-specific output shape spot-checks ─────────────────────

    @Test
    fun face_match_outputs_match_outputSchema() = runBlocking {
        val out = success(FaceMatchComponent())
        assertEquals(true, out["matched"])
        assertEquals(0.91, out["confidence"])
        assertEquals(Verdict.VALID.name, out["verdict"])
    }

    @Test
    fun face_liveness_outputs_include_capturedImage_and_livenessScore() = runBlocking {
        val out = success(FaceLivenessComponent())
        assertNotNull(out["capturedImage"])
        assertNotNull(out["livenessScore"])
        assertEquals(Verdict.VALID.name, out["verdict"])
    }

    @Test
    fun id_verification_outputs_documentType_enum_value() = runBlocking {
        val out = success(IdVerificationComponent())
        assertEquals("NATIONAL_ID", out["documentType"])
        assertNotNull(out["photo"])
        assertEquals(Verdict.VALID.name, out["verdict"])
    }

    @Test
    fun nin_verification_outputs_extractedPhoto() = runBlocking {
        val out = success(NinVerificationComponent())
        assertNotNull(out["extractedPhoto"])
        assertEquals(true, out["verified"])
        assertEquals(Verdict.VALID.name, out["verdict"])
    }

    @Test
    fun bvn_verification_has_no_extractedPhoto_field() = runBlocking {
        // BVN's outputSchema doesn't promise an extracted photo (the
        // service returns name + DOB only, not biometrics). Confirms the
        // stub doesn't accidentally include one.
        val out = success(BvnVerificationComponent())
        assertNull(out["extractedPhoto"])
        assertEquals(true, out["verified"])
    }

    @Test
    fun passport_scan_emits_photo_and_mrzData_no_verdict() = runBlocking {
        val out = success(PassportScanComponent())
        assertNotNull(out["photo"])
        assertNotNull(out["mrzData"])
        assertNull(out["verdict"])
    }

    @Test
    fun portrait_emits_photo_and_qualityPassed_no_verdict() = runBlocking {
        val out = success(PortraitComponent())
        assertNotNull(out["photo"])
        assertEquals(true, out["qualityPassed"])
        assertNull(out["verdict"])
    }

    @Test
    fun data_form_echoes_configured_field_names_into_formData() = runBlocking {
        val component = DataFormComponent()
        val result = component.execute(
            config = mapOf("fields" to listOf("firstName", "lastName", "idNumber")),
            inputs = emptyMap(),
            host = noopHost,
        )
        val outputs = (result as ComponentResult.Success).outputs
        @Suppress("UNCHECKED_CAST")
        val formData = outputs["formData"] as Map<String, Any>
        assertEquals(setOf("firstName", "lastName", "idNumber"), formData.keys)
        // Top-level fields are also present (Admin UI's outputSchema
        // declares both shapes via additionalProperties: true).
        assertEquals("stub-firstName", outputs["firstName"])
    }

    @Test
    fun data_form_with_empty_fields_returns_empty_formData() = runBlocking {
        val component = DataFormComponent()
        val result = component.execute(emptyMap(), emptyMap(), noopHost)
        val outputs = (result as ComponentResult.Success).outputs
        @Suppress("UNCHECKED_CAST")
        val formData = outputs["formData"] as Map<String, Any>
        assertTrue(formData.isEmpty())
    }

    @Test
    fun fingerprint_echoes_configured_fingers() = runBlocking {
        val component = FingerprintComponent()
        val result = component.execute(
            config = mapOf("fingers" to listOf("LEFT_THUMB", "RIGHT_THUMB")),
            inputs = emptyMap(),
            host = noopHost,
        )
        val outputs = (result as ComponentResult.Success).outputs
        assertEquals(listOf("LEFT_THUMB", "RIGHT_THUMB"), outputs["capturedFingers"])
        assertNotNull(outputs["fingerprintTemplate"])
        assertEquals(Verdict.VALID.name, outputs["verdict"])
    }

    // ── End-to-end §10.1 KYC run through the stubs ──────────────────────

    @Test
    fun end_to_end_kyc_workflow_runs_through_built_in_stubs() = runBlocking {
        val workflow = WorkflowDefinition(
            workflowId = "wf_kyc_onboarding",
            version = 1,
            tenantId = "mtn-ng",
            name = "KYC",
            entryNode = "collect_data",
            nodes = listOf(
                WorkflowNode(
                    "collect_data",
                    "DATA_FORM",
                    config = mapOf(
                        "fields" to listOf("firstName", "nationality", "idNumber"),
                    ),
                ),
                WorkflowNode(
                    "id_scan",
                    "ID_VERIFICATION",
                    inputMapping = mapOf(
                        "idNumber" to "\$collect_data.idNumber",
                    ),
                ),
                WorkflowNode("nin_verify", "NIN_VERIFICATION"),
                WorkflowNode("passport_scan", "PASSPORT_SCAN"),
                WorkflowNode("face_liveness", "FACE_LIVENESS"),
                WorkflowNode(
                    "face_match",
                    "FACE_MATCH",
                    inputMapping = mapOf(
                        "idPhoto" to "\$context.idPhoto",
                        "selfie" to "\$face_liveness.capturedImage",
                    ),
                ),
                WorkflowNode(
                    "aggregate",
                    "STATUS_AGGREGATOR",
                    config = mapOf("minValidCount" to 3),
                    inputMapping = mapOf(
                        "faceMatch" to "\$face_match.verdict",
                        "liveness" to "\$face_liveness.verdict",
                        "nin" to "\$nin_verify.verdict",
                        "id" to "\$id_scan.verdict",
                    ),
                ),
            ),
            edges = listOf(
                WorkflowEdge("e1", "collect_data", "id_scan", default = true),
                // DATA_FORM stub echoes "stub-nationality", which != "NG", so
                // the rule below fails. We test the default fallback path.
                WorkflowEdge(
                    "e2",
                    "id_scan",
                    "nin_verify",
                    rule = EdgeRule(
                        RuleOperator.AND,
                        listOf(
                            RuleItem.Condition(
                                "\$collect_data.nationality",
                                Comparator.EQ,
                                "NG",
                            ),
                        ),
                    ),
                ),
                WorkflowEdge("e3", "id_scan", "passport_scan", default = true),
                WorkflowEdge(
                    "e4",
                    "nin_verify",
                    "face_liveness",
                    default = true,
                    dataMapping = mapOf("idPhoto" to "\$nin_verify.extractedPhoto"),
                ),
                WorkflowEdge(
                    "e5",
                    "passport_scan",
                    "face_liveness",
                    default = true,
                    dataMapping = mapOf("idPhoto" to "\$passport_scan.photo"),
                ),
                WorkflowEdge("e6", "face_liveness", "face_match", default = true),
                WorkflowEdge("e7", "face_match", "aggregate", default = true),
            ),
        )

        val store = SessionStore(emptyMap())
        val resolver = DataResolver(store)
        val evaluator = RuleEvaluator(resolver)
        val engine = WorkflowEngine(
            workflow = workflow,
            registry = BuiltInComponents.newRegistry(),
            sessionStore = store,
            dataResolver = resolver,
            ruleEvaluator = evaluator,
            host = noopHost,
        )
        val result = engine.run()

        assertTrue(
            "engine should complete successfully through the stubs",
            result is WorkflowEngine.RunResult.Success,
        )
        // DATA_FORM stub echoes "stub-nationality" → rule fails →
        // default branch fires → passport_scan path runs.
        assertEquals(
            listOf(
                "collect_data",
                "id_scan",
                "passport_scan",
                "face_liveness",
                "face_match",
                "aggregate",
            ),
            store.executionHistory,
        )
        // The aggregate's processStatus is determined by counting verdicts:
        // id_scan, face_liveness, face_match all VALID. passport_scan emits
        // no verdict. nin_verify never ran. So validCount=3 against
        // minValidCount=3 → VALID → "VALID" label.
        @Suppress("UNCHECKED_CAST")
        val aggregateOutputs = store.nodeResults["aggregate"] as Map<String, Any>
        assertEquals("VALID", aggregateOutputs["processStatus"])
        assertEquals("VALID", aggregateOutputs["internalVerdict"])
        assertEquals(3, aggregateOutputs["validCount"])
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun success(
        component: com.seamfix.platformflow.core.component.FlowComponent,
    ): Map<String, Any> {
        val result = component.execute(emptyMap(), emptyMap(), noopHost)
        assertTrue(
            "${component.type} should Succeed",
            result is ComponentResult.Success,
        )
        return (result as ComponentResult.Success).outputs
    }
}
