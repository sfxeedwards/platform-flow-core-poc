package com.seamfix.platformflow.core.components

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.component.Verdict
import com.seamfix.platformflow.core.ui.ComponentHost

/**
 * **Stub** built-in for `FACE_LIVENESS`. Scoring component (§9.1) that
 * presents the hosted active-liveness SDK and returns the captured
 * selfie + liveness score.
 *
 * The V1 stub returns a canned VALID verdict and placeholder selfie
 * bytes; real WebView wiring lands when the active-liveness SDK is
 * integrated.
 */
class FaceLivenessComponent : FlowComponent {
    override val type: String = TYPE

    override suspend fun execute(
        config: Map<String, Any>,
        inputs: Map<String, Any?>,
        host: ComponentHost,
    ): ComponentResult = ComponentResult.Success(
        mapOf(
            "capturedImage" to "stub-selfie-bytes",
            "livenessScore" to 0.95,
            "confidence" to 0.95,
            "verdict" to Verdict.VALID.name,
        ),
    )

    companion object { const val TYPE: String = "FACE_LIVENESS" }
}
