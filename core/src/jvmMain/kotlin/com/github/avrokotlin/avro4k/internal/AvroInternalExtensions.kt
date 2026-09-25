package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.internal.codec.ApacheDecoderAdapter
import com.github.avrokotlin.avro4k.internal.codec.ApacheEncoderAdapter
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryDecoder
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryEncoder
import com.github.avrokotlin.avro4k.internal.codec.validating
import com.github.avrokotlin.avro4k.internal.decoder.direct.AvroValueDirectDecoder
import com.github.avrokotlin.avro4k.internal.encoder.direct.AvroValueDirectEncoder
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import org.apache.avro.Schema
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory

/**
 * Encodes [value] into an Apache [binaryEncoder] handed in from outside (Confluent's serializers, the object container
 * files). Apache-typed, as its callers live in Apache's world: [writerSchema] is converted once per call, through the
 * cached converter (identity first, then equality, so a registry schema re-parsed per message is no conversion either).
 */
@InternalAvro4kApi
public fun <T> Avro.encodeWithApacheEncoder(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
    binaryEncoder: org.apache.avro.io.Encoder,
) {
    encodeWithApacheEncoder(writerSchema, apacheToAvro4k(writerSchema), serializer, value, binaryEncoder)
}

/** [encodeWithApacheEncoder] with both forms of the writer schema, for a caller that converted it once for many calls. */
internal fun <T> Avro.encodeWithApacheEncoder(
    apacheWriterSchema: Schema,
    writerSchema: AvroSchema,
    serializer: SerializationStrategy<T>,
    value: T,
    binaryEncoder: org.apache.avro.io.Encoder,
) {
    val apacheEncoder =
        if (configuration.validateSerialization) {
            EncoderFactory.get().validatingEncoder(apacheWriterSchema, binaryEncoder)
        } else {
            binaryEncoder
        }
    encodeWithBinaryEncoder(writerSchema, serializer, value, ApacheEncoderAdapter(apacheEncoder))
}

/** Decodes a value from an Apache [binaryDecoder] handed in from outside. Converts like [encodeWithApacheEncoder]. */
@InternalAvro4kApi
public fun <T> Avro.decodeWithApacheDecoder(
    writerSchema: Schema,
    deserializer: DeserializationStrategy<T>,
    binaryDecoder: org.apache.avro.io.Decoder,
): T = decodeWithApacheDecoder(writerSchema, apacheToAvro4k(writerSchema), deserializer, binaryDecoder)

/** [decodeWithApacheDecoder] with both forms of the writer schema, for a caller that converted it once for many calls. */
internal fun <T> Avro.decodeWithApacheDecoder(
    apacheWriterSchema: Schema,
    writerSchema: AvroSchema,
    deserializer: DeserializationStrategy<T>,
    binaryDecoder: org.apache.avro.io.Decoder,
): T {
    val apacheDecoder =
        if (configuration.validateSerialization) {
            DecoderFactory.get().validatingDecoder(apacheWriterSchema, binaryDecoder)
        } else {
            binaryDecoder
        }
    return decodeWithBinaryDecoder(writerSchema, deserializer, ApacheDecoderAdapter(apacheDecoder))
}

/**
 * Encodes [value] with one of avro4k's own codecs, validated against [writerSchema] when
 * [com.github.avrokotlin.avro4k.AvroConfiguration.validateSerialization] is enabled. The validation runs on Apache's
 * validating encoder, so it converts [writerSchema] to Apache's model, through the cached converter.
 */
internal fun <T> Avro.encodeWithValidation(
    writerSchema: AvroSchema,
    serializer: SerializationStrategy<T>,
    value: T,
    binaryEncoder: AvroBinaryEncoder,
) {
    val encoder =
        if (configuration.validateSerialization) {
            binaryEncoder.validating(avro4kToApache(writerSchema))
        } else {
            binaryEncoder
        }
    encodeWithBinaryEncoder(writerSchema, serializer, value, encoder)
}

/**
 * Decodes a value with one of avro4k's own codecs, validated against [writerSchema] when
 * [com.github.avrokotlin.avro4k.AvroConfiguration.validateSerialization] is enabled, as [encodeWithValidation] does.
 */
internal fun <T> Avro.decodeWithValidation(
    writerSchema: AvroSchema,
    deserializer: DeserializationStrategy<T>,
    binaryDecoder: AvroBinaryDecoder,
): T {
    val decoder =
        if (configuration.validateSerialization) {
            binaryDecoder.validating(avro4kToApache(writerSchema))
        } else {
            binaryDecoder
        }
    return decodeWithBinaryDecoder(writerSchema, deserializer, decoder)
}

private fun <T> Avro.encodeWithBinaryEncoder(
    writerSchema: AvroSchema,
    serializer: SerializationStrategy<T>,
    value: T,
    binaryEncoder: AvroBinaryEncoder,
) {
    AvroValueDirectEncoder(writerSchema, this, binaryEncoder)
        .encodeSerializableValue(serializer, value)
}

private fun <T> Avro.decodeWithBinaryDecoder(
    writerSchema: AvroSchema,
    deserializer: DeserializationStrategy<T>,
    binaryDecoder: AvroBinaryDecoder,
): T {
    return AvroValueDirectDecoder(writerSchema, this, binaryDecoder)
        .decodeSerializableValue(deserializer)
}