package com.seamfix.platformflow.core.network

import com.seamfix.platformflow.core.model.WorkflowDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [WorkflowCache] against SDK Architecture §14.2.
 */
class WorkflowCacheTest {

    /** Mutable clock the cache reads through, so tests can step time. */
    private class MutableClock(var now: Long = 0L) {
        fun read(): Long = now
    }

    private fun emptyWorkflow(id: String): WorkflowDefinition =
        WorkflowDefinition(
            workflowId = id,
            version = 1,
            tenantId = "t",
            name = "wf-$id",
            entryNode = "a",
            nodes = emptyList(),
            edges = emptyList(),
        )

    // ── Basic put / get ─────────────────────────────────────────────────

    @Test
    fun new_cache_is_empty() {
        val cache = WorkflowCache()
        assertEquals(0, cache.size())
        assertNull(cache.get("anything"))
        assertFalse(cache.hasFresh("anything"))
    }

    @Test
    fun put_then_get_returns_same_workflow_with_fetchedAt_set() {
        val clock = MutableClock(now = 1_000_000_000L)
        val cache = WorkflowCache(clock = clock::read)
        val wf = emptyWorkflow("wf_kyc")

        val stored = cache.put("wf_kyc", wf)
        assertEquals(wf, stored.workflow)
        assertEquals(1_000_000_000L, stored.fetchedAt)

        val fetched = cache.get("wf_kyc")
        assertNotNull(fetched)
        assertEquals(wf, fetched!!.workflow)
        assertEquals(1_000_000_000L, fetched.fetchedAt)
    }

    @Test
    fun re_putting_same_id_replaces_entry_without_growing_size() {
        val clock = MutableClock(now = 100L)
        val cache = WorkflowCache(clock = clock::read)
        val v1 = emptyWorkflow("wf").copy(version = 1)
        val v2 = emptyWorkflow("wf").copy(version = 2)

        cache.put("wf", v1)
        assertEquals(1, cache.size())
        clock.now = 200L
        cache.put("wf", v2)

        assertEquals(1, cache.size())
        val cached = cache.get("wf")!!
        assertEquals(2, cached.workflow.version)
        assertEquals(200L, cached.fetchedAt)
    }

    @Test
    fun clear_empties_the_cache() {
        val cache = WorkflowCache()
        cache.put("a", emptyWorkflow("a"))
        cache.put("b", emptyWorkflow("b"))
        assertEquals(2, cache.size())
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("a"))
    }

    // ── TTL / freshness ────────────────────────────────────────────────

    @Test
    fun isFresh_true_within_ttl() {
        val clock = MutableClock(now = 0L)
        val cache = WorkflowCache(
            config = WorkflowCacheConfig(ttlMs = 10_000),
            clock = clock::read,
        )
        cache.put("wf", emptyWorkflow("wf"))
        clock.now = 9_999L
        assertTrue(cache.isFresh(cache.get("wf")!!))
        assertTrue(cache.hasFresh("wf"))
    }

    @Test
    fun isFresh_true_at_exact_ttl_boundary() {
        // §14.2 doesn't specify open/closed boundary; the impl treats
        // exactly TTL as still fresh ("staleness begins after TTL").
        val clock = MutableClock(now = 0L)
        val cache = WorkflowCache(
            config = WorkflowCacheConfig(ttlMs = 10_000),
            clock = clock::read,
        )
        cache.put("wf", emptyWorkflow("wf"))
        clock.now = 10_000L
        assertTrue(cache.isFresh(cache.get("wf")!!))
    }

    @Test
    fun isFresh_false_after_ttl() {
        val clock = MutableClock(now = 0L)
        val cache = WorkflowCache(
            config = WorkflowCacheConfig(ttlMs = 10_000),
            clock = clock::read,
        )
        cache.put("wf", emptyWorkflow("wf"))
        clock.now = 10_001L
        assertFalse(cache.isFresh(cache.get("wf")!!))
        assertFalse(cache.hasFresh("wf"))
    }

    @Test
    fun stale_entries_remain_in_cache() {
        // §14.4 offline fallback depends on stale entries staying available.
        val clock = MutableClock(now = 0L)
        val cache = WorkflowCache(
            config = WorkflowCacheConfig(ttlMs = 1_000),
            clock = clock::read,
        )
        cache.put("wf", emptyWorkflow("wf"))
        clock.now = 1_000_000L  // way past TTL
        assertNotNull("stale entry should still be retrievable", cache.get("wf"))
        assertFalse(cache.isFresh(cache.get("wf")!!))
    }

    // ── LRU eviction ───────────────────────────────────────────────────

    @Test
    fun evicts_least_recently_used_when_over_max() {
        val cache = WorkflowCache(
            config = WorkflowCacheConfig(maxEntries = 3),
        )
        cache.put("a", emptyWorkflow("a"))
        cache.put("b", emptyWorkflow("b"))
        cache.put("c", emptyWorkflow("c"))
        cache.put("d", emptyWorkflow("d"))  // evicts "a" (oldest, untouched)

        assertEquals(3, cache.size())
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertNotNull(cache.get("d"))
    }

    @Test
    fun get_promotes_entry_to_most_recently_used() {
        val cache = WorkflowCache(
            config = WorkflowCacheConfig(maxEntries = 3),
        )
        cache.put("a", emptyWorkflow("a"))
        cache.put("b", emptyWorkflow("b"))
        cache.put("c", emptyWorkflow("c"))

        // Touch "a" — now b is the LRU.
        cache.get("a")
        cache.put("d", emptyWorkflow("d"))  // evicts "b"

        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertNotNull(cache.get("d"))
    }

    @Test
    fun put_promotes_entry_to_most_recently_used() {
        val cache = WorkflowCache(
            config = WorkflowCacheConfig(maxEntries = 3),
        )
        cache.put("a", emptyWorkflow("a"))
        cache.put("b", emptyWorkflow("b"))
        cache.put("c", emptyWorkflow("c"))

        // Re-put "a" — same effect as touching it, b is now LRU.
        cache.put("a", emptyWorkflow("a"))
        cache.put("d", emptyWorkflow("d"))  // evicts "b"

        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertNotNull(cache.get("d"))
    }

    // ── Config validation ──────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun config_rejects_zero_ttl() {
        WorkflowCacheConfig(ttlMs = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun config_rejects_negative_ttl() {
        WorkflowCacheConfig(ttlMs = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun config_rejects_zero_maxEntries() {
        WorkflowCacheConfig(maxEntries = 0)
    }

    @Test
    fun config_defaults_match_spec() {
        // §14.2 default TTL = 24 hours, default maxEntries = 10.
        val config = WorkflowCacheConfig()
        assertEquals(24L * 60 * 60 * 1000, config.ttlMs)
        assertEquals(10, config.maxEntries)
    }
}
