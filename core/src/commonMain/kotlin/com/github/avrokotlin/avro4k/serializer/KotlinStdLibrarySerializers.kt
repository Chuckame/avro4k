@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.github.avrokotlin.avro4k.serializer

import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.SchemaGenerationFailure
import com.github.avrokotlin.avro4k.internal.UnexpectedDecodeSchemaError
import com.github.avrokotlin.avro4k.internal.schema.fixedSize
import com.github.avrokotlin.avro4k.internal.schema.isUnion
import com.github.avrokotlin.avro4k.internal.schema.knownLogicalTypeName
import com.github.avrokotlin.avro4k.logicalTypeMismatchError
import com.github.avrokotlin.avro4k.trySelectFixedSchemaForSize
import com.github.avrokotlin.avro4k.trySelectLogicalTypeFromUnion
import com.github.avrokotlin.avro4k.trySelectTypeNameFromUnion
import com.github.avrokotlin.avro4k.unsupportedWriterTypeError
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal val KotlinStdLibSerializersModule: SerializersModule
    get() =
        SerializersModule {
            contextual(KotlinUuidSerializer)
            runCatching { contextual(KotlinInstantSerializer) }
        }

/**
 * Serializes a [kotlin.uuid.Uuid] as a string logical type of `uuid`.
 *
 * Note: it does not check if the schema logical type name is `uuid` as it does not make any conversion.
 */
public object KotlinUuidSerializer : AvroSerializer<Uuid>("kotlin.uuid.Uuid") {
    internal const val LOGICAL_TYPE_NAME = "uuid"

    override fun getSchema(context: SchemaSupplierContext): AvroSchema {
        return context.inlinedElements.firstNotNullOfOrNull { element ->
            element.stringable?.createSchema()
                ?: element.fixed?.createSchema(element)
                    ?.withLogicalType(LOGICAL_TYPE_NAME)
                    ?.also {
                        if (it.fixedSize != 16) {
                            throw SchemaGenerationFailure(
                                "Uuid's @${AvroFixed::class.simpleName} must have bytes size of 16. Got ${it.fixedSize}."
                            )
                        }
                    }
        } ?: logicalTypeSchema(AvroSchema.Type.STRING, LOGICAL_TYPE_NAME)
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: Uuid,
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

    override fun serializeGeneric(
        encoder: Encoder,
        value: Uuid,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeAvro(decoder: AvroDecoder): Uuid {
        val schema = decoder.currentWriterSchema
        return when {
            schema.type == AvroSchema.Type.STRING -> Uuid.parse(decoder.decodeString())
            schema.type == AvroSchema.Type.FIXED && schema.fixedSize == 16 -> Uuid.fromByteArray(decoder.decodeBytes())
            else -> throw decoder.UnexpectedDecodeSchemaError("Uuid", AvroSchema.Type.STRING, AvroSchema.Type.FIXED)
        }
    }

    override fun deserializeGeneric(decoder: Decoder): Uuid {
        return Uuid.parse(decoder.decodeString())
    }
}

/**
 * Serializes a [kotlin.time.Instant] as a long logical type of `timestamp-millis` by default.
 *
 * Supports the following Avro logical types:
 * - `timestamp-millis` (default)
 * - `timestamp-micros`
 * - `timestamp-nanos`
 *
 * Also supports string type for ISO-8601 representation.
 */
public object KotlinInstantSerializer : AvroSerializer<Instant>("kotlin.time.Instant") {
    override fun getSchema(context: SchemaSupplierContext): AvroSchema {
        return context.inlinedElements.firstNotNullOfOrNull {
            it.stringable?.createSchema()
        } ?: logicalTypeSchema(AvroSchema.Type.LONG, LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS)
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: Instant,
    ) {
        with(encoder) {
            if (currentWriterSchema.isUnion) {
                trySelectTypeNameFromUnion(AvroSchema.Type.LONG) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                    throw unsupportedWriterTypeError(AvroSchema.Type.LONG, AvroSchema.Type.STRING)
            }
            when (currentWriterSchema.type) {
                AvroSchema.Type.LONG ->
                    when (currentWriterSchema.knownLogicalTypeName) {
                        LOGICAL_TYPE_NAME_TIMESTAMP_NANOS ->
                            encodeLong(value.toExactEpochNanos())

                        LOGICAL_TYPE_NAME_TIMESTAMP_MICROS ->
                            encodeLong(value.toExactEpochMicros())

                        LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS ->
                            encodeLong(value.toExactEpochMillis())

                        else -> throw logicalTypeMismatchError(LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS, AvroSchema.Type.LONG)
                    }

                AvroSchema.Type.STRING -> encodeString(value.toString())

                else -> throw unsupportedWriterTypeError(AvroSchema.Type.LONG, AvroSchema.Type.STRING)
            }
        }
    }

    override fun serializeGeneric(
        encoder: Encoder,
        value: Instant,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeAvro(decoder: AvroDecoder): Instant {
        val schema = decoder.currentWriterSchema
        return when (schema.type) {
            AvroSchema.Type.LONG ->
                when (schema.knownLogicalTypeName) {
                    LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS, null -> Instant.fromEpochMilliseconds(decoder.decodeLong())
                    LOGICAL_TYPE_NAME_TIMESTAMP_MICROS -> Instant.fromEpochMicros(decoder.decodeLong())
                    LOGICAL_TYPE_NAME_TIMESTAMP_NANOS -> Instant.fromEpochNanos(decoder.decodeLong())
                    else -> throw decoder.UnexpectedDecodeSchemaError("Instant", AvroSchema.Type.LONG)
                }

            AvroSchema.Type.STRING -> Instant.parse(decoder.decodeString())

            else -> throw decoder.UnexpectedDecodeSchemaError("Instant", AvroSchema.Type.LONG)
        }
    }

    override fun deserializeGeneric(decoder: Decoder): Instant {
        return Instant.parse(decoder.decodeString())
    }
}