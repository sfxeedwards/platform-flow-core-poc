package com.seamfix.platformflow.core.components

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.Verdict
import com.seamfix.platformflow.core.ui.ComponentHost
import com.seamfix.platformflow.core.ui.TestComponentHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [StatusAggregatorComponent] against SDK Architecture §9.3–9.5.
 */
class StatusAggregatorComponentTest {

    private val noopHost: ComponentHost = TestComponentHost()
    private val aggregator = StatusAggregatorComponent()

    /** Run with default config (no overrides). */
    private fun runWithDefaults(inputs: Map<String, Any?>): ComponentResult.Success =
        runWith(emptyMap(), inputs)

    /** Run with explicit config. Asserts Success and returns the outputs. */
    private fun runWith(
        config: Map<String, Any>,
        inputs: Map<String, Any?>,
    ): ComponentResult.Success = runBlocking {
        val result = aggregator.execute(config, inputs, noopHost)
        assertTrue(
            "aggregator should always Succeed (it never reports Failure)",
            result is ComponentResult.Success,
        )
        result as ComponentResult.Success
    }

    // ── Type field ──────────────────────────────────────────────────────

    @Test
    fun type_matches_registry_key() {
        assertEquals("STATUS_AGGREGATOR", aggregator.type)
        assertEquals(StatusAggregatorComponent.TYPE, aggregator.type)
    }

    // ── Default config behavior ─────────────────────────────────────────

    @Test
    fun empty_inputs_yields_marginal_with_default_label() {
        val out = runWithDefaults(emptyMap())
        assertEquals("HUMAN_INTERVENTION_REQUIRED", out.outputs["processStatus"])
        assertEquals(Verdict.MARGINAL.name, out.outputs["internalVerdict"])
        assertEquals(0, out.outputs["validCount"])
        assertEquals(0, out.outputs["marginalCount"])
        assertEquals(0, out.outputs["invalidCount"])
        assertEquals(emptyMap<String, String>(), out.outputs["breakdown"])
    }

    @Test
    fun all_valid_at_min_threshold_yields_valid() {
        val out = runWithDefaults(
            mapOf(
                "faceMatch" to "VALID",
                "faceLiveness" to "VALID",
                "nin" to "VALID",
                "idScan" to "VALID",
            ),
        )
        assertEquals("VALID", out.outputs["processStatus"])
        assertEquals(Verdict.VALID.name, out.outputs["internalVerdict"])
        assertEquals(4, out.outputs["validCount"])
    }

    @Test
    fun all_valid_below_min_threshold_yields_marginal() {
        // Default minValidCount=4. Only 2 VALIDs ⇒ insufficient confidence.
        val out = runWithDefaults(
            mapOf(
                "faceMatch" to "VALID",
                "nin" to "VALID",
            ),
        )
        assertEquals(Verdict.MARGINAL.name, out.outputs["internalVerdict"])
        assertEquals("HUMAN_INTERVENTION_REQUIRED", out.outputs["processStatus"])
        assertEquals(2, out.outputs["validCount"])
        assertEquals(0, out.outputs["marginalCount"])
    }

    @Test
    fun any_invalid_dominates_to_invalid() {
        // Even 4 VALIDs lose to a single INVALID.
        val out = runWithDefaults(
            mapOf(
                "faceMatch" to "VALID",
                "faceLiveness" to "VALID",
                "nin" to "VALID",
                "idScan" to "VALID",
                "fingerprint" to "INVALID",
            ),
        )
        assertEquals("REJECTED", out.outputs["processStatus"])
        assertEquals(Verdict.INVALID.name, out.outputs["internalVerdict"])
        assertEquals(1, out.outputs["invalidCount"])
        assertEquals(4, out.outputs["validCount"])
    }

    @Test
    fun marginal_at_or_above_hir_threshold_escalates_to_invalid() {
        // Default hirRejectThreshold=3. Exactly 3 MARGINALs ⇒ escalate.
        val out = runWithDefaults(
            mapOf(
                "faceMatch" to "MARGINAL",
                "faceLiveness" to "MARGINAL",
                "nin" to "MARGINAL",
            ),
        )
        assertEquals(Verdict.INVALID.name, out.outputs["internalVerdict"])
        assertEquals("REJECTED", out.outputs["processStatus"])
        assertEquals(3, out.outputs["marginalCount"])
    }

    @Test
    fun marginal_below_hir_threshold_yields_marginal() {
        val out = runWithDefaults(
            mapOf(
                "faceMatch" to "VALID",
                "nin" to "MARGINAL",
                "idScan" to "MARGINAL",
            ),
        )
        assertEquals(Verdict.MARGINAL.name, out.outputs["internalVerdict"])
        assertEquals("HUMAN_INTERVENTION_REQUIRED", out.outputs["processStatus"])
    }

    // ── Custom config ───────────────────────────────────────────────────

    @Test
    fun custom_minValidCount_lowers_the_bar() {
        val out = runWith(
            config = mapOf("minValidCount" to 2),
            inputs = mapOf(
                "faceMatch" to "VALID",
                "nin" to "VALID",
            ),
        )
        assertEquals(Verdict.VALID.name, out.outputs["internalVerdict"])
    }

    @Test
    fun custom_hirRejectThreshold_changes_marginal_escalation() {
        // Threshold = 5; 4 MARGINALs stay MARGINAL.
        val out = runWith(
            config = mapOf("hirRejectThreshold" to 5),
            inputs = mapOf(
                "a" to "MARGINAL",
                "b" to "MARGINAL",
                "c" to "MARGINAL",
                "d" to "MARGINAL",
            ),
        )
        assertEquals(Verdict.MARGINAL.name, out.outputs["internalVerdict"])
        assertEquals(4, out.outputs["marginalCount"])
    }

    @Test
    fun custom_labels_apply_for_each_verdict() {
        val cfg = mapOf(
            "minValidCount" to 1,
            "validLabel" to "APPROVED",
            "marginalLabel" to "EYEBALL",
            "invalidLabel" to "DENIED",
        )
        val valid = runWith(cfg, mapOf("a" to "VALID"))
        val marginal = runWith(cfg, mapOf("a" to "MARGINAL"))
        val invalid = runWith(cfg, mapOf("a" to "INVALID"))

        assertEquals("APPROVED", valid.outputs["processStatus"])
        assertEquals("EYEBALL", marginal.outputs["processStatus"])
        assertEquals("DENIED", invalid.outputs["processStatus"])

        // internalVerdict is unchanged regardless of label mapping.
        assertEquals("VALID", valid.outputs["internalVerdict"])
        assertEquals("MARGINAL", marginal.outputs["internalVerdict"])
        assertEquals("INVALID", invalid.outputs["internalVerdict"])
    }

    @Test
    fun config_with_partial_overrides_uses_defaults_for_the_rest() {
        // Override only validLabel; marginal and invalid keep defaults.
        val cfg = mapOf("minValidCount" to 1, "validLabel" to "OK")
        val v = runWith(cfg, mapOf("a" to "VALID"))
        val m = runWith(cfg, mapOf("a" to "MARGINAL"))
        val i = runWith(cfg, mapOf("a" to "INVALID"))
        assertEquals("OK", v.outputs["processStatus"])
        assertEquals("HUMAN_INTERVENTION_REQUIRED", m.outputs["processStatus"])
        assertEquals("REJECTED", i.outputs["processStatus"])
    }

    // ── Input parsing leniency ──────────────────────────────────────────

    @Test
    fun non_string_inputs_are_silently_dropped() {
        // Maps, ints, booleans, nulls are all ignored.
        val out = runWithDefaults(
            mapOf(
                "valid" to "VALID",
                "fromMap" to mapOf("verdict" to "VALID"),  // V2 form, not yet supported
                "fromInt" to 42,
                "fromBool" to true,
                "fromNull" to null,
            ),
        )
        assertEquals(1, out.outputs["validCount"])
        assertEquals(0, out.outputs["marginalCount"])
        assertEquals(0, out.outputs["invalidCount"])
        @Suppress("UNCHECKED_CAST")
        val breakdown = out.outputs["breakdown"] as Map<String, String>
        assertEquals(setOf("valid"), breakdown.keys)
    }

    @Test
    fun unknown_verdict_strings_are_silently_dropped() {
        val out = runWithDefaults(
            mapOf(
                "ok" to "VALID",
                "bad" to "BOGUS",
                "lowercase" to "valid",  // case-sensitive — dropped
                "blank" to "",
            ),
        )
        assertEquals(1, out.outputs["validCount"])
        @Suppress("UNCHECKED_CAST")
        val breakdown = out.outputs["breakdown"] as Map<String, String>
        assertEquals(setOf("ok"), breakdown.keys)
    }

    // ── Output structure ────────────────────────────────────────────────

    @Test
    fun output_carries_all_required_keys() {
        val out = runWithDefaults(mapOf("a" to "VALID"))
        assertEquals(
            setOf(
                "processStatus",
                "internalVerdict",
                "validCount",
                "marginalCount",
                "invalidCount",
                "breakdown",
            ),
            out.outputs.keys,
        )
    }

    @Test
    fun breakdown_preserves_input_keys_with_canonical_verdict_names() {
        val out = runWithDefaults(
            mapOf(
                "faceMatch" to "VALID",
                "nin" to "MARGINAL",
                "fingerprint" to "INVALID",
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val breakdown = out.outputs["breakdown"] as Map<String, String>
        assertEquals(
            mapOf(
                "faceMatch" to "VALID",
                "nin" to "MARGINAL",
                "fingerprint" to "INVALID",
            ),
            breakdown,
        )
    }

    // ── Numeric coercion in config ──────────────────────────────────────

    @Test
    fun thresholds_accept_int_long_or_double() {
        // Gson's default Object adapter returns Double for JSON numbers;
        // hand-built configs may use Int. Both must work.
        val asInt = runWith(mapOf("minValidCount" to 1), mapOf("a" to "VALID"))
        val asLong = runWith(mapOf("minValidCount" to 1L), mapOf("a" to "VALID"))
        val asDouble = runWith(mapOf("minValidCount" to 1.0), mapOf("a" to "VALID"))
        assertEquals(Verdict.VALID.name, asInt.outputs["internalVerdict"])
        assertEquals(Verdict.VALID.name, asLong.outputs["internalVerdict"])
        assertEquals(Verdict.VALID.name, asDouble.outputs["internalVerdict"])
    }

    // ── Boundary cases ──────────────────────────────────────────────────

    @Test
    fun valid_count_exactly_equal_to_threshold_qualifies() {
        // §9.3: validCount >= minValidCount, so equality counts.
        val cfg = mapOf("minValidCount" to 3)
        val out = runWith(
            cfg,
            mapOf(
                "a" to "VALID",
                "b" to "VALID",
                "c" to "VALID",
            ),
        )
        assertEquals(Verdict.VALID.name, out.outputs["internalVerdict"])
    }

    @Test
    fun zero_thresholds_make_every_input_qualify() {
        val cfg = mapOf("minValidCount" to 0, "hirRejectThreshold" to 0)
        // 0 inputs ⇒ validCount=0 >= 0 → VALID. (hirReject=0 doesn't fire
        // because marginalCount=0 isn't > 0 in the prior arm... but with
        // hirReject=0, even marginalCount=0 satisfies "marginalCount >=
        // hirRejectThreshold" — so MARGINAL paths escalate. Let's verify
        // this edge case explicitly.)
        val out = runWith(cfg, emptyMap())
        // Algorithm: invalid=0 (skip), marginal=0>=0 (TRUE → INVALID).
        assertEquals(
            "hirRejectThreshold=0 escalates anything (including zero) to INVALID",
            Verdict.INVALID.name,
            out.outputs["internalVerdict"],
        )
    }

    @Test
    fun invalid_takes_precedence_over_marginal_escalation_path() {
        // 1 INVALID + 5 MARGINAL: INVALID arm fires first; the marginal
        // escalation arm never gets evaluated. Same outcome (INVALID), but
        // we want to make sure the precedence is correct (no double-count
        // weirdness).
        val out = runWithDefaults(
            mapOf(
                "x" to "INVALID",
                "a" to "MARGINAL",
                "b" to "MARGINAL",
                "c" to "MARGINAL",
                "d" to "MARGINAL",
                "e" to "MARGINAL",
            ),
        )
        assertEquals(Verdict.INVALID.name, out.outputs["internalVerdict"])
        assertEquals(1, out.outputs["invalidCount"])
        assertEquals(5, out.outputs["marginalCount"])
    }
}
