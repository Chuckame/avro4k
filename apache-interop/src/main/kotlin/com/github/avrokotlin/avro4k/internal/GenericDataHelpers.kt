@file:Suppress("FunctionName")

package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.ArraySchema
import com.github.avrokotlin.avro4k.AvroSchema.MapSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.AvroSerializationException
import com.github.avrokotlin.avro4k.ResolvedSchema
import com.github.avrokotlin.avro4k.toJsonElement
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import java.math.BigDecimal
import kotlin.reflect.KClass

/*
 * Copies of the small core helpers that the generic data trees use, so that core does not have to expose them: the
 * schema accessors, the exact number conversions and the decoding errors. Each one behaves, and words its message,
 * exactly like core's.
 */

// ---- Apache-shaped schema accessors: each throws on the wrong kind of schema like Apache's getter ----

internal val AvroSchema.isUnion: Boolean
    get() = type === AvroSchema.Type.UNION

internal val AvroSchema.types: List<ResolvedSchema>
    get() = (this as? UnionSchema ?: throw wrongKind("Not a union")).types

internal val AvroSchema.elementType: AvroSchema
    get() = (this as? ArraySchema ?: throw wrongKind("Not an array")).elementSchema

internal val AvroSchema.valueType: AvroSchema
    get() = (this as? MapSchema ?: throw wrongKind("Not a map")).valueSchema

internal val AvroSchema.fields: List<RecordSchema.Field>
    get() = (this as? RecordSchema ?: throw wrongKind("Not a record")).fields

private fun AvroSchema.wrongKind(prefix: String): RuntimeException = RuntimeException("$prefix: ${toJsonElement()}")

// ---- exact number conversions ----

internal fun Int.toByteExact(): Byte {
    if (this.toByte().toInt() != this) {
        return invalidTypeError()
    }
    return this.toByte()
}

internal fun Long.toByteExact(): Byte {
    if (this.toByte().toLong() != this) {
        return invalidTypeError()
    }
    return this.toByte()
}

internal fun Int.toShortExact(): Short {
    if (this.toShort().toInt() != this) {
        return invalidTypeError()
    }
    return this.toShort()
}

internal fun Long.toShortExact(): Short {
    if (this.toShort().toLong() != this) {
        return invalidTypeError()
    }
    return this.toShort()
}

internal fun Long.toIntExact(): Int {
    if (this.toInt().toLong() != this) {
        return invalidTypeError()
    }
    return this.toInt()
}

internal fun Double.toFloatExact(): Float {
    if (this.toFloat().toDouble() != this) {
        return invalidTypeError()
    }
    return this.toFloat()
}

internal fun BigDecimal.toByteExact(): Byte {
    if (this.toByte().toInt().toBigDecimal() != this) {
        return invalidTypeError()
    }
    return this.toByte()
}

internal fun BigDecimal.toShortExact(): Short {
    if (this.toShort().toInt().toBigDecimal() != this) {
        return invalidTypeError()
    }
    return this.toShort()
}

internal fun BigDecimal.toIntExact(): Int {
    if (this.toInt().toBigDecimal() != this) {
        return invalidTypeError()
    }
    return this.toInt()
}

internal fun BigDecimal.toLongExact(): Long {
    if (this.toLong().toBigDecimal() != this) {
        return invalidTypeError()
    }
    return this.toLong()
}

internal fun BigDecimal.toDoubleExact(): Double {
    if (this.toDouble().toBigDecimal() != this) {
        return invalidTypeError()
    }
    return this.toDouble()
}

internal fun BigDecimal.toFloatExact(): Float {
    if (this.toFloat().toBigDecimal() != this) {
        return invalidTypeError()
    }
    return this.toFloat()
}

private inline fun <reified T> Any.invalidTypeError(): T {
    throw AvroSchemaMismatchException("Value $this is not a valid ${T::class.simpleName}")
}

// ---- decoding errors ----

internal val KClass<*>.nameForMessages: String
    get() = qualifiedName.toString()

internal fun DecodedNullError() = AvroSerializationException("Unexpected null value, Decoder.decodeTaggedNotNullMark should be called first")

internal fun DecodedNullError(
    descriptor: SerialDescriptor,
    elementIndex: Int,
) = AvroSerializationException(
    "Unexpected null value for field '${descriptor.getElementName(elementIndex)}' for type '${descriptor.serialName}', Decoder.decodeTaggedNotNullMark should be called first"
)

internal fun Decoder.IllegalIndexedAccessError() = UnsupportedOperationException("${this::class.nameForMessages} does not support indexed access")

internal inline fun <reified ExpectedType> BadDecodedValueError(
    value: Any?,
    firstExpectedType: KClass<*>,
    vararg expectedTypes: KClass<*>,
): AvroSchemaMismatchException = badDecodedValueError(value, ExpectedType::class.nameForMessages, firstExpectedType, expectedTypes)

internal fun BadDecodedValueError(
    value: Any?,
    expectedKind: SerialKind,
    firstExpectedType: KClass<*>,
    vararg expectedTypes: KClass<*>,
): AvroSchemaMismatchException = badDecodedValueError(value, expectedKind.toString(), firstExpectedType, expectedTypes)

internal fun badDecodedValueError(
    value: Any?,
    expected: String,
    firstExpectedType: KClass<*>,
    expectedTypes: Array<out KClass<*>>,
): AvroSchemaMismatchException {
    val allExpectedTypes = listOf(firstExpectedType) + expectedTypes
    return if (value == null) {
        AvroSchemaMismatchException(
            "Decoded null value for $expected kind, expected one of [${allExpectedTypes.joinToString { it.nameForMessages }}]"
        )
    } else {
        AvroSchemaMismatchException(
            "Decoded value '$value' of type ${value::class.nameForMessages} for $expected kind, expected one of [${allExpectedTypes.joinToString { it.nameForMessages }}]"
        )
    }
}