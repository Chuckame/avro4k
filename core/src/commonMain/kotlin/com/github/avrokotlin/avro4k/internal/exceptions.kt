@file:Suppress("FunctionName")

package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroMalformedDataException
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.AvroSerializationException
import com.github.avrokotlin.avro4k.internal.codec.MAX_BINARY_LENGTH
import com.github.avrokotlin.avro4k.internal.schema.enumSymbols
import com.github.avrokotlin.avro4k.internal.schema.fullNameForMessages
import com.github.avrokotlin.avro4k.internal.schema.getName
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import com.github.avrokotlin.avro4k.internal.schema.types
import kotlinx.io.EOFException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import kotlin.reflect.KClass

/**
 * A schema generation failure thrown where the element's path is not at hand: the visitor of the element turns it into an
 * [com.github.avrokotlin.avro4k.AvroSchemaGenerationException] with the element's path and the same message, without a cause.
 * Thrown outside a schema generation (a custom serializer's `getSchema` called directly), it stays an
 * [AvroSerializationException].
 */
internal class SchemaGenerationFailure(message: String) : AvroSerializationException(message)

context(_: Decoder)
internal fun DecodedNullError() = AvroSerializationException("Unexpected null value, Decoder.decodeTaggedNotNullMark should be called first")

context(_: Decoder)
internal fun DecodedNullError(
    descriptor: SerialDescriptor,
    elementIndex: Int,
) = AvroSerializationException(
    "Unexpected null value for field '${descriptor.getElementName(elementIndex)}' for type '${descriptor.serialName}', Decoder.decodeTaggedNotNullMark should be called first"
)

internal fun Decoder.IllegalIndexedAccessError() = UnsupportedOperationException("${this::class.nameForMessages} does not support indexed access")

context(_: Decoder)
internal inline fun <reified ExpectedType> BadDecodedValueError(
    value: Any?,
    firstExpectedType: KClass<*>,
    vararg expectedTypes: KClass<*>,
): AvroSchemaMismatchException {
    val allExpectedTypes = listOf(firstExpectedType) + expectedTypes
    return if (value == null) {
        AvroSchemaMismatchException(
            "Decoded null value for ${ExpectedType::class.nameForMessages} kind, expected one of [${allExpectedTypes.joinToString { it.nameForMessages }}]"
        )
    } else {
        AvroSchemaMismatchException(
            "Decoded value '$value' of type ${value::class.nameForMessages} for " +
                "${ExpectedType::class.nameForMessages} kind, expected one of [${allExpectedTypes.joinToString { it.nameForMessages }}]"
        )
    }
}

context(_: Decoder)
internal fun BadDecodedValueError(
    value: Any?,
    expectedKind: SerialKind,
    firstExpectedType: KClass<*>,
    vararg expectedTypes: KClass<*>,
): AvroSchemaMismatchException {
    val allExpectedTypes = listOf(firstExpectedType) + expectedTypes
    return if (value == null) {
        AvroSchemaMismatchException(
            "Decoded null value for $expectedKind kind, expected one of [${allExpectedTypes.joinToString { it.nameForMessages }}]"
        )
    } else {
        AvroSchemaMismatchException(
            "Decoded value '$value' of type ${value::class.nameForMessages} for $expectedKind kind, expected one of [${allExpectedTypes.joinToString { it.nameForMessages }}]"
        )
    }
}

internal fun AvroDecoder.UnexpectedDecodeSchemaError(
    actualType: String,
    firstExpectedType: AvroSchema.Type,
    vararg expectedTypes: AvroSchema.Type,
): AvroSchemaMismatchException {
    val allExpectedTypes = listOf(firstExpectedType) + expectedTypes
    return AvroSchemaMismatchException(
        "For $actualType, expected type one of $allExpectedTypes, but had writer schema ${currentWriterSchema.toSchemaJsonString()}"
    )
}

/**
 * A union branch index read from the input is out of the union's branches. Checked before indexing them, so that
 * corrupted input fails as malformed data rather than with an index error.
 */
internal fun unionIndexOutOfRangeError(
    index: Int,
    unionSchema: AvroSchema,
): AvroMalformedDataException =
    AvroMalformedDataException("Malformed data: union index $index out of range [0, ${unionSchema.types.size}) for ${unionSchema.fullNameForMessages}")

/** An enum symbol index read from the input is out of the enum's symbols. */
internal fun enumIndexOutOfRangeError(
    index: Int,
    enumSchema: AvroSchema,
): AvroMalformedDataException =
    AvroMalformedDataException("Malformed data: enum index $index out of range [0, ${enumSchema.enumSymbols.size}) for ${enumSchema.fullNameForMessages}")

/**
 * The item count of a collection block read from the input is negative, or brings the collection's total count above
 * [com.github.avrokotlin.avro4k.internal.codec.MAX_BINARY_LENGTH].
 */
internal fun collectionCountError(
    blockCount: Long,
    previousTotal: Int,
): AvroMalformedDataException =
    if (blockCount < 0L) {
        AvroMalformedDataException("Malformed data: negative item count $blockCount for an array or map block")
    } else {
        AvroMalformedDataException(
            "Cannot read an array or map block of $blockCount items after $previousTotal items: the maximum count of a collection is " +
                MAX_BINARY_LENGTH
        )
    }

/** The input ended in the middle of a value. [cause] is the input's own end-of-input exception. */
internal fun endOfInputError(cause: EOFException): AvroMalformedDataException = AvroMalformedDataException("Unexpected end of input: ${cause.message}", cause)

/**
 * Converts a value read from the input to [kotlinType] with [convert], which must only convert (the value is read
 * before): a value that is a valid encoding of the writer schema but does not fit the Kotlin type (a string that is not
 * a number, a date out of range, …) fails with an [AvroSchemaMismatchException], the conversion's exception as the cause.
 */
internal inline fun <V, R> V.convertDecoded(
    writerType: AvroSchema.Type,
    kotlinType: String,
    convert: (V) -> R,
): R {
    try {
        return convert(this)
    } catch (e: Exception) {
        throw valueConversionError(this, writerType, kotlinType, e)
    }
}

/**
 * Converts a value being encoded to the [writerType] with [convert], which must only convert: a value that does not fit
 * the writer schema (a string that is not a number, a number out of range, …) fails with an
 * [AvroSchemaMismatchException], the conversion's exception as the cause.
 */
internal inline fun <V, R> V.convertForEncoding(
    kotlinType: String,
    writerType: AvroSchema.Type,
    convert: (V) -> R,
): R {
    try {
        return convert(this)
    } catch (e: Exception) {
        throw AvroSchemaMismatchException("Cannot convert the $kotlinType value '$this' to the writer type ${writerType.getName()}: ${e.message}", e)
    }
}

/**
 * A value read from the input is a valid encoding of the writer schema, but it cannot be converted to the Kotlin type
 * (a string that is not a number, a date out of range, …). [cause] is the conversion's own exception.
 */
internal fun valueConversionError(
    value: Any?,
    writerType: AvroSchema.Type,
    kotlinType: String,
    cause: Throwable,
): AvroSchemaMismatchException =
    AvroSchemaMismatchException("Cannot convert the decoded value '$value' of writer type ${writerType.getName()} to $kotlinType: ${cause.message}", cause)