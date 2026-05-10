package com.seamfix.platformflow.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Coverage for [SessionResult] (§11.3) and [SessionStatus]. Both are
 * data-only types but the field shape is part of the public contract,
 * so we lock it down with a few targeted assertions.
 */
class SessionResultTest {

    @Test
    fun all_fields_round_trip_through_value_equality() {
        val a = SessionResult(
            workflowId = "kyc",
            workflowVersion = 3,
            tenantId = "acme",
            status = SessionStatus.COMPLETED,
            nodeOutputs = mapOf("collect" to mapOf("nationality" to "NG")),
            executionPath = listOf("collect", "scan", "match"),
            durationMs = 12_345L,
        )
        val b = SessionResult(
            workflowId = "kyc",
            workflowVersion = 3,
            tenantId = "acme",
            status = SessionStatus.COMPLETED,
            nodeOutputs = mapOf("collect" to mapOf("nationality" to "NG")),
            executionPath = listOf("collect", "scan", "match"),
            durationMs = 12_345L,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun differing_status_breaks_equality() {
        val base = SessionResult(
            workflowId = "wf",
            workflowVersion = 1,
            tenantId = "t",
            status = SessionStatus.COMPLETED,
            nodeOutputs = emptyMap(),
            executionPath = emptyList(),
            durationMs = 0L,
        )
        val failed = base.copy(status = SessionStatus.FAILED)
        assertNotEquals(base, failed)
    }

    @Test
    fun status_enum_has_exactly_three_values() {
        // Pinned by §11.3 — adding/removing arms is a public-API break.
        val values = SessionStatus.values().toSet()
        assertEquals(
            setOf(
                SessionStatus.COMPLETED,
                SessionStatus.FAILED,
                SessionStatus.CANCELLED,
            ),
            values,
        )
    }

    @Test
    fun copy_clones_and_overrides_individual_fields() {
        val original = SessionResult(
            workflowId = "wf",
            workflowVersion = 1,
            tenantId = "t",
            status = SessionStatus.COMPLETED,
            nodeOutputs = mapOf("a" to mapOf("x" to 1)),
            executionPath = listOf("a"),
            durationMs = 100L,
        )
        val updated = original.copy(durationMs = 200L)
        assertEquals(200L, updated.durationMs)
        assertEquals(original.workflowId, updated.workflowId)
        assertEquals(original.nodeOutputs, updated.nodeOutputs)
    }
}
