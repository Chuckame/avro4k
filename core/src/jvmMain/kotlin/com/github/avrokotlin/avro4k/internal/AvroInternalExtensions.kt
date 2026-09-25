package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.Avro
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

@InternalAvro4kApi
public fun <T> Avro.encodeWithApacheEncoder(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
    binaryEncoder: org.apache.avro.io.Encoder,
) {
    val apacheEncoder =
        if (configuration.validateSerialization) {
            EncoderFactory.get().validatingEncoder(writerSchema, binaryEncoder)
        } else {
            binaryEncoder
        }
    encodeWithBinaryEncoder(writerSchema, serializer, value, ApacheEncoderAdapter(apacheEncoder))
}

@InternalAvro4kApi
public fun <T> Avro.decodeWithApacheDecoder(
    writerSchema: Schema,
    deserializer: DeserializationStrategy<T>,
    binaryDecoder: org.apache.avro.io.Decoder,
): T {
    val apacheDecoder =
        if (configuration.validateSerialization) {
            DecoderFactory.get().validatingDecoder(writerSchema, binaryDecoder)
        } else {
            binaryDecoder
        }
    return decodeWithBinaryDecoder(writerSchema, deserializer, ApacheDecoderAdapter(apacheDecoder))
}

/**
 * Encodes [value] with one of avro4k's own codecs, validated against [writerSchema] when
 * [com.github.avrokotlin.avro4k.AvroConfiguration.validateSerialization] is enabled.
 */
internal fun <T> Avro.encodeWithValidation(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
    binaryEncoder: AvroBinaryEncoder,
) {
    val encoder =
        if (configuration.validateSerialization) {
            binaryEncoder.validating(writerSchema)
        } else {
            binaryEncoder
        }
    encodeWithBinaryEncoder(writerSchema, serializer, value, encoder)
}

/**
 * Decodes a value with one of avro4k's own codecs, validated against [writerSchema] when
 * [com.github.avrokotlin.avro4k.AvroConfiguration.validateSerialization] is enabled.
 */
internal fun <T> Avro.decodeWithValidation(
    writerSchema: Schema,
    deserializer: DeserializationStrategy<T>,
    binaryDecoder: AvroBinaryDecoder,
): T {
    val decoder =
        if (configuration.validateSerialization) {
            binaryDecoder.validating(writerSchema)
        } else {
            binaryDecoder
        }
    return decodeWithBinaryDecoder(writerSchema, deserializer, decoder)
}

/** Converts the Apache [writerSchema] once per call, like [decodeWithBinaryDecoder]. */
private fun <T> Avro.encodeWithBinaryEncoder(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
    binaryEncoder: AvroBinaryEncoder,
) {
    AvroValueDirectEncoder(apacheToAvro4k(writerSchema), this, binaryEncoder)
        .encodeSerializableValue(serializer, value)
}

/**
 * The entry points still take an Apache [writerSchema] until the public API is `AvroSchema`-typed: it is converted here,
 * once per call, through the identity-cached converter (a schema from `Avro.schema()` is already paired, so that is a
 * hit). The decoders then only read the [com.github.avrokotlin.avro4k.AvroSchema].
 */
private fun <T> Avro.decodeWithBinaryDecoder(
    writerSchema: Schema,
    deserializer: DeserializationStrategy<T>,
    binaryDecoder: AvroBinaryDecoder,
): T {
    return AvroValueDirectDecoder(apacheToAvro4k(writerSchema), this, binaryDecoder)
        .decodeSerializableValue(deserializer)
}