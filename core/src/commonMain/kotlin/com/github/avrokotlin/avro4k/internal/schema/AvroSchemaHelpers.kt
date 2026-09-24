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
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryDecoder
import com.github.avrokotlin.avro4k.toJsonElement

/*
 * The helpers that the runtime (encoders, decoders, resolvers), the serializers and the schema visitors use on Apache's
 * `Schema`, ported to [AvroSchema] (M3-07). Each one keeps the name and the semantics of the Apache-typed helper it
 * replaces, so switching a call site is an import swap; the few deliberate differences are called out in the KDoc.
 * The complete inventory, with the call sites and what was not ported, is in `docs/plans/notes/m3-07.md`.
 *
 * Hot-path rules (they run per value once the runtime reads `AvroSchema`):
 * - never `==`, `indexOf` or `contains` on an `AvroSchema` or a `Field` (structural walks): kinds are compared through
 *   the stored [AvroSchema.type] (an enum, compared by identity), names and logical types as `String`s;
 * - no allocation on the lookup paths: plain indexed loops, no `listOf`, no capturing lambda, no boxed return value.
 */

// ---- Apache-shaped accessors ----
// Each throws on the wrong kind of schema exactly like the Apache getter it mirrors: same exception class as far as
// common code allows (Apache's `AvroRuntimeException` is a direct `RuntimeException` subclass, so a plain
// `RuntimeException` is thrown, neither an `IllegalArgumentException` nor an `IllegalStateException`), and the same
// message, `"<Not a …>: <the schema as JSON>"`.

/** Apache's `Schema.isUnion()`. */
internal val AvroSchema.isUnion: Boolean
    get() = type === Type.UNION

/** Apache's `Schema.getTypes()`: the branches of a union. Throws `Not a union` on any other schema. */
internal val AvroSchema.types: List<ResolvedSchema>
    get() = (this as? UnionSchema ?: throw wrongKind("Not a union")).types

/** Apache's `Schema.getFixedSize()`. Throws `Not fixed` on any other schema. */
internal val AvroSchema.fixedSize: Int
    get() = (this as? FixedSchema ?: throw wrongKind("Not fixed")).size

/** Apache's `Schema.getEnumSymbols()`. Throws `Not an enum` on any other schema. */
internal val AvroSchema.enumSymbols: List<String>
    get() = (this as? EnumSchema ?: throw wrongKind("Not an enum")).symbols

/** Apache's `Schema.getElementType()`. Throws `Not an array` on any other schema. */
internal val AvroSchema.elementType: AvroSchema
    get() = (this as? ArraySchema ?: throw wrongKind("Not an array")).elementSchema

/** Apache's `Schema.getValueType()`. Throws `Not a map` on any other schema. */
internal val AvroSchema.valueType: AvroSchema
    get() = (this as? MapSchema ?: throw wrongKind("Not a map")).valueSchema

/** Apache's `Schema.getFields()`. Throws `Not a record` on any other schema. */
internal val AvroSchema.fields: List<RecordSchema.Field>
    get() = (this as? RecordSchema ?: throw wrongKind("Not a record")).fields

/**
 * Apache's `Schema.getAliases()`: the **full names** of the aliases of a named schema. Throws `Not a named type` on
 * any other schema. Allocates a new set on each call: for error messages and name-miss fallbacks only. To test one
 * name without allocating, use [isFullNameMatch].
 */
internal val AvroSchema.aliases: Set<String>
    get() = (this as? NamedSchema ?: throw wrongKind("Not a named type")).aliasFullNames()

/** Apache's `Schema.hasEnumSymbol(symbol)`. O(1). Throws `Not an enum` on any other schema. */
internal fun AvroSchema.hasEnumSymbol(symbol: String): Boolean {
    return (this as? EnumSchema ?: throw wrongKind("Not an enum")).getSymbolIndexOrNull(symbol) != null
}

/**
 * Apache's `Schema.getEnumOrdinal(symbol)`. O(1). Throws `Not an enum` on any other schema, and, like Apache,
 * `enum value '<symbol>' is not in the enum symbol set: [<symbols>]` for an unknown symbol.
 */
internal fun AvroSchema.getEnumOrdinal(symbol: String): Int {
    val enumSchema = this as? EnumSchema ?: throw wrongKind("Not an enum")
    return enumSchema.getSymbolIndexOrNull(symbol)
        ?: throw RuntimeException("enum value '$symbol' is not in the enum symbol set: ${enumSchema.symbols}")
}

/**
 * Apache's `Schema.Type.getName()`: the lower-case name of the type, as written in a schema (`"int"`, `"record"`).
 * Returns a constant: no `lowercase()` allocation.
 */
internal fun Type.getName(): String =
    when (this) {
        Type.RECORD -> "record"
        Type.ENUM -> "enum"
        Type.FIXED -> "fixed"
        Type.ARRAY -> "array"
        Type.MAP -> "map"
        Type.UNION -> "union"
        Type.NULL -> "null"
        Type.BOOLEAN -> "boolean"
        Type.INT -> "int"
        Type.LONG -> "long"
        Type.FLOAT -> "float"
        Type.DOUBLE -> "double"
        Type.BYTES -> "bytes"
        Type.STRING -> "string"
    }

/**
 * The full name as Apache's `Schema.getFullName()` returns it, for error messages (`Unsupported schema '<full name>'…`):
 * the same as [AvroSchema.fullName], except for a union, which Apache names after its branches' simple names,
 * `union[null, int, MyRecord]`, where the model says `union`. Allocates for a union: error paths only.
 */
internal val AvroSchema.fullNameForMessages: String
    get() {
        if (this !is UnionSchema) return fullName
        return types.joinToString(separator = ", ", prefix = "union[", postfix = "]") { it.simpleName }
    }

/**
 * The schema as compact JSON, the shape of Apache's `Schema.toString()` (e.g. `"string"`, `["null","int"]`), for error
 * messages that embed the writer schema. Cold: it renders the whole schema on each call.
 */
internal fun AvroSchema.toSchemaJsonString(): String = toJsonElement().toString()

private fun AvroSchema.wrongKind(prefix: String): RuntimeException = RuntimeException("$prefix: ${toSchemaJsonString()}")

// ---- names and aliases ----

/** A record, an enum or a fixed. Was `@InternalAvro4kApi public` on Apache's `Schema` only for Confluent (M3-12). */
internal fun AvroSchema.isNamedSchema(): Boolean = this is NamedSchema

/** [aliases] for a named schema, an empty set for any other one (never throws). Allocates, like [aliases]. */
internal fun AvroSchema.nonFailingAliases(): Set<String> = if (this is NamedSchema) aliasFullNames() else emptySet()

private fun NamedSchema.aliasFullNames(): Set<String> {
    if (aliases.isEmpty()) return emptySet()
    return aliases.mapTo(LinkedHashSet(aliases.size)) { it.fullName }
}

/**
 * True if [fullNameToMatch] is this schema's full name or, for a named schema, the full name of one of its aliases.
 * A non-named schema's full name is its type name (`"int"`, `"array"`), as in Apache, except for a union: `"union"`
 * here, `"union[…]"` in Apache ([fullNameForMessages]). No call site matches a union's name: they all resolve the
 * union first. Allocation-free on a full-name hit; iterates the aliases (an iterator) only on a miss.
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
 * The index of the first union branch of type [expectedType], or **-1** if there is none (not `null`: no boxing).
 * Throws `Not a union` when this schema is not a union, like Apache's `getIndexNamed`.
 *
 * A loop comparing the stored [AvroSchema.type]. For an unnamed type (primitives, `null`, `array`, `map`) a union has
 * at most one such branch, so this is exactly Apache's `getIndexNamed(expectedType.getName())`. For a named type it
 * returns the first record/enum/fixed branch, where Apache's name lookup would only have matched a named schema
 * literally called `record`, `enum` or `fixed`: no call site asks for a named type.
 */
internal fun AvroSchema.getIndexTyped(expectedType: Type): Int {
    val branches = types
    for (i in branches.indices) {
        if (branches[i].type === expectedType) return i
    }
    return -1
}

/**
 * The index of the first union branch of type [expectedType] whose logical type is [logicalTypeName], or **-1**.
 * Throws `Not a union` when this schema is not a union.
 *
 * Compares the cached [AvroSchema.logicalTypeName] (a `String` field), never the props. Replaces the `vararg` Apache
 * helper with one overload per arity used (one or two types), so no array is allocated per call.
 *
 * **Differs from Apache for invalid or unregistered logical types:** Apache's `getLogicalType()` is null when the
 * `logicalType` prop names a type Apache does not know (e.g. avro4k's own `char` on a schema parsed from JSON) or one
 * that fails validation (e.g. `decimal` without `precision`), while [AvroSchema.logicalTypeName] is the raw prop.
 */
internal fun AvroSchema.getIndexLogicallyTyped(
    logicalTypeName: String,
    expectedType: Type,
): Int {
    val branches = types
    for (i in branches.indices) {
        val branch = branches[i]
        if (branch.type === expectedType && branch.logicalTypeName == logicalTypeName) return i
    }
    return -1
}

/**
 * [getIndexLogicallyTyped] for [firstExpectedType], then for [secondExpectedType]: the order of the types is the
 * priority, as in the Apache `vararg` helper, whatever the order of the branches.
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
 * The index of the union branch whose full name, or one of whose aliases' full names, is [expectedName], or null.
 * Throws `Not a union` when this schema is not a union.
 *
 * One hash lookup ([UnionSchema.getIndexOrNull]). Apache looked the full names up first, then scanned the aliases;
 * the model rejects a union where a name is both a branch's full name and another branch's alias, so both orders give
 * the same answer. Returns the map's own boxed index: nothing is allocated.
 */
internal fun AvroSchema.getIndexNamedOrAliased(expectedName: String): Int? {
    return (this as? UnionSchema ?: throw wrongKind("Not a union")).getIndexOrNull(expectedName)
}

// ---- union shaping (schema generation) ----

/** The branches of a union, or a single-element list of this schema. Allocates for a non-union: not for per-value paths. */
internal fun AvroSchema.asSchemaList(): List<AvroSchema> = if (this is UnionSchema) types else listOf(this)

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
 * Skips one value written with [schema], a union's branch index included. Mirrors the Apache-typed skip of
 * `RecordDirectDecoder` call for call: collections are skipped block by block through [AvroBinaryDecoder.skipArray]
 * / [AvroBinaryDecoder.skipMap], which jump over a block written with its byte size (negative count) in one go and
 * return the item count of the next block to skip item by item.
 *
 * Allocation-free: indexed loops over the record fields, no iterator.
 */
internal fun AvroBinaryDecoder.skip(schema: AvroSchema) {
    val resolved = if (schema is UnionSchema) schema.types[readIndex()] else schema
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