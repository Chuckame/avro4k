package com.github.avrokotlin.avro4k.internal.codec

import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test

class VarintTest {
    @Test
    fun `int vectors are written as the Avro spec says`() {
        for ((value, expected) in INT_VECTORS) {
            encodeInt(value) shouldBe expected
            writeIntWithKotlinxIo(value) shouldBe expected
            KotlinxIoDecoder(Buffer().apply { write(expected) }).readInt() shouldBe value
        }
    }

    @Test
    fun `long vectors are written as the Avro spec says`() {
        for ((value, expected) in LONG_VECTORS) {
            encodeLong(value) shouldBe expected
            writeLongWithKotlinxIo(value) shouldBe expected
            KotlinxIoDecoder(Buffer().apply { write(expected) }).readLong() shouldBe value
        }
    }

    @Test
    fun `every int is written as an int widened to long`() {
        for ((value, _) in INT_VECTORS) {
            encodeLong(value.toLong()) shouldBe encodeInt(value)
        }
    }

    @Test
    fun `every 7-bit boundary of the zig-zag value changes the int length by one byte`() {
        // zig-zag maps 2^(k-1) - 1 to 2^k - 2, -2^(k-1) to 2^k - 1, and 2^(k-1) to 2^k
        for (bits in listOf(7, 14, 21, 28)) {
            val bytes = bits / 7
            val half = 1 shl (bits - 1)
            assertIntRoundTrip(half - 1, bytes)
            assertIntRoundTrip(-half, bytes)
            assertIntRoundTrip(half, bytes + 1)
            assertIntRoundTrip(-half - 1, bytes + 1)
        }
    }

    @Test
    fun `every 7-bit boundary of the zig-zag value changes the long length by one byte`() {
        for (bits in listOf(7, 14, 21, 28, 35, 42, 49, 56, 63)) {
            val bytes = bits / 7
            val half = 1L shl (bits - 1)
            assertLongRoundTrip(half - 1, bytes)
            assertLongRoundTrip(-half, bytes)
            assertLongRoundTrip(half, bytes + 1)
            assertLongRoundTrip(-half - 1, bytes + 1)
        }
    }

    @Test
    fun `the writer reports the number of bytes written and leaves the rest of the array untouched`() {
        val bytes = ByteArray(MAX_VARINT_LONG_BYTES + 2) { 0x55 }
        encodeZigZagLong(64, bytes, 1) shouldBe 2
        bytes.toList() shouldBe listOf<Byte>(0x55, 0x80.toByte(), 0x01) + List(MAX_VARINT_LONG_BYTES - 1) { 0x55 }
    }

    private fun assertIntRoundTrip(
        value: Int,
        expectedLength: Int,
    ) {
        val encoded = encodeInt(value)
        encoded.size shouldBe expectedLength
        writeIntWithKotlinxIo(value) shouldBe encoded
        KotlinxIoDecoder(Buffer().apply { write(encoded) }).readInt() shouldBe value
    }

    private fun assertLongRoundTrip(
        value: Long,
        expectedLength: Int,
    ) {
        val encoded = encodeLong(value)
        encoded.size shouldBe expectedLength
        writeLongWithKotlinxIo(value) shouldBe encoded
        KotlinxIoDecoder(Buffer().apply { write(encoded) }).readLong() shouldBe value
    }

    companion object {
        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

        val INT_VECTORS: List<Pair<Int, ByteArray>> =
            listOf(
                0 to bytes(0x00),
                1 to bytes(0x02),
                -1 to bytes(0x01),
                2 to bytes(0x04),
                -2 to bytes(0x03),
                // 7 bits
                63 to bytes(0x7E),
                -64 to bytes(0x7F),
                64 to bytes(0x80, 0x01),
                -65 to bytes(0x81, 0x01),
                // 14 bits
                8191 to bytes(0xFE, 0x7F),
                -8192 to bytes(0xFF, 0x7F),
                8192 to bytes(0x80, 0x80, 0x01),
                -8193 to bytes(0x81, 0x80, 0x01),
                // 21 bits
                1048575 to bytes(0xFE, 0xFF, 0x7F),
                -1048576 to bytes(0xFF, 0xFF, 0x7F),
                1048576 to bytes(0x80, 0x80, 0x80, 0x01),
                -1048577 to bytes(0x81, 0x80, 0x80, 0x01),
                // 28 bits
                134217727 to bytes(0xFE, 0xFF, 0xFF, 0x7F),
                -134217728 to bytes(0xFF, 0xFF, 0xFF, 0x7F),
                134217728 to bytes(0x80, 0x80, 0x80, 0x80, 0x01),
                -134217729 to bytes(0x81, 0x80, 0x80, 0x80, 0x01),
                Int.MAX_VALUE to bytes(0xFE, 0xFF, 0xFF, 0xFF, 0x0F),
                Int.MIN_VALUE to bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F)
            )

        val LONG_VECTORS: List<Pair<Long, ByteArray>> =
            INT_VECTORS.map { (value, bytes) -> value.toLong() to bytes } +
                listOf(
                    (Int.MAX_VALUE.toLong() + 1) to bytes(0x80, 0x80, 0x80, 0x80, 0x10),
                    (Int.MIN_VALUE.toLong() - 1) to bytes(0x81, 0x80, 0x80, 0x80, 0x10),
                    // 35 bits
                    (1L shl 34) to bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x01),
                    // 63 bits
                    ((1L shl 62) - 1) to bytes(0xFE, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F),
                    (1L shl 62) to bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01),
                    Long.MAX_VALUE to bytes(0xFE, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01),
                    Long.MIN_VALUE to bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01)
                )

        fun encodeInt(value: Int): ByteArray {
            val bytes = ByteArray(MAX_VARINT_INT_BYTES)
            return bytes.copyOf(encodeZigZagInt(value, bytes, 0))
        }

        fun encodeLong(value: Long): ByteArray {
            val bytes = ByteArray(MAX_VARINT_LONG_BYTES)
            return bytes.copyOf(encodeZigZagLong(value, bytes, 0))
        }

        fun writeIntWithKotlinxIo(value: Int): ByteArray = Buffer().also { KotlinxIoEncoder(it).writeInt(value) }.readByteArray()

        fun writeLongWithKotlinxIo(value: Long): ByteArray = Buffer().also { KotlinxIoEncoder(it).writeLong(value) }.readByteArray()
    }
}