package com.seamfix.platformflow.core.engine

import com.seamfix.platformflow.core.model.Comparator
import com.seamfix.platformflow.core.model.EdgeRule
import com.seamfix.platformflow.core.model.RuleItem
import com.seamfix.platformflow.core.model.RuleOperator

/**
 * Evaluates an [EdgeRule] tree to a boolean. Per SDK Architecture §6.
 *
 * The evaluator is stateless. It takes a `resolve` callable for left-side
 * scope-reference lookups (typically [DataResolver.resolve]) and applies the
 * comparator semantics from §6.2.
 *
 * **Short-circuit semantics (§6.3).** AND folds via `Iterable.all`, OR folds
 * via `Iterable.any` — both invoke the predicate lazily, so AND stops at
 * the first `false` and OR stops at the first `true`. Nested groups
 * recurse the same way, so the entire condition tree is evaluated in
 * left-to-right minimal-work order.
 *
 * **Empty operand lists.** AND over zero conditions is `true` (vacuous
 * truth); OR over zero conditions is `false`. The validator rejects empty
 * rules at design time, so neither should ship in published workflows —
 * the runtime semantics here are just predictable defaults.
 */
class RuleEvaluator(private val resolve: (String) -> Any?) {

    /** Convenience for the production wiring path. */
    constructor(dataResolver: DataResolver) : this(dataResolver::resolve)

    fun evaluate(rule: EdgeRule): Boolean = when (rule.operator) {
        RuleOperator.AND -> rule.conditions.all { evaluateItem(it) }
        RuleOperator.OR -> rule.conditions.any { evaluateItem(it) }
    }

    private fun evaluateItem(item: RuleItem): Boolean = when (item) {
        is RuleItem.Condition -> evaluateCondition(item)
        is RuleItem.Group -> evaluate(item.rule)
    }

    /**
     * Apply a single comparator. Mirrors §6.2 exactly:
     *
     *  - EQ / NEQ — Kotlin structural equality on the resolved LHS and the
     *    literal RHS. Numbers come back as `Double` from Gson by default;
     *    if the RHS is a different numeric type the equality may fail —
     *    callers are expected to keep both sides in the same type via the
     *    Admin UI's value-type picker.
     *  - GT / LT / GTE / LTE — numeric coercion via [compareNumeric]. Both
     *    sides must be `Number`; either being non-numeric collapses the
     *    comparison to a 0-result, which means GT/LT return `false` and
     *    GTE/LTE return `true` (lenient, per spec).
     *  - IN / NOT_IN — RHS must be a `List<*>` (the validator enforces
     *    this for new workflows). A non-list RHS makes IN false and
     *    NOT_IN true.
     *  - EXISTS / NOT_EXISTS — `null`-check on the resolved LHS only;
     *    the RHS [RuleItem.Condition.value] is ignored.
     */
    private fun evaluateCondition(cond: RuleItem.Condition): Boolean {
        val resolved = resolve(cond.field)
        return when (cond.comparator) {
            Comparator.EQ -> resolved == cond.value
            Comparator.NEQ -> resolved != cond.value
            Comparator.GT -> compareNumeric(resolved, cond.value) > 0
            Comparator.LT -> compareNumeric(resolved, cond.value) < 0
            Comparator.GTE -> compareNumeric(resolved, cond.value) >= 0
            Comparator.LTE -> compareNumeric(resolved, cond.value) <= 0
            Comparator.IN -> {
                val list = cond.value as? List<*> ?: return false
                resolved in list
            }
            Comparator.NOT_IN -> {
                val list = cond.value as? List<*> ?: return true
                resolved !in list
            }
            Comparator.EXISTS -> resolved != null
            Comparator.NOT_EXISTS -> resolved == null
        }
    }

    /**
     * Coerce both sides to `Double` and compare. Returns `0` when either
     * side is non-numeric — this is the lenient §6.2 default.
     */
    private fun compareNumeric(a: Any?, b: Any?): Int {
        val numA = (a as? Number)?.toDouble() ?: return 0
        val numB = (b as? Number)?.toDouble() ?: return 0
        return numA.compareTo(numB)
    }
}
