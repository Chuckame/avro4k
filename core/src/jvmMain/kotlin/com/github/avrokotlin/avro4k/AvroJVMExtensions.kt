package com.github.avrokotlin.avro4k

import kotlinx.io.Buffer
import kotlinx.io.UnsafeIoApi
import kotlinx.io.transferFrom
import kotlinx.io.unsafe.UnsafeBufferOperations
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import java.nio.ByteBuffer

/**
 * Decode the value [T] from the given ByteBuffer, helpful when expecting zero-copy decoding from the jvm.
 * The [input] MUST follow the [writerSchema] to ensure the bytes to be readable, or it may end up to unexpected results or an exception.
 *
 * @param input the ByteBuffer to decode from. The [ByteBuffer.position] will be updated based on the decoded data bytes length.
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
 * The body of [decodeFromByteBuffer], which is inline. Decodes from the [input]'s remaining bytes and then advances its
 * position by exactly the number of bytes decoded.
 *
 * A heap buffer is read in place, without copying its content; any other buffer is copied first.
 */
@InternalAvro4kApi
public fun <T> Avro.decodeFromByteBufferInternal(
    writerSchema: AvroSchema,
    deserializer: DeserializationStrategy<T>,
    input: ByteBuffer,
): T {
    val source = input.toKotlinxIoBuffer()
    val size = source.size
    val result = decodeFromSource(writerSchema, deserializer, source)
    input.position(input.position() + (size - source.size).toInt())
    return result
}

@OptIn(UnsafeIoApi::class)
private fun ByteBuffer.toKotlinxIoBuffer(): Buffer {
    val buffer = Buffer()
    if (!hasRemaining()) {
        return buffer
    }
    if (hasArray()) {
        // Wraps the array without copying it: the decoder only reads from its source, and copies every value out
        UnsafeBufferOperations.moveToTail(buffer, array(), arrayOffset() + position(), arrayOffset() + limit())
    } else {
        buffer.transferFrom(duplicate())
    }
    return buffer
}