package com.seamfix.platformflow.core.components

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.component.Verdict
import com.seamfix.platformflow.core.ui.ComponentHost

/**
 * Terminal pure-compute component that condenses upstream verdicts into a
 * single process-level status. Per SDK Architecture §9.3–9.5.
 *
 * **Inputs.** The aggregator's `inputSchema` is open (§9.5) — the admin
 * wires whatever keys they want pointing at scoring components' `.verdict`
 * outputs. Every input is treated as a verdict string and parsed against
 * [Verdict]; values that aren't `"VALID"` / `"MARGINAL"` / `"INVALID"` are
 * silently ignored. The input key is preserved in `breakdown` as the
 * label admins use to identify which component a verdict came from.
 *
 * **Counting + escalation rules** (§9.3):
 *  1. Any `INVALID` → process is `INVALID`.
 *  2. `marginalCount >= hirRejectThreshold` → escalate to `INVALID`.
 *  3. `marginalCount > 0` (but below threshold) → `MARGINAL`.
 *  4. `validCount >= minValidCount` → `VALID`.
 *  5. Otherwise → `MARGINAL` (insufficient confidence).
 *
 * **Labels** (§9.4) come from config. `processStatus` carries the
 * tenant-configurable display label; `internalVerdict` carries the
 * canonical engine value.
 *
 * **No UI.** [execute] never touches `host`. The component runs to
 * completion synchronously inside the suspend.
 *
 * **V2 expansion (§9.5).** A future version will accept whole node
 * outputs and surface confidence scores in `breakdown`. V1 takes the
 * simpler verdict-string-only path.
 */
class StatusAggregatorComponent : FlowComponent {

    override val type: String = TYPE

    override suspend fun execute(
        config: Map<String, Any>,
        inputs: Map<String, Any?>,
        host: ComponentHost,
    ): ComponentResult {
        val verdicts: List<Pair<String, Verdict>> = inputs.entries.mapNotNull { (key, value) ->
            val parsed = (value as? String)?.let { raw ->
                try {
                    Verdict.valueOf(raw)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            if (parsed != null) key to parsed else null
        }

        val validCount = verdicts.count { it.second == Verdict.VALID }
        val marginalCount = verdicts.count { it.second == Verdict.MARGINAL }
        val invalidCount = verdicts.count { it.second == Verdict.INVALID }

        val minValidCount = (config["minValidCount"] as? Number)?.toInt()
            ?: DEFAULT_MIN_VALID_COUNT
        val hirRejectThreshold = (config["hirRejectThreshold"] as? Number)?.toInt()
            ?: DEFAULT_HIR_REJECT_THRESHOLD

        val internalVerdict: Verdict = when {
            invalidCount > 0 -> Verdict.INVALID
            marginalCount >= hirRejectThreshold -> Verdict.INVALID
            marginalCount > 0 -> Verdict.MARGINAL
            validCount >= minValidCount -> Verdict.VALID
            else -> Verdict.MARGINAL
        }

        val labelMap = mapOf(
            Verdict.VALID to (config["validLabel"] as? String ?: DEFAULT_VALID_LABEL),
            Verdict.MARGINAL to (config["marginalLabel"] as? String ?: DEFAULT_MARGINAL_LABEL),
            Verdict.INVALID to (config["invalidLabel"] as? String ?: DEFAULT_INVALID_LABEL),
        )

        return ComponentResult.Success(
            mapOf(
                "processStatus" to labelMap.getValue(internalVerdict),
                "internalVerdict" to internalVerdict.name,
                "validCount" to validCount,
                "marginalCount" to marginalCount,
                "invalidCount" to invalidCount,
                "breakdown" to verdicts.associate { it.first to it.second.name },
            ),
        )
    }

    companion object {
        const val TYPE: String = "STATUS_AGGREGATOR"

        // Defaults match the §9.4 config-schema defaults.
        const val DEFAULT_MIN_VALID_COUNT: Int = 4
        const val DEFAULT_HIR_REJECT_THRESHOLD: Int = 3
        const val DEFAULT_VALID_LABEL: String = "VALID"
        const val DEFAULT_MARGINAL_LABEL: String = "HUMAN_INTERVENTION_REQUIRED"
        const val DEFAULT_INVALID_LABEL: String = "REJECTED"
    }
}
