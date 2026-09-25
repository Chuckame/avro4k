package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryDecoder
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryEncoder
import com.github.avrokotlin.avro4k.internal.decoder.direct.AvroValueDirectDecoder
import com.github.avrokotlin.avro4k.internal.encoder.direct.AvroValueDirectEncoder
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

/**
 * Encodes [value] with one of avro4k's own codecs, validated against [writerSchema] when
 * [com.github.avrokotlin.avro4k.AvroConfiguration.validateSerialization] is enabled and the platform can validate (see
 * [validatingAgainst]).
 */
internal fun <T> Avro.encodeWithValidation(
    writerSchema: AvroSchema,
    serializer: SerializationStrategy<T>,
    value: T,
    binaryEncoder: AvroBinaryEncoder,
) {
    val encoder =
        if (configuration.validateSerialization) {
            binaryEncoder.validatingAgainst(writerSchema)
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
            binaryDecoder.validatingAgainst(writerSchema)
        } else {
            binaryDecoder
        }
    return decodeWithBinaryDecoder(writerSchema, deserializer, decoder)
}

internal fun <T> Avro.encodeWithBinaryEncoder(
    writerSchema: AvroSchema,
    serializer: SerializationStrategy<T>,
    value: T,
    binaryEncoder: AvroBinaryEncoder,
) {
    AvroValueDirectEncoder(writerSchema, this, binaryEncoder)
        .encodeSerializableValue(serializer, value)
}

internal fun <T> Avro.decodeWithBinaryDecoder(
    writerSchema: AvroSchema,
    deserializer: DeserializationStrategy<T>,
    binaryDecoder: AvroBinaryDecoder,
): T {
    return AvroValueDirectDecoder(writerSchema, this, binaryDecoder)
        .decodeSerializableValue(deserializer)
}