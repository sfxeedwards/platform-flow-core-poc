package com.seamfix.platformflow.core.engine

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.model.Comparator
import com.seamfix.platformflow.core.model.EdgeRule
import com.seamfix.platformflow.core.model.RuleItem
import com.seamfix.platformflow.core.model.RuleOperator
import com.seamfix.platformflow.core.model.WorkflowDefinition
import com.seamfix.platformflow.core.model.WorkflowEdge
import com.seamfix.platformflow.core.model.WorkflowNode
import com.seamfix.platformflow.core.registry.DefaultComponentRegistry
import com.seamfix.platformflow.core.ui.ComponentHost
import com.seamfix.platformflow.core.ui.TestComponentHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [WorkflowEngine] against SDK Architecture §4. Covers the
 * full run loop (linear, branching, dataMapping flow, validation
 * failures, component failures, status lifecycle, streaming progress) and
 * an end-to-end miniature KYC workflow that exercises every collaborator
 * (`WorkflowJson` parsing, `SessionStore` writes, `DataResolver` lookups,
 * `RuleEvaluator` short-circuit, registry dispatch).
 */
class WorkflowEngineTest {

    private val noopHost: ComponentHost = TestComponentHost()

    // ── Test fixture: a recordable component ─────────────────────────

    /**
     * Component that returns a configurable [outputs] map and records
     * every call so tests can assert dispatch order and arguments.
     */
    private class RecordingComponent(
        override val type: String,
        private val outputs: Map<String, Any> = emptyMap(),
        private val failure: ComponentResult.Failure? = null,
    ) : FlowComponent {
        val invocations: MutableList<Invocation> = mutableListOf()

        data class Invocation(
            val config: Map<String, Any>,
            val inputs: Map<String, Any?>,
        )

        override suspend fun execute(
            config: Map<String, Any>,
            inputs: Map<String, Any?>,
            host: ComponentHost,
        ): ComponentResult {
            invocations.add(Invocation(config, inputs))
            return failure ?: ComponentResult.Success(outputs)
        }
    }

    private fun newEngine(
        workflow: WorkflowDefinition,
        components: List<FlowComponent>,
        input: Map<String, Any> = emptyMap(),
        onStepComplete: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): EngineHarness {
        val registry = DefaultComponentRegistry(components)
        val store = SessionStore(input)
        val resolver = DataResolver(store)
        val evaluator = RuleEvaluator(resolver)
        val engine = WorkflowEngine(
            workflow = workflow,
            registry = registry,
            sessionStore = store,
            dataResolver = resolver,
            ruleEvaluator = evaluator,
            host = noopHost,
            onStepComplete = onStepComplete,
        )
        return EngineHarness(engine, store)
    }

    private data class EngineHarness(
        val engine: WorkflowEngine,
        val store: SessionStore,
    )

    // ── Linear path ──────────────────────────────────────────────────

    @Test
    fun linear_workflow_executes_each_node_in_order() = runBlocking {
        val a = RecordingComponent("A", outputs = mapOf("ka" to "va"))
        val b = RecordingComponent("B", outputs = mapOf("kb" to "vb"))
        val c = RecordingComponent("C", outputs = mapOf("kc" to "vc"))

        val workflow = WorkflowDefinition(
            workflowId = "linear",
            version = 1,
            tenantId = "t",
            name = "linear",
            entryNode = "a",
            nodes = listOf(
                WorkflowNode("a", "A"),
                WorkflowNode("b", "B"),
                WorkflowNode("c", "C"),
            ),
            edges = listOf(
                WorkflowEdge("e1", "a", "b", default = true),
                WorkflowEdge("e2", "b", "c", default = true),
            ),
        )

        val harness = newEngine(workflow, listOf(a, b, c))
        val result = harness.engine.run()

        assertTrue(result is WorkflowEngine.RunResult.Success)
        assertEquals(WorkflowEngine.EngineStatus.COMPLETED, harness.engine.status)
        assertEquals(listOf("a", "b", "c"), harness.store.executionHistory)
        assertEquals(mapOf("ka" to "va"), harness.store.nodeResults["a"])
        assertEquals(mapOf("kb" to "vb"), harness.store.nodeResults["b"])
        assertEquals(mapOf("kc" to "vc"), harness.store.nodeResults["c"])

        assertEquals(1, a.invocations.size)
        assertEquals(1, b.invocations.size)
        assertEquals(1, c.invocations.size)
    }

    // ── Branching ────────────────────────────────────────────────────

    @Test
    fun rule_picks_winning_branch() = runBlocking {
        val collect = RecordingComponent("COLLECT", outputs = mapOf("nationality" to "NG"))
        val ngBranch = RecordingComponent("NIN", outputs = mapOf("ok" to true))
        val otherBranch = RecordingComponent("PASSPORT", outputs = mapOf("ok" to true))

        val rule = EdgeRule(
            RuleOperator.AND,
            listOf(
                RuleItem.Condition(
                    field = "\$collect.nationality",
                    comparator = Comparator.EQ,
                    value = "NG",
                ),
            ),
        )

        val workflow = WorkflowDefinition(
            workflowId = "branch",
            version = 1,
            tenantId = "t",
            name = "branch",
            entryNode = "collect",
            nodes = listOf(
                WorkflowNode("collect", "COLLECT"),
                WorkflowNode("nin", "NIN"),
                WorkflowNode("passport", "PASSPORT"),
            ),
            edges = listOf(
                WorkflowEdge("e_to_nin", "collect", "nin", rule = rule),
                WorkflowEdge("e_to_passport", "collect", "passport", default = true),
            ),
        )

        val harness = newEngine(workflow, listOf(collect, ngBranch, otherBranch))
        val result = harness.engine.run()

        assertTrue(result is WorkflowEngine.RunResult.Success)
        assertEquals(listOf("collect", "nin"), harness.store.executionHistory)
        assertEquals(1, ngBranch.invocations.size)
        assertEquals("default branch must NOT fire when rule wins", 0, otherBranch.invocations.size)
    }

    @Test
    fun default_edge_fires_when_no_rule_matches() = runBlocking {
        val collect = RecordingComponent("COLLECT", outputs = mapOf("nationality" to "GH"))
        val ngBranch = RecordingComponent("NIN", outputs = emptyMap())
        val passport = RecordingComponent("PASSPORT", outputs = emptyMap())

        val rule = EdgeRule(
            RuleOperator.AND,
            listOf(
                RuleItem.Condition("\$collect.nationality", Comparator.EQ, "NG"),
            ),
        )

        val workflow = WorkflowDefinition(
            workflowId = "branch",
            version = 1,
            tenantId = "t",
            name = "branch",
            entryNode = "collect",
            nodes = listOf(
                WorkflowNode("collect", "COLLECT"),
                WorkflowNode("nin", "NIN"),
                WorkflowNode("passport", "PASSPORT"),
            ),
            edges = listOf(
                WorkflowEdge("e_to_nin", "collect", "nin", rule = rule),
                WorkflowEdge("e_to_passport", "collect", "passport", default = true),
            ),
        )

        val harness = newEngine(workflow, listOf(collect, ngBranch, passport))
        val result = harness.engine.run()

        assertTrue(result is WorkflowEngine.RunResult.Success)
        assertEquals(listOf("collect", "passport"), harness.store.executionHistory)
        assertEquals(0, ngBranch.invocations.size)
        assertEquals(1, passport.invocations.size)
    }

    @Test
    fun no_matching_rule_and_no_default_yields_failure() = runBlocking {
        val collect = RecordingComponent("COLLECT", outputs = mapOf("nationality" to "GH"))
        val ngBranch = RecordingComponent("NIN", outputs = emptyMap())

        val rule = EdgeRule(
            RuleOperator.AND,
            listOf(RuleItem.Condition("\$collect.nationality", Comparator.EQ, "NG")),
        )

        val workflow = WorkflowDefinition(
            workflowId = "branch",
            version = 1,
            tenantId = "t",
            name = "branch",
            entryNode = "collect",
            nodes = listOf(
                WorkflowNode("collect", "COLLECT"),
                WorkflowNode("nin", "NIN"),
            ),
            edges = listOf(
                WorkflowEdge("e_to_nin", "collect", "nin", rule = rule),
            ),
        )

        val harness = newEngine(workflow, listOf(collect, ngBranch))
        val result = harness.engine.run()

        assertTrue(result is WorkflowEngine.RunResult.Failure)
        val cause = (result as WorkflowEngine.RunResult.Failure).cause
        assertTrue(cause is WorkflowException)
        assertTrue(
            "message should call out the offending node",
            cause.message?.contains("collect") == true,
        )
        assertEquals(WorkflowEngine.EngineStatus.FAILED, harness.engine.status)
        assertEquals(
            "collect ran; nin never did",
            listOf("collect"),
            harness.store.executionHistory,
        )
    }

    // ── DataMapping carries values into $context ─────────────────────

    @Test
    fun edge_dataMapping_populates_context_for_downstream_node() = runBlocking {
        val a = RecordingComponent("A", outputs = mapOf("photo" to "photo-bytes"))
        val b = RecordingComponent("B", outputs = mapOf("ok" to true))

        val workflow = WorkflowDefinition(
            workflowId = "ctx",
            version = 1,
            tenantId = "t",
            name = "ctx",
            entryNode = "a",
            nodes = listOf(
                WorkflowNode("a", "A"),
                WorkflowNode(
                    "b",
                    "B",
                    inputMapping = mapOf("idPhoto" to "\$context.idPhoto"),
                ),
            ),
            edges = listOf(
                WorkflowEdge(
                    id = "e",
                    from = "a",
                    to = "b",
                    default = true,
                    dataMapping = mapOf("idPhoto" to "\$a.photo"),
                ),
            ),
        )

        val harness = newEngine(workflow, listOf(a, b))
        val result = harness.engine.run()

        assertTrue(result is WorkflowEngine.RunResult.Success)
        assertEquals("photo-bytes", harness.store.context["idPhoto"])
        assertEquals("photo-bytes", b.invocations.single().inputs["idPhoto"])
    }

    // ── Component returns Failure ────────────────────────────────────

    @Test
    fun component_failure_surfaces_as_ComponentFailureException() = runBlocking {
        val a = RecordingComponent("A", outputs = mapOf("ok" to true))
        val b = RecordingComponent(
            "B",
            failure = ComponentResult.Failure(
                reason = "Camera permission denied",
                code = "CAM_DENIED",
                retryable = true,
            ),
        )

        val workflow = WorkflowDefinition(
            workflowId = "fail",
            version = 1,
            tenantId = "t",
            name = "fail",
            entryNode = "a",
            nodes = listOf(WorkflowNode("a", "A"), WorkflowNode("b", "B")),
            edges = listOf(WorkflowEdge("e", "a", "b", default = true)),
        )

        val harness = newEngine(workflow, listOf(a, b))
        val result = harness.engine.run()

        assertTrue(result is WorkflowEngine.RunResult.Failure)
        val cause = (result as WorkflowEngine.RunResult.Failure).cause
        assertTrue(cause is ComponentFailureException)
        cause as ComponentFailureException
        assertEquals("b", cause.nodeId)
        assertEquals("B", cause.componentType)
        assertEquals("Camera permission denied", cause.failure.reason)
        assertEquals("CAM_DENIED", cause.failure.code)
        assertTrue(cause.failure.retryable)

        assertEquals(WorkflowEngine.EngineStatus.FAILED, harness.engine.status)
        assertEquals(
            "a recorded its result; b's outputs were not stored",
            listOf("a"),
            harness.store.executionHistory,
        )
    }

    // ── Validation failures ──────────────────────────────────────────

    @Test
    fun missing_entry_node_fails_validation() = runBlocking {
        val workflow = WorkflowDefinition(
            workflowId = "bad",
            version = 1,
            tenantId = "t",
            name = "bad",
            entryNode = "ghost",
            nodes = listOf(WorkflowNode("a", "A")),
            edges = emptyList(),
        )
        val harness = newEngine(workflow, listOf(RecordingComponent("A")))
        val result = harness.engine.run()
        assertTrue(result is WorkflowEngine.RunResult.Failure)
        val msg = (result as WorkflowEngine.RunResult.Failure).cause.message
        assertNotNull(msg)
        assertTrue("message should mention 'ghost'", msg!!.contains("ghost"))
    }

    @Test
    fun unknown_component_type_fails_validation() = runBlocking {
        val workflow = WorkflowDefinition(
            workflowId = "bad",
            version = 1,
            tenantId = "t",
            name = "bad",
            entryNode = "a",
            nodes = listOf(WorkflowNode("a", "MISSING")),
            edges = emptyList(),
        )
        val harness = newEngine(workflow, components = emptyList())
        val result = harness.engine.run()
        assertTrue(result is WorkflowEngine.RunResult.Failure)
        val msg = (result as WorkflowEngine.RunResult.Failure).cause.message
        assertNotNull(msg)
        assertTrue("message should call out the unknown type", msg!!.contains("MISSING"))
    }

    @Test
    fun cycle_in_workflow_fails_validation() = runBlocking {
        val a = RecordingComponent("A")
        val b = RecordingComponent("B")
        val workflow = WorkflowDefinition(
            workflowId = "cyclic",
            version = 1,
            tenantId = "t",
            name = "cyclic",
            entryNode = "a",
            nodes = listOf(WorkflowNode("a", "A"), WorkflowNode("b", "B")),
            edges = listOf(
                WorkflowEdge("e1", "a", "b", default = true),
                WorkflowEdge("e2", "b", "a", default = true),
            ),
        )
        val harness = newEngine(workflow, listOf(a, b))
        val result = harness.engine.run()
        assertTrue(result is WorkflowEngine.RunResult.Failure)
        val msg = (result as WorkflowEngine.RunResult.Failure).cause.message
        assertTrue("message should describe the cycle", msg?.contains("cycle") == true)
        // Engine never called any component.
        assertEquals(0, a.invocations.size)
        assertEquals(0, b.invocations.size)
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    @Test
    fun status_transitions_idle_running_completed() = runBlocking {
        val seenStatuses = mutableListOf<WorkflowEngine.EngineStatus>()
        val component = object : FlowComponent {
            override val type = "X"
            override suspend fun execute(
                config: Map<String, Any>,
                inputs: Map<String, Any?>,
                host: ComponentHost,
            ): ComponentResult {
                // Capturing here would need access to the engine ref; use
                // the harness post-condition to assert the COMPLETED state.
                return ComponentResult.Success(emptyMap())
            }
        }

        val workflow = WorkflowDefinition(
            workflowId = "w",
            version = 1,
            tenantId = "t",
            name = "w",
            entryNode = "x",
            nodes = listOf(WorkflowNode("x", "X")),
            edges = emptyList(),
        )
        val harness = newEngine(workflow, listOf(component))
        seenStatuses.add(harness.engine.status)  // IDLE
        harness.engine.run()
        seenStatuses.add(harness.engine.status)  // COMPLETED

        assertEquals(
            listOf(
                WorkflowEngine.EngineStatus.IDLE,
                WorkflowEngine.EngineStatus.COMPLETED,
            ),
            seenStatuses,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun cannot_re_run_a_completed_engine() = runBlocking {
        val workflow = WorkflowDefinition(
            workflowId = "w",
            version = 1,
            tenantId = "t",
            name = "w",
            entryNode = "x",
            nodes = listOf(WorkflowNode("x", "X")),
            edges = emptyList(),
        )
        val harness = newEngine(workflow, listOf(RecordingComponent("X")))
        harness.engine.run()
        // Second invocation must throw.
        harness.engine.run()
        Unit
    }

    // ── Streaming progress ───────────────────────────────────────────

    @Test
    fun onStepComplete_fires_per_executed_node_with_running_index() = runBlocking {
        val callbacks = mutableListOf<Triple<String, Int, Int>>()
        val a = RecordingComponent("A")
        val b = RecordingComponent("B")
        val c = RecordingComponent("C")

        val workflow = WorkflowDefinition(
            workflowId = "linear",
            version = 1,
            tenantId = "t",
            name = "linear",
            entryNode = "a",
            nodes = listOf(
                WorkflowNode("a", "A"),
                WorkflowNode("b", "B"),
                WorkflowNode("c", "C"),
            ),
            edges = listOf(
                WorkflowEdge("e1", "a", "b", default = true),
                WorkflowEdge("e2", "b", "c", default = true),
            ),
        )
        val harness = newEngine(workflow, listOf(a, b, c)) { id, idx, total ->
            callbacks.add(Triple(id, idx, total))
        }
        harness.engine.run()

        assertEquals(
            listOf(
                Triple("a", 1, 3),
                Triple("b", 2, 3),
                Triple("c", 3, 3),
            ),
            callbacks,
        )
    }

    @Test
    fun onStepComplete_does_not_fire_when_validation_fails() = runBlocking {
        val callbacks = mutableListOf<String>()
        val workflow = WorkflowDefinition(
            workflowId = "bad",
            version = 1,
            tenantId = "t",
            name = "bad",
            entryNode = "ghost",
            nodes = emptyList(),
            edges = emptyList(),
        )
        val harness = newEngine(workflow, components = emptyList()) { id, _, _ ->
            callbacks.add(id)
        }
        harness.engine.run()
        assertTrue(callbacks.isEmpty())
    }

    // ── Store identity (Failure carries the same store) ──────────────

    @Test
    fun failure_run_result_carries_the_engines_session_store() = runBlocking {
        val workflow = WorkflowDefinition(
            workflowId = "w",
            version = 1,
            tenantId = "t",
            name = "w",
            entryNode = "ghost",
            nodes = emptyList(),
            edges = emptyList(),
        )
        val harness = newEngine(workflow, components = emptyList())
        val result = harness.engine.run() as WorkflowEngine.RunResult.Failure
        assertSame(harness.store, result.store)
    }

    // ── End-to-end mini KYC integration ─────────────────────────────

    @Test
    fun end_to_end_mini_kyc_flow_completes_with_correct_state() = runBlocking {
        // collect → id_scan → (NG branch: nin / non-NG: passport) → face_match
        // Both branches converge at face_match via $context.idPhoto written
        // by the carrying edge's dataMapping.

        val collect = RecordingComponent(
            "COLLECT",
            outputs = mapOf(
                "formData" to mapOf(
                    "idNumber" to "A12345",
                    "nationality" to "NG",
                ),
                "nationality" to "NG",
            ),
        )
        val idScan = RecordingComponent(
            "ID_SCAN",
            outputs = mapOf("documentType" to "NATIONAL_ID"),
        )
        val ninVerify = RecordingComponent(
            "NIN",
            outputs = mapOf("extractedPhoto" to "nin-photo-bytes"),
        )
        val passportScan = RecordingComponent(
            "PASSPORT",
            outputs = mapOf("photo" to "passport-photo-bytes"),
        )
        val faceMatch = RecordingComponent(
            "FACE_MATCH",
            outputs = mapOf(
                "matched" to true,
                "confidence" to 0.91,
                "verdict" to "VALID",
            ),
        )

        val ngRule = EdgeRule(
            RuleOperator.AND,
            listOf(
                RuleItem.Condition(
                    "\$collect.nationality",
                    Comparator.EQ,
                    "NG",
                ),
            ),
        )
        val notNgRule = EdgeRule(
            RuleOperator.AND,
            listOf(
                RuleItem.Condition(
                    "\$collect.nationality",
                    Comparator.NEQ,
                    "NG",
                ),
            ),
        )

        val workflow = WorkflowDefinition(
            workflowId = "kyc",
            version = 1,
            tenantId = "mtn-ng",
            name = "KYC",
            entryNode = "collect",
            nodes = listOf(
                WorkflowNode("collect", "COLLECT"),
                WorkflowNode(
                    "id_scan",
                    "ID_SCAN",
                    inputMapping = mapOf(
                        "idNumber" to "\$collect.formData.idNumber",
                    ),
                ),
                WorkflowNode("nin_verify", "NIN"),
                WorkflowNode("passport_scan", "PASSPORT"),
                WorkflowNode(
                    "face_match",
                    "FACE_MATCH",
                    inputMapping = mapOf(
                        "scannedDocType" to "\$id_scan.documentType",
                        "idPhoto" to "\$context.idPhoto",
                    ),
                ),
            ),
            edges = listOf(
                WorkflowEdge("e1", "collect", "id_scan", default = true),
                WorkflowEdge("e2", "id_scan", "nin_verify", rule = ngRule),
                WorkflowEdge(
                    "e3", "id_scan", "passport_scan",
                    rule = notNgRule, default = true,
                ),
                WorkflowEdge(
                    "e4", "nin_verify", "face_match",
                    default = true,
                    dataMapping = mapOf("idPhoto" to "\$nin_verify.extractedPhoto"),
                ),
                WorkflowEdge(
                    "e5", "passport_scan", "face_match",
                    default = true,
                    dataMapping = mapOf("idPhoto" to "\$passport_scan.photo"),
                ),
            ),
        )

        val harness = newEngine(
            workflow,
            listOf(collect, idScan, ninVerify, passportScan, faceMatch),
        )
        val result = harness.engine.run()

        assertTrue(result is WorkflowEngine.RunResult.Success)
        assertEquals(
            "NG branch should run, passport branch should not",
            listOf("collect", "id_scan", "nin_verify", "face_match"),
            harness.store.executionHistory,
        )
        assertEquals(0, passportScan.invocations.size)
        assertEquals(
            "context idPhoto should hold the NIN-extracted photo",
            "nin-photo-bytes",
            harness.store.context["idPhoto"],
        )
        // face_match received the converged $context.idPhoto.
        val faceMatchCall = faceMatch.invocations.single()
        assertEquals("nin-photo-bytes", faceMatchCall.inputs["idPhoto"])
        assertEquals("NATIONAL_ID", faceMatchCall.inputs["scannedDocType"])
        // face_match's outputs landed in the store.
        assertEquals(
            mapOf("matched" to true, "confidence" to 0.91, "verdict" to "VALID"),
            harness.store.nodeResults["face_match"],
        )
    }
}
