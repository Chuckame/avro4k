package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.EOFException
import com.github.avrokotlin.avro4k.internal.codec.ByteArrayEncoder
import com.github.avrokotlin.avro4k.internal.codec.ByteBufferDecoder
import com.github.avrokotlin.avro4k.internal.codec.InputStreamDecoder
import com.github.avrokotlin.avro4k.internal.decodeWithValidation
import com.github.avrokotlin.avro4k.internal.encodeWithValidation
import com.github.avrokotlin.avro4k.internal.endOfInputError
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import java.io.InputStream
import java.io.OutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * Encodes the given [value] as binary Avro into the given [outputStream], respecting the [writerSchema].
 *
 * For any custom encoding or conversion of the [value] or its inner type, you may need to register a custom [kotlinx.serialization.KSerializer] in [Avro.serializersModule].
 *
 * Every byte of the value is handed to the [outputStream] before this function returns. The stream is neither flushed
 * nor closed: wrapping it in a [java.io.BufferedOutputStream] is not needed, and closing it is up to the caller. Several
 * values can be written one after another to the same stream.
 *
 * The bytes are handed to the stream as the value is encoded, without holding the whole value in memory. If the
 * encoding fails part-way, the stream may already hold the beginning of the value.
 *
 * @param writerSchema the schema to use for encoding the value which must be compatible with the given [serializer].
 * @param serializer the serialization strategy to use for encoding the value. You may prefer the other extension methods without the serializer parameter for convenience.
 * @param value the value to encode.
 * @param outputStream the stream to write the encoded value to.
 *
 * @see Avro.encodeToByteArray
 * @see Avro.decodeFromStream
 */
public fun <T> Avro.encodeToStream(
    writerSchema: AvroSchema,
    serializer: SerializationStrategy<T>,
    value: T,
    outputStream: OutputStream,
) {
    val write = outputStream.chunkWriter()
    val encoder = ByteArrayEncoder(write)
    encodeWithValidation(writerSchema, serializer, value, encoder)
    encoder.writeChunksTo(write)
}

/**
 * Encodes the given [value] as binary Avro into the given [outputStream], using the [value]'s schema as the writer schema.
 *
 * Every byte is handed to the [outputStream] before this function returns; the stream is neither flushed nor closed.
 *
 * @see Avro.encodeToStream
 * @see Avro.schema
 */
public inline fun <reified T> Avro.encodeToStream(
    value: T,
    outputStream: OutputStream,
) {
    val serializer = serializersModule.serializer<T>()
    encodeToStream(schema(serializer), serializer, value, outputStream)
}

/**
 * Encodes the given [value] as binary Avro into the given [outputStream], respecting the [writerSchema].
 *
 * Every byte is handed to the [outputStream] before this function returns; the stream is neither flushed nor closed.
 *
 * @see Avro.encodeToStream
 */
public inline fun <reified T> Avro.encodeToStream(
    writerSchema: AvroSchema,
    value: T,
    outputStream: OutputStream,
) {
    val serializer = serializersModule.serializer<T>()
    encodeToStream(writerSchema, serializer, value, outputStream)
}

/**
 * Decodes the single value held by the [inputStream], assuming the data is respecting the given [writerSchema].
 * If during the decoding, the written data does not match the [writerSchema], you may end up to unexpected results or an exception.
 *
 * For any custom encoding or conversion of type [T] or its inner type, you may need to register a custom [kotlinx.serialization.KSerializer] in [Avro.serializersModule].
 *
 * The stream is read as the value is decoded, never whole in memory at once, and must end right after the value: like
 * kotlinx-serialization-json's `decodeFromStream`, it holds exactly one value. The stream is neither rewound nor
 * closed. Several values written one after another are read from a [ByteBuffer] holding their bytes with
 * [decodeFromByteBuffer], whose position moves past each value.
 *
 * @param writerSchema the schema to use for decoding the value.
 * @param deserializer the deserialization strategy to use for decoding the value. You may prefer the other extension methods without the deserializer parameter for convenience.
 * @param inputStream the stream to read the encoded value from.
 * @return the decoded value of type [T].
 *
 * @throws AvroMalformedDataException if the stream ends in the middle of the value, or holds more bytes after it.
 * @throws java.io.IOException if reading the stream fails, unchanged.
 *
 * @see Avro.decodeFromByteArray
 * @see Avro.encodeToStream
 */
public fun <T> Avro.decodeFromStream(
    writerSchema: AvroSchema,
    deserializer: DeserializationStrategy<T>,
    inputStream: InputStream,
): T {
    val decoder = InputStreamDecoder(inputStream)
    val result = decodeWithValidation(writerSchema, deserializer, decoder)
    decoder.requireEnd()
    return result
}

/**
 * Decodes the single value held by the [inputStream], assuming the data is respecting the schema inferred from [T] using [Avro.schema].
 *
 * The stream must end right after the value; it is neither rewound nor closed.
 *
 * @see Avro.decodeFromStream
 * @see Avro.schema
 */
public inline fun <reified T> Avro.decodeFromStream(inputStream: InputStream): T {
    val serializer = serializersModule.serializer<T>()
    return decodeFromStream(schema(serializer.descriptor), serializer, inputStream)
}

/**
 * Decodes the single value held by the [inputStream], assuming the data is respecting the given [writerSchema].
 *
 * The stream must end right after the value; it is neither rewound nor closed.
 *
 * @see Avro.decodeFromStream
 */
public inline fun <reified T> Avro.decodeFromStream(
    writerSchema: AvroSchema,
    inputStream: InputStream,
): T {
    val serializer = serializersModule.serializer<T>()
    return decodeFromStream(writerSchema, serializer, inputStream)
}

/**
 * Encodes the given [value] as a single Avro object into the given [outputStream]: the header naming the [writerSchema],
 * then the value.
 *
 * Every byte is handed to the [outputStream] before this function returns; the stream is neither flushed nor closed.
 * If the encoding fails part-way, the stream may already hold the header and the beginning of the value.
 *
 * @see Avro.encodeToStream
 */
@ExperimentalAvro4kApi
public fun <T> AvroSingleObject.encodeToStream(
    writerSchema: AvroSchema,
    serializer: SerializationStrategy<T>,
    value: T,
    outputStream: OutputStream,
) {
    val write = outputStream.chunkWriter()
    val encoder = ByteArrayEncoder(write)
    encodeWithHeader(writerSchema, serializer, value, encoder)
    encoder.writeChunksTo(write)
}

/** Writes each chunk to the stream as the encoder fills it, so that a large value is never held whole. */
private fun OutputStream.chunkWriter(): (ByteArray, Int) -> Unit = { chunk, length -> write(chunk, 0, length) }

/**
 * Decodes the single Avro object held by the [inputStream], with the writer schema its header names.
 *
 * The stream must end right after the value; it is neither rewound nor closed.
 *
 * @throws AvroMalformedDataException if the stream ends in the middle of the object, or holds more bytes after it.
 *
 * @see Avro.decodeFromStream
 */
@ExperimentalAvro4kApi
public fun <T> AvroSingleObject.decodeFromStream(
    deserializer: DeserializationStrategy<T>,
    inputStream: InputStream,
): T {
    val decoder = InputStreamDecoder(inputStream)
    val result = decodeWithHeader(deserializer, decoder)
    decoder.requireEnd()
    return result
}

/**
 * Decode the value [T] from the given ByteBuffer, helpful when expecting zero-copy decoding from the jvm.
 * The [input] MUST follow the [writerSchema] to ensure the bytes to be readable, or it may end up to unexpected results or an exception.
 *
 * @param input the ByteBuffer to decode from, heap or direct. Only the value's bytes are read: its [ByteBuffer.position] ends right after
 *              them, or is left unchanged when decoding fails.
 * @param deserializer the deserialization strategy to use for decoding the value. It is inferred from [T] based on [Avro.serializersModule] if not provided.
 * @param writerSchema the schema to use for decoding the value. It is highly recommended to set this schema if the [T] type is not stable across serializations to ensure the
 *                     bytes to be readable. It is inferred from [T] using [Avro.schema] if not provided.
 */
@ExperimentalAvro4kApi
public inline fun <reified T> Avro.decodeFromByteBuffer(
    input: ByteBuffer,
    deserializer: DeserializationStrategy<T> = serializersModule.serializer<T>(),
    writerSchema: AvroSchema = schema(deserializer.descriptor),
): T {
    return decodeFromByteBufferInternal(writerSchema, deserializer, input)
}

/**
 * The body of [decodeFromByteBuffer], which is inline. Decodes straight from the [input],
 * heap or direct: only the value's bytes are read, none is copied up front, and the position ends right after the
 * value. When decoding fails, the position is restored to where it was.
 */
@InternalAvro4kApi
public fun <T> Avro.decodeFromByteBufferInternal(
    writerSchema: AvroSchema,
    deserializer: DeserializationStrategy<T>,
    input: ByteBuffer,
): T {
    val start = input.position()
    try {
        return decodeWithValidation(writerSchema, deserializer, ByteBufferDecoder(input))
    } catch (e: BufferUnderflowException) {
        input.position(start)
        throw endOfInputError(EOFException("the ByteBuffer ended in the middle of a value").apply { initCause(e) })
    } catch (e: Throwable) {
        input.position(start)
        throw e
    }
}