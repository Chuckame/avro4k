package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.EOFException
import com.github.avrokotlin.avro4k.internal.IdentityFirstCache
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryReader
import com.github.avrokotlin.avro4k.internal.codec.ByteArrayBinaryReader
import com.github.avrokotlin.avro4k.internal.codec.ByteArrayBinaryWriter
import com.github.avrokotlin.avro4k.internal.decodeWithValidation
import com.github.avrokotlin.avro4k.internal.encodeWithValidation
import com.github.avrokotlin.avro4k.internal.endOfInputError
import com.github.avrokotlin.avro4k.internal.parsingFingerprintCrc64AvroBytes
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
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
     * callers hand the array to the encoder's `writeFixed`, which copies the bytes out and neither retains nor mutates
     * the array. No public API exposes the fingerprint bytes.
     */
    private val fingerprintCache = IdentityFirstCache<AvroSchema, ByteArray>()

    // Byte for byte the fingerprint Apache Avro's `SchemaNormalization.parsingFingerprint("CRC-64-AVRO", …)` computes
    private fun AvroSchema.crc64avro(): ByteArray = fingerprintCache.getOrPut(this) { parsingFingerprintCrc64AvroBytes() }

    /** Writes the header then the value into [encoder]. */
    internal fun <T> encodeWithHeader(
        writerSchema: AvroSchema,
        serializer: SerializationStrategy<T>,
        value: T,
        encoder: ByteArrayBinaryWriter,
    ) {
        encoder.writeByte(MAGIC_BYTE)
        encoder.writeByte(FORMAT_VERSION)
        encoder.writeFixed(writerSchema.crc64avro())
        avro.encodeWithValidation(writerSchema, serializer, value, encoder)
    }

    /** Reads the header from [decoder], then the value with the writer schema it names. */
    internal fun <T> decodeWithHeader(
        deserializer: DeserializationStrategy<T>,
        decoder: AvroBinaryReader,
    ): T {
        val header =
            try {
                decoder.readFixed(HEADER_SIZE)
            } catch (e: EOFException) {
                throw endOfInputError(e)
            }
        var index = 0
        val fingerprint = readHeader { header[index++] }
        return avro.decodeWithValidation(writerSchema(fingerprint), deserializer, decoder)
    }

    public override fun <T> decodeFromByteArray(
        deserializer: DeserializationStrategy<T>,
        bytes: ByteArray,
    ): T {
        var index = 0
        val fingerprint =
            readHeader {
                if (index == bytes.size) {
                    throw EOFException("The input ended in the single-object header")
                }
                bytes[index++]
            }
        return avro.decodeWithValidation(writerSchema(fingerprint), deserializer, ByteArrayBinaryReader(bytes, index))
    }

    /** Reads the marker and the version, then returns the writer schema's fingerprint, read little-endian. */
    private inline fun readHeader(readByte: () -> Byte): Long {
        try {
            if (readByte() != MAGIC_BYTE) {
                throw AvroMalformedDataException("Not a valid single-object avro format, bad magic byte")
            }
            if (readByte() != FORMAT_VERSION) {
                throw AvroMalformedDataException("Not a valid single-object avro format, bad version byte")
            }
            var fingerprint = 0L
            for (i in 0 until 8) {
                fingerprint = fingerprint or ((readByte().toLong() and 0xFF) shl (8 * i))
            }
            return fingerprint
        } catch (e: EOFException) {
            throw endOfInputError(e)
        }
    }

    private fun writerSchema(fingerprint: Long): AvroSchema =
        schemaRegistry(fingerprint) ?: throw AvroSerializationException("schema not found for the given object's schema fingerprint 0x${fingerprint.toString(16)}")

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

/** The marker, the version and the fingerprint. */
private const val HEADER_SIZE = 2 + 8

public fun <T> AvroSingleObject.encodeToByteArray(
    writerSchema: AvroSchema,
    serializer: SerializationStrategy<T>,
    value: T,
): ByteArray {
    val encoder = ByteArrayBinaryWriter()
    encodeWithHeader(writerSchema, serializer, value, encoder)
    return encoder.toByteArray()
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