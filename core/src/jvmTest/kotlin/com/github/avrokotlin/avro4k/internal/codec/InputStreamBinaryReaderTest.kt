package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroMalformedDataException
import com.github.avrokotlin.avro4k.internal.codec.VarintTest.Companion.INT_VECTORS
import com.github.avrokotlin.avro4k.internal.codec.VarintTest.Companion.LONG_VECTORS
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.test.Test

class InputStreamBinaryReaderTest {
    @Test
    fun `every value split across two refills is read whole at every split position`() {
        val encoded =
            ByteArrayBinaryWriter().apply {
                writeBoolean(true)
                writeInt(-300_000)
                writeLong(Long.MIN_VALUE)
                writeFloat(1.5f)
                writeDouble(-0.1)
                writeString("héllo")
                writeBytes(byteArrayOf(1, 2, 3))
                writeFixed(byteArrayOf(4, 5, 6, 7))
                writeString("skipped")
                writeBytes(byteArrayOf(8, 9))
                writeFixed(byteArrayOf(10, 11))
                writeLong(7)
            }.toByteArray()
        for (bytesInFirstRead in 1..encoded.size) {
            InputStreamBinaryReader(SplitInputStream(encoded, bytesInFirstRead)).apply {
                readBoolean() shouldBe true
                readInt() shouldBe -300_000
                readLong() shouldBe Long.MIN_VALUE
                readFloat() shouldBe 1.5f
                readDouble() shouldBe -0.1
                readString() shouldBe "héllo"
                readBytes() shouldBe byteArrayOf(1, 2, 3)
                readFixed(4) shouldBe byteArrayOf(4, 5, 6, 7)
                skipString()
                skipBytes()
                skipFixed(2)
                readLong() shouldBe 7L
                requireEnd()
            }
        }
    }

    @Test
    fun `every varint split across two refills is read whole at every split position`() {
        for ((value, encoded) in INT_VECTORS) {
            for (bytesInFirstRead in 1..encoded.size) {
                InputStreamBinaryReader(SplitInputStream(encoded + TRAILER, bytesInFirstRead)).apply {
                    readInt() shouldBe value
                    readFixed(TRAILER.size) shouldBe TRAILER
                }
            }
        }
        for ((value, encoded) in LONG_VECTORS) {
            for (bytesInFirstRead in 1..encoded.size) {
                InputStreamBinaryReader(SplitInputStream(encoded + TRAILER, bytesInFirstRead)).apply {
                    readLong() shouldBe value
                    readFixed(TRAILER.size) shouldBe TRAILER
                }
            }
        }
    }

    @Test
    fun `a varint of too many bytes split across two refills is malformed`() {
        val intTooLong = bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x01)
        val longTooLong = bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01)
        for (bytesInFirstRead in 1..MAX_VARINT_INT_BYTES) {
            shouldThrow<AvroMalformedDataException> { InputStreamBinaryReader(SplitInputStream(intTooLong + TRAILER, bytesInFirstRead)).readInt() }
        }
        for (bytesInFirstRead in 1..MAX_VARINT_LONG_BYTES) {
            shouldThrow<AvroMalformedDataException> { InputStreamBinaryReader(SplitInputStream(longTooLong + TRAILER, bytesInFirstRead)).readLong() }
        }
    }

    @Test
    fun `the input ending inside a varint read one byte at a time is an end of input`() {
        shouldThrow<EOFException> { InputStreamBinaryReader(ByteArrayInputStream(ByteArray(0))).readInt() }
        shouldThrow<EOFException> { InputStreamBinaryReader(ByteArrayInputStream(ByteArray(0))).readLong() }
        for ((_, encoded) in INT_VECTORS) {
            for (size in 1 until encoded.size) {
                shouldThrow<EOFException> { InputStreamBinaryReader(OneByteInputStream(encoded.copyOf(size))).readInt() }
            }
        }
        for ((_, encoded) in LONG_VECTORS) {
            for (size in 1 until encoded.size) {
                shouldThrow<EOFException> { InputStreamBinaryReader(OneByteInputStream(encoded.copyOf(size))).readLong() }
            }
        }
    }

    /** Returns [bytesInFirstRead] bytes on the first read, then the rest. */
    private class SplitInputStream(bytes: ByteArray, private var bytesInFirstRead: Int) : InputStream() {
        private val input = ByteArrayInputStream(bytes)

        override fun read(): Int = input.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val limit = if (bytesInFirstRead > 0) minOf(len, bytesInFirstRead) else len
            bytesInFirstRead = 0
            return input.read(b, off, limit)
        }
    }

    /** Returns one byte per read. */
    private class OneByteInputStream(bytes: ByteArray) : InputStream() {
        private val input = ByteArrayInputStream(bytes)

        override fun read(): Int = input.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = input.read(b, off, minOf(len, 1))
    }

    private companion object {
        /** Read after a varint to check that it consumed exactly its own bytes. */
        val TRAILER = ByteArray(MAX_VARINT_LONG_BYTES + 1) { (it + 1).toByte() }

        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    }
}