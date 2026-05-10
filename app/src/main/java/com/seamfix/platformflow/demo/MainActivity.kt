package com.seamfix.platformflow.demo

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.seamfix.platformflow.R
import com.seamfix.platformflow.core.components.BuiltInComponents
import com.seamfix.platformflow.core.components.DataFormComponent
import com.seamfix.platformflow.core.components.FaceMatchComponent
import com.seamfix.platformflow.core.components.NinVerificationComponent
import com.seamfix.platformflow.core.components.StatusAggregatorComponent
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
import kotlinx.coroutines.launch

/**
 * Demo activity that proves the engine runs end-to-end on a real
 * device. No fancy UI — one button, one scrollable text view.
 *
 * On click, the activity:
 *  1. Builds an in-memory sample KYC workflow (DATA_FORM →
 *     NIN_VERIFICATION → FACE_MATCH → STATUS_AGGREGATOR).
 *  2. Pre-loads a [com.seamfix.platformflow.core.registry.DefaultComponentRegistry]
 *     via [BuiltInComponents.newRegistry].
 *  3. Constructs a [WorkflowEngine] with a sample input map and our
 *     [SampleHost].
 *  4. Runs it on the lifecycle scope (the engine is suspending) and
 *     formats the [WorkflowEngine.RunResult] into the text view.
 *
 * No singleton ([com.seamfix.platformflow.core.api.PlatformFlow]) here
 * — the goal is to exercise the engine itself, not the public-API
 * façade. The façade is exercised in unit tests.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 36 enforces edge-to-edge: opt in explicitly and pad
        // the root for status / nav bar insets so content isn't hidden
        // under system bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            v.updatePadding(
                left = bars.left + PADDING_PX,
                top = bars.top + PADDING_PX,
                right = bars.right + PADDING_PX,
                bottom = bars.bottom + PADDING_PX,
            )
            insets
        }

        output = findViewById(R.id.output_view)
        findViewById<Button>(R.id.run_button).setOnClickListener {
            output.text = "Running…"
            lifecycleScope.launch {
                val text = runOnce()
                output.text = text
            }
        }
    }

    /** Build a fresh engine, run the sample workflow, return a printable summary. */
    private suspend fun runOnce(): String {
        val workflow = sampleWorkflow()
        val registry = BuiltInComponents.newRegistry()

        val sessionStore = SessionStore(SAMPLE_INPUT)
        val resolver = DataResolver(sessionStore)
        val evaluator = RuleEvaluator(resolver)
        val engine = WorkflowEngine(
            workflow = workflow,
            registry = registry,
            sessionStore = sessionStore,
            dataResolver = resolver,
            ruleEvaluator = evaluator,
            host = SampleHost(applicationContext),
            onStepComplete = { nodeId, step, total ->
                android.util.Log.d(TAG, "step $step/$total → $nodeId")
            },
        )

        val result = engine.run()
        return formatResult(workflow, result)
    }

    private fun formatResult(
        workflow: WorkflowDefinition,
        result: WorkflowEngine.RunResult,
    ): String = buildString {
        appendLine("Workflow: ${workflow.name} (id=${workflow.workflowId}, v${workflow.version})")
        appendLine("Tenant: ${workflow.tenantId}")
        appendLine("Engine status: ${(result as? WorkflowEngine.RunResult.Failure)?.let { "FAILED" } ?: "COMPLETED"}")
        appendLine()
        appendLine("Execution path:")
        result.store.executionHistory.forEachIndexed { idx, nodeId ->
            appendLine("  ${idx + 1}. $nodeId")
        }
        appendLine()
        appendLine("Node outputs:")
        result.store.nodeResults.forEach { (nodeId, outputs) ->
            appendLine("  $nodeId:")
            outputs.forEach { (key, value) ->
                appendLine("    $key = $value")
            }
        }
        if (result is WorkflowEngine.RunResult.Failure) {
            appendLine()
            appendLine("Failure: ${result.cause::class.java.simpleName}")
            appendLine("  ${result.cause.message ?: "(no message)"}")
        }
    }

    /**
     * Sample KYC workflow with branching + dataMapping. The four nodes
     * are wired:
     *
     *   collect_data ─default→ nin_verify ─default→ face_match ─default→ aggregate
     *
     * The `nin_verify → face_match` edge writes the extracted photo into
     * `$context.idPhoto`, which `face_match` then reads via input
     * mapping. The aggregator pulls the two scoring components' verdicts.
     */
    private fun sampleWorkflow(): WorkflowDefinition = WorkflowDefinition(
        workflowId = "sample-kyc",
        version = 1,
        tenantId = "demo-tenant",
        name = "Sample KYC",
        entryNode = "collect_data",
        nodes = listOf(
            WorkflowNode(
                id = "collect_data",
                componentType = DataFormComponent.TYPE,
                config = mapOf("fields" to listOf("nationality", "ninNumber")),
            ),
            WorkflowNode(
                id = "nin_verify",
                componentType = NinVerificationComponent.TYPE,
                inputMapping = mapOf(
                    "ninNumber" to "\$collect_data.ninNumber",
                ),
            ),
            WorkflowNode(
                id = "face_match",
                componentType = FaceMatchComponent.TYPE,
                inputMapping = mapOf(
                    "idPhoto" to "\$context.idPhoto",
                    "selfie" to "\$input.selfie",
                ),
            ),
            WorkflowNode(
                id = "aggregate",
                componentType = StatusAggregatorComponent.TYPE,
                config = mapOf(
                    "minValidCount" to 2,
                    "hirRejectThreshold" to 3,
                ),
                inputMapping = mapOf(
                    "ninVerdict" to "\$nin_verify.verdict",
                    "faceVerdict" to "\$face_match.verdict",
                ),
            ),
        ),
        edges = listOf(
            // Branch on nationality just to demonstrate rule evaluation.
            // Both Nigerian + non-Nigerian paths land on nin_verify in
            // this stub-only demo.
            WorkflowEdge(
                id = "e1",
                from = "collect_data",
                to = "nin_verify",
                rule = EdgeRule(
                    operator = RuleOperator.AND,
                    conditions = listOf(
                        RuleItem.Condition(
                            field = "\$collect_data.nationality",
                            comparator = Comparator.EXISTS,
                            value = null,
                        ),
                    ),
                ),
                default = true,
            ),
            // Pull the NIN-extracted photo into $context for face_match.
            WorkflowEdge(
                id = "e2",
                from = "nin_verify",
                to = "face_match",
                default = true,
                dataMapping = mapOf("idPhoto" to "\$nin_verify.extractedPhoto"),
            ),
            WorkflowEdge(
                id = "e3",
                from = "face_match",
                to = "aggregate",
                default = true,
            ),
        ),
    )

    companion object {
        private const val TAG = "PlatformFlowDemo"

        /** 16dp expressed in pixels at hdpi baseline — fine for a demo. */
        private val PADDING_PX: Int = (16 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

        /** Sample `$input` bag the engine seeds [SessionStore] with. */
        private val SAMPLE_INPUT: Map<String, Any> = mapOf(
            "firstName" to "Chinedu",
            "lastName" to "Okeke",
            "selfie" to "stub-selfie-bytes",
        )
    }
}
