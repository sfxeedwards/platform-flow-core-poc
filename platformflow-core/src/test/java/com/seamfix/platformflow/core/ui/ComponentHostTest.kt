package com.seamfix.platformflow.core.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the [ComponentHost] interface surface added in SDK
 * Architecture §7.3, plus its companion value classes
 * [FragmentResult] / [ActivityResult] and the [TestComponentHost]
 * fixture that downstream component tests depend on.
 *
 * `showFragment` / `startActivityForResult` integration tests need real
 * Fragment / Intent instances and live in `androidTest` (instrumented or
 * Robolectric). This file is the pure-JVM contract check: data classes,
 * default no-op behavior, and the parts of the host surface that don't
 * touch Android types.
 */
class ComponentHostTest {

    // ── FragmentResult ──────────────────────────────────────────────────

    @Test
    fun fragmentResult_defaults_to_empty_data_and_not_cancelled() {
        val r = FragmentResult()
        assertEquals(emptyMap<String, Any?>(), r.data)
        assertFalse(r.cancelled)
    }

    @Test
    fun fragmentResult_carries_data_and_cancellation_independently() {
        val data = mapOf("photo" to "bytes", "score" to 0.91)
        val ok = FragmentResult(data = data)
        val cancelled = FragmentResult(cancelled = true)

        assertEquals(data, ok.data)
        assertFalse(ok.cancelled)
        assertTrue(cancelled.cancelled)
        assertTrue(cancelled.data.isEmpty())
    }

    @Test
    fun fragmentResult_equality_is_structural() {
        val a = FragmentResult(mapOf("k" to 1))
        val b = FragmentResult(mapOf("k" to 1))
        val c = FragmentResult(mapOf("k" to 2))
        assertEquals(a, b)
        assertFalse(a == c)
    }

    // ── ActivityResult ──────────────────────────────────────────────────

    @Test
    fun activityResult_with_only_resultCode_has_null_data() {
        val r = ActivityResult(resultCode = 0)
        assertEquals(0, r.resultCode)
        assertNull(r.data)
    }

    @Test
    fun activityResult_equality_is_structural_when_data_is_null() {
        // Real Intent equality is reference-based; null data is the only
        // case where structural equality holds. That's the case the SDK
        // exercises via cancellations (resultCode=RESULT_CANCELED, data=null).
        val a = ActivityResult(resultCode = 0)
        val b = ActivityResult(resultCode = 0)
        val c = ActivityResult(resultCode = -1)
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test
    fun activityResult_supports_custom_resultCodes() {
        val ok = ActivityResult(resultCode = -1)         // Activity.RESULT_OK
        val cancelled = ActivityResult(resultCode = 0)   // RESULT_CANCELED
        val custom = ActivityResult(resultCode = 42)
        assertEquals(-1, ok.resultCode)
        assertEquals(0, cancelled.resultCode)
        assertEquals(42, custom.resultCode)
    }

    // ── TestComponentHost defaults ──────────────────────────────────────

    @Test
    fun testComponentHost_default_context_throws_with_clear_message() {
        val host = TestComponentHost()
        val ex = assertThrows(IllegalStateException::class.java) { host.context }
        assertTrue(
            "error should mention how to provide a Context",
            ex.message?.contains("Robolectric") == true,
        )
    }

    // ── TestComponentHost.withLoading ───────────────────────────────────

    @Test
    fun withLoading_runs_block_and_returns_value() = runBlocking {
        val host = TestComponentHost()
        val result = host.withLoading("Working…") { 42 }
        assertEquals(42, result)
    }

    @Test
    fun withLoading_propagates_block_exceptions() = runBlocking {
        val host = TestComponentHost()
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                host.withLoading<Unit>("Boom") { error("boom") }
            }
        }
        assertEquals("boom", ex.message)
    }

    @Test
    fun withLoading_returns_null_when_block_returns_null() = runBlocking {
        val host = TestComponentHost()
        val result: Int? = host.withLoading("…") { null }
        assertNull(result)
    }
}
