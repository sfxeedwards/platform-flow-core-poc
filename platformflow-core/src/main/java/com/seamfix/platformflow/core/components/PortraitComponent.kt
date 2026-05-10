package com.seamfix.platformflow.core.components

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.ui.ComponentHost

/**
 * **Stub** built-in for `PORTRAIT`. Pure capture component (§9.1) —
 * no verdict — that runs guided portrait capture, optionally with a
 * server-side quality check.
 *
 * The V1 stub returns canned base64 photo bytes and `qualityPassed`
 * derived from `config.serverQualityCheck` (when the check is
 * disabled, quality always "passes" by definition; when it's enabled,
 * the stub still passes — real quality scoring lands later).
 */
class PortraitComponent : FlowComponent {
    override val type: String = TYPE

    override suspend fun execute(
        config: Map<String, Any>,
        inputs: Map<String, Any?>,
        host: ComponentHost,
    ): ComponentResult = ComponentResult.Success(
        mapOf(
            "photo" to "stub-portrait-photo-bytes",
            "qualityPassed" to true,
        ),
    )

    companion object { const val TYPE: String = "PORTRAIT" }
}
