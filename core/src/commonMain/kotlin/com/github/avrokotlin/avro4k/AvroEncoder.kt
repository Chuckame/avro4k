package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.aliases
import com.github.avrokotlin.avro4k.internal.nonNullSerialName
import com.github.avrokotlin.avro4k.internal.schema.fixedSize
import com.github.avrokotlin.avro4k.internal.schema.fullNameForMessages
import com.github.avrokotlin.avro4k.internal.schema.getIndexNamedOrAliased
import com.github.avrokotlin.avro4k.internal.schema.getIndexTyped
import com.github.avrokotlin.avro4k.internal.schema.getName
import com.github.avrokotlin.avro4k.internal.schema.knownLogicalTypeName
import com.github.avrokotlin.avro4k.internal.schema.nonFailingAliases
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import com.github.avrokotlin.avro4k.internal.schema.types
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Encoder

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
     * The [Avro] instance this encoder encodes with: a custom serializer reads its [Avro.configuration] and its
     * [Avro.serializersModule] from it, as with kotlinx-serialization's `JsonEncoder.json`.
     */
    public val avro: Avro

    /**
     * Provides the schema used to encode the current value.
     */
    @ExperimentalAvro4kApi
    public val currentWriterSchema: AvroSchema

    /**
     * Encodes a [AvroSchema.Type.BYTES] value from a [ByteArray].
     */
    @ExperimentalAvro4kApi
    public fun encodeBytes(value: ByteArray)

    /**
     * Encodes a [AvroSchema.Type.FIXED] value from a [ByteArray]. Its size must match the size of the fixed schema in [currentWriterSchema].
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

@InternalAvro4kApi
public fun AvroEncoder.namedSchemaNotFoundInUnionError(
    schema: AvroSchema,
): Throwable = namedSchemaNotFoundInUnionError(schema.fullName, schema.nonFailingAliases())

@InternalAvro4kApi
public fun AvroEncoder.namedSchemaNotFoundInUnionError(
    expectedName: String,
    possibleAliases: Set<String>,
    vararg fallbackTypes: AvroSchema.Type,
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
    return AvroSchemaMismatchException("Named schema $expectedName$aliasesStr not found in union.$fallbacksStr Actual schema: ${currentWriterSchema.toSchemaJsonString()}")
}

internal fun AvroEncoder.unsupportedWriterTypeError(
    mainType: AvroSchema.Type,
    vararg fallbackTypes: AvroSchema.Type,
): Throwable {
    val fallbacksStr = if (fallbackTypes.isNotEmpty()) ", and also not matching to any compatible type (one of ${fallbackTypes.joinToString()})." else ""
    val schema = currentWriterSchema
    return AvroSchemaMismatchException(
        "Unsupported schema '${schema.fullNameForMessages}' for encoded type of ${mainType.getName()}$fallbacksStr. Actual schema: ${schema.toSchemaJsonString()}"
    )
}

internal fun AvroEncoder.ensureFixedSize(byteArray: ByteArray): ByteArray {
    val schema = currentWriterSchema
    if (schema.fixedSize != byteArray.size) {
        throw AvroSchemaMismatchException("Fixed size mismatch for actual size of ${byteArray.size}. Actual schema: ${schema.toSchemaJsonString()}")
    }
    return byteArray
}

internal fun AvroEncoder.fullNameOrAliasMismatchError(
    fullName: String,
    aliases: Set<String>,
): Throwable {
    val aliasesStr = if (aliases.isNotEmpty()) " (with aliases ${aliases.joinToString()})" else ""
    return AvroSchemaMismatchException("The descriptor $fullName$aliasesStr doesn't match the schema ${currentWriterSchema.toSchemaJsonString()}")
}

internal fun AvroEncoder.logicalTypeMismatchError(
    logicalType: String,
    type: AvroSchema.Type,
): Throwable {
    return AvroSchemaMismatchException(
        "Expected schema type of ${type.getName()} with logical type $logicalType but had schema ${currentWriterSchema.toSchemaJsonString()}"
    )
}

/**
 * Selects the union branch of type [expectedType], if any. Only called when the current writer schema is a union.
 * A type compare per branch ([getIndexTyped]), no allocation.
 */
internal fun AvroEncoder.trySelectTypeNameFromUnion(expectedType: AvroSchema.Type): Boolean {
    val index = currentWriterSchema.getIndexTyped(expectedType)
    if (index < 0) return false
    encodeUnionIndex(index)
    return true
}

internal fun AvroEncoder.trySelectFixedSchemaForSize(fixedSize: Int): Boolean {
    val branches = currentWriterSchema.types
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
    val branches = currentWriterSchema.types
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
    val schema = currentWriterSchema
    val index =
        schema.getIndexNamedOrAliased(descriptor.nonNullSerialName)
            ?: descriptor.aliases.firstNotNullOfOrNull { schema.getIndexNamedOrAliased(it) }
            ?: return false
    encodeUnionIndex(index)
    return true
}

@InternalAvro4kApi
public fun AvroEncoder.trySelectNamedSchema(schema: AvroSchema): Boolean {
    return trySelectNamedSchema(schema.fullName, schema::nonFailingAliases)
}

@InternalAvro4kApi
public fun AvroEncoder.trySelectNamedSchema(
    name: String,
    aliases: () -> Set<String> = ::emptySet,
): Boolean {
    val schema = currentWriterSchema
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
 * Selects the first union branch of type [type] whose logical type is [logicalTypeName] and valid: a branch whose logical
 * type breaks the specification's rules is not selected, as the specification says to ignore such a logical type, and as
 * Apache's `getLogicalType()` (null for it) did. One overload per arity the serializers use, so no `vararg` array is
 * allocated per call.
 */
internal fun AvroEncoder.trySelectLogicalTypeFromUnion(
    logicalTypeName: String,
    type: AvroSchema.Type,
): Boolean {
    val index = currentWriterSchema.getIndexValidlyLogicallyTyped(logicalTypeName, type)
    if (index < 0) return false
    encodeUnionIndex(index)
    return true
}

/** [trySelectLogicalTypeFromUnion] for [firstType], then for [secondType]: the order of the types is the priority. */
internal fun AvroEncoder.trySelectLogicalTypeFromUnion(
    logicalTypeName: String,
    firstType: AvroSchema.Type,
    secondType: AvroSchema.Type,
): Boolean {
    val schema = currentWriterSchema
    var index = schema.getIndexValidlyLogicallyTyped(logicalTypeName, firstType)
    if (index < 0) index = schema.getIndexValidlyLogicallyTyped(logicalTypeName, secondType)
    if (index < 0) return false
    encodeUnionIndex(index)
    return true
}

/**
 * The common `getIndexLogicallyTyped`, skipping the branches whose logical type is invalid. -1 when there is none.
 * [logicalTypeName] is always one of the specification's logical types here, so [knownLogicalTypeName] (memoized per
 * schema) answers both the name and the validity with a field read.
 */
private fun AvroSchema.getIndexValidlyLogicallyTyped(
    logicalTypeName: String,
    expectedType: AvroSchema.Type,
): Int {
    val branches = types
    for (i in branches.indices) {
        val branch = branches[i]
        if (branch.type === expectedType && branch.knownLogicalTypeName == logicalTypeName) return i
    }
    return -1
}

@ExperimentalAvro4kApi
public class MissingFieldsEncodingException(message: String) : AvroSchemaMismatchException(message) {
    public constructor(
        missingFields: List<AvroSchema.RecordSchema.Field>,
        writerSchema: AvroSchema,
    ) : this("Missing required fields '${missingFields.joinToString("', '") { it.name }}' when encoding with writer schema ${writerSchema.toSchemaJsonString()}")

    public constructor(
        missingFields: List<AvroSchema.RecordSchema.Field>,
        writerSchema: AvroSchema,
        descriptor: SerialDescriptor,
    ) : this(
        "Missing required fields '${missingFields.joinToString("', '") { it.name }}' when encoding with writer schema " +
            "${writerSchema.toSchemaJsonString()} from descriptor $descriptor"
    )
}