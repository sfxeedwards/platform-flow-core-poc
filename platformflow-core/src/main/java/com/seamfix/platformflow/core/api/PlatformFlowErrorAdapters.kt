package com.seamfix.platformflow.core.api

import com.seamfix.platformflow.core.engine.ComponentFailureException
import com.seamfix.platformflow.core.engine.WorkflowEngine
import com.seamfix.platformflow.core.engine.WorkflowException

/**
 * Translate the engine's typed-Throwable failure into the public
 * [PlatformFlowError] shape. Per SDK Architecture §13.2.
 *
 * The four engine outcomes map cleanly:
 *
 *  - [ComponentFailureException] (a component returned
 *    `ComponentResult.Failure`) → [PlatformFlowError.ComponentError],
 *    carrying the node id, component type, reason, retryable flag, and
 *    the partial `SessionStore.nodeResults` snapshot for §13.3.
 *  - [WorkflowException] (validation failure or an unrecoverable edge-
 *    routing problem at runtime) → [PlatformFlowError.ValidationError].
 *    The V1 engine throws on the first detected problem, so [errors]
 *    is a single-element list.
 *  - Anything else thrown out of the engine loop →
 *    [PlatformFlowError.InternalError], wrapping the original cause for
 *    diagnostics.
 *
 * `NetworkError` doesn't appear here — the engine never sees the network.
 * Task 26's `WorkflowClient` produces those directly.
 */
fun WorkflowEngine.RunResult.Failure.toPlatformFlowError(): PlatformFlowError =
    when (val ex = cause) {
        is ComponentFailureException -> PlatformFlowError.ComponentError(
            nodeId = ex.nodeId,
            componentType = ex.componentType,
            reason = ex.failure.reason,
            retryable = ex.failure.retryable,
            partialResults = store.nodeResults,
        )
        is WorkflowException -> {
            val msg = ex.message ?: "Workflow validation failed"
            PlatformFlowError.ValidationError(
                message = msg,
                errors = listOf(msg),
            )
        }
        else -> PlatformFlowError.InternalError(
            message = ex.message ?: ex::class.java.simpleName,
            cause = ex,
        )
    }
