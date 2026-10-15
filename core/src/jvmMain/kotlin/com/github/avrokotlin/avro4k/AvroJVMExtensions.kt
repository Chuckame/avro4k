package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.codec.ByteBufferDecoder
import com.github.avrokotlin.avro4k.internal.decodeWithValidation
import com.github.avrokotlin.avro4k.internal.endOfInputError
import kotlinx.io.EOFException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

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