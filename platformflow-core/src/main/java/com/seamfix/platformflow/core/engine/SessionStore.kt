package com.seamfix.platformflow.core.engine

/**
 * In-memory state for one workflow execution.
 *
 * Holds the four scopes the engine and [DataResolver][com.seamfix.platformflow.core.engine.DataResolver]
 * read from per SDK Architecture §10:
 *
 *  - [input]            — the data bag the host app passed at session start. Immutable.
 *  - [nodeResults]      — outputs of each successfully-executed node, keyed by node id.
 *  - [context]          — values written by edge dataMappings.
 *  - [executionHistory] — ordered audit trail of executed node ids.
 *
 * **Memory-only guarantee (§10.3).** The store is never serialized to disk.
 * When the session ends — success, failure, or cancellation — the engine
 * calls [clear] and drops its reference. This keeps biometric data
 * (selfies, fingerprint templates, ID photos) off the device's storage.
 *
 * Thread-safety is not provided; the engine drives a single-threaded loop
 * over the workflow per §4.
 */
class SessionStore(input: Map<String, Any>) {

    /**
     * The host app's original data bag. Immutable: a defensive copy is
     * taken at construction so a caller mutating their own map afterwards
     * has no effect on the session.
     */
    val input: Map<String, Any> = input.toMap()

    private val mutableNodeResults: MutableMap<String, Map<String, Any>> = mutableMapOf()
    private val mutableContext: MutableMap<String, Any> = mutableMapOf()
    private val mutableExecutionHistory: MutableList<String> = mutableListOf()

    /**
     * Read-only view of [recordNodeResult] writes, keyed by node id.
     * The engine and [DataResolver] read this for `$<nodeId>.*` references.
     */
    val nodeResults: Map<String, Map<String, Any>> get() = mutableNodeResults

    /**
     * Read-only view of [applyDataMapping] writes.
     * Read by the engine and [DataResolver] for `$context.*` references.
     */
    val context: Map<String, Any> get() = mutableContext

    /**
     * Ordered audit trail. Each successful [recordNodeResult] appends a new
     * entry; the same node id may appear more than once if the engine ever
     * re-executes a node (V1 doesn't, but retry/back-navigation features
     * planned for later may).
     */
    val executionHistory: List<String> get() = mutableExecutionHistory

    /**
     * Record a successful node execution: store its outputs and append the
     * node id to the audit trail. Per SDK Architecture §10.2.
     *
     * A defensive copy of [outputs] is taken — the component may have
     * returned a backing map it intends to keep mutating.
     */
    fun recordNodeResult(nodeId: String, outputs: Map<String, Any>) {
        mutableNodeResults[nodeId] = outputs.toMap()
        mutableExecutionHistory.add(nodeId)
    }

    /**
     * Apply an edge's `dataMapping` to the [context] scope. Per SDK
     * Architecture §10.2. For each `(contextKey, sourceRef)` pair the
     * caller-supplied [resolve] looks up the source value; non-null
     * resolutions land in `context`. Null resolutions are skipped silently
     * — the validator is responsible for surfacing missing-source errors.
     *
     * The [resolve] callable is provided externally so this class doesn't
     * depend on `DataResolver` (Task 18). When the resolver is built, the
     * engine passes its `::resolve` method reference.
     */
    fun applyDataMapping(
        mapping: Map<String, String>,
        resolve: (String) -> Any?,
    ) {
        for ((contextKey, sourceRef) in mapping) {
            val value = resolve(sourceRef)
            if (value != null) {
                mutableContext[contextKey] = value
            }
        }
    }

    /**
     * Wipe every mutable scope. Per SDK Architecture §10.3 the engine calls
     * this when the session ends, then drops its reference to the store.
     * [input] is a `val` and stays put until the store itself is GC'd —
     * which happens immediately once the engine drops the reference.
     */
    fun clear() {
        mutableNodeResults.clear()
        mutableContext.clear()
        mutableExecutionHistory.clear()
    }
}
