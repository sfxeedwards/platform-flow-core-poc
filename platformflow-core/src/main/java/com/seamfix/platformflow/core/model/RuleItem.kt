package com.seamfix.platformflow.core.model

/**
 * A single entry inside an [EdgeRule]'s `conditions` list. Either a leaf
 * [Condition] (field/comparator/value) or a nested rule [Group] (which
 * itself has an operator + conditions).
 *
 * On the wire there's no envelope — the discriminator is structural:
 *  - JSON object with an `operator` key → [Group] wrapping the nested rule.
 *  - JSON object without `operator` → [Condition].
 *
 * The custom (de)serializer in [WorkflowJson] enforces this convention.
 *
 * Per SDK Architecture §3.4.
 */
sealed class RuleItem {
    data class Condition(
        val field: String,
        val comparator: Comparator,
        /**
         * Comparator-dependent. `null` for `EXISTS` / `NOT_EXISTS`. Scalar
         * (String/Number/Boolean) for the binary comparators. List of
         * scalars for `IN` / `NOT_IN`.
         *
         * JSON numbers always come back as [Double] (Gson default for
         * `Object`). The host engine is expected to coerce as needed.
         */
        val value: Any? = null,
    ) : RuleItem()

    data class Group(val rule: EdgeRule) : RuleItem()
}
