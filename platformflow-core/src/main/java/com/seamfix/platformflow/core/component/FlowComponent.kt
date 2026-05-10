package com.seamfix.platformflow.core.component

import com.seamfix.platformflow.core.ui.ComponentHost

/**
 * The contract every workflow step implements. Per SDK Architecture §7.1.
 *
 * The engine resolves a node's `inputMapping` (via
 * [DataResolver][com.seamfix.platformflow.core.engine.DataResolver]),
 * looks the component up in the registry by [type], and calls [execute]
 * with the node's `config`, the resolved inputs, and a [ComponentHost] for
 * UI work. The component returns a [ComponentResult] which the engine
 * writes into the [SessionStore][com.seamfix.platformflow.core.engine.SessionStore].
 *
 * Implementations are typically:
 *  - **Capture / biometric components** (e.g. `PORTRAIT`, `FACE_LIVENESS`)
 *    — render a Fragment via [ComponentHost.showFragment] and await the
 *    user's interaction.
 *  - **Verification components** (e.g. `NIN_VERIFICATION`,
 *    `BVN_VERIFICATION`) — call backend APIs via
 *    [ComponentHost.withLoading].
 *  - **Pure-compute components** (e.g. `STATUS_AGGREGATOR`) — touch no UI
 *    or network and return synchronously inside the suspend.
 *
 * `execute` is `suspend` so components can do async work (UI dialogs,
 * network calls) without blocking the engine's main loop.
 */
interface FlowComponent {

    /**
     * Unique registry key. Matches the `componentType` in the workflow
     * JSON (e.g. `"FACE_MATCH"`, `"DATA_FORM"`).
     */
    val type: String

    /**
     * Run the component to completion.
     *
     * @param config Component-specific configuration from the workflow node.
     *  The Admin UI's config-panel form produces this map; the runtime
     *  consumes it. Numbers come back as `Double` (Gson default).
     * @param inputs Resolved input values, keyed by the component's
     *  declared input names. Values are whatever
     *  [DataResolver.resolveInputMapping][com.seamfix.platformflow.core.engine.DataResolver.resolveInputMapping]
     *  returned — `null` if the source ref didn't resolve.
     * @param host Bridge to the Android environment for UI and async work.
     * @return [ComponentResult.Success] with an output map matching the
     *  component's `outputSchema`, or [ComponentResult.Failure].
     */
    suspend fun execute(
        config: Map<String, Any>,
        inputs: Map<String, Any?>,
        host: ComponentHost,
    ): ComponentResult
}
