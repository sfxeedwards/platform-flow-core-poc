package com.seamfix.platformflow.core.registry

import com.seamfix.platformflow.core.component.FlowComponent

/**
 * In-memory [ComponentRegistry] backed by a `MutableMap`. Per SDK
 * Architecture §8.2.
 *
 * Two construction paths:
 *
 *  - `DefaultComponentRegistry()` — starts empty. Call [register] to add
 *    components.
 *  - `DefaultComponentRegistry(listOf(...))` — pre-populated from an
 *    initial collection. Used by the SDK's bootstrap (Task 25) to install
 *    the built-in components, and by tests / host apps that want to seed
 *    a registry in one shot.
 *
 * Re-registering a component with a type that's already present
 * **silently replaces** the existing entry — this is how a host app
 * overrides a built-in (e.g. swap in a tenant-specific FaceMatch).
 *
 * Not thread-safe. The engine drives a single-threaded loop, and
 * registration is expected to happen during one-shot bootstrap before
 * `OneVerify.start(...)`.
 */
class DefaultComponentRegistry(
    initial: Iterable<FlowComponent> = emptyList(),
) : ComponentRegistry {

    private val byType: MutableMap<String, FlowComponent> = mutableMapOf()

    init {
        for (component in initial) {
            byType[component.type] = component
        }
    }

    override fun get(type: String): FlowComponent? = byType[type]

    override fun require(type: String): FlowComponent =
        byType[type] ?: throw NoSuchComponentException(type)

    override fun register(component: FlowComponent) {
        byType[component.type] = component
    }

    override fun has(type: String): Boolean = byType.containsKey(type)

    /** Snapshot of registered types — modifying the result has no effect. */
    override fun types(): Set<String> = byType.keys.toSet()
}
