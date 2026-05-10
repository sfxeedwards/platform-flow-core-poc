package com.seamfix.platformflow.core.components

import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.registry.DefaultComponentRegistry

/**
 * Catalogue of the SDK's ten built-in components. Per SDK Architecture
 * §8.2: "the SDK ships with a default registry pre-loaded with all
 * built-in components".
 *
 * Two consumption patterns:
 *
 *  - **Default** — host code calls [newRegistry] and gets a fresh
 *    [DefaultComponentRegistry] pre-loaded with all stubs. Custom
 *    components can be added afterwards via `registry.register(...)`.
 *  - **Composition** — host code wants to start from scratch (e.g. to
 *    replace several built-ins with custom impls). It can pass [all]
 *    minus the unwanted ones into `DefaultComponentRegistry(...)`.
 *
 * The list order mirrors Platform Architecture §3.4 (the same order
 * the Admin UI palette uses), with `STATUS_AGGREGATOR` appended last
 * since it's the natural end-of-flow choice.
 */
object BuiltInComponents {

    /**
     * Every built-in component the SDK ships with. Each instance is
     * stateless; reusing the singletons across registries is fine.
     *
     * Currently nine UI / verification components plus one terminal
     * aggregator. Stubs return placeholder Success outputs matching
     * each component's registered `outputSchema`. Real implementations
     * (camera fragments, network calls, vendor SDKs) replace these as
     * later tasks land.
     */
    val all: List<FlowComponent> = listOf(
        DataFormComponent(),
        FaceLivenessComponent(),
        FaceMatchComponent(),
        NinVerificationComponent(),
        BvnVerificationComponent(),
        PassportScanComponent(),
        IdVerificationComponent(),
        FingerprintComponent(),
        PortraitComponent(),
        StatusAggregatorComponent(),
    )

    /**
     * Build a fresh [DefaultComponentRegistry] pre-loaded with [all]
     * built-ins. Host code typically calls this once at SDK init and
     * then optionally registers custom components on the result.
     */
    fun newRegistry(): DefaultComponentRegistry =
        DefaultComponentRegistry(all)
}
