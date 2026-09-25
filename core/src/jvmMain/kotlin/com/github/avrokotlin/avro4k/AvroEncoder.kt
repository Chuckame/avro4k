package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.aliases
import com.github.avrokotlin.avro4k.internal.apacheToAvro4k
import com.github.avrokotlin.avro4k.internal.encoder.AbstractAvroEncoder
import com.github.avrokotlin.avro4k.internal.nonFailingAliases
import com.github.avrokotlin.avro4k.internal.nonNullSerialName
import com.github.avrokotlin.avro4k.internal.schema.fixedSize
import com.github.avrokotlin.avro4k.internal.schema.fullNameForMessages
import com.github.avrokotlin.avro4k.internal.schema.getIndexNamedOrAliased
import com.github.avrokotlin.avro4k.internal.schema.getIndexTyped
import com.github.avrokotlin.avro4k.internal.schema.getName
import com.github.avrokotlin.avro4k.internal.schema.hasValidLogicalType
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import com.github.avrokotlin.avro4k.internal.schema.types
import com.github.avrokotlin.avro4k.internal.toAvroSchemaType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Encoder
import org.apache.avro.Schema

/**
 * Interface to encode Avro values.
 * Here are the main methods to encode values. Each encode method is adapting the type to the raw type, that means unions are resolved if needed, and also all primitives are converted automatically (a wanted `int` could be encoded to a `long`).
 * - [encodeNull]
 * - [encodeBoolean]
 * - [encodeByte]
 * - [encodeShort]
 * - [encodeInt]
 * - [encodeLong]
 * - [encodeFloat]
 * - [encodeDouble]
 * - [encodeString]
 * - [encodeChar]
 * - [encodeEnum]
 * - [encodeBytes]
 * - [encodeFixed]
 */
public interface AvroEncoder : Encoder {
    /**
     * Provides the schema used to encode the current value.
     */
    @ExperimentalAvro4kApi
    public val currentWriterSchema: Schema

    /**
     * Encodes a [Schema.Type.BYTES] value from a [ByteArray].
     */
    @ExperimentalAvro4kApi
    public fun encodeBytes(value: ByteArray)

    /**
     * Encodes a [Schema.Type.FIXED] value from a [ByteArray]. Its size must match the size of the fixed schema in [currentWriterSchema].
     * When many fixed schemas are in a union, the first one that matches the size is selected. To avoid this auto-selection, use [encodeUnionIndex] with the index of the expected fixed schema.
     */
    @ExperimentalAvro4kApi
    public fun encodeFixed(value: ByteArray)

    /**
     * Selects the index of the union type to encode. Also sets [currentWriterSchema] to the selected type.
     */
    @ExperimentalAvro4kApi
    public fun encodeUnionIndex(index: Int)
}

/**
 * The writer schema of the value being encoded, as avro4k's own model: read straight from core's encoders, or converted
 * (identity-cached) from [AvroEncoder.currentWriterSchema] for any other [AvroEncoder]. Core's runtime and helpers read
 * this, never the Apache-typed facade.
 */
internal val AvroEncoder.currentAvroSchema: AvroSchema
    get() = if (this is AbstractAvroEncoder) currentAvroSchema else apacheToAvro4k(currentWriterSchema)

@InternalAvro4kApi
public fun AvroEncoder.namedSchemaNotFoundInUnionError(
    schema: Schema,
): Throwable = namedSchemaNotFoundInUnionError(schema.fullName, schema.nonFailingAliases())

@InternalAvro4kApi
public fun AvroEncoder.namedSchemaNotFoundInUnionError(
    expectedName: String,
    possibleAliases: Set<String>,
    vararg fallbackTypes: Schema.Type,
): Throwable = namedSchemaNotFoundInUnionError(expectedName, possibleAliases, fallbackTypes.joinToString())

/** [namedSchemaNotFoundInUnionError] with one fallback type, named as avro4k's own [AvroSchema.Type]. */
internal fun AvroEncoder.namedSchemaNotFoundInUnionError(
    expectedName: String,
    possibleAliases: Set<String>,
    fallbackType: AvroSchema.Type,
): Throwable = namedSchemaNotFoundInUnionError(expectedName, possibleAliases, fallbackType.toString())

private fun AvroEncoder.namedSchemaNotFoundInUnionError(
    expectedName: String,
    possibleAliases: Set<String>,
    fallbackTypes: String,
): Throwable {
    val aliasesStr = if (possibleAliases.isNotEmpty()) " (with aliases ${possibleAliases.joinToString()})" else ""
    val fallbacksStr = if (fallbackTypes.isNotEmpty()) " Also no compatible type found (one of $fallbackTypes)." else ""
    return SerializationException("Named schema $expectedName$aliasesStr not found in union.$fallbacksStr Actual schema: ${currentAvroSchema.toSchemaJsonString()}")
}

internal fun AvroEncoder.unsupportedWriterTypeError(
    mainType: AvroSchema.Type,
    vararg fallbackTypes: AvroSchema.Type,
): Throwable {
    val fallbacksStr = if (fallbackTypes.isNotEmpty()) ", and also not matching to any compatible type (one of ${fallbackTypes.joinToString()})." else ""
    val schema = currentAvroSchema
    return SerializationException(
        "Unsupported schema '${schema.fullNameForMessages}' for encoded type of ${mainType.getName()}$fallbacksStr. Actual schema: ${schema.toSchemaJsonString()}"
    )
}

/** [unsupportedWriterTypeError] for the serializers, which still name Apache's types until they read [AvroSchema]. */
internal fun AvroEncoder.unsupportedWriterTypeError(
    mainType: Schema.Type,
    vararg fallbackTypes: Schema.Type,
): Throwable = unsupportedWriterTypeError(mainType.toAvroSchemaType(), *Array(fallbackTypes.size) { fallbackTypes[it].toAvroSchemaType() })

internal fun AvroEncoder.ensureFixedSize(byteArray: ByteArray): ByteArray {
    val schema = currentAvroSchema
    if (schema.fixedSize != byteArray.size) {
        throw SerializationException("Fixed size mismatch for actual size of ${byteArray.size}. Actual schema: ${schema.toSchemaJsonString()}")
    }
    return byteArray
}

internal fun AvroEncoder.fullNameOrAliasMismatchError(
    fullName: String,
    aliases: Set<String>,
): Throwable {
    val aliasesStr = if (aliases.isNotEmpty()) " (with aliases ${aliases.joinToString()})" else ""
    return SerializationException("The descriptor $fullName$aliasesStr doesn't match the schema ${currentAvroSchema.toSchemaJsonString()}")
}

internal fun AvroEncoder.logicalTypeMismatchError(
    logicalType: String,
    type: Schema.Type,
): Throwable {
    return SerializationException(
        "Expected schema type of ${type.toAvroSchemaType().getName()} with logical type $logicalType but had schema ${currentAvroSchema.toSchemaJsonString()}"
    )
}

/**
 * Selects the union branch of type [expectedType], if any. Only called when the current writer schema is a union.
 * A type compare per branch ([getIndexTyped]), no allocation.
 */
internal fun AvroEncoder.trySelectTypeNameFromUnion(expectedType: AvroSchema.Type): Boolean {
    val index = currentAvroSchema.getIndexTyped(expectedType)
    if (index < 0) return false
    encodeUnionIndex(index)
    return true
}

/** [trySelectTypeNameFromUnion] for the serializers, which still name Apache's types until they read [AvroSchema]. */
internal fun AvroEncoder.trySelectTypeNameFromUnion(expectedType: Schema.Type): Boolean = trySelectTypeNameFromUnion(expectedType.toAvroSchemaType())

internal fun AvroEncoder.trySelectFixedSchemaForSize(fixedSize: Int): Boolean {
    val branches = currentAvroSchema.types
    for (index in branches.indices) {
        val branch = branches[index]
        if (branch is AvroSchema.FixedSchema && branch.size == fixedSize) {
            encodeUnionIndex(index)
            return true
        }
    }
    return false
}

internal fun AvroEncoder.trySelectEnumSchemaForSymbol(symbol: String): Boolean {
    val branches = currentAvroSchema.types
    for (index in branches.indices) {
        val branch = branches[index]
        if (branch is AvroSchema.EnumSchema && branch.getSymbolIndexOrNull(symbol) != null) {
            encodeUnionIndex(index)
            return true
        }
    }
    return false
}

/**
 * Selects the union branch named after the descriptor's serial name, or one of its `@AvroAlias`es, matching a branch's
 * full name or aliases. One hash lookup on a name hit; the descriptor's aliases are only read on a miss.
 */
internal fun AvroEncoder.trySelectNamedSchema(descriptor: SerialDescriptor): Boolean {
    val schema = currentAvroSchema
    val index =
        schema.getIndexNamedOrAliased(descriptor.nonNullSerialName)
            ?: descriptor.aliases.firstNotNullOfOrNull { schema.getIndexNamedOrAliased(it) }
            ?: return false
    encodeUnionIndex(index)
    return true
}

@InternalAvro4kApi
public fun AvroEncoder.trySelectNamedSchema(schema: Schema): Boolean {
    return trySelectNamedSchema(schema.fullName, schema::nonFailingAliases)
}

@InternalAvro4kApi
public fun AvroEncoder.trySelectNamedSchema(
    name: String,
    aliases: () -> Set<String> = ::emptySet,
): Boolean {
    val schema = currentAvroSchema
    val index =
        schema.getIndexNamedOrAliased(name)
            ?: aliases().firstNotNullOfOrNull { schema.getIndexNamedOrAliased(it) }
    if (index != null) {
        encodeUnionIndex(index)
        return true
    }
    return false
}

/**
 * Selects the first union branch of type [type] whose logical type is [logicalTypeName] and valid
 * ([hasValidLogicalType]): a branch whose logical type breaks the specification's rules is not selected, as the
 * specification says to ignore such a logical type, and as Apache's `getLogicalType()` (null for it) did. One overload
 * per arity the serializers use, so no `vararg` array is allocated per call.
 */
internal fun AvroEncoder.trySelectLogicalTypeFromUnion(
    logicalTypeName: String,
    type: Schema.Type,
): Boolean {
    val index = currentAvroSchema.getIndexValidlyLogicallyTyped(logicalTypeName, type.toAvroSchemaType())
    if (index < 0) return false
    encodeUnionIndex(index)
    return true
}

/** [trySelectLogicalTypeFromUnion] for [firstType], then for [secondType]: the order of the types is the priority. */
internal fun AvroEncoder.trySelectLogicalTypeFromUnion(
    logicalTypeName: String,
    firstType: Schema.Type,
    secondType: Schema.Type,
): Boolean {
    val schema = currentAvroSchema
    var index = schema.getIndexValidlyLogicallyTyped(logicalTypeName, firstType.toAvroSchemaType())
    if (index < 0) index = schema.getIndexValidlyLogicallyTyped(logicalTypeName, secondType.toAvroSchemaType())
    if (index < 0) return false
    encodeUnionIndex(index)
    return true
}

/** The common `getIndexLogicallyTyped`, skipping the branches whose logical type is invalid. -1 when there is none. */
private fun AvroSchema.getIndexValidlyLogicallyTyped(
    logicalTypeName: String,
    expectedType: AvroSchema.Type,
): Int {
    val branches = types
    for (i in branches.indices) {
        val branch = branches[i]
        if (branch.type === expectedType && branch.logicalTypeName == logicalTypeName && branch.hasValidLogicalType()) return i
    }
    return -1
}

@ExperimentalAvro4kApi
public class MissingFieldsEncodingException(message: String) : SerializationException(message) {
    public constructor(
        missingFields: List<Schema.Field>,
        writerSchema: Schema,
    ) : this("Missing required fields '${missingFields.joinToString("', '") { it.name() }}' when encoding with writer schema $writerSchema")

    public constructor(
        missingFields: List<Schema.Field>,
        writerSchema: Schema,
        descriptor: SerialDescriptor,
    ) : this("Missing required fields '${missingFields.joinToString("', '") { it.name() }}' when encoding with writer schema $writerSchema from descriptor $descriptor")
}