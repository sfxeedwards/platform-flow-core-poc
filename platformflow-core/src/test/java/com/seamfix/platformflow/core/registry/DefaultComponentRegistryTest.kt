package com.seamfix.platformflow.core.registry

import com.seamfix.platformflow.core.component.ComponentResult
import com.seamfix.platformflow.core.component.FlowComponent
import com.seamfix.platformflow.core.ui.ComponentHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [DefaultComponentRegistry] against SDK Architecture §8.
 */
class DefaultComponentRegistryTest {

    /** Minimal stub component — the registry doesn't care about behavior. */
    private class StubComponent(override val type: String) : FlowComponent {
        override suspend fun execute(
            config: Map<String, Any>,
            inputs: Map<String, Any?>,
            host: ComponentHost,
        ): ComponentResult = ComponentResult.Success(emptyMap())
    }

    // ── Empty registry ──────────────────────────────────────────────────

    @Test
    fun new_registry_is_empty() {
        val registry = DefaultComponentRegistry()
        assertNull(registry.get("FACE_MATCH"))
        assertFalse(registry.has("FACE_MATCH"))
        assertTrue(registry.types().isEmpty())
    }

    @Test
    fun require_on_empty_registry_throws_with_clear_type() {
        val registry = DefaultComponentRegistry()
        val ex = assertThrows(NoSuchComponentException::class.java) {
            registry.require("FACE_MATCH")
        }
        assertEquals("FACE_MATCH", ex.type)
        assertTrue(
            "exception message should include the missing type",
            ex.message?.contains("FACE_MATCH") == true,
        )
    }

    // ── register / get / has / types ────────────────────────────────────

    @Test
    fun register_adds_a_component_indexed_by_its_type() {
        val component = StubComponent("FACE_MATCH")
        val registry = DefaultComponentRegistry()
        registry.register(component)

        assertSame(component, registry.get("FACE_MATCH"))
        assertSame(component, registry.require("FACE_MATCH"))
        assertTrue(registry.has("FACE_MATCH"))
        assertEquals(setOf("FACE_MATCH"), registry.types())
    }

    @Test
    fun register_multiple_components() {
        val a = StubComponent("FACE_MATCH")
        val b = StubComponent("DATA_FORM")
        val c = StubComponent("PORTRAIT")

        val registry = DefaultComponentRegistry()
        registry.register(a)
        registry.register(b)
        registry.register(c)

        assertEquals(setOf("FACE_MATCH", "DATA_FORM", "PORTRAIT"), registry.types())
        assertSame(a, registry.get("FACE_MATCH"))
        assertSame(b, registry.get("DATA_FORM"))
        assertSame(c, registry.get("PORTRAIT"))
    }

    @Test
    fun register_replaces_existing_component_with_same_type() {
        val original = StubComponent("FACE_MATCH")
        val replacement = StubComponent("FACE_MATCH")

        val registry = DefaultComponentRegistry()
        registry.register(original)
        assertSame(original, registry.get("FACE_MATCH"))

        registry.register(replacement)
        assertSame(
            "second registration with the same type wins",
            replacement,
            registry.get("FACE_MATCH"),
        )
        assertEquals(
            "type set still contains exactly one entry after override",
            setOf("FACE_MATCH"),
            registry.types(),
        )
    }

    // ── Initial collection constructor ──────────────────────────────────

    @Test
    fun constructor_with_initial_components_pre_populates() {
        val components = listOf(
            StubComponent("FACE_MATCH"),
            StubComponent("DATA_FORM"),
            StubComponent("STATUS_AGGREGATOR"),
        )
        val registry = DefaultComponentRegistry(components)

        assertEquals(
            setOf("FACE_MATCH", "DATA_FORM", "STATUS_AGGREGATOR"),
            registry.types(),
        )
        for (c in components) {
            assertSame(c, registry.get(c.type))
        }
    }

    @Test
    fun constructor_initial_components_can_be_replaced_post_construction() {
        val builtin = StubComponent("FACE_MATCH")
        val customOverride = StubComponent("FACE_MATCH")

        val registry = DefaultComponentRegistry(listOf(builtin))
        assertSame(builtin, registry.get("FACE_MATCH"))

        registry.register(customOverride)
        assertSame(
            "host-registered override replaces the seeded built-in",
            customOverride,
            registry.get("FACE_MATCH"),
        )
    }

    @Test
    fun duplicate_types_in_initial_collection_keep_the_last_one() {
        val first = StubComponent("FACE_MATCH")
        val second = StubComponent("FACE_MATCH")
        val registry = DefaultComponentRegistry(listOf(first, second))

        // Iteration order is preserved by the constructor's `for` loop, so
        // the second entry wins — same semantics as registering twice.
        assertSame(second, registry.get("FACE_MATCH"))
        assertEquals(setOf("FACE_MATCH"), registry.types())
    }

    // ── types() snapshot semantics ──────────────────────────────────────

    @Test
    fun types_returns_a_snapshot_not_a_live_view() {
        val registry = DefaultComponentRegistry()
        registry.register(StubComponent("A"))

        val firstSnapshot = registry.types()
        registry.register(StubComponent("B"))
        val secondSnapshot = registry.types()

        assertEquals(setOf("A"), firstSnapshot)
        assertEquals(setOf("A", "B"), secondSnapshot)
        assertNotSame(
            "snapshots should be distinct collections",
            firstSnapshot,
            secondSnapshot,
        )
    }
}
