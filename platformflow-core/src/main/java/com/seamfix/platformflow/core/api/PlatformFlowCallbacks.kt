package com.seamfix.platformflow.core.api

/**
 * Host-supplied event listeners for one workflow session. Per SDK
 * Architecture §11.2.
 *
 * **Threading.** Per §12.1 callbacks are delivered on the same
 * coroutine context that invoked [PlatformFlow.start]. Hosts that need
 * Main-thread guarantees should call `start()` from a `lifecycleScope`
 * launched on `Dispatchers.Main`.
 *
 * Every method has a default no-op implementation so hosts only
 * override the events they care about. Typical usage overrides
 * [onComplete] + [onError]; [onStepComplete] is for progress UI;
 * [onCancelled] for analytics on user drop-offs.
 *
 * Lifecycle: exactly one terminal callback fires per session —
 * [onComplete], [onError], or [onCancelled]. [onStepComplete] fires
 * zero or more times before the terminal callback.
 */
interface PlatformFlowCallbacks {

    /**
     * A node completed successfully. Fires after the node's outputs
     * have been recorded in the session store but before edge
     * resolution. Use for progress UI.
     *
     * @param nodeId The node that just completed.
     * @param stepIndex 1-based count of completed steps so far.
     * @param totalSteps Static node count of the workflow — a stable
     *  upper bound on path length. Actual path length depends on
     *  which branches fire.
     */
    fun onStepComplete(nodeId: String, stepIndex: Int, totalSteps: Int) {}

    /** Workflow ran to completion. [result] carries every node's outputs. */
    fun onComplete(result: SessionResult) {}

    /** Workflow failed. See the [PlatformFlowError] arms for failure modes. */
    fun onError(error: PlatformFlowError) {}

    /** User cancelled the session (back press, swipe-dismiss, host cancellation). */
    fun onCancelled() {}
}
