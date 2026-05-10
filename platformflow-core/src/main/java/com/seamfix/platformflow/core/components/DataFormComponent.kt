package com.seamfix.platformflow.core.components

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.ui.ComponentHost

/**
 * **Stub** built-in for `DATA_FORM`. Per Admin UI registry: capture
 * component that renders a dynamic form from `config.fields` and returns
 * a `formData` map with the user's entries.
 *
 * This V1 stub doesn't render UI — it echoes the configured field names
 * back as placeholder values inside `formData`, plus the same key-value
 * pairs at the top level (the Admin UI's outputSchema declares both
 * shapes via `additionalProperties: true`). Real form-rendering lands
 * in a later task once the Compose UI lib is wired up.
 */
class DataFormComponent : FlowComponent {
    override val type: String = TYPE

    override suspend fun execute(
        config: Map<String, Any>,
        inputs: Map<String, Any?>,
        host: ComponentHost,
    ): ComponentResult {
        @Suppress("UNCHECKED_CAST")
        val fields = (config["fields"] as? List<String>).orEmpty()
        val formData = fields.associateWith { name -> "stub-$name" }
        // outputSchema permits both formData and pass-through top-level fields.
        val outputs: Map<String, Any> = buildMap {
            put("formData", formData)
            putAll(formData)
        }
        return ComponentResult.Success(outputs)
    }

    companion object { const val TYPE: String = "DATA_FORM" }
}
