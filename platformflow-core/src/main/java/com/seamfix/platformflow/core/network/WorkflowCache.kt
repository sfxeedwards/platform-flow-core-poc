package com.seamfix.platformflow.core.network

import com.seamfix.platformflow.core.model.WorkflowDefinition

/**
 * In-memory LRU cache of [WorkflowDefinition]s keyed by `workflowId`.
 * Per SDK Architecture §14.2.
 *
 * Storage characteristics:
 *
 *  - **TTL** — entries older than [WorkflowCacheConfig.ttlMs] are
 *    classified as stale by [isFresh]. The cache itself never auto-evicts
 *    on TTL; expired entries stick around so the SWR path (§14.3) and
 *    offline fallback (§14.4) can return them when fresher data is
 *    unavailable.
 *  - **LRU** — entries beyond [WorkflowCacheConfig.maxEntries] cause the
 *    least-recently-used entry to be dropped. Both [get] and [put]
 *    promote the touched entry to most-recent.
 *  - **Privacy** — only the [WorkflowDefinition] JSON is stored. No PII,
 *    no biometric data, no session state.
 *
 * Thread-safe via a single intrinsic-lock. Operations are O(1); the
 * lock contention surface is tiny (one foreground reader + one
 * background SWR refresher per `fetchWorkflow` call at most).
 */
class WorkflowCache(
    private val config: WorkflowCacheConfig = WorkflowCacheConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()

    /** access-order LinkedHashMap used as the backing LRU. */
    private val entries: LinkedHashMap<String, CachedWorkflow> =
        object : LinkedHashMap<String, CachedWorkflow>(
            config.maxEntries,
            DEFAULT_LOAD_FACTOR,
            true,  // accessOrder = true: get() bumps recency
        ) {
            override fun removeEldestEntry(
                eldest: Map.Entry<String, CachedWorkflow>?,
            ): Boolean = size > config.maxEntries
        }

    /** Look up an entry. Returns `null` if absent. Promotes it in LRU order. */
    fun get(workflowId: String): CachedWorkflow? = synchronized(lock) {
        entries[workflowId]
    }

    /** Insert or replace an entry under [workflowId]. Returns the [CachedWorkflow] just stored. */
    fun put(workflowId: String, workflow: WorkflowDefinition): CachedWorkflow {
        val entry = CachedWorkflow(workflow = workflow, fetchedAt = clock())
        synchronized(lock) {
            entries[workflowId] = entry
        }
        return entry
    }

    /** Wipe the cache. Useful for sign-out or test cleanup. */
    fun clear(): Unit = synchronized(lock) { entries.clear() }

    /** Current size — monotonically bounded by [WorkflowCacheConfig.maxEntries]. */
    fun size(): Int = synchronized(lock) { entries.size }

    /**
     * `true` when [entry] was fetched within the configured TTL. Always
     * called against the same [clock] the entry was captured under.
     */
    fun isFresh(entry: CachedWorkflow): Boolean =
        clock() - entry.fetchedAt <= config.ttlMs

    /** Convenience — `true` when [workflowId] has a cached entry that's still fresh. */
    fun hasFresh(workflowId: String): Boolean {
        val entry = get(workflowId) ?: return false
        return isFresh(entry)
    }

    private companion object {
        const val DEFAULT_LOAD_FACTOR = 0.75f
    }
}
