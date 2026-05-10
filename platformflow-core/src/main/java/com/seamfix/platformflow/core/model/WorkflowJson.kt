package com.seamfix.platformflow.core.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.lang.reflect.Type

/**
 * Gson-based parser and serializer for the workflow JSON contract.
 *
 *  - [parse] turns the Admin UI's published JSON into the typed model.
 *  - [stringify] round-trips the typed model back to canonical JSON.
 *
 * The wire format is the exact contract the Admin UI emits (Platform
 * Architecture §10 / SDK Architecture §3). Custom adapters cover:
 *
 *  - [Comparator]: enum ↔ token strings (`"=="`, `"notIn"` …).
 *  - [RuleItem]: structural discriminator on the `operator` key.
 *  - [WorkflowDefinition] / [WorkflowNode] / [WorkflowEdge]: explicit
 *    field-by-field deserialization so missing optional fields default to
 *    `false` / empty maps without relying on Gson's reflective constructor
 *    bypass (which leaves Kotlin defaults unapplied).
 *
 * Extra fields the Admin UI ships for canvas display only (`position`,
 * `label`) are quietly ignored.
 */
object WorkflowJson {

    /** Shared Gson instance with all adapters wired in. Thread-safe. */
    val gson: Gson = build()

    fun build(): Gson = GsonBuilder()
        .registerTypeAdapter(Comparator::class.java, ComparatorAdapter())
        .registerTypeHierarchyAdapter(RuleItem::class.java, RuleItemAdapter())
        .registerTypeAdapter(WorkflowDefinition::class.java, WorkflowDefinitionDeserializer())
        .registerTypeAdapter(WorkflowNode::class.java, WorkflowNodeDeserializer())
        .registerTypeAdapter(WorkflowEdge::class.java, WorkflowEdgeDeserializer())
        .create()

    /** Parse a workflow JSON string. Throws on malformed input. */
    fun parse(json: String): WorkflowDefinition =
        gson.fromJson(json, WorkflowDefinition::class.java)
            ?: throw IllegalArgumentException("Workflow JSON parsed as null")

    /** Serialize a workflow back to JSON. Pretty-printed when [pretty] is true. */
    fun stringify(workflow: WorkflowDefinition, pretty: Boolean = false): String =
        if (pretty) GsonBuilder().setPrettyPrinting().also { copyAdapters(it) }.create().toJson(workflow)
        else gson.toJson(workflow)

    private fun copyAdapters(builder: GsonBuilder) {
        builder
            .registerTypeAdapter(Comparator::class.java, ComparatorAdapter())
            .registerTypeHierarchyAdapter(RuleItem::class.java, RuleItemAdapter())
            .registerTypeAdapter(WorkflowDefinition::class.java, WorkflowDefinitionDeserializer())
            .registerTypeAdapter(WorkflowNode::class.java, WorkflowNodeDeserializer())
            .registerTypeAdapter(WorkflowEdge::class.java, WorkflowEdgeDeserializer())
    }
}

// ── Adapters ───────────────────────────────────────────────────────────

/** Stream-based adapter for [Comparator] — wire token ↔ enum. */
private class ComparatorAdapter : TypeAdapter<Comparator>() {
    override fun write(out: JsonWriter, value: Comparator?) {
        if (value == null) {
            out.nullValue()
            return
        }
        out.value(value.token)
    }

    override fun read(reader: JsonReader): Comparator =
        Comparator.fromJson(reader.nextString())
}

/**
 * Adapter for [RuleItem]. Implements both directions on one class so the
 * hierarchy registration covers serialization of [RuleItem.Condition] /
 * [RuleItem.Group] subclass instances as well as deserialization of the
 * abstract sealed type.
 *
 *  - Condition → `{ field, comparator, value? }`. `value` is omitted when null.
 *  - Group     → emits the wrapped [EdgeRule] inline (no envelope).
 *  - Discriminator on parse: presence of an `operator` key → Group, else
 *    Condition.
 */
private class RuleItemAdapter :
    JsonSerializer<RuleItem>,
    JsonDeserializer<RuleItem> {

    override fun serialize(
        src: RuleItem,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement = when (src) {
        is RuleItem.Condition -> {
            val obj = JsonObject()
            obj.addProperty("field", src.field)
            obj.addProperty("comparator", src.comparator.token)
            if (src.value != null) {
                obj.add("value", context.serialize(src.value))
            }
            obj
        }
        is RuleItem.Group -> context.serialize(src.rule, EdgeRule::class.java)
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): RuleItem {
        require(json.isJsonObject) {
            "RuleItem JSON must be an object, got: $json"
        }
        val obj = json.asJsonObject
        return if (obj.has("operator")) {
            // Group: deserialize the JSON object as an EdgeRule directly.
            RuleItem.Group(context.deserialize(obj, EdgeRule::class.java))
        } else {
            val field = obj.get("field")?.asString
                ?: throw IllegalArgumentException("RuleItem.Condition missing 'field': $obj")
            val comparator = obj.get("comparator")?.asString
                ?.let { Comparator.fromJson(it) }
                ?: throw IllegalArgumentException("RuleItem.Condition missing 'comparator': $obj")
            val valueElement = obj.get("value")
            val value: Any? =
                if (valueElement == null || valueElement.isJsonNull) null
                else context.deserialize(valueElement, Any::class.java)
            RuleItem.Condition(field, comparator, value)
        }
    }
}

private class WorkflowDefinitionDeserializer : JsonDeserializer<WorkflowDefinition> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): WorkflowDefinition {
        require(json.isJsonObject) {
            "WorkflowDefinition JSON must be an object, got: $json"
        }
        val obj = json.asJsonObject
        return WorkflowDefinition(
            workflowId = obj.requireString("workflowId"),
            version = obj.get("version")?.asInt
                ?: throw IllegalArgumentException("WorkflowDefinition missing 'version'"),
            tenantId = obj.requireString("tenantId"),
            name = obj.requireString("name"),
            entryNode = obj.requireString("entryNode"),
            nodes = obj.deserializeList<WorkflowNode>("nodes", context),
            edges = obj.deserializeList<WorkflowEdge>("edges", context),
        )
    }
}

private class WorkflowNodeDeserializer : JsonDeserializer<WorkflowNode> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): WorkflowNode {
        require(json.isJsonObject) {
            "WorkflowNode JSON must be an object, got: $json"
        }
        val obj = json.asJsonObject
        return WorkflowNode(
            id = obj.requireString("id"),
            componentType = obj.requireString("componentType"),
            config = obj.deserializeMapOfAny("config", context),
            inputMapping = obj.deserializeMapOfString("inputMapping"),
        )
    }
}

private class WorkflowEdgeDeserializer : JsonDeserializer<WorkflowEdge> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): WorkflowEdge {
        require(json.isJsonObject) {
            "WorkflowEdge JSON must be an object, got: $json"
        }
        val obj = json.asJsonObject

        val ruleElement = obj.get("rule")
        val rule = if (ruleElement == null || ruleElement.isJsonNull) null
            else context.deserialize<EdgeRule>(ruleElement, EdgeRule::class.java)

        val defaultElement = obj.get("default")
        val default = defaultElement != null && !defaultElement.isJsonNull &&
            defaultElement.asBoolean

        val dataMappingElement = obj.get("dataMapping")
        val dataMapping = if (dataMappingElement == null || dataMappingElement.isJsonNull) null
            else dataMappingElement.asJsonObject.entrySet().associate { (k, v) ->
                k to v.asString
            }

        return WorkflowEdge(
            id = obj.requireString("id"),
            from = obj.requireString("from"),
            to = obj.requireString("to"),
            rule = rule,
            default = default,
            dataMapping = dataMapping,
        )
    }
}

// ── Tiny JsonObject helpers ────────────────────────────────────────────

private fun JsonObject.requireString(key: String): String =
    get(key)?.asString
        ?: throw IllegalArgumentException("Missing required string '$key' in: $this")

private inline fun <reified T> JsonObject.deserializeList(
    key: String,
    context: JsonDeserializationContext,
): List<T> {
    val element = get(key) ?: return emptyList()
    if (element.isJsonNull) return emptyList()
    val array: JsonArray = element.asJsonArray
    return array.map { context.deserialize<T>(it, T::class.java) }
}

private fun JsonObject.deserializeMapOfAny(
    key: String,
    context: JsonDeserializationContext,
): Map<String, Any> {
    val element = get(key) ?: return emptyMap()
    if (element.isJsonNull) return emptyMap()
    val type: Type = object : TypeToken<Map<String, Any>>() {}.type
    return context.deserialize(element, type) ?: emptyMap()
}

private fun JsonObject.deserializeMapOfString(key: String): Map<String, String> {
    val element = get(key) ?: return emptyMap()
    if (element.isJsonNull) return emptyMap()
    return element.asJsonObject.entrySet().associate { (k, v) -> k to v.asString }
}
