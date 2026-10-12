package com.github.avrokotlin.avro4k.fixtures

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.serializer.AvroDuration
import kotlinx.serialization.KSerializer

/**
 * A value, its schema and its bytes, which must be the same on every platform.
 *
 * [schema] and [hex] were produced once on the JVM and pasted here. The JVM test suite checks them against Apache Avro
 * (`GoldenFixturesApacheOracleTest`): the schema parses to the one avro4k infers, and the bytes are what Apache's own
 * writer produces for the value. Every other platform is therefore checked against Apache through them.
 */
internal class GoldenFixture<T>(
    val name: String,
    val serializer: KSerializer<T>,
    val value: T,
    /** The writer schema, as compact JSON. */
    val schema: String,
    /** [value] encoded with [schema]. */
    val hex: String,
    /** Whether [schema] is the schema avro4k infers for [serializer]; `false` for a value encoded with a chosen writer schema. */
    val inferred: Boolean = true,
    /**
     * Other encodings of [value] that are valid with [schema], e.g. a collection split into several blocks as other writers do.
     * They decode to [value]; avro4k's encoder only ever writes [hex].
     */
    val alternativeHex: List<String> = emptyList(),
    /**
     * Whether the decoded value can be compared with `==`. `false` when [T] holds a `ByteArray`, whose `equals` is identity,
     * or a `Float` that 32 bits do not hold exactly, which Kotlin/JS does not round in a literal: the decoded value is then
     * checked by encoding it again.
     */
    val structuralEquals: Boolean = true,
    /** [hex] decoded as `Any` with `AnySerializer`, as [renderGeneric] writes it: the same on every platform. */
    val generic: String,
    val avro: Avro = Avro,
) {
    override fun toString(): String = name
}

/**
 * A value decoded as `Any` with its writer [schema], written the same way on every platform. Numbers are written by the
 * schema's type rather than their runtime type, which JS cannot tell apart (any number `is Int` there): an `int` as `1`,
 * a `long` as `1L`, a `float` or a `double` by its bits (`f:3fc00000`), which `toString` would also write differently on
 * JS. Bytes in hex, strings quoted with anything outside printable ASCII escaped, records, maps and lists in their order.
 * Other values (the logical types) by `toString`, except [AvroDuration].
 */
internal fun renderGeneric(
    value: Any?,
    schema: AvroSchema,
): String {
    val resolved = if (schema is AvroSchema.UnionSchema) branchOf(value, schema) else schema
    return when (value) {
        null -> "null"

        // By its fields rather than its toString, which does not pad the milliseconds (4,005 ms is written `4.5S`) and
        // would pin that here
        is AvroDuration -> "AvroDuration(months=${value.months}, days=${value.days}, millis=${value.millis})"

        is String -> renderString(value)

        is Boolean -> value.toString()

        is ByteArray -> "0x" + value.toHexString()

        is Long -> "${value}L"

        is Number ->
            when (resolved.type) {
                AvroSchema.Type.FLOAT -> "f:" + value.toFloat().toRawBits().toUInt().toString(16)
                AvroSchema.Type.DOUBLE -> "d:" + value.toDouble().toRawBits().toULong().toString(16)
                else -> value.toInt().toString()
            }

        is Map<*, *> ->
            value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
                val itemSchema =
                    when (resolved) {
                        is AvroSchema.RecordSchema -> resolved.getFieldByName(key as String).schema
                        is AvroSchema.MapSchema -> resolved.valueSchema
                        else -> error("A map decoded from $resolved")
                    }
                renderString(key as String) + "=" + renderGeneric(item, itemSchema)
            }

        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { renderGeneric(it, (resolved as AvroSchema.ArraySchema).elementSchema) }

        else -> value.toString()
    }
}

/** The branch [value] was decoded from: the only one of its kind in every fixture, or the record with its field names. */
private fun branchOf(
    value: Any?,
    union: AvroSchema.UnionSchema,
): AvroSchema =
    union.types.first { branch ->
        when (value) {
            null -> branch.type == AvroSchema.Type.NULL
            is Map<*, *> -> branch is AvroSchema.MapSchema || (branch is AvroSchema.RecordSchema && branch.fields.map { it.name }.toSet() == value.keys)
            is List<*> -> branch.type == AvroSchema.Type.ARRAY
            is String -> branch.type == AvroSchema.Type.STRING || branch.type == AvroSchema.Type.ENUM
            is Long -> branch.type == AvroSchema.Type.LONG
            is Number -> branch.type in setOf(AvroSchema.Type.INT, AvroSchema.Type.FLOAT, AvroSchema.Type.DOUBLE)
            else -> branch.type != AvroSchema.Type.NULL
        }
    }

private fun renderString(value: String): String =
    buildString {
        append('"')
        for (char in value) {
            if (char in ' '..'~' && char != '"' && char != '\\') append(char) else append("\\u").append(char.code.toString(16).padStart(4, '0'))
        }
        append('"')
    }

/**
 * Bytes written with one schema and read with another (the reader's, inferred from [readerSerializer]): Avro's schema
 * resolution. The writer side is a model of its own ([writerSerializer], [writerValue]), so that [hex] can be produced
 * and checked by Apache from a real value.
 */
internal class ResolutionFixture<W, R>(
    val name: String,
    val writerSerializer: KSerializer<W>,
    val writerValue: W,
    /** The schema avro4k infers for [writerSerializer], as compact JSON. */
    val writerSchema: String,
    /** [writerValue] encoded with [writerSchema]. */
    val hex: String,
    val readerSerializer: KSerializer<R>,
    /** What [hex] decodes to, read with [readerSerializer]. */
    val expected: R,
    val avro: Avro = Avro,
) {
    override fun toString(): String = name
}

internal val allGoldenFixtures: List<GoldenFixture<*>> =
    primitiveFixtures + recordFixtures + unionFixtures + collectionFixtures + namedTypeFixtures + logicalTypeFixtures