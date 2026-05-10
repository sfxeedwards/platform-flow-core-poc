package com.seamfix.platformflow.core.engine

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.model.WorkflowDefinition
import com.seamfix.platformflow.core.model.WorkflowEdge
import com.seamfix.platformflow.core.model.WorkflowNode
import com.seamfix.platformflow.core.registry.ComponentRegistry
import com.seamfix.platformflow.core.ui.ComponentHost

/**
 * The runtime conductor. Per SDK Architecture §4.
 *
 * One engine instance executes one workflow session: validate the graph,
 * walk node-by-node from `entryNode`, resolve inputs, dispatch the
 * registered component, write results into the [sessionStore], pick the
 * next edge, repeat until a leaf node completes.
 *
 * **Lifecycle.** `IDLE → RUNNING → COMPLETED | FAILED`. `run()` may only
 * be called from `IDLE`; subsequent calls throw [IllegalStateException]
 * because re-running would duplicate the [SessionStore.executionHistory]
 * trail. To run again, construct a fresh engine with a fresh session
 * store.
 *
 * **Failure model.** [run] always returns a [RunResult] — it never throws
 * normally. Validation failures, edge-routing failures, and component
 * failures all surface as [RunResult.Failure] with the underlying
 * [WorkflowException] / [ComponentFailureException] / [Throwable]
 * attached. The session store is included in both arms so callers can
 * inspect partial state on failure (per arch §4.1 step d: "fire onError
 * callback with partial results").
 *
 * **Streaming progress.** [onStepComplete] fires once per successfully
 * executed node, after its outputs have been recorded but before edge
 * resolution. `totalSteps` is the static node count of the workflow — a
 * stable upper bound on how many steps the path can take. The actual
 * path length depends on which branches fire.
 */
class WorkflowEngine(
    private val workflow: WorkflowDefinition,
    private val registry: ComponentRegistry,
    private val sessionStore: SessionStore,
    private val dataResolver: DataResolver,
    private val ruleEvaluator: RuleEvaluator,
    private val host: ComponentHost,
    private val onStepComplete: (nodeId: String, stepIndex: Int, totalSteps: Int) -> Unit =
        { _, _, _ -> },
) {
    enum class EngineStatus { IDLE, RUNNING, COMPLETED, FAILED }

    private var statusInternal: EngineStatus = EngineStatus.IDLE

    /** Snapshot of engine progress. Read-only outside the engine. */
    val status: EngineStatus get() = statusInternal

    /**
     * The outcome of a single [run] invocation. Both arms include the
     * [store] so the caller can inspect everything that did execute, even
     * on failure.
     */
    sealed class RunResult {
        abstract val store: SessionStore

        data class Success(override val store: SessionStore) : RunResult()
        data class Failure(
            val cause: Throwable,
            override val store: SessionStore,
        ) : RunResult()
    }

    /**
     * Drive the workflow to completion or terminal failure. Suspending
     * because [com.seamfix.platformflow.core.component.FlowComponent.execute]
     * is suspending — components may render UI fragments and call back-end
     * APIs concurrently with the engine's main loop.
     */
    suspend fun run(): RunResult {
        check(statusInternal == EngineStatus.IDLE) {
            "WorkflowEngine cannot be re-run; status=$statusInternal"
        }
        statusInternal = EngineStatus.RUNNING
        return try {
            validate()
            executeLoop()
            statusInternal = EngineStatus.COMPLETED
            RunResult.Success(sessionStore)
        } catch (e: Throwable) {
            statusInternal = EngineStatus.FAILED
            RunResult.Failure(e, sessionStore)
        }
    }

    // ── Validation (algorithm step 2) ────────────────────────────────

    private fun validate() {
        val knownNodeIds = workflow.nodes.map { it.id }.toSet()
        if (workflow.entryNode.isEmpty() || workflow.entryNode !in knownNodeIds) {
            throw WorkflowException(
                "Entry node '${workflow.entryNode}' does not exist in workflow",
            )
        }
        for (node in workflow.nodes) {
            if (!registry.has(node.componentType)) {
                throw WorkflowException(
                    "Node '${node.id}' references unknown componentType '${node.componentType}'",
                )
            }
        }
        detectCycle()
    }

    /**
     * Three-color DFS over the edge graph. Throws on the first back-edge
     * with the participating node ids in path order so the message is
     * actionable.
     */
    private fun detectCycle() {
        val white = 0
        val grey = 1
        val black = 2
        val color = mutableMapOf<String, Int>()
        for (n in workflow.nodes) color[n.id] = white
        val pathStack = mutableListOf<String>()

        fun dfs(nodeId: String): List<String>? {
            color[nodeId] = grey
            pathStack.add(nodeId)
            for (e in workflow.edges) {
                if (e.from != nodeId) continue
                val next = e.to
                when (color[next]) {
                    grey -> {
                        // Back-edge — slice the path from `next` onward.
                        val start = pathStack.indexOf(next)
                        return if (start >= 0)
                            pathStack.subList(start, pathStack.size).toList() + next
                        else
                            listOf(next)
                    }
                    white -> {
                        val found = dfs(next)
                        if (found != null) return found
                    }
                    else -> { /* black: already settled; skip */ }
                }
            }
            pathStack.removeAt(pathStack.lastIndex)
            color[nodeId] = black
            return null
        }

        for (n in workflow.nodes) {
            if (color[n.id] == white) {
                val cycle = dfs(n.id)
                if (cycle != null) {
                    throw WorkflowException(
                        "Workflow contains a cycle: ${cycle.joinToString(" → ")}",
                    )
                }
            }
        }
    }

    // ── Main loop (algorithm step 4) ─────────────────────────────────

    private suspend fun executeLoop() {
        val nodeById = workflow.nodes.associateBy { it.id }
        val totalSteps = workflow.nodes.size
        var currentNodeId = workflow.entryNode
        var stepIndex = 0

        while (true) {
            val node: WorkflowNode = nodeById[currentNodeId]
                ?: throw WorkflowException(
                    "Node '$currentNodeId' not found during execution",
                )

            // a. Resolve inputs for this node.
            val inputs = dataResolver.resolveInputMapping(node)

            // b. Look up the component.
            val component = registry.require(node.componentType)

            // c. Execute. Component-thrown exceptions propagate up to the
            //    `run()` catch block as terminal failures.
            val result = component.execute(node.config, inputs, host)

            // d/e. Record success or surface failure.
            when (result) {
                is ComponentResult.Failure ->
                    throw ComponentFailureException(
                        nodeId = node.id,
                        componentType = node.componentType,
                        failure = result,
                    )
                is ComponentResult.Success ->
                    sessionStore.recordNodeResult(node.id, result.outputs)
            }

            // f. Streaming progress.
            stepIndex++
            onStepComplete(node.id, stepIndex, totalSteps)

            // g/h. Outgoing edges — none means we've reached a terminal.
            val outgoing = workflow.edges.filter { it.from == node.id }
            if (outgoing.isEmpty()) return

            // i. Pick the winning edge (rules in definition order; default
            //    fallback if no rule matched).
            val winning = resolveWinningEdge(outgoing, node.id)

            // j. Apply this edge's dataMapping into $context.
            winning.dataMapping?.let {
                sessionStore.applyDataMapping(it, dataResolver::resolve)
            }

            // k. Advance.
            currentNodeId = winning.to
        }
    }

    /**
     * Edge resolution per §4.3. Iterate outgoing in declaration order;
     * first edge whose rule evaluates true wins. If no rule matches,
     * fall back to the first edge marked `default = true`. If neither
     * fires, the workflow has no defined route from this node — throw.
     */
    private fun resolveWinningEdge(
        outgoing: List<WorkflowEdge>,
        fromNodeId: String,
    ): WorkflowEdge {
        for (edge in outgoing) {
            val rule = edge.rule ?: continue
            if (ruleEvaluator.evaluate(rule)) return edge
        }
        return outgoing.firstOrNull { it.default }
            ?: throw WorkflowException(
                "No matching rule and no default edge from node '$fromNodeId'",
            )
    }
}
