package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryDecoder
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryEncoder
import com.github.avrokotlin.avro4k.internal.codec.ValidatingAvroBinaryDecoder
import com.github.avrokotlin.avro4k.internal.codec.ValidatingAvroBinaryEncoder
import com.github.avrokotlin.avro4k.internal.decoder.direct.AvroValueDirectDecoder
import com.github.avrokotlin.avro4k.internal.encoder.direct.AvroValueDirectEncoder
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

/**
 * Encodes [value] with [binaryEncoder], every call checked against [writerSchema] when
 * [com.github.avrokotlin.avro4k.AvroConfiguration.validateSerialization] is enabled (see [ValidatingAvroBinaryEncoder]).
 * Disabled, the encoder is used as is: a single branch.
 *
 * Public for the apache-interop module, whose Apache codec entry points encode through it.
 */
@InternalAvro4kApi
public fun <T> Avro.encodeWithValidation(
    writerSchema: AvroSchema,
    serializer: SerializationStrategy<T>,
    value: T,
    binaryEncoder: AvroBinaryEncoder,
) {
    if (configuration.validateSerialization) {
        val validatingEncoder = ValidatingAvroBinaryEncoder(binaryEncoder, writerSchema)
        encodeWithBinaryEncoder(writerSchema, serializer, value, validatingEncoder)
        validatingEncoder.checkDatumComplete()
    } else {
        encodeWithBinaryEncoder(writerSchema, serializer, value, binaryEncoder)
    }
}

/**
 * Decodes a value with [binaryDecoder], every call checked against [writerSchema] when
 * [com.github.avrokotlin.avro4k.AvroConfiguration.validateSerialization] is enabled, as [encodeWithValidation] does.
 */
@InternalAvro4kApi
public fun <T> Avro.decodeWithValidation(
    writerSchema: AvroSchema,
    deserializer: DeserializationStrategy<T>,
    binaryDecoder: AvroBinaryDecoder,
): T {
    if (configuration.validateSerialization) {
        val validatingDecoder = ValidatingAvroBinaryDecoder(binaryDecoder, writerSchema)
        val value = decodeWithBinaryDecoder(writerSchema, deserializer, validatingDecoder)
        validatingDecoder.checkDatumComplete()
        return value
    }
    return decodeWithBinaryDecoder(writerSchema, deserializer, binaryDecoder)
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