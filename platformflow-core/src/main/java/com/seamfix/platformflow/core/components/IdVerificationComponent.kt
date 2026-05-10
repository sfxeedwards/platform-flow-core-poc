package com.seamfix.platformflow.core.components

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.component.Verdict
import com.seamfix.platformflow.core.ui.ComponentHost

/**
 * **Stub** built-in for `ID_VERIFICATION`. Scoring component that does
 * general ID document scanning + extraction via the Ionic ID SDK.
 *
 * The V1 stub returns a canned NATIONAL_ID document type with a
 * placeholder photo and an empty extracted-fields map. Real Ionic ID
 * SDK integration lands later.
 */
class IdVerificationComponent : FlowComponent {
    override val type: String = TYPE

    override suspend fun execute(
        config: Map<String, Any>,
        inputs: Map<String, Any?>,
        host: ComponentHost,
    ): ComponentResult = ComponentResult.Success(
        mapOf(
            "documentType" to "NATIONAL_ID",
            "photo" to "stub-id-photo-bytes",
            "extractedFields" to emptyMap<String, Any>(),
            "confidence" to 0.95,
            "verdict" to Verdict.VALID.name,
        ),
    )

    companion object { const val TYPE: String = "ID_VERIFICATION" }
}
