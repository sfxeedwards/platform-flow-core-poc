package com.seamfix.platformflow.core.api

/**
 * Public, sealed error type the SDK delivers via
 * `PlatformFlowCallbacks.onError(error)` (Task 27). Per SDK Architecture §13.1.
 *
 * **Not an exception.** This is a data type for the error callback, not
 * something to throw. Internal implementation exceptions (
 * [com.seamfix.platformflow.core.engine.WorkflowException],
 * [com.seamfix.platformflow.core.engine.ComponentFailureException], any
 * other [Throwable]) are translated into one of these variants by
 * `RunResult.Failure.toPlatformFlowError()` (see
 * `PlatformFlowErrorAdapters.kt`). Host apps see only this clean,
 * stable shape.
 *
 * Sealed class — host code can `when` over the four variants
 * exhaustively for branch-specific UX (offer retry on transient
 * `NetworkError` / retryable `ComponentError`, show generic failure on
 * `InternalError`, etc.).
 *
 * Per §13.2 the error flow is:
 * ```
 * Workflow fetch fails          → NetworkError
 * Workflow validation fails     → ValidationError
 * Component returns Failure     → ComponentError (with partialResults)
 * Uncaught exception in engine  → InternalError
 * User presses back             → onCancelled callback (NOT an error)
 * ```
 */
sealed class PlatformFlowError {

    /**
     * The workflow JSON couldn't be fetched from the backend. Produced by
     * the network layer (Task 26) on connection failure, non-2xx HTTP
     * status, malformed JSON, etc. The optional [cause] carries the
     * underlying exception when one exists.
     */
    data class NetworkError(
        val message: String,
        val cause: Throwable? = null,
    ) : PlatformFlowError()

    /**
     * The workflow JSON is structurally invalid: a cycle, a missing entry
     * node, an unknown component type, a node whose only outgoing edges
     * have no rules and no default, etc. Per arch §4.1's validation
     * step.
     *
     * @property errors A list of human-readable problem descriptions. The
     *  V1 engine throws on the first detected issue, so this list is
     *  typically size 1; the list shape is forward-looking for a future
     *  engine that collects every validation error before failing.
     */
    data class ValidationError(
        val message: String,
        val errors: List<String>,
    ) : PlatformFlowError()

    /**
     * A component reported [com.seamfix.platformflow.core.component.ComponentResult.Failure]
     * during execution. Preserves what executed before the failure in
     * [partialResults] so the host can pre-fill on retry, persist the
     * collected portion, or surface "you got this far" to the user
     * (§13.3).
     *
     * @property nodeId The id of the workflow node whose component failed.
     * @property componentType The component-type key
     *  (e.g. `"FACE_MATCH"`).
     * @property reason The component's human-readable failure message.
     * @property retryable Whether the failure is transient. Hosts use this
     *  to decide whether to offer a retry or treat the failure as terminal.
     * @property partialResults The
     *  [com.seamfix.platformflow.core.engine.SessionStore.nodeResults]
     *  snapshot at failure time — every node that completed before the
     *  failure, keyed by node id.
     */
    data class ComponentError(
        val nodeId: String,
        val componentType: String,
        val reason: String,
        val retryable: Boolean,
        val partialResults: Map<String, Map<String, Any>>,
    ) : PlatformFlowError()

    /**
     * An unexpected internal SDK error: a component threw an uncaught
     * exception, an engine invariant broke, etc. Treat as terminal — by
     * the time the host sees this, the SDK doesn't know enough to
     * suggest a retry strategy.
     */
    data class InternalError(
        val message: String,
        val cause: Throwable? = null,
    ) : PlatformFlowError()
}
