package com.seamfix.platformflow.core.components

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.component.Verdict
import com.seamfix.platformflow.core.ui.ComponentHost

/**
 * **Stub** built-in for `BVN_VERIFICATION`. Scoring component that
 * verifies a Bank Verification Number against the NIBSS API.
 *
 * The V1 stub returns a canned VALID verdict and an empty
 * extracted-data map. Real NIBSS integration lands when the network
 * layer is wired (Task 26).
 */
class BvnVerificationComponent : FlowComponent {
    override val type: String = TYPE

    override suspend fun execute(
        config: Map<String, Any>,
        inputs: Map<String, Any?>,
        host: ComponentHost,
    ): ComponentResult = ComponentResult.Success(
        mapOf(
            "verified" to true,
            "extractedData" to emptyMap<String, Any>(),
            "confidence" to 0.95,
            "verdict" to Verdict.VALID.name,
        ),
    )

    companion object { const val TYPE: String = "BVN_VERIFICATION" }
}
