package com.seamfix.platformflow.core.components

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.ui.ComponentHost

/**
 * **Stub** built-in for `PASSPORT_SCAN`. Pure capture component (§9.1) —
 * no verdict — that scans a passport's MRZ and extracts the document
 * photo + parsed fields.
 *
 * The V1 stub returns placeholder image bytes and an empty `mrzData`
 * map. Real MRZ scanning lands when the camera + OCR pipeline is wired.
 */
class PassportScanComponent : FlowComponent {
    override val type: String = TYPE

    override suspend fun execute(
        config: Map<String, Any>,
        inputs: Map<String, Any?>,
        host: ComponentHost,
    ): ComponentResult = ComponentResult.Success(
        mapOf(
            "photo" to "stub-passport-photo-bytes",
            "mrzData" to emptyMap<String, Any>(),
        ),
    )

    companion object { const val TYPE: String = "PASSPORT_SCAN" }
}
