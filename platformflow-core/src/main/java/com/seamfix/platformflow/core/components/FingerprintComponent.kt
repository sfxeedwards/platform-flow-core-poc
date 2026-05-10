package com.seamfix.platformflow.core.components

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.component.Verdict
import com.seamfix.platformflow.core.ui.ComponentHost

/**
 * **Stub** built-in for `FINGERPRINT`. Scoring biometric component that
 * captures fingerprints with configurable finger selection and override
 * policy.
 *
 * The V1 stub returns a canned VALID verdict, a placeholder template,
 * and the configured fingers (echoed from `config.fingers` if
 * provided). Real fingerprint capture lands when the biometric SDK is
 * integrated.
 */
class FingerprintComponent : FlowComponent {
    override val type: String = TYPE

    override suspend fun execute(
        config: Map<String, Any>,
        inputs: Map<String, Any?>,
        host: ComponentHost,
    ): ComponentResult {
        @Suppress("UNCHECKED_CAST")
        val captured = (config["fingers"] as? List<String>).orEmpty()
        return ComponentResult.Success(
            mapOf(
                "fingerprintTemplate" to "stub-template-bytes",
                "capturedFingers" to captured,
                "confidence" to 0.95,
                "verdict" to Verdict.VALID.name,
            ),
        )
    }

    companion object { const val TYPE: String = "FINGERPRINT" }
}
