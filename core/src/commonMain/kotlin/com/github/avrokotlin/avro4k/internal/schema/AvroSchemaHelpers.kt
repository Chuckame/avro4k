package com.github.avrokotlin.avro4k.internal.schema

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.ArraySchema
import com.github.avrokotlin.avro4k.AvroSchema.EnumSchema
import com.github.avrokotlin.avro4k.AvroSchema.FixedSchema
import com.github.avrokotlin.avro4k.AvroSchema.MapSchema
import com.github.avrokotlin.avro4k.AvroSchema.NamedSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.Type
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.ResolvedSchema
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryReader
import com.github.avrokotlin.avro4k.internal.unionIndexOutOfRangeError
import com.github.avrokotlin.avro4k.toJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToLong

/*
 * Hot-path rules for these helpers, which run per value:
 * - never `==`, `indexOf` or `contains` on an `AvroSchema` or a `Field` (structural walks): kinds are compared through
 *   the stored [AvroSchema.type] (an enum, compared by identity), names and logical types as `String`s;
 * - no allocation on the lookup paths: plain indexed loops, no `listOf`, no capturing lambda, no boxed return value.
 */

/** This schema as a [T], or an error naming the expected kind and the actual schema. */
internal inline fun <reified T : AvroSchema> AvroSchema.asType(): T =
    this as? T ?: throw IllegalStateException("Expected a ${T::class.simpleName}, but was ${toSchemaJsonString()}")

/** Whether this schema is a union: a field read and an identity comparison, cheaper than an `is` check on JS. */
internal val AvroSchema.isUnion: Boolean
    get() = type === Type.UNION

/** The full name for error messages: a union is named after its branches, `union[null, int, MyRecord]`. Allocates. */
internal val AvroSchema.fullNameForMessages: String
    get() {
        if (this !is UnionSchema) return fullName
        return types.joinToString(separator = ", ", prefix = "union[", postfix = "]") { it.simpleName }
    }

/** The schema as compact JSON (`"string"`, `["null","int"]`), for error messages. Renders the whole schema. */
internal fun AvroSchema.toSchemaJsonString(): String = toJsonElement().toString()

// ---- names and aliases ----

/** A record, an enum or a fixed. */
internal fun AvroSchema.isNamedSchema(): Boolean = this is NamedSchema

/** The full names of a named schema's aliases, an empty set for any other one. Allocates. */
internal fun AvroSchema.nonFailingAliases(): Set<String> = if (this is NamedSchema) aliasFullNames() else emptySet()

private fun NamedSchema.aliasFullNames(): Set<String> {
    if (aliases.isEmpty()) return emptySet()
    return aliases.mapTo(LinkedHashSet(aliases.size)) { it.fullName }
}

/**
 * True if [fullNameToMatch] is this schema's full name or, for a named schema, the full name of one of its aliases.
 * A non-named schema's full name is its type name (`"int"`, `"array"`, `"union"`).
 */
internal fun AvroSchema.isFullNameMatch(fullNameToMatch: String): Boolean {
    if (fullName == fullNameToMatch) return true
    if (this !is NamedSchema) return false
    for (alias in aliases) {
        if (alias.fullName == fullNameToMatch) return true
    }
    return false
}

/**
 * True if [fullName] or one of [aliases] matches this schema's full name or aliases ([isFullNameMatch]).
 * [aliases] is only called on a miss of [fullName]. Inline, so a lambda or a bound reference passed as [aliases] is
 * not allocated.
 */
internal inline fun AvroSchema.isFullNameOrAliasMatch(
    fullName: String,
    aliases: () -> Set<String>,
): Boolean {
    if (isFullNameMatch(fullName)) return true
    for (alias in aliases()) {
        if (isFullNameMatch(alias)) return true
    }
    return false
}

// ---- union branch lookups ----

/**
 * The index of the first union branch of type [expectedType], or -1 (not `null`: no boxing). For a named type, the
 * first record, enum or fixed branch. One read of [UnionSchema.firstIndexByType].
 */
internal fun AvroSchema.getIndexTyped(expectedType: Type): Int {
    return asType<UnionSchema>().firstIndexByType[expectedType.ordinal]
}

/**
 * The index of the first union branch of type [expectedType] whose raw [ResolvedSchema.logicalTypeName] is
 * [logicalTypeName], or -1. One overload per number of types rather than a `vararg`: no array per call.
 */
internal fun AvroSchema.getIndexLogicallyTyped(
    logicalTypeName: String,
    expectedType: Type,
): Int {
    val branches = asType<AvroSchema.UnionSchema>().types
    for (i in branches.indices) {
        val branch = branches[i]
        if (branch.type === expectedType && branch.logicalTypeName == logicalTypeName) return i
    }
    return -1
}

/**
 * [getIndexLogicallyTyped] for [firstExpectedType], then for [secondExpectedType]: the order of the types is the
 * priority, whatever the order of the branches.
 */
internal fun AvroSchema.getIndexLogicallyTyped(
    logicalTypeName: String,
    firstExpectedType: Type,
    secondExpectedType: Type,
): Int {
    val index = getIndexLogicallyTyped(logicalTypeName, firstExpectedType)
    if (index >= 0) return index
    return getIndexLogicallyTyped(logicalTypeName, secondExpectedType)
}

/**
 * False when this schema's `logicalType` names one of the specification's logical types but breaks its rules, e.g.
 * `date` on a string, `uuid` on a `fixed` of another size than 16, or `decimal` without a positive int `precision`: the
 * specification says to ignore such a logical type. True otherwise, including for no logical type and for any other name
 * (avro4k's own `char`, a user's custom one).
 */
internal fun ResolvedSchema.hasValidLogicalType(): Boolean {
    val name = logicalTypeName ?: return true
    val props = props
    val validForType =
        when (name) {
            "decimal" -> isValidDecimal(props)

            "big-decimal" -> type === Type.BYTES

            "duration" -> this is FixedSchema && size == 12

            "uuid" -> type === Type.STRING || (this is FixedSchema && size == 16)

            "date", "time-millis" -> type === Type.INT

            "time-micros", "timestamp-millis", "timestamp-micros", "timestamp-nanos",
            "local-timestamp-millis", "local-timestamp-micros", "local-timestamp-nanos",
            -> type === Type.LONG

            else -> return true
        }
    // a logical type next to one of the Java binding props is ignored, as Java does
    return validForType &&
        !props.isStringProp("avro.java.string") &&
        !props.isStringProp("java-class") &&
        !props.isStringProp("java-key-class") &&
        !props.isStringProp("java-element-class")
}

/** The logical types of the specification. */
private val SPECIFICATION_LOGICAL_TYPES =
    setOf(
        "decimal",
        "big-decimal",
        "duration",
        "uuid",
        "date",
        "time-millis",
        "time-micros",
        "timestamp-millis",
        "timestamp-micros",
        "timestamp-nanos",
        "local-timestamp-millis",
        "local-timestamp-micros",
        "local-timestamp-nanos"
    )

/**
 * The name [com.github.avrokotlin.avro4k.AvroConfiguration.logicalTypes] is looked up with for this schema: a logical
 * type of the specification only when it is valid ([knownLogicalTypeName]), as the specification says to ignore an
 * invalid one (a `uuid` on a fixed of 8 bytes, a `decimal` without precision), and any other name as it is (avro4k's
 * own `char`, a user's custom one).
 */
internal val ResolvedSchema.configuredLogicalTypeName: String?
    get() = logicalTypeName?.let { if (it in SPECIFICATION_LOGICAL_TYPES) knownLogicalTypeName else it }

/**
 * The name of this schema's logical type when it is one of the specification's and valid ([hasValidLogicalType]), or
 * null, also for any other name ([ResolvedSchema.logicalTypeName] is the raw prop). Memoized per schema instance.
 */
internal val ResolvedSchema.knownLogicalTypeName: String?
    get() {
        val memo = knownLogicalTypeNameMemo
        if (memo !== AvroSchema.NotComputed) return memo as String?
        val name = logicalTypeName?.takeIf { it in SPECIFICATION_LOGICAL_TYPES && hasValidLogicalType() }
        knownLogicalTypeNameMemo = name
        return name
    }

/** [ResolvedSchema.knownLogicalTypeName], or null for a union: for the encoders, whose writer schema is a union until a branch is selected. */
internal val AvroSchema.knownLogicalTypeName: String?
    get() = (this as? ResolvedSchema)?.knownLogicalTypeName

/** [ResolvedSchema.configuredLogicalTypeName], or null for a union. */
internal val AvroSchema.configuredLogicalTypeName: String?
    get() = (this as? ResolvedSchema)?.configuredLogicalTypeName

private fun Map<String, JsonElement>.isStringProp(key: String): Boolean = (this[key] as? JsonPrimitive)?.isString == true

/** An int `precision` > 0 that the fixed size can hold, and an int `scale` (0 when absent) in `0..precision`. */
private fun ResolvedSchema.isValidDecimal(props: Map<String, JsonElement>): Boolean {
    if (type !== Type.BYTES && type !== Type.FIXED) return false
    val precision = props.intProp("precision") ?: return false
    val scale = if ("scale" in props) props.intProp("scale") ?: return false else 0
    if (precision <= 0 || scale < 0 || scale > precision) return false
    if (this is FixedSchema) {
        val maxPrecision = floor(log10(2.0) * (8 * size - 1)).roundToLong()
        if (precision > maxPrecision) return false
    }
    return true
}

/** The prop as an `Int`, when it is a json integer in the `Int` range, else null. */
private fun Map<String, JsonElement>.intProp(key: String): Int? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (value.isString || value is JsonNull) return null
    return value.content.toIntOrNull()
}

/**
 * The index of the union branch whose full name, or one of whose aliases' full names, is [expectedName], or null. One
 * hash lookup ([UnionSchema.getIndexOrNull]).
 */
internal fun AvroSchema.getIndexNamedOrAliased(expectedName: String): Int? {
    return asType<UnionSchema>().getIndexOrNull(expectedName)
}

// ---- union shaping (schema generation) ----

/** The branches of a union, or a single-element list of this schema. Allocates for a non-union: not for per-value paths. */
internal fun AvroSchema.asSchemaList(): List<ResolvedSchema> = if (this is UnionSchema) types else listOf(this as ResolvedSchema)

/**
 * Moves the first union branch matching [predicate] to the head of the union. Returns this schema when it is not a
 * union or when no branch matches; otherwise always a new union, even when the branch is already first (as before).
 */
internal inline fun AvroSchema.moveToHeadOfUnion(predicate: (ResolvedSchema) -> Boolean): AvroSchema {
    if (this !is UnionSchema) return this
    val index = types.indexOfFirst(predicate)
    if (index == -1) return this
    return moveToHeadOfUnion(index)
}

/**
 * Moves the union branch at [typeIndex] to the head of the union. Returns this schema when it is not a union or when
 * [typeIndex] is past the end; otherwise always a new union.
 */
internal fun AvroSchema.moveToHeadOfUnion(typeIndex: Int): AvroSchema {
    if (this !is UnionSchema || typeIndex >= types.size) return this
    return UnionSchema(types.toMutableList().apply { add(0, removeAt(typeIndex)) })
}

/** Like [moveToHeadOfUnion], moving the first branch matching [predicate] to the tail of the union. */
internal inline fun AvroSchema.moveToTailOfUnion(predicate: (ResolvedSchema) -> Boolean): AvroSchema {
    if (this !is UnionSchema) return this
    val index = types.indexOfFirst(predicate)
    if (index == -1) return this
    return moveToTailOfUnion(index)
}

/** Like [moveToHeadOfUnion], moving the branch at [typeIndex] to the tail of the union. */
internal fun AvroSchema.moveToTailOfUnion(typeIndex: Int): AvroSchema {
    if (this !is UnionSchema || typeIndex >= types.size) return this
    return UnionSchema(types.toMutableList().apply { add(removeAt(typeIndex)) })
}

/**
 * True for a type whose value can be written as a map key through `toString()`: boolean, int, long, float, double,
 * string, enum, bytes and fixed, or a union made of such types only. False for null, array, map and record.
 */
internal fun AvroSchema.isNonNullScalarType(): Boolean =
    when (type) {
        Type.BOOLEAN,
        Type.INT,
        Type.LONG,
        Type.FLOAT,
        Type.DOUBLE,
        Type.STRING,
        Type.ENUM,
        Type.BYTES,
        Type.FIXED,
        -> true

        Type.NULL,
        Type.ARRAY,
        Type.MAP,
        Type.RECORD,
        -> false

        Type.UNION -> {
            val branches = (this as UnionSchema).types
            var allScalar = true
            for (i in branches.indices) {
                if (!branches[i].isNonNullScalarType()) {
                    allScalar = false
                    break
                }
            }
            allScalar
        }
    }

// ---- skipping ----

/**
 * Skips one value written with [schema], a union's branch index included. Collections are skipped block by block
 * through [AvroBinaryReader.skipArray] / [AvroBinaryReader.skipMap], which jump over a block written with its byte size
 * (negative count) in one go and return the item count of the next block to skip item by item.
 */
internal fun AvroBinaryReader.skip(schema: AvroSchema) {
    // a `when` over the type, one table jump, rather than over the schema's class, where `is` tests each class in turn
    val resolved: ResolvedSchema =
        if (schema is UnionSchema) {
            val index = readIndex()
            if (index < 0 || index >= schema.types.size) throw unionIndexOutOfRangeError(index, schema)
            schema.types[index]
        } else {
            schema as ResolvedSchema
        }
    when (resolved.type) {
        Type.BOOLEAN -> readBoolean()

        Type.INT -> readInt()

        Type.LONG -> readLong()

        Type.FLOAT -> readFloat()

        Type.DOUBLE -> readDouble()

        Type.STRING -> skipString()

        Type.BYTES -> skipBytes()

        Type.FIXED -> skipFixed((resolved as FixedSchema).size)

        Type.ENUM -> readEnum()

        Type.NULL -> readNull()

        Type.ARRAY -> {
            val elementSchema = (resolved as ArraySchema).elementSchema
            var blockItems = skipArray()
            while (blockItems > 0L) {
                var i = 0L
                while (i < blockItems) {
                    skip(elementSchema)
                    i++
                }
                blockItems = skipArray()
            }
        }

        Type.MAP -> {
            val valueSchema = (resolved as MapSchema).valueSchema
            var blockItems = skipMap()
            while (blockItems > 0L) {
                var i = 0L
                while (i < blockItems) {
                    skipString()
                    skip(valueSchema)
                    i++
                }
                blockItems = skipMap()
            }
        }

        Type.RECORD -> {
            val fields = (resolved as RecordSchema).fields
            for (i in fields.indices) {
                skip(fields[i].schema)
            }
        }

        // Impossible: a union's branches are resolved schemas, never unions
        Type.UNION -> throw UnsupportedOperationException("Union type should be already resolved")
    }
}