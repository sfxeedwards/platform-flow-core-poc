package com.seamfix.platformflow.core.ui

/**
 * The data a [androidx.fragment.app.Fragment] returns when it finishes
 * via [ComponentHost.showFragment].
 *
 * Convention: components emit a free-form `data` map whose keys match the
 * field names the component will surface in its
 * [com.seamfix.platformflow.core.component.ComponentResult.Success] outputs.
 * Components that need to signal a user-cancellation set [cancelled] to
 * `true` instead of returning data — the calling component can then
 * translate that to a
 * [com.seamfix.platformflow.core.component.ComponentResult.Failure].
 */
data class FragmentResult(
    /** Free-form data the fragment captured. Empty when [cancelled]. */
    val data: Map<String, Any?> = emptyMap(),

    /** True when the user backed out / cancelled the fragment's UI flow. */
    val cancelled: Boolean = false,
)
