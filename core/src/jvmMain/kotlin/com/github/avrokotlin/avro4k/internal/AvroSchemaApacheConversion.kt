package com.github.avrokotlin.avro4k.internal

import com.fasterxml.jackson.databind.JsonNode
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.ArraySchema
import com.github.avrokotlin.avro4k.AvroSchema.BooleanSchema
import com.github.avrokotlin.avro4k.AvroSchema.BytesSchema
import com.github.avrokotlin.avro4k.AvroSchema.DoubleSchema
import com.github.avrokotlin.avro4k.AvroSchema.EnumSchema
import com.github.avrokotlin.avro4k.AvroSchema.FixedSchema
import com.github.avrokotlin.avro4k.AvroSchema.FloatSchema
import com.github.avrokotlin.avro4k.AvroSchema.IntSchema
import com.github.avrokotlin.avro4k.AvroSchema.LongSchema
import com.github.avrokotlin.avro4k.AvroSchema.MapSchema
import com.github.avrokotlin.avro4k.AvroSchema.NullSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.StringSchema
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.LockableList
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.ResolvedSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.avro.JsonProperties
import org.apache.avro.NameValidator
import org.apache.avro.Schema
import org.apache.avro.util.internal.Accessor
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import com.github.avrokotlin.avro4k.toJsonElement as avroSchemaToJson

// The conversions between Apache's Schema and avro4k's AvroSchema. Hosted in core (JVM only) from M3-05 until M3-14, so
// core can convert at its public boundaries while its internals move to AvroSchema; apache-interop's public
// toAvro4k()/toApacheSchema() delegate here. See docs/plans/notes/m3-05.md.
//
// ## Pairing ("two-way seeding")
//
// A cached conversion pairs every node it creates with the node it was converted from, in *both* caches, so that for
// every paired Apache node `s` and avro4k node `a`:
//   s.toAvro4k() === a  and  a.toApacheSchema() === s
// Each live node is paired at most once: a node already paired keeps its partner.
//
// ## Why the entries stay collectable
//
// The two caches are weak-keyed, but their values are held strongly by the tables. If both held their partner strongly,
// avro4kByApache[s] -> a and apacheByAvro4k[a] -> s would keep each other's key alive forever. So each pair has exactly one
// strong link, under the key of its *origin* (the node that was converted), and a weak link under the other key:
// - s.toAvro4k() (Apache origin):         avro4kByApache[s] = strong a,  apacheByAvro4k[a] = weak s
// - a.toApacheSchema() (avro4k origin):   apacheByAvro4k[a] = strong s,  avro4kByApache[s] = weak a
// The origin keeps its partner alive (a cache hit stays a hit while the caller holds the node it converts), and the
// partner never keeps the origin alive. Neither model references the other, so no strong path goes from a partner back to
// its origin. Once both are unreachable from outside: the origin is only weakly reachable and is collected; its entry
// goes stale and is dropped at the table's next rebuild (WeakIdentityKeyCache's deferred reclamation); the partner is then
// only weakly reachable too, is collected, and its entry is dropped at the next rebuild of the other table.
// A weak link whose target was collected is dead, and the next conversion of its key re-pairs it, with that key as the
// origin.
//
// ## Concurrency
//
// Both caches are WeakIdentityKeyCaches (copy-on-write, compare-and-set); a Link is a single AtomicReference. Nothing is
// published before the conversion is complete (a recursive record's fields are locked only at the end of the walk): the
// new nodes are linked back to their origin first, which is invisible until the root's link is claimed, and the root is
// claimed before any other node. A thread that loses the root claim returns the winner's result. A node whose claim is
// lost to an overlapping conversion stays unpaired (its back link is cleared): its schema is still equal, only its
// identity pairing is not guaranteed, and its next conversion pairs it.

/** Apache node -> avro4k node. */
private val avro4kByApache = WeakIdentityKeyCache<Schema, Link<AvroSchema>>()

/** avro4k node -> Apache node. */
private val apacheByAvro4k = WeakIdentityKeyCache<AvroSchema, Link<Schema>>()

/**
 * Converts [schema] to avro4k's [AvroSchema], cached by identity and paired both ways: every node of the result maps back
 * to its Apache node through [avro4kToApache], and every Apache node visited maps to its avro4k node.
 *
 * Only for long-lived schema instances: the caches are O(capacity) per insert. Use [apacheToAvro4kUncached] for a schema
 * created per call.
 *
 * Field default values are taken from their json, except that a number is converted to its schema's type, like Apache Avro's
 * [Schema.Field.defaultVal] does: an integer literal like `36` as the default of a `float` field becomes `36.0`.
 * The field's sort order, when not the default ascending one, is kept as the field's `order` prop, like in the schema's json.
 */
@InternalAvro4kApi
public fun apacheToAvro4k(schema: Schema): AvroSchema {
    avro4kByApache.pairedWith(schema)?.let { return it }
    val conversion = ApacheToAvro4k(cached = true)
    val converted = conversion.convert(schema)
    return seed(schema, converted, conversion.created, contended = avro4kByApache, fresh = apacheByAvro4k)
}

/** Like [apacheToAvro4k], without reading or writing any cache: for a schema created per call (e.g. a `getSchema()` result). */
@InternalAvro4kApi
public fun apacheToAvro4kUncached(schema: Schema): AvroSchema = ApacheToAvro4k(cached = false).convert(schema)

/**
 * Converts [schema] to an Apache Avro schema, through its json representation, cached by identity and paired both ways: every
 * node of the result maps back to its avro4k node through [apacheToAvro4k], and every avro4k node maps to its Apache node.
 *
 * A node of [schema] that is already paired keeps its partner, so the node at its position in the result stays unpaired
 * until it is converted itself: the json route cannot reuse an existing Apache node, as Apache's parser copies every record
 * it resolves, even the ones handed to it with `Parser.addTypes`.
 *
 * Only for long-lived schema instances: the caches are O(capacity) per insert. Use [avro4kToApacheUncached] for a schema
 * created per call.
 */
@InternalAvro4kApi
public fun avro4kToApache(schema: AvroSchema): Schema {
    apacheByAvro4k.pairedWith(schema)?.let { return it }
    val parsed = avro4kToApacheUncached(schema)
    val created = ArrayList<Pair<AvroSchema, Schema>>()
    pairParsedNodes(schema, parsed, identitySet(), identitySet(), created)
    return seed(schema, parsed, created, contended = apacheByAvro4k, fresh = avro4kByApache)
}

/** Like [avro4kToApache], without reading or writing any cache: for a schema created per call. */
@InternalAvro4kApi
public fun avro4kToApacheUncached(schema: AvroSchema): Schema {
    return Schema.Parser(NameValidator.NO_VALIDATION).parse(Json.encodeToString(JsonElement.serializer(), schema.avroSchemaToJson()))
}

/** Number of entries of both caches, stale ones included. For tests. */
@InternalAvro4kApi
public fun apacheConversionCacheSize(): Int = avro4kByApache.size + apacheByAvro4k.size

/**
 * One cache value: a link to the node paired with the key, either strong (the key is the origin of the pair) or weak (the
 * key is the partner), or dead (empty, cleared, or its weak target was collected). Changed only by compare-and-set.
 */
private class Link<T : Any> {
    /** `null`, a strongly held `T`, or a [WeakTarget] of `T`. */
    private val ref = AtomicReference<Any?>(null)

    fun get(): T? = target(ref.get())

    /**
     * Links to [target] unless this link already has a live target. Returns the linked target: [target], or the live one
     * that was already there.
     */
    fun linkIfDead(target: T, strong: Boolean): T {
        val newValue: Any = if (strong) target else WeakTarget(target)
        while (true) {
            val current = ref.get()
            target(current)?.let { return it }
            if (ref.compareAndSet(current, newValue)) return target
        }
    }

    /** Clears this link if it still links to [target]. */
    fun unlink(target: T) {
        val current = ref.get()
        if (target(current) === target) ref.compareAndSet(current, null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun target(value: Any?): T? = if (value is WeakTarget<*>) value.get() as T? else value as T?
}

/** Its own class, so a weak link can never be confused with a strongly held target. */
private class WeakTarget<T : Any>(target: T) : WeakReference<T>(target)

/** The live partner of [key], or null. Inserts nothing and never allocates: the hit path of the cached conversions. */
private fun <K : Any, T : Any> WeakIdentityKeyCache<K, Link<T>>.pairedWith(key: K): T? = getOrNull(key)?.get()

/** The link of [key], inserting a dead one on a miss: only called for a key that is about to be paired. */
private fun <K : Any, T : Any> WeakIdentityKeyCache<K, Link<T>>.linkOf(key: K): Link<T> = getOrPut(key) { Link() }

/**
 * Pairs the nodes of one conversion. [created] holds each node the conversion created (`fresh`, unpublished so far) with the
 * node it was converted from (`contended`: the caller's, possibly visible to other threads), root included.
 *
 * @return the conversion result of [root]: [converted], or the one of a concurrent conversion that paired [root] first.
 */
private fun <C : Any, F : Any> seed(
    root: C,
    converted: F,
    created: List<Pair<C, F>>,
    contended: WeakIdentityKeyCache<C, Link<F>>,
    fresh: WeakIdentityKeyCache<F, Link<C>>,
): F {
    // 1. Weak back links first: the new nodes are not reachable by anyone else until the root is claimed. Weak, or each
    // pair would hold both of its keys strongly through the two tables, and neither would ever be collected.
    created.forEach { (source, node) -> fresh.linkOf(node).linkIfDead(source, strong = false) }
    // 2. The root publishes the whole result. Losing it means another conversion of the same root won: ours is dropped.
    val winner = contended.linkOf(root).linkIfDead(converted, strong = true)
    if (winner !== converted) return winner
    // 3. The other nodes. One already paired with another node keeps its partner, and ours stays unpaired.
    created.forEach { (source, node) ->
        if (source !== root && contended.linkOf(source).linkIfDead(node, strong = true) !== node) {
            fresh.linkOf(node).unlink(source)
        }
    }
    return converted
}

/**
 * The walk of [apacheToAvro4k]: one instance per conversion. The result mirrors the sharing of the Apache tree by identity,
 * so each Apache node has exactly one avro4k node and the other way around. Two Apache instances of the same named type
 * (which core's schema generation produces, e.g. an enum used by two fields) give two equal avro4k nodes, each paired with
 * its own: the pairing then returns the caller's exact node at every position. Recursion goes through the same instance, so
 * the identity memo is enough to end it.
 */
private class ApacheToAvro4k(private val cached: Boolean) {
    private val convertedNodes = IdentityHashMap<Schema, AvroSchema>()

    /** The nodes this walk created, with their Apache node. */
    val created = ArrayList<Pair<Schema, AvroSchema>>()

    fun convert(schema: Schema): AvroSchema {
        convertedNodes[schema]?.let { return it }
        if (cached) {
            // Already paired, from an earlier conversion (e.g. of this sub-schema alone): reused, with its whole subtree
            avro4kByApache.pairedWith(schema)?.let {
                convertedNodes[schema] = it
                return it
            }
        }

        return when (schema.type) {
            Schema.Type.RECORD -> {
                // the record is registered before converting its fields, so they can reference it (recursive records)
                val fields = LockableList<RecordSchema.Field>()
                val recordSchema =
                    RecordSchema(
                        name = Name(schema.name, schema.namespace),
                        fields = fields,
                        doc = schema.doc,
                        aliases = schema.aliasesWithSpace,
                        props = schema.objectProps.toJsonElementMap()
                    )
                register(schema, recordSchema)
                schema.fields.map { field ->
                    RecordSchema.Field(
                        name = field.name(),
                        schema = convert(field.schema()),
                        defaultValue = if (field.hasDefaultValue()) field.defaultValueAsJson() else null,
                        doc = field.doc(),
                        aliases = field.aliases(),
                        props = field.propsWithOrder()
                    )
                }.forEach { fields += it }
                fields.lock()
                recordSchema
            }

            Schema.Type.ENUM ->
                EnumSchema(
                    name = Name(schema.name, schema.namespace),
                    symbols = schema.enumSymbols,
                    defaultSymbol = schema.enumDefault,
                    doc = schema.doc,
                    aliases = schema.aliasesWithSpace,
                    props = schema.objectProps.toJsonElementMap()
                ).also { register(schema, it) }

            Schema.Type.UNION -> UnionSchema(schema.types.map { convert(it) as ResolvedSchema }).also { register(schema, it) }

            Schema.Type.FIXED ->
                FixedSchema(
                    name = Name(schema.name, schema.namespace),
                    size = schema.fixedSize,
                    doc = schema.doc,
                    aliases = schema.aliasesWithSpace,
                    props = schema.objectProps.toJsonElementMap()
                ).also { register(schema, it) }

            Schema.Type.ARRAY ->
                ArraySchema(
                    elementSchema = convert(schema.elementType),
                    props = schema.objectProps.toJsonElementMap()
                ).also { register(schema, it) }

            Schema.Type.MAP ->
                MapSchema(
                    valueSchema = convert(schema.valueType),
                    props = schema.objectProps.toJsonElementMap()
                ).also { register(schema, it) }

            Schema.Type.BOOLEAN -> BooleanSchema(schema.objectProps.toJsonElementMap()).also { register(schema, it) }

            Schema.Type.INT -> IntSchema(schema.objectProps.toJsonElementMap()).also { register(schema, it) }

            Schema.Type.LONG -> LongSchema(schema.objectProps.toJsonElementMap()).also { register(schema, it) }

            Schema.Type.FLOAT -> FloatSchema(schema.objectProps.toJsonElementMap()).also { register(schema, it) }

            Schema.Type.DOUBLE -> DoubleSchema(schema.objectProps.toJsonElementMap()).also { register(schema, it) }

            Schema.Type.STRING -> StringSchema(schema.objectProps.toJsonElementMap()).also { register(schema, it) }

            Schema.Type.BYTES -> BytesSchema(schema.objectProps.toJsonElementMap()).also { register(schema, it) }

            Schema.Type.NULL -> NullSchema(schema.objectProps.toJsonElementMap()).also { register(schema, it) }
        }
    }

    private fun register(
        schema: Schema,
        converted: AvroSchema,
    ) {
        convertedNodes[schema] = converted
        if (cached) created += schema to converted
    }
}

/**
 * Walks the avro4k tree and the Apache tree parsed from its json in parallel, collecting the pairs of nodes at the same
 * position into [created]. Each node is paired once: named types, recursive records and any shared node are visited once.
 * An avro4k node shared at several positions meets a distinct Apache node at each non-named one (the parser shares named
 * types only): the first one is paired, the others stay unpaired.
 */
private fun pairParsedNodes(
    avro4k: AvroSchema,
    apache: Schema,
    visitedAvro4k: MutableSet<AvroSchema>,
    visitedApache: MutableSet<Schema>,
    created: MutableList<Pair<AvroSchema, Schema>>,
) {
    if (!visitedAvro4k.add(avro4k) || !visitedApache.add(apache)) return
    check(avro4k.type.name == apache.type.name) { "Parsed schema does not match: ${apache.type} for ${avro4k.type}" }
    created += avro4k to apache
    when (avro4k) {
        is RecordSchema ->
            avro4k.fields.forEachIndexed { index, field ->
                pairParsedNodes(field.schema, apache.fields[index].schema(), visitedAvro4k, visitedApache, created)
            }

        is UnionSchema ->
            avro4k.types.forEachIndexed { index, type ->
                pairParsedNodes(type, apache.types[index], visitedAvro4k, visitedApache, created)
            }

        is ArraySchema -> pairParsedNodes(avro4k.elementSchema, apache.elementType, visitedAvro4k, visitedApache, created)

        is MapSchema -> pairParsedNodes(avro4k.valueSchema, apache.valueType, visitedAvro4k, visitedApache, created)

        else -> {}
    }
}

private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())

private fun Map<String, Any?>.toJsonElementMap(): Map<String, JsonElement> {
    return mapValues { (_, value) -> toJsonElement(value) }
}

private fun toJsonElement(value: Any?): JsonElement =
    when (value) {
        null, JsonProperties.NULL_VALUE -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is CharSequence -> JsonPrimitive(value.toString())
        is ByteArray -> JsonPrimitive(String(value, StandardCharsets.ISO_8859_1))
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        is Map<*, *> -> JsonObject(value.entries.associate { it.key as String to toJsonElement(it.value) })
        else -> throw UnsupportedOperationException("unsupported value of type ${value::class}: $value")
    }

/**
 * The default value is taken from its raw json. Only a number is converted to the type of the schema it is for,
 * like Apache Avro's [Schema.Field.defaultVal] does, so `36` as the default of a `float` becomes `36.0`.
 * [Schema.Field.defaultVal] is not used directly as it returns null for what it cannot convert, like a long json number for an int.
 */
private fun Schema.Field.defaultValueAsJson(): JsonElement {
    return Accessor.defaultValue(this).toJsonElement(schema())
}

private fun JsonNode.toJsonElement(schema: Schema?): JsonElement {
    // Like in Apache Avro, the default value of a union is for its first type
    val actualSchema = if (schema?.type == Schema.Type.UNION) schema.types.first() else schema
    return when {
        isNull -> JsonNull

        isTextual -> JsonPrimitive(textValue())

        isBoolean -> JsonPrimitive(booleanValue())

        isNumber ->
            when (actualSchema?.type) {
                Schema.Type.FLOAT -> JsonPrimitive(floatValue())
                Schema.Type.DOUBLE -> JsonPrimitive(doubleValue())
                else -> JsonPrimitive(numberValue())
            }

        isArray -> {
            val elementSchema = actualSchema?.takeIf { it.type == Schema.Type.ARRAY }?.elementType
            JsonArray(map { it.toJsonElement(elementSchema) })
        }

        isObject ->
            JsonObject(
                properties().associate { (key, value) ->
                    val valueSchema =
                        when (actualSchema?.type) {
                            Schema.Type.RECORD -> actualSchema.getField(key)?.schema()
                            Schema.Type.MAP -> actualSchema.valueType
                            else -> null
                        }
                    key to value.toJsonElement(valueSchema)
                }
            )

        else -> throw UnsupportedOperationException("Unsupported default value json node ${this::class}: $this")
    }
}

private val Schema.aliasesWithSpace: Set<Name>
    get() = aliases.map { Name(it, namespace) }.toSet()

/**
 * The field order is not a prop in Apache Avro, but it is one in [AvroSchema], as in the schema's json.
 * Like Apache Avro, it is only written when not the default one (ascending).
 */
private fun Schema.Field.propsWithOrder(): Map<String, JsonElement> {
    val props = objectProps.toJsonElementMap()
    if (order() == Schema.Field.Order.ASCENDING) return props
    return props + ("order" to JsonPrimitive(order().name.lowercase()))
}