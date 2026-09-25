package com.github.avrokotlin.avro4k.serializer

import com.github.avrokotlin.avro4k.AnyValueDecoder
import com.github.avrokotlin.avro4k.AvroDecimal
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.ResolvedSchema
import com.github.avrokotlin.avro4k.decodeResolvingAny
import com.github.avrokotlin.avro4k.internal.AvroSchemaGenerationException
import com.github.avrokotlin.avro4k.internal.Buffer
import com.github.avrokotlin.avro4k.internal.UnexpectedDecodeSchemaError
import com.github.avrokotlin.avro4k.internal.codec.KotlinxIoDecoder
import com.github.avrokotlin.avro4k.internal.codec.KotlinxIoEncoder
import com.github.avrokotlin.avro4k.internal.schema.fixedSize
import com.github.avrokotlin.avro4k.internal.schema.isUnion
import com.github.avrokotlin.avro4k.internal.schema.knownLogicalTypeName
import com.github.avrokotlin.avro4k.trySelectFixedSchemaForSize
import com.github.avrokotlin.avro4k.trySelectLogicalTypeFromUnion
import com.github.avrokotlin.avro4k.trySelectTypeNameFromUnion
import com.github.avrokotlin.avro4k.unsupportedWriterTypeError
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.apache.avro.AvroTypeException
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.net.URL
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToLong

internal val JavaStdLibSerializersModule: SerializersModule
    get() =
        SerializersModule {
            contextual(URLSerializer)
            contextual(UUIDSerializer)
            contextual(BigIntegerSerializer)
            contextual(BigDecimalSerializer)
        }

public object URLSerializer : KSerializer<URL> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(URL::class.qualifiedName!!, PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: URL,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): URL = URL(decoder.decodeString())
}

/**
 * Serializes an [UUID] as a string logical type of `uuid`.
 *
 * Note: it does not check if the schema logical type name is `uuid` as it does not make any conversion.
 */
public object UUIDSerializer : AvroSerializer<UUID>(UUID::class.qualifiedName!!) {
    internal const val LOGICAL_TYPE_NAME = "uuid"

    override fun getSchema(context: SchemaSupplierContext): AvroSchema {
        return context.inlinedElements.firstNotNullOfOrNull { element ->
            element.stringable?.createSchema()
                ?: element.fixed?.createSchema(element)
                    ?.withLogicalType(LOGICAL_TYPE_NAME)
                    ?.also {
                        if (it.fixedSize != 16) {
                            throw SerializationException(
                                "UUID's @${AvroFixed::class.simpleName} must have bytes size of 16. Got ${it.fixedSize}."
                            )
                        }
                    }
        } ?: logicalTypeSchema(AvroSchema.Type.STRING, LOGICAL_TYPE_NAME)
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: UUID,
    ) {
        with(encoder) {
            if (currentWriterSchema.isUnion) {
                trySelectLogicalTypeFromUnion(LOGICAL_TYPE_NAME, AvroSchema.Type.FIXED) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                    trySelectFixedSchemaForSize(16) ||
                    throw unsupportedWriterTypeError(AvroSchema.Type.STRING, AvroSchema.Type.FIXED)
            }
            when (currentWriterSchema.type) {
                AvroSchema.Type.STRING -> encodeString(value.toString())
                AvroSchema.Type.FIXED -> encodeFixed(value.toByteArray())
                else -> throw unsupportedWriterTypeError(AvroSchema.Type.STRING, AvroSchema.Type.FIXED)
            }
        }
    }

    private fun UUID.toByteArray(): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(mostSignificantBits)
            .putLong(leastSignificantBits)
            .array()

    override fun serializeGeneric(
        encoder: Encoder,
        value: UUID,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeAvro(decoder: AvroDecoder): UUID {
        with(decoder) {
            return decodeResolvingAny({
                UnexpectedDecodeSchemaError(
                    "UUID",
                    AvroSchema.Type.STRING,
                    AvroSchema.Type.FIXED
                )
            }) { schema ->
                when (schema.type) {
                    AvroSchema.Type.STRING -> {
                        AnyValueDecoder { UUID.fromString(decoder.decodeString()) }
                    }

                    AvroSchema.Type.FIXED -> {
                        if (schema.fixedSize == 16) {
                            AnyValueDecoder { parseUuid(decoder.decodeBytes()) }
                        } else {
                            null
                        }
                    }

                    else -> null
                }
            }
        }
    }

    private fun parseUuid(bytes: ByteArray): UUID {
        return ByteBuffer.wrap(bytes).let {
            UUID(
                it.getLong(),
                it.getLong()
            )
        }
    }

    override fun deserializeGeneric(decoder: Decoder): UUID {
        return UUID.fromString(decoder.decodeString())
    }
}

public object BigIntegerSerializer : AvroSerializer<BigInteger>(BigInteger::class.qualifiedName!!) {
    override fun getSchema(context: SchemaSupplierContext): AvroSchema {
        return AvroSchema.StringSchema()
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: BigInteger,
    ) {
        with(encoder) {
            if (currentWriterSchema.isUnion) {
                trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.INT) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.LONG) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.FLOAT) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.DOUBLE) ||
                    throw unsupportedWriterTypeError(AvroSchema.Type.STRING, AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.FLOAT, AvroSchema.Type.DOUBLE)
            }
            when (currentWriterSchema.type) {
                AvroSchema.Type.STRING -> encodeString(value.toString())
                AvroSchema.Type.INT -> encodeInt(value.intValueExact())
                AvroSchema.Type.LONG -> encodeLong(value.longValueExact())
                AvroSchema.Type.FLOAT -> encodeFloat(value.toFloat())
                AvroSchema.Type.DOUBLE -> encodeDouble(value.toDouble())
                else -> throw unsupportedWriterTypeError(AvroSchema.Type.STRING, AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.FLOAT, AvroSchema.Type.DOUBLE)
            }
        }
    }

    override fun serializeGeneric(
        encoder: Encoder,
        value: BigInteger,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeAvro(decoder: AvroDecoder): BigInteger {
        with(decoder) {
            return decodeResolvingAny({
                UnexpectedDecodeSchemaError(
                    "BigInteger",
                    AvroSchema.Type.STRING,
                    AvroSchema.Type.INT,
                    AvroSchema.Type.LONG,
                    AvroSchema.Type.FLOAT,
                    AvroSchema.Type.DOUBLE
                )
            }) { schema ->
                when (schema.type) {
                    AvroSchema.Type.STRING -> {
                        AnyValueDecoder { decoder.decodeString().toBigInteger() }
                    }

                    AvroSchema.Type.INT -> {
                        AnyValueDecoder { decoder.decodeInt().toBigInteger() }
                    }

                    AvroSchema.Type.LONG -> {
                        AnyValueDecoder { decoder.decodeLong().toBigInteger() }
                    }

                    AvroSchema.Type.FLOAT -> {
                        AnyValueDecoder { decoder.decodeFloat().toBigDecimal().toBigIntegerExact() }
                    }

                    AvroSchema.Type.DOUBLE -> {
                        AnyValueDecoder { decoder.decodeDouble().toBigDecimal().toBigIntegerExact() }
                    }

                    else -> null
                }
            }
        }
    }

    override fun deserializeGeneric(decoder: Decoder): BigInteger {
        return decoder.decodeString().toBigInteger()
    }
}

public object BigDecimalSerializer : AvroSerializer<BigDecimal>(BigDecimal::class.qualifiedName!!) {
    internal const val DECIMAL_LOGICAL_TYPE_NAME = "decimal"
    internal const val BIG_DECIMAL_LOGICAL_TYPE_NAME = "big-decimal"

    /**
     * If element is stringable, use string schema.
     * If element is fixed, use fixed schema with `decimal` logical type, and require @AvroDecimal. If @AvroDecimal is missing, throw an error.
     * Otherwise, use bytes schema with `decimal` logical type if @AvroDecimal is present.
     * If @AvroDecimal is missing, use bytes schema with `big-decimal` logical type.
     */
    override fun getSchema(context: SchemaSupplierContext): AvroSchema {
        val decimal = context.inlinedElements.firstNotNullOfOrNull { it.decimal }

        return context.inlinedElements.firstNotNullOfOrNull {
            it.stringable?.createSchema()
                ?: it.fixed?.createSchema(it)?.withDecimalLogicalType(
                    decimal ?: throw AvroSchemaGenerationException("BigDecimal requires @${AvroDecimal::class.qualifiedName} to work with FIXED schemas.")
                )
        } ?: decimal?.let { AvroSchema.BytesSchema().withDecimalLogicalType(it) }
            ?: logicalTypeSchema(AvroSchema.Type.BYTES, BIG_DECIMAL_LOGICAL_TYPE_NAME)
    }

    /**
     * The props Apache's `LogicalTypes.decimal(precision, scale).addToSchema` adds, in its order, after the same
     * validation, with the same messages.
     */
    private fun AvroSchema.withDecimalLogicalType(decimal: AvroDecimal): AvroSchema {
        val precision = decimal.precision
        val scale = decimal.scale
        if (precision <= 0) {
            throw IllegalArgumentException("Invalid decimal precision: $precision (must be positive)")
        }
        if (this is AvroSchema.FixedSchema) {
            val maxPrecision = floor(log10(2.0) * (8 * size - 1)).roundToLong()
            if (precision > maxPrecision) {
                throw IllegalArgumentException("fixed($size) cannot store $precision digits (max $maxPrecision)")
            }
        }
        if (scale < 0) {
            throw IllegalArgumentException("Invalid decimal scale: $scale (must be positive)")
        } else if (scale > precision) {
            throw IllegalArgumentException("Invalid decimal scale: $scale (greater than precision: $precision)")
        }
        return withLogicalType(DECIMAL_LOGICAL_TYPE_NAME, "precision" to JsonPrimitive(precision), "scale" to JsonPrimitive(scale))
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: BigDecimal,
    ) {
        with(encoder) {
            if (currentWriterSchema.isUnion) {
                trySelectLogicalTypeFromUnion(DECIMAL_LOGICAL_TYPE_NAME, AvroSchema.Type.BYTES, AvroSchema.Type.FIXED) ||
                    trySelectLogicalTypeFromUnion(BIG_DECIMAL_LOGICAL_TYPE_NAME, AvroSchema.Type.BYTES) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.INT) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.LONG) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.FLOAT) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.DOUBLE) ||
                    throw unsupportedWriterTypeError()
            }
            val schema = currentWriterSchema
            when (schema.type) {
                AvroSchema.Type.BYTES ->
                    encodeBytes(
                        when (schema.knownLogicalTypeName) {
                            DECIMAL_LOGICAL_TYPE_NAME -> value.toDecimalBytes(schema)
                            BIG_DECIMAL_LOGICAL_TYPE_NAME -> value.toBigDecimalBytes()
                            else -> throw unsupportedWriterTypeError()
                        }
                    )

                AvroSchema.Type.FIXED ->
                    encodeFixed(
                        when (schema.knownLogicalTypeName) {
                            DECIMAL_LOGICAL_TYPE_NAME -> value.toDecimalFixed(schema)
                            else -> throw unsupportedWriterTypeError()
                        }
                    )

                AvroSchema.Type.STRING -> encodeString(value.toString())

                AvroSchema.Type.INT -> encodeInt(value.intValueExact())

                AvroSchema.Type.LONG -> encodeLong(value.longValueExact())

                AvroSchema.Type.FLOAT -> encodeFloat(value.toFloat())

                AvroSchema.Type.DOUBLE -> encodeDouble(value.toDouble())

                else -> throw unsupportedWriterTypeError()
            }
        }
    }

    private fun AvroEncoder.unsupportedWriterTypeError() =
        unsupportedWriterTypeError(
            AvroSchema.Type.BYTES,
            AvroSchema.Type.FIXED,
            AvroSchema.Type.STRING,
            AvroSchema.Type.INT,
            AvroSchema.Type.LONG,
            AvroSchema.Type.FLOAT,
            AvroSchema.Type.DOUBLE
        )

    override fun serializeGeneric(
        encoder: Encoder,
        value: BigDecimal,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeAvro(decoder: AvroDecoder): BigDecimal {
        with(decoder) {
            return decodeResolvingAny({
                UnexpectedDecodeSchemaError(
                    "BigDecimal",
                    AvroSchema.Type.STRING,
                    AvroSchema.Type.BYTES,
                    AvroSchema.Type.FIXED
                )
            }) { schema ->
                when (schema.type) {
                    AvroSchema.Type.STRING -> {
                        AnyValueDecoder { decoder.decodeString().toBigDecimal() }
                    }

                    AvroSchema.Type.BYTES ->
                        when (schema.knownLogicalTypeName) {
                            DECIMAL_LOGICAL_TYPE_NAME -> AnyValueDecoder { decimalFromBytes(decoder.decodeBytes(), schema) }
                            BIG_DECIMAL_LOGICAL_TYPE_NAME -> AnyValueDecoder { bigDecimalFromBytes(decoder.decodeBytes()) }
                            else -> null
                        }

                    AvroSchema.Type.FIXED ->
                        when (schema.knownLogicalTypeName) {
                            DECIMAL_LOGICAL_TYPE_NAME -> AnyValueDecoder { decimalFromBytes(decoder.decodeFixed(), schema) }
                            else -> null
                        }

                    else -> null
                }
            }
        }
    }

    override fun deserializeGeneric(decoder: Decoder): BigDecimal {
        return decoder.decodeString().toBigDecimal()
    }
}

// The bytes of the `decimal` and `big-decimal` logical types, as Apache Avro 1.12's `Conversions.DecimalConversion` and
// `Conversions.BigDecimalConversion` write and read them, in plain `java.math`: no Apache schema or logical type object is
// needed per value, only the `scale` and `precision` props of the writer schema (valid, as the callers checked
// `knownLogicalTypeName`).

/** The `scale` of a valid `decimal` schema: 0 when absent, as Apache reads it. */
private val AvroSchema.decimalScale: Int
    get() = ((this as ResolvedSchema).props["scale"] as? JsonPrimitive)?.int ?: 0

private val AvroSchema.decimalPrecision: Int
    get() = ((this as ResolvedSchema).props["precision"] as JsonPrimitive).int

/** `DecimalConversion.toBytes`: the two's-complement unscaled value, after [validateDecimal]. */
private fun BigDecimal.toDecimalBytes(schema: AvroSchema): ByteArray = validateDecimal(schema).unscaledValue().toByteArray()

/** `DecimalConversion.toFixed`: the unscaled value, sign-extended on the left to the fixed size, after [validateDecimal]. */
private fun BigDecimal.toDecimalFixed(schema: AvroSchema): ByteArray {
    val value = validateDecimal(schema)
    val fillByte = if (value.signum() < 0) 0xFF.toByte() else 0x00
    val unscaled = value.unscaledValue().toByteArray()
    val bytes = ByteArray(schema.fixedSize)
    val offset = bytes.size - unscaled.size
    bytes.fill(fillByte, 0, offset)
    unscaled.copyInto(bytes, offset)
    return bytes
}

/** `DecimalConversion.fromBytes` and `fromFixed`. */
private fun decimalFromBytes(
    bytes: ByteArray,
    schema: AvroSchema,
): BigDecimal = BigDecimal(BigInteger(bytes), schema.decimalScale)

/** `DecimalConversion.validate`: the value at the schema's scale (without rounding), within its precision. */
private fun BigDecimal.validateDecimal(schema: AvroSchema): BigDecimal {
    val scale = schema.decimalScale
    val valueScale = scale()
    var value = this
    var scaleAdjusted = false
    if (valueScale != scale) {
        try {
            value = setScale(scale, RoundingMode.UNNECESSARY)
            scaleAdjusted = true
        } catch (_: ArithmeticException) {
            throw AvroTypeException("Cannot encode decimal with scale $valueScale as scale $scale without rounding")
        }
    }
    val precision = schema.decimalPrecision
    val valuePrecision = value.precision()
    if (valuePrecision > precision) {
        if (scaleAdjusted) {
            throw AvroTypeException(
                "Cannot encode decimal with precision $valuePrecision as max precision $precision. This is after safely adjusting scale from $valueScale to required $scale"
            )
        } else {
            throw AvroTypeException("Cannot encode decimal with precision $valuePrecision as max precision $precision")
        }
    }
    return value
}

/** `BigDecimalConversion.toBytes`: the unscaled value as length-prefixed bytes, then the scale, both in Avro's binary encoding. */
private fun BigDecimal.toBigDecimalBytes(): ByteArray {
    val buffer = Buffer()
    val encoder = KotlinxIoEncoder(buffer)
    encoder.writeBytes(unscaledValue().toByteArray())
    encoder.writeInt(scale())
    return buffer.readByteArray()
}

/** `BigDecimalConversion.fromBytes`: an empty unscaled value is `BigDecimal(null, …)`, which throws, as in Apache. */
private fun bigDecimalFromBytes(bytes: ByteArray): BigDecimal {
    val decoder = KotlinxIoDecoder(Buffer(bytes))
    val unscaled = decoder.readBytes()
    val unscaledValue: BigInteger? = if (unscaled.isNotEmpty()) BigInteger(unscaled) else null
    val scale = decoder.readInt()
    return BigDecimal(unscaledValue, scale)
}