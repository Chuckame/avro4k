package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroMalformedDataException
import com.github.avrokotlin.avro4k.internal.EOFException
import com.github.avrokotlin.avro4k.internal.codec.VarintTest.Companion.INT_VECTORS
import com.github.avrokotlin.avro4k.internal.codec.VarintTest.Companion.LONG_VECTORS
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ByteArrayDecoderTest {
    @Test
    fun `every int length is read and consumes exactly its bytes`() {
        for ((value, encoded) in INT_VECTORS) {
            // alone, so that the varint ends the input, and followed by enough bytes for any varint
            ByteArrayDecoder(encoded).apply { readInt() shouldBe value }.isExhausted shouldBe true
            val decoder = ByteArrayDecoder(encoded + TRAILER)
            decoder.readInt() shouldBe value
            decoder.readFixed(TRAILER.size) shouldBe TRAILER
            decoder.isExhausted shouldBe true
        }
    }

    @Test
    fun `every long length is read and consumes exactly its bytes`() {
        for ((value, encoded) in LONG_VECTORS) {
            ByteArrayDecoder(encoded).apply { readLong() shouldBe value }.isExhausted shouldBe true
            val decoder = ByteArrayDecoder(encoded + TRAILER)
            decoder.readLong() shouldBe value
            decoder.readFixed(TRAILER.size) shouldBe TRAILER
            decoder.isExhausted shouldBe true
        }
    }

    @Test
    fun `an int of more than 5 bytes is malformed`() {
        val tooLong = bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x01)
        shouldThrow<AvroMalformedDataException> { ByteArrayDecoder(tooLong).readInt() }
        shouldThrow<AvroMalformedDataException> { ByteArrayDecoder(tooLong + TRAILER).readInt() }
    }

    @Test
    fun `a long of more than 10 bytes is malformed`() {
        val tooLong = bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01)
        shouldThrow<AvroMalformedDataException> { ByteArrayDecoder(tooLong).readLong() }
        shouldThrow<AvroMalformedDataException> { ByteArrayDecoder(tooLong + TRAILER).readLong() }
    }

    @Test
    fun `the input ending inside a varint is an end of input`() {
        shouldThrow<EOFException> { ByteArrayDecoder(ByteArray(0)).readInt() }
        shouldThrow<EOFException> { ByteArrayDecoder(ByteArray(0)).readLong() }
        for ((_, encoded) in INT_VECTORS) {
            for (size in 1 until encoded.size) {
                shouldThrow<EOFException> { ByteArrayDecoder(encoded.copyOf(size)).readInt() }
            }
        }
        for ((_, encoded) in LONG_VECTORS) {
            for (size in 1 until encoded.size) {
                shouldThrow<EOFException> { ByteArrayDecoder(encoded.copyOf(size)).readLong() }
            }
        }
    }

    @Test
    fun `every value reads back what the encoder wrote`() {
        val encoded =
            ByteArrayEncoder().apply {
                writeBoolean(true)
                writeBoolean(false)
                writeInt(-300)
                writeLong(Long.MIN_VALUE)
                writeFloat(1.5f)
                writeFloat(-0.0f)
                writeFloat(Float.MAX_VALUE)
                writeDouble(-2.25)
                writeDouble(Double.MIN_VALUE)
                writeDouble(Double.NEGATIVE_INFINITY)
                writeString("")
                writeString("ascii")
                writeString("é€😀")
                writeBytes(bytes(1, 2, 3))
                writeFixed(bytes(4, 5))
                writeEnum(3)
                writeIndex(70)
            }.toByteArray()
        val array = ByteArrayDecoder(encoded)
        array.readBoolean() shouldBe true
        array.readBoolean() shouldBe false
        array.readInt() shouldBe -300
        array.readLong() shouldBe Long.MIN_VALUE
        array.readFloat().toBits() shouldBe 1.5f.toBits()
        array.readFloat().toBits() shouldBe (-0.0f).toBits()
        array.readFloat().toBits() shouldBe Float.MAX_VALUE.toBits()
        array.readDouble().toBits() shouldBe (-2.25).toBits()
        array.readDouble().toBits() shouldBe Double.MIN_VALUE.toBits()
        array.readDouble().toBits() shouldBe Double.NEGATIVE_INFINITY.toBits()
        array.readString() shouldBe ""
        array.readString() shouldBe "ascii"
        array.readString() shouldBe "é€😀"
        array.readBytes() shouldBe bytes(1, 2, 3)
        array.readFixed(2) shouldBe bytes(4, 5)
        array.readEnum() shouldBe 3
        array.readIndex() shouldBe 70
        array.isExhausted shouldBe true
    }

    @Test
    fun `floats and doubles are read little-endian`() {
        ByteArrayDecoder(bytes(0x00, 0x00, 0xC0, 0x3F)).readFloat() shouldBe 1.5f
        ByteArrayDecoder(bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0xC0)).readDouble() shouldBe -2.25
    }

    @Test
    fun `strings and bytes are skipped without being read`() {
        val encoded =
            ByteArrayEncoder().apply {
                writeString("skipped")
                writeBytes(bytes(9, 9, 9))
                writeFixed(bytes(8, 8))
                writeInt(42)
            }.toByteArray()
        val decoder = ByteArrayDecoder(encoded)
        decoder.skipString()
        decoder.skipBytes()
        decoder.skipFixed(2)
        decoder.readInt() shouldBe 42
        decoder.isExhausted shouldBe true
    }

    @Test
    fun `a value longer than the remaining input is an end of input`() {
        // length 3, then only 2 bytes
        shouldThrow<EOFException> { ByteArrayDecoder(bytes(0x06, 0x61, 0x62)).readString() }
        shouldThrow<EOFException> { ByteArrayDecoder(bytes(0x06, 0x61, 0x62)).readBytes() }
        shouldThrow<EOFException> { ByteArrayDecoder(bytes(0x06, 0x61, 0x62)).skipString() }
        shouldThrow<EOFException> { ByteArrayDecoder(bytes(0x61, 0x62)).readFixed(3) }
        shouldThrow<EOFException> { ByteArrayDecoder(bytes(0x00, 0x00, 0x00)).readFloat() }
        shouldThrow<EOFException> { ByteArrayDecoder(bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)).readDouble() }
        shouldThrow<EOFException> { ByteArrayDecoder(ByteArray(0)).readBoolean() }
    }

    @Test
    fun `a negative length is malformed`() {
        shouldThrow<AvroMalformedDataException> { ByteArrayDecoder(bytes(0x01)).readString() }
    }

    @Test
    fun `blocks written with their byte size are skipped whole`() {
        // 2 items of 3 bytes in total, then a block of 1 item without its size, then the end of the array
        val encoded = bytes(0x03, 0x06, 0x61, 0x62, 0x63, 0x02, 0x64, 0x00, 0x54)
        val decoder = ByteArrayDecoder(encoded)
        // the block with a byte size is skipped, the next one has to be read item by item
        decoder.skipArray() shouldBe 1L
        decoder.readFixed(1) shouldBe bytes(0x64)
        decoder.arrayNext() shouldBe 0L
        decoder.readInt() shouldBe 42
        decoder.isExhausted shouldBe true
    }

    @Test
    fun `a block byte size beyond the input is an end of input`() {
        shouldThrow<EOFException> { ByteArrayDecoder(bytes(0x03, 0x14, 0x61)).skipArray() }
    }

    @Test
    fun `a negative block count is read with its byte size`() {
        // a block of 2 items with a byte size of 4
        val decoder = ByteArrayDecoder(bytes(0x03, 0x08))
        decoder.readArrayStart() shouldBe 2L
        decoder.isExhausted shouldBe true
    }

    private companion object {
        /** Read after a varint to check that it consumed exactly its own bytes. */
        val TRAILER = ByteArray(MAX_VARINT_LONG_BYTES + 1) { (it + 1).toByte() }

        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    }
}