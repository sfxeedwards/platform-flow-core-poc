package com.seamfix.platformflow.core.registry

import com.seamfix.platformflow.core.component.FlowComponent

/**
 * Lookup table from a workflow node's `componentType` string to the
 * [FlowComponent] that implements it. Per SDK Architecture §8.
 *
 * The engine consults the registry once at parse time (to validate every
 * `componentType` referenced in the workflow JSON exists — §8.3) and again
 * at each execution step to dispatch the right component.
 *
 * Host apps register custom components via [register] before starting a
 * workflow, alongside the built-ins the SDK ships with.
 */
interface ComponentRegistry {

    /** Look up a component by [type]. Returns `null` if not registered. */
    fun get(type: String): FlowComponent?

    /**
     * Same as [get] but throws [NoSuchComponentException] when the type
     * isn't registered. Use this when a prior validation pass has already
     * proven the component exists.
     */
    fun require(type: String): FlowComponent

    /**
     * Register [component] under its declared [FlowComponent.type]. If a
     * component with the same type is already registered it is **replaced
     * silently** — this lets host apps override built-ins by registering a
     * component with the same type key.
     */
    fun register(component: FlowComponent)

    /** True if a component with [type] is registered. */
    fun has(type: String): Boolean

    /** All registered type keys. The returned set is a snapshot. */
    fun types(): Set<String>
}

/**
 * Thrown by [ComponentRegistry.require] when the requested type isn't
 * registered. Callers that already validated the workflow upstream can
 * treat this as a programming error; callers handling untrusted workflow
 * JSON should use [ComponentRegistry.get] and check for `null` instead.
 */
class NoSuchComponentException(val type: String) :
    NoSuchElementException("No component registered for type \"$type\"")
