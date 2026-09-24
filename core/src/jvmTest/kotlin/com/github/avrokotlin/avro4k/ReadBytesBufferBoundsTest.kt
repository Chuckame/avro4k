package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.decodeWithApacheDecoder
import com.github.avrokotlin.avro4k.serializer.AvroSerializer
import com.github.avrokotlin.avro4k.serializer.SchemaSupplierContext
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.serializer
import org.apache.avro.Schema
import org.apache.avro.io.DecoderFactory
import org.apache.avro.util.ByteBufferInputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

/**
 * Regression tests for reading BYTES values into an exclusively owned, exactly sized [ByteArray].
 *
 * - [decodeFromByteBuffer] reads a heap buffer in place (its array is wrapped, not copied, into the kotlinx-io source),
 *   so every decoded value must be copied out of it, bounded by the buffer's position and limit.
 * - `org.apache.avro.io.Decoder.readBytes(null)`, which the Apache adapter calls, is only contracted to return a
 *   [ByteBuffer] positioned on the requested bytes: it is free to return a view onto memory owned by someone else,
 *   as `DirectBinaryDecoder` over a `ByteBufferInputStream` does. So the adapter copies `position()`..`limit()` out,
 *   never `array()`.
 */
internal class ReadBytesBufferBoundsTest : StringSpec({
    val payload = byteArrayOf(11, 22, 33, 44, 55)

    "decodes a trailing BYTES field as ByteArray without leaking the preceding bytes" {
        // The bytes field is the last one, so the decoder sees a buffer whose remaining() is exactly the field
        // length and hands it back without copying — array() then holds the whole record, header included.
        val encoded = Avro.encodeToByteArray(BytesRecord("header", payload))

        val decoded = Avro.decodeFromByteBuffer<BytesRecord>(ByteBuffer.wrap(encoded))

        decoded.header shouldBe "header"
        decoded.payload shouldBe payload
    }

    "decodes a trailing BYTES field as String without leaking the preceding bytes" {
        val encoded = Avro.encodeToByteArray(BytesRecord("header", "hello".encodeToByteArray()))
        val writerSchema = Avro.schema<BytesRecord>()

        val decoded =
            Avro.decodeFromByteBuffer(
                ByteBuffer.wrap(encoded),
                Avro.serializersModule.serializer<StringPayloadRecord>(),
                writerSchema
            )

        decoded.header shouldBe "header"
        decoded.payload shouldBe "hello"
    }

    "decodes a trailing BYTES field as GenericFixed without leaking the preceding bytes" {
        val encoded = Avro.encodeToByteArray(FixedPayloadRecord("header", payload))

        val decoded = Avro.decodeFromByteBuffer<FixedPayloadRecord>(ByteBuffer.wrap(encoded))

        decoded.header shouldBe "header"
        decoded.payload shouldBe payload
    }

    "decodes a trailing BYTES field from a sliced buffer with a non-zero arrayOffset" {
        val encoded = Avro.encodeToByteArray(BytesRecord("header", payload))
        val prefix = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
        val sliced =
            ByteBuffer.wrap(prefix + encoded)
                .position(prefix.size)
                .slice()
        sliced.arrayOffset() shouldBe prefix.size

        val decoded = Avro.decodeFromByteBuffer<BytesRecord>(sliced)

        decoded.header shouldBe "header"
        decoded.payload shouldBe payload
    }

    "decodes a BYTES field spanning multiple source segments" {
        // kotlinx-io's readAtMostTo only drains the head segment, so a value bigger than a segment must be read
        // in a loop, otherwise the tail of the decoded array is silently left zeroed.
        val bigPayload = ByteArray(20_000) { (it % 251).toByte() }
        val encoded = Avro.encodeToByteArray(BytesRecord("header", bigPayload))

        val decoded = Avro.decodeFromSource<BytesRecord>(ByteArrayInputStream(encoded).asSource().buffered())

        decoded.payload shouldBe bigPayload
    }

    "does not alias the caller's heap buffer, which is decoded in place" {
        val encoded = Avro.encodeToByteArray(BytesRecord("header", payload))

        val decoded = Avro.decodeFromByteBuffer<BytesRecord>(ByteBuffer.wrap(encoded))

        decoded.payload shouldBe payload
        encoded.fill(0)
        decoded.payload shouldBe payload
        decoded.header shouldBe "header"
    }

    "decodes a trailing BYTES field from a read-only and from a direct buffer" {
        val encoded = Avro.encodeToByteArray(BytesRecord("header", payload))
        val direct = ByteBuffer.allocateDirect(encoded.size).put(encoded).flip()

        Avro.decodeFromByteBuffer<BytesRecord>(ByteBuffer.wrap(encoded).asReadOnlyBuffer()).payload shouldBe payload
        Avro.decodeFromByteBuffer<BytesRecord>(direct).payload shouldBe payload
    }

    "does not alias the caller's buffer even when its bounds exactly match the BYTES field" {
        // Split the encoded record so that the payload lands in a buffer of its own: position() == 0,
        // arrayOffset() == 0 and remaining() == array().size, yet the array is still owned by the caller.
        val encoded = Avro.encodeToByteArray(BytesRecord("header", payload))
        val payloadStart = encoded.size - payload.size
        val head = ByteBuffer.wrap(encoded.copyOfRange(0, payloadStart))
        val tailArray = encoded.copyOfRange(payloadStart, encoded.size)
        val tail = ByteBuffer.wrap(tailArray)

        val decoded =
            Avro.decodeWithApacheDecoder(
                Avro.schema<BytesRecord>(),
                Avro.serializersModule.serializer<BytesRecord>(),
                DecoderFactory.get().directBinaryDecoder(ByteBufferInputStream(listOf(head, tail)), null)
            )

        decoded.payload shouldBe payload
        tailArray.fill(0)
        decoded.payload shouldBe payload
    }
}) {
    @Serializable
    @SerialName("BytesRecord")
    private data class BytesRecord(val header: String, val payload: ByteArray)

    @Serializable
    @SerialName("BytesRecord")
    private data class StringPayloadRecord(val header: String, val payload: String)

    @Serializable
    @SerialName("BytesRecord")
    private data class FixedPayloadRecord(
        val header: String,
        @Serializable(with = BytesAsFixedSerializer::class) val payload: ByteArray,
    )

    /** Forces the `Schema.Type.BYTES -> GenericData.Fixed(...)` branch of `decodeFixed()`. */
    private object BytesAsFixedSerializer : AvroSerializer<ByteArray>("BytesAsFixed") {
        override fun getSchema(context: SchemaSupplierContext): Schema = Schema.create(Schema.Type.BYTES)

        override fun serializeAvro(
            encoder: AvroEncoder,
            value: ByteArray,
        ) {
            encoder.encodeBytes(value)
        }

        override fun deserializeAvro(decoder: AvroDecoder): ByteArray = decoder.decodeFixed().bytes()
    }
}