package com.seamfix.platformflow.core.components

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.component.Verdict
import com.seamfix.platformflow.core.ui.ComponentHost

/**
 * **Stub** built-in for `FACE_MATCH`. Scoring component (§9.1) that
 * sends the ID photo and selfie to the match endpoint and emits a
 * matched/no-match verdict with confidence.
 *
 * The V1 stub returns a canned VALID match at 0.91 confidence,
 * regardless of inputs. The §7.5 reference impl sketches the real
 * shape (input validation + `host.withLoading { matchApi.match(…) }`)
 * — that lands when the real match API is integrated.
 */
class FaceMatchComponent : FlowComponent {
    override val type: String = TYPE

    override suspend fun execute(
        config: Map<String, Any>,
        inputs: Map<String, Any?>,
        host: ComponentHost,
    ): ComponentResult = ComponentResult.Success(
        mapOf(
            "matched" to true,
            "confidence" to 0.91,
            "verdict" to Verdict.VALID.name,
        ),
    )

    companion object { const val TYPE: String = "FACE_MATCH" }
}
