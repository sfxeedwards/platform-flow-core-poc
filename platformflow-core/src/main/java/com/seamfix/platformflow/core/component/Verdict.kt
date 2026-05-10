package com.seamfix.platformflow.core.component

/**
 * Canonical engine verdict. Per SDK Architecture §9.1.
 *
 * Scoring components ([FACE_MATCH, FACE_LIVENESS, NIN_VERIFICATION,
 * BVN_VERIFICATION, ID_VERIFICATION, FINGERPRINT]) emit one of these as
 * the `"verdict"` field in their output map. Pure capture components
 * (DATA_FORM, PASSPORT_SCAN, PORTRAIT) do not emit a verdict — they
 * collect data without scoring it.
 *
 * The aggregator and the engine never interpret raw confidence scores;
 * they only read this verdict. Each scoring component decides locally
 * (typically via its `matchThreshold` config) which bucket its result
 * falls into.
 *
 * Wire / scope-ref form is the enum's `name`: `"VALID"`, `"MARGINAL"`,
 * `"INVALID"`. The label-mapping in
 * [StatusAggregatorComponent][com.seamfix.platformflow.core.components.StatusAggregatorComponent]
 * translates this canonical value to a tenant-configurable display label.
 */
enum class Verdict {
    /** Score met or exceeded the component's threshold. */
    VALID,

    /** Score fell in an ambiguous range — needs human review. */
    MARGINAL,

    /** Score clearly below threshold — rejected. */
    INVALID,
}
