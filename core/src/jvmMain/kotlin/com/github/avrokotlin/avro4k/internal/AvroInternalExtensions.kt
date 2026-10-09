package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.internal.codec.ApacheDecoderAdapter
import com.github.avrokotlin.avro4k.internal.codec.ApacheEncoderAdapter
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import org.apache.avro.Schema

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
    encodeWithApacheEncoder(apacheToAvro4k(writerSchema), serializer, value, binaryEncoder)
}

/** [encodeWithApacheEncoder] with the writer schema already converted, for a caller that converted it once for many calls. */
internal fun <T> Avro.encodeWithApacheEncoder(
    writerSchema: AvroSchema,
    serializer: SerializationStrategy<T>,
    value: T,
    binaryEncoder: org.apache.avro.io.Encoder,
) {
    encodeWithValidation(writerSchema, serializer, value, ApacheEncoderAdapter(binaryEncoder))
}

/** Decodes a value from an Apache [binaryDecoder] handed in from outside. Converts like [encodeWithApacheEncoder]. */
@InternalAvro4kApi
public fun <T> Avro.decodeWithApacheDecoder(
    writerSchema: Schema,
    deserializer: DeserializationStrategy<T>,
    binaryDecoder: org.apache.avro.io.Decoder,
): T = decodeWithApacheDecoder(apacheToAvro4k(writerSchema), deserializer, binaryDecoder)

/** [decodeWithApacheDecoder] with the writer schema already converted, for a caller that converted it once for many calls. */
internal fun <T> Avro.decodeWithApacheDecoder(
    writerSchema: AvroSchema,
    deserializer: DeserializationStrategy<T>,
    binaryDecoder: org.apache.avro.io.Decoder,
): T {
    return decodeWithValidation(writerSchema, deserializer, ApacheDecoderAdapter(binaryDecoder))
}