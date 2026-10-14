package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroMalformedDataException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.RawSource
import kotlinx.io.UnsafeIoApi
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.unsafe.UnsafeBufferOperations
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

    @Test
    fun `every int length is read from the segment array and consumes exactly its bytes`() {
        for ((value, encoded) in INT_VECTORS) {
            // the trailer leaves enough room after the first byte for the rest of any varint
            val source = Buffer().apply { write(encoded + TRAILER) }
            KotlinxIoDecoder(source).readInt() shouldBe value
            source.readByteArray() shouldBe TRAILER
        }
    }

    @Test
    fun `every long length is read from the segment array and consumes exactly its bytes`() {
        for ((value, encoded) in LONG_VECTORS) {
            val source = Buffer().apply { write(encoded + TRAILER) }
            KotlinxIoDecoder(source).readLong() shouldBe value
            source.readByteArray() shouldBe TRAILER
        }
    }

    @Test
    fun `an int split across two segments is read whole at every split position`() {
        for ((value, encoded) in INT_VECTORS) {
            for (bytesInFirstSegment in 0..encoded.size) {
                val source = sourceSplitAt(encoded, bytesInFirstSegment)
                KotlinxIoDecoder(source).readInt() shouldBe value
                source.readByteArray() shouldBe TRAILER
            }
        }
    }

    @Test
    fun `a long split across two segments is read whole at every split position`() {
        for ((value, encoded) in LONG_VECTORS) {
            for (bytesInFirstSegment in 0..encoded.size) {
                val source = sourceSplitAt(encoded, bytesInFirstSegment)
                KotlinxIoDecoder(source).readLong() shouldBe value
                source.readByteArray() shouldBe TRAILER
            }
        }
    }

    @Test
    fun `a varint arriving one byte at a time from the underlying source is read whole`() {
        for ((value, encoded) in INT_VECTORS) {
            KotlinxIoDecoder(OneByteAtATime(encoded).buffered()).readInt() shouldBe value
        }
        for ((value, encoded) in LONG_VECTORS) {
            KotlinxIoDecoder(OneByteAtATime(encoded).buffered()).readLong() shouldBe value
        }
    }

    @Test
    fun `an int of more than 5 bytes is malformed`() {
        val tooLong = bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x01)
        // from the segment array, byte by byte, and across a segment boundary
        shouldThrow<AvroMalformedDataException> { KotlinxIoDecoder(Buffer().apply { write(tooLong + TRAILER) }).readInt() }
        shouldThrow<AvroMalformedDataException> { KotlinxIoDecoder(OneByteAtATime(tooLong).buffered()).readInt() }
        for (bytesInFirstSegment in 1..MAX_VARINT_INT_BYTES) {
            val source = sourceSplitAt(tooLong, bytesInFirstSegment)
            shouldThrow<AvroMalformedDataException> { KotlinxIoDecoder(source).readInt() }
        }
    }

    @Test
    fun `a long of more than 10 bytes is malformed`() {
        val tooLong = bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01)
        shouldThrow<AvroMalformedDataException> { KotlinxIoDecoder(Buffer().apply { write(tooLong + TRAILER) }).readLong() }
        shouldThrow<AvroMalformedDataException> { KotlinxIoDecoder(OneByteAtATime(tooLong).buffered()).readLong() }
        for (bytesInFirstSegment in 1..MAX_VARINT_LONG_BYTES) {
            val source = sourceSplitAt(tooLong, bytesInFirstSegment)
            shouldThrow<AvroMalformedDataException> { KotlinxIoDecoder(source).readLong() }
        }
    }

    @Test
    fun `the input ending inside a varint is an end of input`() {
        shouldThrow<EOFException> { KotlinxIoDecoder(Buffer()).readInt() }
        shouldThrow<EOFException> { KotlinxIoDecoder(Buffer()).readLong() }
        for ((_, encoded) in INT_VECTORS) {
            for (size in 1 until encoded.size) {
                val truncated = encoded.copyOf(size)
                shouldThrow<EOFException> { KotlinxIoDecoder(Buffer().apply { write(truncated) }).readInt() }
                shouldThrow<EOFException> { KotlinxIoDecoder(OneByteAtATime(truncated).buffered()).readInt() }
            }
        }
        for ((_, encoded) in LONG_VECTORS) {
            for (size in 1 until encoded.size) {
                val truncated = encoded.copyOf(size)
                shouldThrow<EOFException> { KotlinxIoDecoder(Buffer().apply { write(truncated) }).readLong() }
                shouldThrow<EOFException> { KotlinxIoDecoder(OneByteAtATime(truncated).buffered()).readLong() }
            }
        }
    }

    /**
     * A source whose head segment is full and ends with the first [bytesInFirstSegment] bytes of [encoded], the rest
     * of it starting the next segment, followed by [TRAILER]. Positioned at the start of [encoded].
     */
    @OptIn(UnsafeIoApi::class)
    private fun sourceSplitAt(
        encoded: ByteArray,
        bytesInFirstSegment: Int,
    ): Buffer {
        val filler = ByteArray(SEGMENT_SIZE - bytesInFirstSegment) { 0x2A }
        val source = Buffer().apply { write(filler + encoded + TRAILER) }
        var firstSegmentSize = -1
        UnsafeBufferOperations.forEachSegment(source) { _, segment ->
            if (firstSegmentSize == -1) firstSegmentSize = segment.size
        }
        // the test only means something if the buffer is laid out as expected
        firstSegmentSize shouldBe SEGMENT_SIZE
        source.skip(filler.size.toLong())
        return source
    }

    /** Hands its bytes over one by one, so that the buffer reading from it never holds more than one byte ahead. */
    private class OneByteAtATime(
        private val bytes: ByteArray,
    ) : RawSource {
        private var position = 0

        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            if (position == bytes.size) return -1
            sink.writeByte(bytes[position++])
            return 1
        }

        override fun close() {
        }
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
        /** The size of a kotlinx-io segment. */
        private const val SEGMENT_SIZE = 8192

        /** Read after a varint to check that it consumed exactly its own bytes. */
        private val TRAILER = ByteArray(MAX_VARINT_LONG_BYTES + 1) { (it + 1).toByte() }

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