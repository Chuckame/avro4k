package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.Buffer
import com.github.avrokotlin.avro4k.internal.IdentityFirstCache
import com.github.avrokotlin.avro4k.internal.parsingFingerprintCrc64AvroBytes
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readLongLe
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

/**
 * Single Avro objects are encoded as follows:
 * - A two-byte marker, C3 01, to show that the message is Avro and uses this single-record format (version 1).
 * - The 8-byte little-endian CRC-64-AVRO fingerprint of the object’s schema.
 * - The Avro object encoded using Avro’s binary encoding.
 *
 * [spec](https://avro.apache.org/docs/1.12.1/specification/#single-object-encoding)
 *
 * @param schemaRegistry a function to find a schema by its fingerprint, and returns null when not found. The fingerprint is
 *   the 64-bit CRC-64-AVRO fingerprint of the schema's Parsing Canonical Form, e.g. Apache Avro's
 *   `SchemaNormalization.parsingFingerprint64` on the JVM.
 */
@ExperimentalAvro4kApi
public class AvroSingleObject(
    private val schemaRegistry: (fingerprint: Long) -> AvroSchema?,
    @PublishedApi
    internal val avro: Avro = Avro,
) : BinaryFormat {
    override val serializersModule: SerializersModule
        get() = avro.serializersModule

    /**
     * Memoizes the CRC-64-AVRO fingerprints, as computing one requires canonicalizing the whole schema
     * to its parsing form and hashing it, which is far more expensive than the payload encoding itself
     * for the small records typical of single-object encoding.
     *
     * The cache is weak-keyed so that a long-lived [AvroSingleObject] never keeps an [AvroSchema] alive, and
     * per-instance so that its lifetime is bounded by this format instance rather than by the class loader. It matches
     * the writer schema by identity first, then by equality: a writer schema created per call (e.g. parsed per message)
     * finds the fingerprint of an equal one, and is never remembered by identity (see [IdentityFirstCache]).
     *
     * The cached values are mutable [ByteArray]s, but they never escape: [crc64avro] is private, and its
     * only caller hands the array to [Sink.write], which copies the bytes out and neither retains nor
     * mutates the array. No public API exposes the fingerprint bytes.
     */
    private val fingerprintCache = IdentityFirstCache<AvroSchema, ByteArray>()

    // Byte for byte the fingerprint Apache Avro's `SchemaNormalization.parsingFingerprint("CRC-64-AVRO", …)` computes
    private fun AvroSchema.crc64avro(): ByteArray = fingerprintCache.getOrPut(this) { parsingFingerprintCrc64AvroBytes() }

    public fun <T> encodeToSink(
        writerSchema: AvroSchema,
        serializer: SerializationStrategy<T>,
        value: T,
        sink: Sink,
    ) {
        sink.writeByte(MAGIC_BYTE)
        sink.writeByte(FORMAT_VERSION)
        sink.write(writerSchema.crc64avro())
        avro.encodeToSink(writerSchema, serializer, value, sink)
    }

    public fun <T> decodeFromSource(
        deserializer: DeserializationStrategy<T>,
        source: Source,
    ): T {
        check(source.readByte() == MAGIC_BYTE) { "Not a valid single-object avro format, bad magic byte" }
        check(source.readByte() == FORMAT_VERSION) { "Not a valid single-object avro format, bad version byte" }
        val fingerprint = source.readLongLe()
        val writerSchema =
            schemaRegistry(fingerprint) ?: throw SerializationException("schema not found for the given object's schema fingerprint 0x${fingerprint.toString(16)}")

        return avro.decodeFromSource(writerSchema, deserializer, source)
    }

    public override fun <T> decodeFromByteArray(
        deserializer: DeserializationStrategy<T>,
        bytes: ByteArray,
    ): T {
        return decodeFromSource(deserializer, Buffer(bytes))
    }

    override fun <T> encodeToByteArray(
        serializer: SerializationStrategy<T>,
        value: T,
    ): ByteArray {
        val writerSchema = avro.schema(serializer.descriptor)
        return encodeToByteArray(writerSchema, serializer, value)
    }
}

private const val MAGIC_BYTE: Byte = 0xC3.toByte()
private const val FORMAT_VERSION: Byte = 1

public fun <T> AvroSingleObject.encodeToByteArray(
    writerSchema: AvroSchema,
    serializer: SerializationStrategy<T>,
    value: T,
): ByteArray {
    val sink = Buffer()
    encodeToSink(writerSchema, serializer, value, sink)
    return sink.readByteArray()
}

public inline fun <reified T> AvroSingleObject.encodeToByteArray(
    writerSchema: AvroSchema,
    value: T,
): ByteArray = encodeToByteArray(writerSchema, avro.serializersModule.serializer<T>(), value)

public inline fun <reified T> AvroSingleObject.encodeToByteArray(value: T): ByteArray {
    val serializer = avro.serializersModule.serializer<T>()
    return encodeToByteArray(avro.schema(serializer), serializer, value)
}

public inline fun <reified T> AvroSingleObject.decodeFromByteArray(bytes: ByteArray): T = decodeFromByteArray(avro.serializersModule.serializer<T>(), bytes)