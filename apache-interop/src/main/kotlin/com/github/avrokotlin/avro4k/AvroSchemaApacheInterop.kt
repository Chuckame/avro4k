package com.github.avrokotlin.avro4k

import com.fasterxml.jackson.databind.JsonNode
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
import com.github.avrokotlin.avro4k.AvroSchema.NamedSchema
import com.github.avrokotlin.avro4k.AvroSchema.NullSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.StringSchema
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.internal.WeakIdentityKeyCache
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
import java.nio.charset.StandardCharsets

// Identity-keyed: both schema models have structural equals/hashCode that walk the whole schema. Neither result
// references its source schema, so the weak keys stay collectable. The cached results are shared: an Apache Schema
// must not be mutated (addProp...) by callers, as is already the case for the ones Avro.schema() caches.
private val toAvro4kCache = WeakIdentityKeyCache<Schema, AvroSchema>()
private val toApacheSchemaCache = WeakIdentityKeyCache<AvroSchema, Schema>()

/**
 * Converts this Apache Avro schema to avro4k's multiplatform [AvroSchema].
 *
 * The result is cached per schema instance (by identity), so only the first conversion of a given instance walks it.
 *
 * Field default values are taken from their json, except that a number is converted to its schema's type, like Apache Avro's
 * [Schema.Field.defaultVal] does: an integer literal like `36` as the default of a `float` field becomes `36.0`.
 * The field's sort order, when not the default ascending one, is kept as the field's `order` prop, like in the schema's json.
 */
public fun Schema.toAvro4k(): AvroSchema {
    return toAvro4kCache.getOrPut(this) { from(this, mutableMapOf()) }
}

/**
 * Converts this [AvroSchema] to an Apache Avro schema, through its json representation.
 *
 * The result is cached per schema instance (by identity), so only the first conversion of a given instance walks the
 * schema and parses json.
 */
public fun AvroSchema.toApacheSchema(): Schema {
    return toApacheSchemaCache.getOrPut(this) { toApacheSchemaUncached() }
}

private fun AvroSchema.toApacheSchemaUncached(): Schema {
    return Schema.Parser(NameValidator.NO_VALIDATION).parse(Json.encodeToString(JsonElement.serializer(), toJsonElement()))
}

private fun from(schema: Schema, seenNamedTypes: MutableMap<String, NamedSchema>): AvroSchema {
    seenNamedTypes[schema.fullName]?.let {
        return it
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
            seenNamedTypes[schema.fullName] = recordSchema
            schema.fields.map { field ->
                RecordSchema.Field(
                    name = field.name(),
                    schema = from(field.schema(), seenNamedTypes),
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
            ).also { seenNamedTypes[schema.fullName] = it }

        Schema.Type.UNION -> UnionSchema(schema.types.map { from(it, seenNamedTypes) as ResolvedSchema })

        Schema.Type.FIXED ->
            FixedSchema(
                name = Name(schema.name, schema.namespace),
                size = schema.fixedSize,
                doc = schema.doc,
                aliases = schema.aliasesWithSpace,
                props = schema.objectProps.toJsonElementMap()
            ).also { seenNamedTypes[schema.fullName] = it }

        Schema.Type.ARRAY ->
            ArraySchema(
                elementSchema = from(schema.elementType, seenNamedTypes),
                props = schema.objectProps.toJsonElementMap()
            )

        Schema.Type.MAP ->
            MapSchema(
                valueSchema = from(schema.valueType, seenNamedTypes),
                props = schema.objectProps.toJsonElementMap()
            )

        Schema.Type.BOOLEAN -> BooleanSchema(schema.objectProps.toJsonElementMap())

        Schema.Type.INT -> IntSchema(schema.objectProps.toJsonElementMap())

        Schema.Type.LONG -> LongSchema(schema.objectProps.toJsonElementMap())

        Schema.Type.FLOAT -> FloatSchema(schema.objectProps.toJsonElementMap())

        Schema.Type.DOUBLE -> DoubleSchema(schema.objectProps.toJsonElementMap())

        Schema.Type.STRING -> StringSchema(schema.objectProps.toJsonElementMap())

        Schema.Type.BYTES -> BytesSchema(schema.objectProps.toJsonElementMap())

        Schema.Type.NULL -> NullSchema(schema.objectProps.toJsonElementMap())
    }
}

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