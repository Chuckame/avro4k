package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryReader
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryWriter
import com.github.avrokotlin.avro4k.internal.codec.ValidatingAvroBinaryReader
import com.github.avrokotlin.avro4k.internal.codec.ValidatingAvroBinaryWriter
import com.github.avrokotlin.avro4k.internal.decoder.AvroValueDecoder
import com.github.avrokotlin.avro4k.internal.encoder.AvroValueEncoder
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

/**
 * Encodes [value] with [binaryWriter], every call checked against [writerSchema] when
 * [com.github.avrokotlin.avro4k.AvroConfiguration.validateSerialization] is enabled (see [ValidatingAvroBinaryWriter]).
 * Disabled, the encoder is used as is: a single branch.
 *
 * Public for the apache-interop module, whose Apache codec entry points encode through it.
 */
@InternalAvro4kApi
public fun <T> Avro.encodeWithValidation(
    writerSchema: AvroSchema,
    serializer: SerializationStrategy<T>,
    value: T,
    binaryWriter: AvroBinaryWriter,
) {
    if (configuration.validateSerialization) {
        val validatingEncoder = ValidatingAvroBinaryWriter(binaryWriter, writerSchema)
        encodeWithBinaryWriter(writerSchema, serializer, value, validatingEncoder)
        validatingEncoder.checkDatumComplete()
    } else {
        encodeWithBinaryWriter(writerSchema, serializer, value, binaryWriter)
    }
}

/**
 * Decodes a value with [binaryReader], every call checked against [writerSchema] when
 * [com.github.avrokotlin.avro4k.AvroConfiguration.validateSerialization] is enabled, as [encodeWithValidation] does.
 *
 * The single entry point of every decode, so this is where the end of the input in the middle of a value becomes an
 * [com.github.avrokotlin.avro4k.AvroMalformedDataException], whatever the input: one `try` per decoded value, nothing per
 * read. On the JVM, an Apache decoder's `java.io.EOFException` is the same class.
 */
@InternalAvro4kApi
public fun <T> Avro.decodeWithValidation(
    writerSchema: AvroSchema,
    deserializer: DeserializationStrategy<T>,
    binaryReader: AvroBinaryReader,
): T {
    try {
        if (configuration.validateSerialization) {
            val validatingDecoder = ValidatingAvroBinaryReader(binaryReader, writerSchema)
            val value = decodeWithBinaryReader(writerSchema, deserializer, validatingDecoder)
            validatingDecoder.checkDatumComplete()
            return value
        }
        return decodeWithBinaryReader(writerSchema, deserializer, binaryReader)
    } catch (e: EOFException) {
        throw endOfInputError(e)
    }
}

internal fun <T> Avro.encodeWithBinaryWriter(
    writerSchema: AvroSchema,
    serializer: SerializationStrategy<T>,
    value: T,
    binaryWriter: AvroBinaryWriter,
) {
    AvroValueEncoder(writerSchema, this, binaryWriter)
        .encodeSerializableValue(serializer, value)
}

internal fun <T> Avro.decodeWithBinaryReader(
    writerSchema: AvroSchema,
    deserializer: DeserializationStrategy<T>,
    binaryReader: AvroBinaryReader,
): T {
    return AvroValueDecoder(writerSchema, this, binaryReader)
        .decodeSerializableValue(deserializer)
}