package com.seamfix.platformflow.core.ui

import android.os.Bundle

/**
 * Bridge between the AndroidX FragmentResult API (Bundle-based) and
 * the SDK's [FragmentResult] (Map-based).
 *
 * Components hand back a Bundle via
 * `parentFragmentManager.setFragmentResult(KEY_COMPONENT_RESULT, bundle)`.
 * The host's listener parses it into a [FragmentResult] using
 * [bundleToFragmentResult].
 *
 * Bundle-key conventions:
 *  - [KEY_CANCELLED] (`Boolean`, default `false`) — set `true` to signal
 *    user cancellation; `data` is ignored when true.
 *  - [KEY_DATA] (`Bundle?`) — nested Bundle whose key/value pairs become
 *    the [FragmentResult.data] map. Only Bundle-supported primitives
 *    (String, Int, Long, Double, Float, Boolean) and nested Bundles are
 *    preserved; complex types should be serialised by the component.
 *
 * Components that don't bother with a `data` Bundle can put their
 * primitives directly on the top-level Bundle alongside [KEY_CANCELLED]
 * — the parser looks at [KEY_DATA] first, then falls back to
 * top-level entries (excluding the cancellation flag) for ergonomic
 * cases.
 */
object FragmentResultBundles {

    /** Fragment-result registry key shared by every component. */
    const val KEY_COMPONENT_RESULT: String = "pf_component_result"

    /** Bundle key on the result Bundle: cancellation flag (Boolean). */
    const val KEY_CANCELLED: String = "cancelled"

    /** Bundle key on the result Bundle: nested data Bundle. */
    const val KEY_DATA: String = "data"

    /** Convert a result [Bundle] back into a [FragmentResult]. */
    fun bundleToFragmentResult(bundle: Bundle): FragmentResult {
        val cancelled = bundle.getBoolean(KEY_CANCELLED, false)
        val data: Map<String, Any?> = when {
            cancelled -> emptyMap()
            bundle.containsKey(KEY_DATA) -> {
                @Suppress("DEPRECATION")
                val nested = bundle.getBundle(KEY_DATA)
                nested?.let { bundleToMap(it) }.orEmpty()
            }
            else -> bundleToMap(bundle, exclude = setOf(KEY_CANCELLED))
        }
        return FragmentResult(data = data, cancelled = cancelled)
    }

    /** Build a result [Bundle] from a [FragmentResult] (used by tests + custom Fragments). */
    fun fragmentResultToBundle(result: FragmentResult): Bundle = Bundle().apply {
        putBoolean(KEY_CANCELLED, result.cancelled)
        if (!result.cancelled && result.data.isNotEmpty()) {
            putBundle(KEY_DATA, mapToBundle(result.data))
        }
    }

    /** Walk a Bundle's keys into a Map. Skips keys in [exclude]. */
    private fun bundleToMap(
        bundle: Bundle,
        exclude: Set<String> = emptySet(),
    ): Map<String, Any?> = buildMap {
        for (key in bundle.keySet()) {
            if (key in exclude) continue
            @Suppress("DEPRECATION")
            put(key, bundle.get(key))
        }
    }

    /** Best-effort Map-to-Bundle for the primitive subset Bundle supports. */
    private fun mapToBundle(map: Map<String, Any?>): Bundle = Bundle().apply {
        for ((key, value) in map) {
            when (value) {
                null -> putString(key, null)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Double -> putDouble(key, value)
                is Bundle -> putBundle(key, value)
                else -> putString(key, value.toString())
            }
        }
    }
}
