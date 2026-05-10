package com.seamfix.platformflow.core.component

/**
 * The outcome of one [FlowComponent.execute] call.
 *
 * Per SDK Architecture §7.2 a component can either complete with a map of
 * outputs (matching its declared `outputSchema`) or report a failure. There
 * is no "in progress" state — a component is awaited to completion via the
 * suspending [FlowComponent.execute] contract; intermediate UI is
 * rendered via [com.seamfix.platformflow.core.ui.ComponentHost].
 */
sealed class ComponentResult {

    /**
     * The component finished its work and produced [outputs].
     *
     * @property outputs Output field name → value, matching the component's
     *  registered `outputSchema`. The engine writes this map verbatim into
     *  the [SessionStore][com.seamfix.platformflow.core.engine.SessionStore]
     *  under the node's id.
     */
    data class Success(val outputs: Map<String, Any>) : ComponentResult()

    /**
     * The component could not complete.
     *
     * @property reason Human-readable description of what went wrong.
     *  Surfaced to the host app via the SDK's error callbacks.
     * @property code Optional machine-readable error code (e.g. an HTTP
     *  status, an SDK error enum's name) so host code can branch
     *  programmatically without parsing [reason].
     * @property retryable Whether the host should offer the user a retry.
     *  `false` means the failure is terminal for this node — typically a
     *  validation, configuration, or input-mapping error rather than a
     *  transient one (network timeout, camera permission denial, etc.).
     */
    data class Failure(
        val reason: String,
        val code: String? = null,
        val retryable: Boolean = false,
    ) : ComponentResult()
}
