package com.seamfix.platformflow.core.model

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Round-trip and edge-case coverage for [WorkflowJson] against the JSON
 * contract published by the Admin UI (Platform Architecture §10.1, SDK
 * Architecture §3).
 */
class WorkflowJsonTest {

    // ── Comparator ───────────────────────────────────────────────────────

    @Test
    fun comparator_fromJson_covers_all_ten_tokens() {
        val mapping = listOf(
            "==" to Comparator.EQ,
            "!=" to Comparator.NEQ,
            ">" to Comparator.GT,
            "<" to Comparator.LT,
            ">=" to Comparator.GTE,
            "<=" to Comparator.LTE,
            "in" to Comparator.IN,
            "notIn" to Comparator.NOT_IN,
            "exists" to Comparator.EXISTS,
            "notExists" to Comparator.NOT_EXISTS,
        )
        for ((token, expected) in mapping) {
            assertSame("token \"$token\"", expected, Comparator.fromJson(token))
            assertEquals("round-trip token for $expected", token, expected.token)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun comparator_fromJson_rejects_unknown() {
        Comparator.fromJson("===")
    }

    // ── Top-level round-trip on the §10.1 KYC sample ─────────────────────

    @Test
    fun parses_kyc_workflow_full_shape() {
        val wf = WorkflowJson.parse(kycWorkflowJson)

        assertEquals("wf_kyc_onboarding", wf.workflowId)
        assertEquals(3, wf.version)
        assertEquals("mtn-ng", wf.tenantId)
        assertEquals("KYC Full Onboarding", wf.name)
        assertEquals("collect_data", wf.entryNode)
        assertEquals(6, wf.nodes.size)
        assertEquals(6, wf.edges.size)

        // Node-level checks.
        val collectData = wf.nodes.first { it.id == "collect_data" }
        assertEquals("DATA_FORM", collectData.componentType)
        @Suppress("UNCHECKED_CAST")
        val fields = collectData.config["fields"] as List<String>
        assertEquals(listOf("firstName", "lastName", "nationality", "idNumber"), fields)
        assertEquals(emptyMap<String, String>(), collectData.inputMapping)

        val faceMatch = wf.nodes.first { it.id == "face_match" }
        // Numbers come back as Double per Gson's default Object adapter.
        assertEquals(0.85, faceMatch.config["matchThreshold"] as Double, 1e-9)
        assertEquals("\$context.idPhoto", faceMatch.inputMapping["idPhoto"])
        assertEquals("\$face_liveness.capturedImage", faceMatch.inputMapping["selfie"])

        // Edge-level checks.
        val e1 = wf.edges.first { it.id == "e1" }
        assertNull("e1 has no rule", e1.rule)
        assertTrue("e1 is default", e1.default)
        assertNull("e1 has no dataMapping", e1.dataMapping)

        val e2 = wf.edges.first { it.id == "e2" }
        val rule = e2.rule ?: fail("e2 should have a rule").let { return }
        assertEquals(RuleOperator.AND, rule.operator)
        assertEquals(1, rule.conditions.size)
        val cond = rule.conditions[0] as RuleItem.Condition
        assertEquals("\$collect_data.nationality", cond.field)
        assertEquals(Comparator.EQ, cond.comparator)
        assertEquals("NG", cond.value)

        val e4 = wf.edges.first { it.id == "e4" }
        assertEquals(
            mapOf("idPhoto" to "\$nin_verify.extractedPhoto"),
            e4.dataMapping,
        )
    }

    @Test
    fun roundtrip_preserves_full_shape() {
        val parsed = WorkflowJson.parse(kycWorkflowJson)
        val serialized = WorkflowJson.stringify(parsed)
        val reparsed = WorkflowJson.parse(serialized)
        assertEquals(parsed, reparsed)
    }

    // ── Optional fields ──────────────────────────────────────────────────

    @Test
    fun edge_without_rule_or_dataMapping_uses_defaults() {
        val json = """
        {
          "workflowId": "wf",
          "version": 1,
          "tenantId": "t",
          "name": "T",
          "entryNode": "a",
          "nodes": [
            { "id": "a", "componentType": "DATA_FORM", "config": {}, "inputMapping": {} },
            { "id": "b", "componentType": "DATA_FORM", "config": {}, "inputMapping": {} }
          ],
          "edges": [
            { "id": "e", "from": "a", "to": "b" }
          ]
        }
        """.trimIndent()

        val wf = WorkflowJson.parse(json)
        val edge = wf.edges.single()
        assertNull(edge.rule)
        assertNull(edge.dataMapping)
        assertEquals(false, edge.default)
    }

    @Test
    fun node_with_missing_config_and_inputMapping_defaults_to_empty() {
        val json = """
        {
          "workflowId": "wf",
          "version": 1,
          "tenantId": "t",
          "name": "T",
          "entryNode": "a",
          "nodes": [ { "id": "a", "componentType": "DATA_FORM" } ],
          "edges": []
        }
        """.trimIndent()

        val wf = WorkflowJson.parse(json)
        val node = wf.nodes.single()
        assertEquals(emptyMap<String, Any>(), node.config)
        assertEquals(emptyMap<String, String>(), node.inputMapping)
    }

    // ── Admin UI canvas-only fields ──────────────────────────────────────

    @Test
    fun unknown_fields_are_ignored() {
        // Admin UI ships position/label on nodes and label on edges. SDK
        // doesn't model those — they should be quietly dropped on parse.
        val json = """
        {
          "workflowId": "wf", "version": 1, "tenantId": "t", "name": "T",
          "entryNode": "a",
          "nodes": [ {
            "id": "a", "componentType": "DATA_FORM",
            "config": {}, "inputMapping": {},
            "position": { "x": 100, "y": 200 },
            "label": "First step"
          } ],
          "edges": [ {
            "id": "e", "from": "a", "to": "a",
            "label": "self-loop"
          } ]
        }
        """.trimIndent()

        // The real check is just that parse didn't throw. Spot-check the
        // fields the SDK does model.
        val wf = WorkflowJson.parse(json)
        assertEquals("a", wf.nodes.single().id)
        assertEquals("DATA_FORM", wf.nodes.single().componentType)
        assertEquals("e", wf.edges.single().id)
    }

    // ── RuleItem variants ────────────────────────────────────────────────

    @Test
    fun condition_with_all_value_shapes() {
        val cases = listOf(
            // (jsonValue, expectedValueClass, expectedValue)
            "\"NG\"" to "NG",
            "18" to 18.0,                // numbers come back as Double
            "true" to true,
            "false" to false,
            "[\"NG\", \"GH\"]" to listOf("NG", "GH"),
        )
        for ((jsonValue, expected) in cases) {
            val edgeJson = """
                { "id": "e", "from": "a", "to": "b",
                  "rule": { "operator": "AND", "conditions": [
                    { "field": "${'$'}x.y", "comparator": "==", "value": $jsonValue }
                  ] } }
            """.trimIndent()
            val edge = WorkflowJson.gson.fromJson(edgeJson, WorkflowEdge::class.java)
            val cond = (edge.rule!!.conditions.single() as RuleItem.Condition)
            assertEquals("value for $jsonValue", expected, cond.value)
        }
    }

    @Test
    fun exists_and_not_exists_have_no_value() {
        val edgeJson = """
            { "id": "e", "from": "a", "to": "b",
              "rule": { "operator": "AND", "conditions": [
                { "field": "${'$'}x.y", "comparator": "exists" },
                { "field": "${'$'}x.y", "comparator": "notExists" }
              ] } }
        """.trimIndent()
        val edge = WorkflowJson.gson.fromJson(edgeJson, WorkflowEdge::class.java)
        val conds = edge.rule!!.conditions.map { it as RuleItem.Condition }
        assertNull(conds[0].value)
        assertNull(conds[1].value)
        assertEquals(Comparator.EXISTS, conds[0].comparator)
        assertEquals(Comparator.NOT_EXISTS, conds[1].comparator)
    }

    @Test
    fun nested_rule_groups_round_trip() {
        // (nationality == "NG") AND ((age >= 18) OR (overrideFlag exists))
        val rule = EdgeRule(
            operator = RuleOperator.AND,
            conditions = listOf(
                RuleItem.Condition("\$x.nationality", Comparator.EQ, "NG"),
                RuleItem.Group(
                    EdgeRule(
                        operator = RuleOperator.OR,
                        conditions = listOf(
                            RuleItem.Condition("\$x.age", Comparator.GTE, 18.0),
                            RuleItem.Condition(
                                "\$x.overrideFlag",
                                Comparator.EXISTS,
                                null,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val edge = WorkflowEdge(id = "e", from = "a", to = "b", rule = rule)
        val json = WorkflowJson.gson.toJson(edge)
        val reparsed = WorkflowJson.gson.fromJson(json, WorkflowEdge::class.java)
        assertEquals(edge, reparsed)
    }

    @Test
    fun group_serializes_inline_no_envelope() {
        // RuleItem.Group should appear in JSON as the wrapped EdgeRule
        // ({operator, conditions}), NOT inside a {"rule": ...} envelope.
        val group: RuleItem = RuleItem.Group(
            EdgeRule(
                operator = RuleOperator.OR,
                conditions = listOf(
                    RuleItem.Condition("\$x.y", Comparator.EQ, "z"),
                ),
            ),
        )
        val element = JsonParser.parseString(WorkflowJson.gson.toJson(group))
        assertTrue(element.isJsonObject)
        val obj = element.asJsonObject
        assertTrue("Group should serialize with operator key", obj.has("operator"))
        assertTrue("Group should have conditions", obj.has("conditions"))
        assertTrue("Group should NOT wrap with rule envelope", !obj.has("rule"))
    }

    @Test
    fun condition_omits_value_when_null() {
        val cond: RuleItem = RuleItem.Condition(
            field = "\$x.y",
            comparator = Comparator.EXISTS,
            value = null,
        )
        val obj = JsonParser.parseString(WorkflowJson.gson.toJson(cond)).asJsonObject
        assertTrue(obj.has("field"))
        assertTrue(obj.has("comparator"))
        assertTrue("null value should be omitted, not emitted as null", !obj.has("value"))
        assertEquals("exists", obj.get("comparator").asString)
    }

    // ── Parse failure surfaces a clear error ─────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun missing_required_top_level_field_throws() {
        // Missing entryNode.
        val json = """
        {
          "workflowId": "wf",
          "version": 1,
          "tenantId": "t",
          "name": "T",
          "nodes": [],
          "edges": []
        }
        """.trimIndent()
        WorkflowJson.parse(json)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private val kycWorkflowJson = """
    {
      "workflowId": "wf_kyc_onboarding",
      "version": 3,
      "tenantId": "mtn-ng",
      "name": "KYC Full Onboarding",
      "entryNode": "collect_data",
      "nodes": [
        {
          "id": "collect_data",
          "componentType": "DATA_FORM",
          "config": { "fields": ["firstName", "lastName", "nationality", "idNumber"] },
          "inputMapping": {}
        },
        {
          "id": "id_scan",
          "componentType": "ID_VERIFICATION",
          "config": {},
          "inputMapping": { "idNumber": "${'$'}collect_data.formData.idNumber" }
        },
        {
          "id": "nin_verify",
          "componentType": "NIN_VERIFICATION",
          "config": {},
          "inputMapping": { "idNumber": "${'$'}collect_data.formData.idNumber" }
        },
        {
          "id": "passport_scan",
          "componentType": "PASSPORT_SCAN",
          "config": { "scanMRZ": true },
          "inputMapping": {}
        },
        {
          "id": "face_liveness",
          "componentType": "FACE_LIVENESS",
          "config": { "challengeType": "active" },
          "inputMapping": {}
        },
        {
          "id": "face_match",
          "componentType": "FACE_MATCH",
          "config": { "matchThreshold": 0.85 },
          "inputMapping": {
            "idPhoto": "${'$'}context.idPhoto",
            "selfie": "${'$'}face_liveness.capturedImage"
          }
        }
      ],
      "edges": [
        { "id": "e1", "from": "collect_data", "to": "id_scan", "default": true },
        {
          "id": "e2",
          "from": "id_scan",
          "to": "nin_verify",
          "rule": {
            "operator": "AND",
            "conditions": [
              { "field": "${'$'}collect_data.nationality", "comparator": "==", "value": "NG" }
            ]
          }
        },
        {
          "id": "e3",
          "from": "id_scan",
          "to": "passport_scan",
          "default": true,
          "rule": {
            "operator": "AND",
            "conditions": [
              { "field": "${'$'}collect_data.nationality", "comparator": "!=", "value": "NG" }
            ]
          }
        },
        {
          "id": "e4",
          "from": "nin_verify",
          "to": "face_liveness",
          "default": true,
          "dataMapping": { "idPhoto": "${'$'}nin_verify.extractedPhoto" }
        },
        {
          "id": "e5",
          "from": "passport_scan",
          "to": "face_liveness",
          "default": true,
          "dataMapping": { "idPhoto": "${'$'}passport_scan.photo" }
        },
        { "id": "e6", "from": "face_liveness", "to": "face_match", "default": true }
      ]
    }
    """.trimIndent()

}
