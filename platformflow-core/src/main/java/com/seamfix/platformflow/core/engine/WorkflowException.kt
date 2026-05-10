package com.seamfix.platformflow.core.engine

import com.seamfix.platformflow.core.component.ComponentResult

/**
 * Engine-level structural failure: validation problems, edge-routing
 * failures, missing nodes during execution, etc. Per SDK Architecture §4.3
 * (and §4.1 step 2).
 *
 * V1 collapses every structural cause into a single class with a
 * descriptive message. Task 28 introduces the proper sealed
 * `OneVerifyError` hierarchy and adapts these into typed cases for the
 * public API.
 */
class WorkflowException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * A component returned [ComponentResult.Failure] during execution. The
 * engine wraps the failure in this exception so the calling layer can
 * inspect `nodeId` / `componentType` along with the original failure
 * payload.
 */
class ComponentFailureException(
    val nodeId: String,
    val componentType: String,
    val failure: ComponentResult.Failure,
) : RuntimeException(
    "Component '$componentType' (node '$nodeId') failed: ${failure.reason}",
)
