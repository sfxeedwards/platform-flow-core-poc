package com.seamfix.platformflow.core.engine

import com.seamfix.platformflow.core.model.Comparator
import com.seamfix.platformflow.core.model.EdgeRule
import com.seamfix.platformflow.core.model.RuleItem
import com.seamfix.platformflow.core.model.RuleOperator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [RuleEvaluator] against SDK Architecture §6.
 */
class RuleEvaluatorTest {

    /** Pre-baked resolver fixture mirroring a §10.1 KYC-shaped session. */
    private val resolveSample: (String) -> Any? = { ref ->
        when (ref) {
            "\$collect_data.nationality" -> "NG"
            "\$collect_data.age" -> 30.0           // numbers come back as Double from Gson
            "\$collect_data.consent" -> true
            "\$collect_data.tags" -> listOf("a", "b", "c")
            "\$collect_data.name" -> "Chinedu"
            "\$nin_verify.confidence" -> 0.95
            "\$context.maybeNull" -> null
            else -> null
        }
    }

    private fun cond(
        field: String,
        op: Comparator,
        value: Any? = null,
    ): RuleItem.Condition = RuleItem.Condition(field, op, value)

    private fun rule(
        op: RuleOperator,
        vararg items: RuleItem,
    ): EdgeRule = EdgeRule(op, items.toList())

    // ── Comparator semantics — one test per operator, both arms ──────────

    @Test fun eq_matches_strings() {
        val ev = RuleEvaluator(resolveSample)
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.nationality", Comparator.EQ, "NG"))))
        assertFalse(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.nationality", Comparator.EQ, "GH"))))
    }

    @Test fun eq_matches_numbers_when_types_align() {
        val ev = RuleEvaluator(resolveSample)
        // Both Doubles → equal.
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.age", Comparator.EQ, 30.0))))
        // Mismatched numeric types do NOT match via structural equality.
        // Documents the Admin UI's contract that both sides should be the same type.
        assertFalse(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.age", Comparator.EQ, 30))))
    }

    @Test fun eq_matches_booleans() {
        val ev = RuleEvaluator(resolveSample)
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.consent", Comparator.EQ, true))))
        assertFalse(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.consent", Comparator.EQ, false))))
    }

    @Test fun neq_is_inverse_of_eq() {
        val ev = RuleEvaluator(resolveSample)
        assertFalse(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.nationality", Comparator.NEQ, "NG"))))
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.nationality", Comparator.NEQ, "GH"))))
    }

    @Test fun gt_lt_gte_lte_on_numbers() {
        val ev = RuleEvaluator(resolveSample)
        // age = 30
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.age", Comparator.GT, 18.0))))
        assertFalse(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.age", Comparator.GT, 30.0))))

        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.age", Comparator.LT, 65.0))))
        assertFalse(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.age", Comparator.LT, 30.0))))

        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.age", Comparator.GTE, 30.0))))
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.age", Comparator.GTE, 18.0))))
        assertFalse(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.age", Comparator.GTE, 31.0))))

        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.age", Comparator.LTE, 30.0))))
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.age", Comparator.LTE, 65.0))))
        assertFalse(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.age", Comparator.LTE, 29.0))))
    }

    @Test fun gt_lt_compare_int_sources_with_double_targets() {
        // Mixed Int/Double should compare correctly because compareNumeric
        // coerces both sides via toDouble().
        val resolve: (String) -> Any? = { if (it == "\$x") 18 else null }
        val ev = RuleEvaluator(resolve)
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$x", Comparator.GTE, 18.0))))
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$x", Comparator.LT, 65.0))))
    }

    @Test fun gt_lt_with_nonnumeric_resolved_value_returns_false() {
        // nationality = "NG" — non-numeric LHS. Per spec, compareNumeric
        // returns 0 when either side isn't a Number; GT/LT then return false.
        val ev = RuleEvaluator(resolveSample)
        assertFalse(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.nationality", Comparator.GT, 0.0))))
        assertFalse(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.nationality", Comparator.LT, 100.0))))
    }

    @Test fun gte_lte_with_nonnumeric_returns_true_per_lenient_spec() {
        // Quirk per §6.2: with a 0-result from compareNumeric, 0 ≥ 0 and 0 ≤ 0
        // are both true. Documenting the lenient behavior — a stricter
        // policy is a future refinement.
        val ev = RuleEvaluator(resolveSample)
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.nationality", Comparator.GTE, 100.0))))
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.nationality", Comparator.LTE, -100.0))))
    }

    @Test fun in_with_list_value() {
        val ev = RuleEvaluator(resolveSample)
        assertTrue(
            ev.evaluate(
                rule(
                    RuleOperator.AND,
                    cond(
                        "\$collect_data.nationality",
                        Comparator.IN,
                        listOf("NG", "GH", "KE"),
                    ),
                ),
            ),
        )
        assertFalse(
            ev.evaluate(
                rule(
                    RuleOperator.AND,
                    cond("\$collect_data.nationality", Comparator.IN, listOf("UK", "US")),
                ),
            ),
        )
    }

    @Test fun in_with_non_list_returns_false() {
        val ev = RuleEvaluator(resolveSample)
        assertFalse(
            ev.evaluate(
                rule(RuleOperator.AND, cond("\$collect_data.nationality", Comparator.IN, "NG")),
            ),
        )
        assertFalse(
            ev.evaluate(
                rule(RuleOperator.AND, cond("\$collect_data.nationality", Comparator.IN, null)),
            ),
        )
    }

    @Test fun not_in_with_list_value() {
        val ev = RuleEvaluator(resolveSample)
        assertTrue(
            ev.evaluate(
                rule(
                    RuleOperator.AND,
                    cond("\$collect_data.nationality", Comparator.NOT_IN, listOf("UK", "US")),
                ),
            ),
        )
        assertFalse(
            ev.evaluate(
                rule(
                    RuleOperator.AND,
                    cond("\$collect_data.nationality", Comparator.NOT_IN, listOf("NG")),
                ),
            ),
        )
    }

    @Test fun not_in_with_non_list_returns_true() {
        val ev = RuleEvaluator(resolveSample)
        assertTrue(
            ev.evaluate(
                rule(RuleOperator.AND, cond("\$collect_data.nationality", Comparator.NOT_IN, "NG")),
            ),
        )
    }

    @Test fun exists_and_not_exists() {
        val ev = RuleEvaluator(resolveSample)
        // Resolves to "NG" — non-null.
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.nationality", Comparator.EXISTS))))
        assertFalse(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.nationality", Comparator.NOT_EXISTS))))

        // Resolves to null (key absent).
        assertFalse(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.middleName", Comparator.EXISTS))))
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.middleName", Comparator.NOT_EXISTS))))

        // Resolves to explicit null (stored as null).
        assertFalse(ev.evaluate(rule(RuleOperator.AND, cond("\$context.maybeNull", Comparator.EXISTS))))
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$context.maybeNull", Comparator.NOT_EXISTS))))
    }

    @Test fun eq_with_null_resolved_value() {
        val ev = RuleEvaluator(resolveSample)
        // Null == null → true.
        assertTrue(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.middleName", Comparator.EQ, null))))
        // Null == "NG" → false.
        assertFalse(ev.evaluate(rule(RuleOperator.AND, cond("\$collect_data.middleName", Comparator.EQ, "NG"))))
    }

    // ── Logical operators ───────────────────────────────────────────────

    @Test fun and_returns_true_when_all_conditions_true() {
        val ev = RuleEvaluator(resolveSample)
        val r = rule(
            RuleOperator.AND,
            cond("\$collect_data.nationality", Comparator.EQ, "NG"),
            cond("\$collect_data.age", Comparator.GTE, 18.0),
            cond("\$collect_data.consent", Comparator.EQ, true),
        )
        assertTrue(ev.evaluate(r))
    }

    @Test fun and_returns_false_when_any_condition_false() {
        val ev = RuleEvaluator(resolveSample)
        val r = rule(
            RuleOperator.AND,
            cond("\$collect_data.nationality", Comparator.EQ, "NG"),
            cond("\$collect_data.age", Comparator.LT, 18.0),  // false
            cond("\$collect_data.consent", Comparator.EQ, true),
        )
        assertFalse(ev.evaluate(r))
    }

    @Test fun or_returns_true_when_any_condition_true() {
        val ev = RuleEvaluator(resolveSample)
        val r = rule(
            RuleOperator.OR,
            cond("\$collect_data.nationality", Comparator.EQ, "UK"),  // false
            cond("\$collect_data.age", Comparator.GTE, 18.0),         // true
            cond("\$collect_data.consent", Comparator.EQ, false),     // false
        )
        assertTrue(ev.evaluate(r))
    }

    @Test fun or_returns_false_when_all_conditions_false() {
        val ev = RuleEvaluator(resolveSample)
        val r = rule(
            RuleOperator.OR,
            cond("\$collect_data.nationality", Comparator.EQ, "UK"),
            cond("\$collect_data.age", Comparator.LT, 18.0),
        )
        assertFalse(ev.evaluate(r))
    }

    // Empty operand lists — vacuous truth defaults.
    @Test fun and_over_empty_conditions_is_true() {
        val ev = RuleEvaluator(resolveSample)
        assertTrue(ev.evaluate(EdgeRule(RuleOperator.AND, emptyList())))
    }

    @Test fun or_over_empty_conditions_is_false() {
        val ev = RuleEvaluator(resolveSample)
        assertFalse(ev.evaluate(EdgeRule(RuleOperator.OR, emptyList())))
    }

    // ── Recursion ───────────────────────────────────────────────────────

    @Test fun nested_groups_evaluate_correctly() {
        val ev = RuleEvaluator(resolveSample)
        // (nationality == "NG") AND ((age >= 18) OR (consent == false))
        val inner = EdgeRule(
            RuleOperator.OR,
            listOf(
                cond("\$collect_data.age", Comparator.GTE, 18.0),
                cond("\$collect_data.consent", Comparator.EQ, false),
            ),
        )
        val outer = EdgeRule(
            RuleOperator.AND,
            listOf(
                cond("\$collect_data.nationality", Comparator.EQ, "NG"),
                RuleItem.Group(inner),
            ),
        )
        assertTrue(ev.evaluate(outer))
    }

    @Test fun deeply_nested_short_circuits_at_outermost_false_branch() {
        val ev = RuleEvaluator(resolveSample)
        // outermost is AND with first child false → entire tree false
        // regardless of the deeply-nested OR's truth value.
        val deeplyNested = EdgeRule(
            RuleOperator.OR,
            listOf(
                cond("\$collect_data.consent", Comparator.EQ, true),  // true, would short-circuit OR
            ),
        )
        val outer = EdgeRule(
            RuleOperator.AND,
            listOf(
                cond("\$collect_data.nationality", Comparator.EQ, "UK"),  // false
                RuleItem.Group(deeplyNested),                              // never evaluated
            ),
        )
        assertFalse(ev.evaluate(outer))
    }

    // ── Short-circuit (call-counting) ───────────────────────────────────

    @Test fun and_short_circuits_after_first_false() {
        val calls = mutableListOf<String>()
        val resolve: (String) -> Any? = { ref ->
            calls.add(ref)
            when (ref) {
                "\$a" -> "no-match"
                "\$b" -> "match"
                "\$c" -> "match"
                else -> null
            }
        }
        val ev = RuleEvaluator(resolve)
        val r = rule(
            RuleOperator.AND,
            cond("\$a", Comparator.EQ, "match"),  // false → stop here
            cond("\$b", Comparator.EQ, "match"),  // not evaluated
            cond("\$c", Comparator.EQ, "match"),  // not evaluated
        )
        assertFalse(ev.evaluate(r))
        assertEquals(listOf("\$a"), calls)
    }

    @Test fun or_short_circuits_after_first_true() {
        val calls = mutableListOf<String>()
        val resolve: (String) -> Any? = { ref ->
            calls.add(ref)
            when (ref) {
                "\$a" -> "match"
                "\$b" -> "match"
                "\$c" -> "match"
                else -> null
            }
        }
        val ev = RuleEvaluator(resolve)
        val r = rule(
            RuleOperator.OR,
            cond("\$a", Comparator.EQ, "match"),  // true → stop here
            cond("\$b", Comparator.EQ, "match"),
            cond("\$c", Comparator.EQ, "match"),
        )
        assertTrue(ev.evaluate(r))
        assertEquals(listOf("\$a"), calls)
    }

    @Test fun nested_short_circuit_skips_inner_group() {
        val calls = mutableListOf<String>()
        val resolve: (String) -> Any? = { ref ->
            calls.add(ref)
            when (ref) {
                "\$outerA" -> "no-match"   // outer AND fails immediately
                "\$innerA" -> "match"
                "\$innerB" -> "match"
                else -> null
            }
        }
        val ev = RuleEvaluator(resolve)
        val inner = EdgeRule(
            RuleOperator.AND,
            listOf(
                cond("\$innerA", Comparator.EQ, "match"),
                cond("\$innerB", Comparator.EQ, "match"),
            ),
        )
        val outer = EdgeRule(
            RuleOperator.AND,
            listOf(
                cond("\$outerA", Comparator.EQ, "match"),  // false
                RuleItem.Group(inner),                       // skipped
            ),
        )
        assertFalse(ev.evaluate(outer))
        assertEquals(
            "outer AND should short-circuit before touching the inner group",
            listOf("\$outerA"),
            calls,
        )
    }

    @Test fun convenience_constructor_with_DataResolver_wires_resolve() {
        // End-to-end smoke through the production constructor: real
        // SessionStore + real DataResolver feeding the evaluator.
        val store = SessionStore(mapOf("nationality" to "NG"))
        val resolver = DataResolver(store)
        val ev = RuleEvaluator(resolver)

        assertTrue(
            ev.evaluate(
                rule(
                    RuleOperator.AND,
                    cond("\$input.nationality", Comparator.EQ, "NG"),
                ),
            ),
        )
    }
}
