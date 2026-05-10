package com.seamfix.platformflow.core.engine

import com.seamfix.platformflow.core.model.WorkflowNode

/**
 * Translates scope references like `"$collect_data.formData.idNumber"` into
 * concrete runtime values by reading from a [SessionStore]. Per SDK
 * Architecture §5.
 *
 * Reference grammar:
 *
 *  - `$input.<path>`   — reads from [SessionStore.input] (host bag).
 *  - `$context.<path>` — reads from [SessionStore.context] (edge dataMappings).
 *  - `$<nodeId>.<path>` — reads from [SessionStore.nodeResults]`[nodeId]`.
 *
 * The leading `$` and the **first** `.` are syntactic; everything after the
 * first `.` is a dot-separated path traversed through nested [Map]s.
 *
 * Any failure along the way — unknown scope, unexecuted node, missing key,
 * non-Map intermediate, malformed ref — resolves to `null`. The resolver
 * never throws on bad input; the validation engine and runtime callers are
 * responsible for surfacing errors when a `null` is unexpected.
 */
class DataResolver(private val sessionStore: SessionStore) {

    /**
     * Resolve a single scope reference. Returns `null` if anything along the
     * lookup chain is missing, malformed, or non-traversable.
     */
    fun resolve(ref: String): Any? {
        if (ref.isEmpty()) return null

        val withoutDollar = ref.removePrefix("$")
        val dotIndex = withoutDollar.indexOf('.')
        // Per §5.2: bare `$scope` with no path resolves to null.
        if (dotIndex < 0) return null

        val scope = withoutDollar.substring(0, dotIndex)
        val path = withoutDollar.substring(dotIndex + 1)
        if (scope.isEmpty() || path.isEmpty()) return null

        val root: Any? = when (scope) {
            "input" -> sessionStore.input
            "context" -> sessionStore.context
            else -> sessionStore.nodeResults[scope]
        }
        return traversePath(root, path)
    }

    /**
     * Resolve every entry in a node's [WorkflowNode.inputMapping] in one
     * pass. Per SDK Architecture §5.3 the engine calls this immediately
     * before invoking a component, then hands the resulting map to the
     * component's `execute(...)`. Unresolvable refs surface as `null`
     * values so the component (and the engine's missing-input checks) can
     * react accordingly.
     */
    fun resolveInputMapping(node: WorkflowNode): Map<String, Any?> {
        return node.inputMapping.mapValues { (_, ref) -> resolve(ref) }
    }

    /**
     * Walk a dot-path through nested [Map]s starting at [root]. Empty
     * segments and non-Map intermediates short-circuit to `null`.
     */
    private fun traversePath(root: Any?, path: String): Any? {
        if (root == null) return null
        var current: Any? = root
        for (segment in path.split('.')) {
            if (segment.isEmpty()) return null
            current = when (current) {
                is Map<*, *> -> current[segment]
                else -> null
            }
            if (current == null) return null
        }
        return current
    }
}
