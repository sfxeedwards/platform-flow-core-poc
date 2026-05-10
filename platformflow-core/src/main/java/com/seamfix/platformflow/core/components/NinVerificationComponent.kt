package com.seamfix.platformflow.core.components

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.component.Verdict
import com.seamfix.platformflow.core.ui.ComponentHost

/**
 * **Stub** built-in for `NIN_VERIFICATION`. Scoring component that
 * verifies a National Identification Number against the NIMC API.
 *
 * The V1 stub returns a canned VALID verdict, a placeholder
 * extracted-photo string, and an empty extracted-data map. Real NIMC
 * integration lands when the network layer is wired (Task 26).
 */
class NinVerificationComponent : FlowComponent {
    override val type: String = TYPE

    override suspend fun execute(
        config: Map<String, Any>,
        inputs: Map<String, Any?>,
        host: ComponentHost,
    ): ComponentResult = ComponentResult.Success(
        mapOf(
            "extractedPhoto" to "stub-nin-photo-bytes",
            "verified" to true,
            "extractedData" to emptyMap<String, Any>(),
            "confidence" to 0.95,
            "verdict" to Verdict.VALID.name,
        ),
    )

    companion object { const val TYPE: String = "NIN_VERIFICATION" }
}
