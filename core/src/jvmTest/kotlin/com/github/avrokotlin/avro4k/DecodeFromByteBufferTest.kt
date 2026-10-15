package com.github.avrokotlin.avro4k

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class DecodeFromByteBufferTest : StringSpec({
    val value = SimpleRecord(name = "alice", age = 42)
    val encoded = Avro.encodeToByteArray(value)

    "decodes from ByteBuffer starting at position 0" {
        val buffer = ByteBuffer.wrap(encoded)

        val decoded = Avro.decodeFromByteBuffer<SimpleRecord>(buffer)

        decoded shouldBe value
    }

    "decodes from ByteBuffer with a prefix (non-zero initial position)" {
        // Simulates a Confluent Schema Registry payload: magic byte (0x00) + 4-byte schema id
        val prefix = byteArrayOf(0x00, 0x00, 0x00, 0x00, 42)
        val buffer = ByteBuffer.wrap(prefix + encoded)
        buffer.position(prefix.size)

        val decoded = Avro.decodeFromByteBuffer<SimpleRecord>(buffer)

        decoded shouldBe value
    }

    "does not throw when there are remaining bytes after the record" {
        val suffix = byteArrayOf(0x01, 0x02, 0x03)
        val buffer = ByteBuffer.wrap(encoded + suffix)

        val decoded = Avro.decodeFromByteBuffer<SimpleRecord>(buffer)

        decoded shouldBe value
    }

    "advances the ByteBuffer position past the consumed bytes" {
        val suffix = byteArrayOf(0x01, 0x02, 0x03)
        val buffer = ByteBuffer.wrap(encoded + suffix)

        Avro.decodeFromByteBuffer<SimpleRecord>(buffer)

        buffer.position() shouldBe encoded.size
    }

    "advances the ByteBuffer position correctly when starting from a non-zero position" {
        val prefix = byteArrayOf(0x00, 0x00, 0x00, 0x00, 42)
        val suffix = byteArrayOf(0x01, 0x02, 0x03)
        val buffer = ByteBuffer.wrap(prefix + encoded + suffix)
        buffer.position(prefix.size)

        Avro.decodeFromByteBuffer<SimpleRecord>(buffer)

        buffer.position() shouldBe prefix.size + encoded.size
    }

    "decodes consecutive values from the same ByteBuffer" {
        val other = SimpleRecord(name = "bob", age = 7)
        val buffer = ByteBuffer.wrap(encoded + Avro.encodeToByteArray(other))

        Avro.decodeFromByteBuffer<SimpleRecord>(buffer) shouldBe value
        Avro.decodeFromByteBuffer<SimpleRecord>(buffer) shouldBe other
        buffer.hasRemaining() shouldBe false
    }

    "decodes from a direct ByteBuffer and advances its position past the consumed bytes" {
        val suffix = byteArrayOf(0x01, 0x02, 0x03)
        val buffer = ByteBuffer.allocateDirect(encoded.size + suffix.size).put(encoded).put(suffix).flip()

        Avro.decodeFromByteBuffer<SimpleRecord>(buffer) shouldBe value
        buffer.position() shouldBe encoded.size
    }

    "decodes from a read-only ByteBuffer and advances its position past the consumed bytes" {
        val suffix = byteArrayOf(0x01, 0x02, 0x03)
        val buffer = ByteBuffer.wrap(encoded + suffix).asReadOnlyBuffer()

        Avro.decodeFromByteBuffer<SimpleRecord>(buffer) shouldBe value
        buffer.position() shouldBe encoded.size
    }

    "decodes from a sliced ByteBuffer with a non-zero arrayOffset" {
        val prefix = byteArrayOf(0x00, 0x00, 0x00, 0x00, 42)
        val suffix = byteArrayOf(0x01, 0x02, 0x03)
        val sliced = ByteBuffer.wrap(prefix + encoded + suffix).position(prefix.size).slice()

        Avro.decodeFromByteBuffer<SimpleRecord>(sliced) shouldBe value
        sliced.position() shouldBe encoded.size
    }

    "does not advance the position of an empty ByteBuffer that fails to decode" {
        val buffer = ByteBuffer.wrap(encoded).position(encoded.size)

        shouldThrow<Exception> { Avro.decodeFromByteBuffer<SimpleRecord>(buffer) }
        buffer.position() shouldBe encoded.size
    }

    "fails on a value truncated by the limit, without reading the array past it, and restores the position" {
        val prefix = byteArrayOf(0x00, 0x00, 0x00, 0x00, 42)
        // The limit cuts the name string short while the array still holds its last bytes
        val buffer = ByteBuffer.wrap(prefix + encoded).position(prefix.size).limit(prefix.size + 3)

        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteBuffer<SimpleRecord>(buffer) }
        buffer.position() shouldBe prefix.size
    }

    "fails on a direct ByteBuffer ending in the middle of a varint, and restores the position" {
        val buffer = ByteBuffer.allocateDirect(encoded.size).put(encoded).flip().limit(encoded.size - 1)

        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteBuffer<SimpleRecord>(buffer) }
        buffer.position() shouldBe 0
    }

    "reads floats and doubles whatever the ByteBuffer's byte order" {
        val numbers = Numbers(float = 1.5f, double = -2.25e300)
        val bytes = Avro.encodeToByteArray(numbers)

        Avro.decodeFromByteBuffer<Numbers>(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)) shouldBe numbers
        Avro.decodeFromByteBuffer<Numbers>(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)) shouldBe numbers
        val direct = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).put(bytes).flip()
        Avro.decodeFromByteBuffer<Numbers>(direct) shouldBe numbers
        direct.order() shouldBe ByteOrder.LITTLE_ENDIAN
    }
}) {
    @Serializable
    private data class Numbers(val float: Float, val double: Double)

    @Serializable
    private data class SimpleRecord(val name: String, val age: Int)
}