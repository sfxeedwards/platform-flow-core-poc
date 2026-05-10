package com.seamfix.platformflow.core.network

import java.util.concurrent.TimeUnit

/**
 * [WorkflowCache] configuration. Per SDK Architecture §14.2.
 *
 * @property ttlMs How long a cached entry counts as fresh, in
 *  milliseconds. Default 24 hours. After this point the entry is "stale"
 *  and the client triggers a stale-while-revalidate refresh (§14.3) but
 *  still returns the cached value to the caller.
 * @property maxEntries Hard ceiling on cached entries. When exceeded the
 *  least-recently-used entry is evicted. Default 10.
 */
data class WorkflowCacheConfig(
    val ttlMs: Long = TimeUnit.HOURS.toMillis(DEFAULT_TTL_HOURS),
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(ttlMs > 0) { "ttlMs must be positive (got $ttlMs)" }
        require(maxEntries > 0) { "maxEntries must be positive (got $maxEntries)" }
    }

    companion object {
        const val DEFAULT_TTL_HOURS: Long = 24
        const val DEFAULT_MAX_ENTRIES: Int = 10
    }
}
