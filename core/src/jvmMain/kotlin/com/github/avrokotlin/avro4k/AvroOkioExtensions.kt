package com.github.avrokotlin.avro4k

import kotlinx.io.Buffer
import kotlinx.io.InternalIoApi
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource
import org.apache.avro.Schema

@Deprecated("Use kotlinx.io's encodeToSink instead")
@ExperimentalAvro4kApi
public fun <T> Avro.encodeToSink(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
    sink: BufferedSink,
) {
    val kotlinxIoSink = sink.outputStream().asSink().buffered()
    encodeToSink(writerSchema, serializer, value, kotlinxIoSink)
    kotlinxIoSink.flush()
}

@Deprecated("Use kotlinx.io's encodeToSink instead")
@ExperimentalAvro4kApi
public inline fun <reified T> Avro.encodeToSink(
    value: T,
    sink: BufferedSink,
) {
    val serializer = serializersModule.serializer<T>()
    @Suppress("DEPRECATION")
    encodeToSink(schema(serializer), serializer, value, sink)
}

@Deprecated("Use kotlinx.io's encodeToSink instead")
@ExperimentalAvro4kApi
public inline fun <reified T> Avro.encodeToSink(
    writerSchema: Schema,
    value: T,
    sink: BufferedSink,
) {
    val serializer = serializersModule.serializer<T>()
    @Suppress("DEPRECATION")
    encodeToSink(writerSchema, serializer, value, sink)
}

@Deprecated("Use kotlinx.io's decodeFromSource instead")
@ExperimentalAvro4kApi
public fun <T> Avro.decodeFromSource(
    writerSchema: Schema,
    deserializer: DeserializationStrategy<T>,
    source: BufferedSource,
): T {
    // Decoding reads ahead, so it reads from a peek of the source, which is then advanced by exactly the decoded bytes
    val peekSource = CountingPeekSource(source)
    val kotlinxIoSource = peekSource.buffered()
    val result = decodeFromSource(writerSchema, deserializer, kotlinxIoSource)
    @OptIn(InternalIoApi::class)
    source.skip(peekSource.bytesRead - kotlinxIoSource.buffer.size)
    return result
}

private class CountingPeekSource(source: BufferedSource) : RawSource {
    private val peek = source.peek()
    var bytesRead = 0L
        private set

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (!peek.request(1)) {
            return -1
        }
        val bytes = peek.readByteArray(minOf(byteCount, peek.buffer.size))
        sink.write(bytes)
        bytesRead += bytes.size
        return bytes.size.toLong()
    }

    override fun close() {
    }
}

@Deprecated("Use kotlinx.io's decodeFromSource instead")
@ExperimentalAvro4kApi
public inline fun <reified T> Avro.decodeFromSource(source: BufferedSource): T {
    val serializer = serializersModule.serializer<T>()
    @Suppress("DEPRECATION")
    return decodeFromSource(schema(serializer.descriptor), serializer, source)
}

@Deprecated("Use kotlinx.io's decodeFromSource instead")
@ExperimentalAvro4kApi
public inline fun <reified T> Avro.decodeFromSource(
    writerSchema: Schema,
    source: BufferedSource,
): T {
    val serializer = serializersModule.serializer<T>()
    @Suppress("DEPRECATION")
    return decodeFromSource(writerSchema, serializer, source)
}