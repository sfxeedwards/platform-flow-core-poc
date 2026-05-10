package com.seamfix.platformflow.core.api

import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.components.BuiltInComponents
import com.seamfix.platformflow.core.engine.DataResolver
import com.seamfix.platformflow.core.engine.RuleEvaluator
import com.seamfix.platformflow.core.engine.SessionStore
import com.seamfix.platformflow.core.engine.WorkflowEngine
import com.seamfix.platformflow.core.network.WorkflowClient
import com.seamfix.platformflow.core.network.WorkflowClientException
import com.seamfix.platformflow.core.registry.DefaultComponentRegistry
import com.seamfix.platformflow.core.ui.ComponentHost
import kotlinx.coroutines.CancellationException

/**
 * Testable backbone of the public API. The singleton [PlatformFlow]
 * façade delegates every operation here. Per SDK Architecture §11.
 *
 * Construction is `internal` — host apps go through [PlatformFlow] —
 * but unit tests instantiate this directly with fakes.
 *
 * @property config The resolved [PlatformFlowConfig].
 * @property workflowClient The network layer (Task 26). Tests inject a
 *  fake; the singleton wires a Retrofit-backed implementation.
 * @property builtIns Components shipped by the SDK. Defaults to
 *  [BuiltInComponents.all]; tests can substitute a smaller set.
 * @property clock Wall clock used to compute [SessionResult.durationMs].
 *  Injectable so tests can produce deterministic durations.
 */
class PlatformFlowClient internal constructor(
    val config: PlatformFlowConfig,
    private val workflowClient: WorkflowClient,
    private val builtIns: List<FlowComponent> = BuiltInComponents.all,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val customComponents: MutableList<FlowComponent> = mutableListOf()

    /**
     * Register a custom component. Per §11.4 the registered component
     * overrides any built-in with the same `type` (last-write-wins —
     * see [DefaultComponentRegistry.register]).
     *
     * Call before [start] so the component is present when the engine
     * builds the registry.
     */
    fun registerComponent(component: FlowComponent) {
        customComponents.add(component)
    }

    /**
     * Run one workflow session end-to-end. Per §11.2.
     *
     * The flow:
     *  1. Fetch the workflow from [workflowClient] (cache → SWR → wire,
     *     transparent to the caller).
     *  2. Build a registry layering built-ins and any
     *     [registerComponent]-provided custom components.
     *  3. Construct the engine and run it.
     *  4. Translate the [WorkflowEngine.RunResult] into a callback —
     *     [PlatformFlowCallbacks.onComplete] on Success,
     *     [PlatformFlowCallbacks.onError] on Failure (via the
     *     [toPlatformFlowError] adapter).
     *
     * Failure modes mapped per §13:
     *  - Workflow fetch fails → `NetworkError`
     *  - Engine validation / edge-routing fails → `ValidationError`
     *  - Component returns Failure → `ComponentError`
     *  - Anything else thrown → `InternalError`
     *  - Coroutine cancellation → `onCancelled()` then re-throw to
     *    honour structured concurrency.
     *
     * @param host Bridge to the Android environment. V1 hosts construct
     *  this themselves (`TestComponentHost` for tests; an
     *  Activity-bound default impl is a follow-up task).
     * @param workflowId Server-side workflow id to fetch + execute.
     * @param input The host-supplied input bag (the `$input` scope).
     * @param callbacks Listeners that observe the session.
     */
    suspend fun start(
        host: ComponentHost,
        workflowId: String,
        input: Map<String, Any>,
        callbacks: PlatformFlowCallbacks,
    ) {
        val started = clock()
        try {
            val workflow = workflowClient.fetchWorkflow(workflowId)

            val registry = DefaultComponentRegistry(builtIns + customComponents)
            val sessionStore = SessionStore(input)
            val resolver = DataResolver(sessionStore)
            val evaluator = RuleEvaluator(resolver)
            val engine = WorkflowEngine(
                workflow = workflow,
                registry = registry,
                sessionStore = sessionStore,
                dataResolver = resolver,
                ruleEvaluator = evaluator,
                host = host,
                onStepComplete = callbacks::onStepComplete,
            )

            when (val result = engine.run()) {
                is WorkflowEngine.RunResult.Success -> {
                    val sessionResult = SessionResult(
                        workflowId = workflow.workflowId,
                        workflowVersion = workflow.version,
                        tenantId = workflow.tenantId,
                        status = SessionStatus.COMPLETED,
                        nodeOutputs = sessionStore.nodeResults.toMap(),
                        executionPath = sessionStore.executionHistory.toList(),
                        durationMs = clock() - started,
                    )
                    callbacks.onComplete(sessionResult)
                }
                is WorkflowEngine.RunResult.Failure ->
                    callbacks.onError(result.toPlatformFlowError())
            }
        } catch (cancel: CancellationException) {
            // Coroutine cancellation — honour structured concurrency by
            // surfacing the cancellation to the host AND re-throwing so
            // the calling scope unwinds.
            callbacks.onCancelled()
            throw cancel
        } catch (network: WorkflowClientException) {
            callbacks.onError(
                PlatformFlowError.NetworkError(
                    message = network.message ?: "Network failure",
                    cause = network.cause ?: network,
                ),
            )
        } catch (other: Throwable) {
            callbacks.onError(
                PlatformFlowError.InternalError(
                    message = other.message ?: other::class.java.simpleName,
                    cause = other,
                ),
            )
        }
    }

    /**
     * Ship a finished [SessionResult] to the backend. Wraps
     * [WorkflowClient.reportResult] — fire-and-forget on the host's
     * side; failures throw [WorkflowClientException] for the host to
     * decide whether to retry.
     */
    suspend fun reportResult(result: SessionResult) {
        workflowClient.reportResult(result)
    }
}
