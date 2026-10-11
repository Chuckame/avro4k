package com.github.avrokotlin.avro4k.serializer

import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.AvroSerializationException
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.ensureFixedSize
import com.github.avrokotlin.avro4k.fullNameOrAliasMismatchError
import com.github.avrokotlin.avro4k.internal.Buffer
import com.github.avrokotlin.avro4k.internal.UnexpectedDecodeSchemaError
import com.github.avrokotlin.avro4k.internal.schema.fixedSize
import com.github.avrokotlin.avro4k.internal.schema.isFullNameOrAliasMatch
import com.github.avrokotlin.avro4k.internal.schema.isUnion
import com.github.avrokotlin.avro4k.internal.schema.knownLogicalTypeName
import com.github.avrokotlin.avro4k.trySelectLogicalTypeFromUnion
import com.github.avrokotlin.avro4k.trySelectNamedSchema
import com.github.avrokotlin.avro4k.trySelectTypeNameFromUnion
import com.github.avrokotlin.avro4k.unsupportedWriterTypeError
import kotlinx.io.readIntLe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonPrimitive
import kotlin.jvm.JvmStatic

/**
 * Represents a duration in months, days and milliseconds.
 *
 * This is the exact representation of the Avro `duration` logical type.
 *
 * [avro spec](https://avro.apache.org/docs/1.12.1/specification/#duration)
 */
@SerialName("time.Duration")
@Serializable(with = AvroDurationSerializer::class)
@ExperimentalAvro4kApi
public data class AvroDuration(
    val months: UInt,
    val days: UInt,
    val millis: UInt,
) {
    override fun toString(): String {
        if (months == 0u && days == 0u && millis == 0u) {
            return "PT0S"
        }
        return buildString {
            append("P")
            if (months != 0u) {
                append("${months}M")
            }
            if (days != 0u) {
                append("${days}D")
            }
            if (millis != 0u) {
                append("T")
                append(millis / 1000u)
                val millisPart = millis % 1000u
                if (millisPart != 0u) {
                    append('.')
                    append(millisPart)
                }
                append("S")
            }
        }
    }

    public companion object {
        @JvmStatic
        private fun part(
            name: Char,
            digitsRegex: String = "",
        ): String {
            val digitsPart = if (digitsRegex.isNotEmpty()) "(?:[.,]($digitsRegex))?" else ""
            return "(?:\\+?([0-9]+)$digitsPart$name)?"
        }

        @JvmStatic
        private val PATTERN: Regex =
            buildString {
                append("P")
                append(part('Y'))
                append(part('M'))
                append(part('W'))
                append(part('D'))
                append("(?:T")
                append(part('H'))
                append(part('M'))
                append(part('S', digitsRegex = "[0-9]{0,3}"))
                append(")?")
            }.toRegex(RegexOption.IGNORE_CASE)

        @JvmStatic
        @Throws(AvroDurationParseException::class)
        public fun tryParse(value: String): AvroDuration? {
            val match = PATTERN.matchEntire(value) ?: return null
            val (years, months, weeks, days, hours, minutes, seconds, millis) = match.destructured
            return AvroDuration(
                months = years * 12u + months.toUIntOrZero(),
                days = weeks * 7u + days.toUIntOrZero(),
                millis = hours * 60u * 60u * 1000u + minutes * 60u * 1000u + seconds * 1000u + millis.toUIntOrZero()
            )
        }

        private operator fun String.times(other: UInt): UInt {
            return toUIntOrNull()?.times(other) ?: 0u
        }

        private fun String.toUIntOrZero(): UInt {
            return toUIntOrNull() ?: 0u
        }

        @JvmStatic
        public fun parse(value: String): AvroDuration {
            return tryParse(value) ?: throw AvroDurationParseException(value)
        }
    }
}

@ExperimentalAvro4kApi
public class AvroDurationParseException(value: String) : AvroSerializationException("Unable to parse duration: $value")

@InternalAvro4kApi
// A literal rather than `AvroDuration::class.qualifiedName`: that property does not exist on Kotlin/JS, and a serial
// name must be the same on every platform.
public object AvroDurationSerializer : AvroSerializer<AvroDuration>("com.github.avrokotlin.avro4k.serializer.AvroDuration") {
    internal const val LOGICAL_TYPE_NAME = "duration"
    private const val DURATION_BYTES = 12
    private const val DEFAULT_DURATION_FULL_NAME = "time.Duration"

    /** A new instance on each read: a schema node is never shared by two generated schemas. */
    internal val DURATION_SCHEMA: AvroSchema
        get() =
            AvroSchema.FixedSchema(
                name = Name(DEFAULT_DURATION_FULL_NAME, null),
                size = DURATION_BYTES,
                doc = "A 12-byte byte array encoding a duration in months, days and milliseconds.",
                props = mapOf("logicalType" to JsonPrimitive(LOGICAL_TYPE_NAME))
            )

    override fun getSchema(context: SchemaSupplierContext): AvroSchema {
        return context.inlinedElements.firstNotNullOfOrNull {
            it.stringable?.createSchema()
        } ?: DURATION_SCHEMA
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: AvroDuration,
    ) {
        with(encoder) {
            if (currentWriterSchema.isUnion) {
                trySelectNamedSchema(DEFAULT_DURATION_FULL_NAME) ||
                    trySelectLogicalTypeFromUnion(LOGICAL_TYPE_NAME, AvroSchema.Type.FIXED) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                    throw unsupportedWriterTypeError(AvroSchema.Type.FIXED, AvroSchema.Type.STRING)
            }
            when (currentWriterSchema.type) {
                AvroSchema.Type.FIXED ->
                    if (currentWriterSchema.knownLogicalTypeName == LOGICAL_TYPE_NAME || currentWriterSchema.isFullNameOrAliasMatch(DEFAULT_DURATION_FULL_NAME, ::emptySet)) {
                        encodeFixed(ensureFixedSize(encodeDuration(value)))
                    } else {
                        throw fullNameOrAliasMismatchError(DEFAULT_DURATION_FULL_NAME, emptySet())
                    }

                AvroSchema.Type.STRING -> encodeString(value.toString())

                else -> throw unsupportedWriterTypeError(AvroSchema.Type.FIXED, AvroSchema.Type.STRING)
            }
        }
    }

    override fun deserializeAvro(decoder: AvroDecoder): AvroDuration {
        return with(decoder) {
            val schema = currentWriterSchema
            when (schema.type) {
                AvroSchema.Type.FIXED -> {
                    if (schema.knownLogicalTypeName == LOGICAL_TYPE_NAME && schema.fixedSize == DURATION_BYTES) {
                        decodeDuration(decodeFixed())
                    } else {
                        throw UnexpectedDecodeSchemaError(descriptor.serialName, AvroSchema.Type.FIXED, AvroSchema.Type.STRING)
                    }
                }

                AvroSchema.Type.STRING -> AvroDuration.parse(decodeString())

                else -> throw AvroSchemaMismatchException("Expected the duration logical type to be of type fixed or string, but had ${schema.type}")
            }
        }
    }

    /**
     * Three unsigned 32-bit integers, each little-endian: months, days, then milliseconds (the spec's layout).
     *
     * Written by hand into the 12-byte array rather than through a kotlinx-io `Buffer`: on Kotlin/Native, kotlinx-io does
     * not pool its segments, so a `Buffer` would allocate an 8 KiB segment per encoded duration, then copy the 12 bytes
     * out of it.
     */
    private fun encodeDuration(value: AvroDuration): ByteArray {
        val bytes = ByteArray(DURATION_BYTES)
        bytes.putIntLe(0, value.months.toInt())
        bytes.putIntLe(4, value.days.toInt())
        bytes.putIntLe(8, value.millis.toInt())
        return bytes
    }

    /** The reverse of [encodeDuration]. [bytes] is exactly [DURATION_BYTES] long (checked on the writer schema). */
    private fun decodeDuration(bytes: ByteArray): AvroDuration {
        // Wraps the array without copying it
        val source = Buffer(bytes)
        return AvroDuration(
            months = source.readIntLe().toUInt(),
            days = source.readIntLe().toUInt(),
            millis = source.readIntLe().toUInt()
        )
    }

    private fun ByteArray.putIntLe(
        offset: Int,
        value: Int,
    ) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

    override fun serializeGeneric(
        encoder: Encoder,
        value: AvroDuration,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeGeneric(decoder: Decoder): AvroDuration {
        return AvroDuration.parse(decoder.decodeString())
    }
}