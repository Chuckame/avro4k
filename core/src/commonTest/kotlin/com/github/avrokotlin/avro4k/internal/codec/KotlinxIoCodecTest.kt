package com.github.avrokotlin.avro4k.internal.codec

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlin.test.Test

class KotlinxIoCodecTest {
    @Test
    fun `every encoder method round trips through the decoder`() {
        val buffer = Buffer()
        KotlinxIoEncoder(buffer).apply {
            writeNull()
            writeBoolean(true)
            writeBoolean(false)
            writeInt(-42)
            writeLong(Long.MIN_VALUE)
            writeFloat(1.5f)
            writeDouble(-0.1)
            writeString("héllo")
            writeString("")
            writeString("wörld".encodeToByteArray())
            writeString(ByteArray(0))
            writeBytes(byteArrayOf(1, 2, 3))
            writeBytes(ByteArray(0))
            writeFixed(byteArrayOf(4, 5, 6, 7))
            writeEnum(3)
            writeIndex(1)
            writeArrayStart(2)
            startItem()
            writeInt(10)
            startItem()
            writeInt(20)
            writeArrayEnd()
            writeArrayStart(0)
            writeArrayEnd()
            writeMapStart(1)
            startItem()
            writeString("k")
            writeLong(7)
            writeMapEnd()
            writeMapStart(0)
            writeMapEnd()
        }
        KotlinxIoDecoder(buffer).apply {
            readNull()
            readBoolean() shouldBe true
            readBoolean() shouldBe false
            readInt() shouldBe -42
            readLong() shouldBe Long.MIN_VALUE
            readFloat() shouldBe 1.5f
            readDouble() shouldBe -0.1
            readString() shouldBe "héllo"
            readString() shouldBe ""
            readStringBytes() shouldBe "wörld".encodeToByteArray()
            readStringBytes() shouldBe ByteArray(0)
            readBytes() shouldBe byteArrayOf(1, 2, 3)
            readBytes() shouldBe ByteArray(0)
            readFixed(4) shouldBe byteArrayOf(4, 5, 6, 7)
            readEnum() shouldBe 3
            readIndex() shouldBe 1
            readArrayStart() shouldBe 2
            readInt() shouldBe 10
            readInt() shouldBe 20
            arrayNext() shouldBe 0
            readArrayStart() shouldBe 0
            readMapStart() shouldBe 1
            readString() shouldBe "k"
            readLong() shouldBe 7
            mapNext() shouldBe 0
            readMapStart() shouldBe 0
        }
        buffer.exhausted() shouldBe true
    }

    @Test
    fun `the encoder writes the bytes the Avro spec says`() {
        val buffer = Buffer()
        KotlinxIoEncoder(buffer).apply {
            writeNull()
            writeBoolean(true)
            writeFloat(1.0f)
            writeDouble(1.0)
            writeString("ab")
            writeString(byteArrayOf(0x63))
            writeBytes(byteArrayOf(9))
            writeBytes(ByteArray(0))
            writeFixed(byteArrayOf(8, 7))
            writeEnum(2)
            writeIndex(0)
            // one block of 2 items, then the empty block ending the array
            writeArrayStart(2)
            startItem()
            writeBoolean(false)
            startItem()
            writeBoolean(true)
            writeArrayEnd()
            // an empty map is the ending empty block only
            writeMapStart(0)
            writeMapEnd()
        }
        buffer.readByteArray() shouldBe
            bytes(
                0x01,
                0x00,
                0x00,
                0x80,
                0x3F,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0xF0,
                0x3F,
                0x04,
                0x61,
                0x62,
                0x02,
                0x63,
                0x02,
                0x09,
                0x00,
                0x08,
                0x07,
                0x04,
                0x00,
                0x04,
                0x00,
                0x01,
                0x00,
                0x00
            )
    }

    @Test
    fun `values spanning several segments are read whole`() {
        val big = ByteArray(3 * 8192 + 17) { (it % 251).toByte() }
        val bigString = "é".repeat(3 * 8192)
        val buffer = Buffer()
        KotlinxIoEncoder(buffer).apply {
            writeBytes(big)
            writeString(bigString)
            writeString(big)
            writeFixed(big)
            writeInt(123)
        }
        KotlinxIoDecoder(buffer).apply {
            readBytes() shouldBe big
            readString() shouldBe bigString
            readStringBytes() shouldBe big
            readFixed(big.size) shouldBe big
            readInt() shouldBe 123
        }
    }

    @Test
    fun `returned arrays are not shared with the source`() {
        val buffer = Buffer()
        KotlinxIoEncoder(buffer).apply {
            writeBytes(byteArrayOf(1, 2))
            writeBytes(byteArrayOf(1, 2))
        }
        val decoder = KotlinxIoDecoder(buffer)
        val first = decoder.readBytes()
        first[0] = 99
        decoder.readBytes() shouldBe byteArrayOf(1, 2)
    }

    @Test
    fun `skipping reads past every value`() {
        val buffer = Buffer()
        KotlinxIoEncoder(buffer).apply {
            writeString("skipped")
            writeBytes(byteArrayOf(1, 2, 3))
            writeFixed(byteArrayOf(4, 5))
            writeArrayStart(2)
            startItem()
            writeInt(1)
            startItem()
            writeInt(2)
            writeArrayEnd()
            writeMapStart(1)
            startItem()
            writeString("k")
            writeString("v")
            writeMapEnd()
            writeInt(42)
        }
        KotlinxIoDecoder(buffer).apply {
            skipString()
            skipBytes()
            skipFixed(2)
            // without a block byte size, a block's items must be skipped one by one by the caller
            skipArray() shouldBe 2
            readInt()
            readInt()
            skipArray() shouldBe 0
            skipMap() shouldBe 1
            skipString()
            skipString()
            skipMap() shouldBe 0
            readInt() shouldBe 42
        }
    }

    @Test
    fun `blocks with a negative count carry their byte size`() {
        // array of ints [1, 2] as a block of -2 items spanning 2 bytes, then [3] as a positive block, then the end
        val encoded = bytes(0x03, 0x04, 0x02, 0x04, 0x02, 0x06, 0x00, 0x54)

        KotlinxIoDecoder(Buffer().apply { write(encoded) }).apply {
            readArrayStart() shouldBe 2
            readInt() shouldBe 1
            readInt() shouldBe 2
            arrayNext() shouldBe 1
            readInt() shouldBe 3
            arrayNext() shouldBe 0
            readInt() shouldBe 42
        }
        KotlinxIoDecoder(Buffer().apply { write(encoded) }).apply {
            // the sized block is skipped whole, the positive one is left to the caller
            skipArray() shouldBe 1
            readInt() shouldBe 3
            skipArray() shouldBe 0
            readInt() shouldBe 42
        }
        KotlinxIoDecoder(Buffer().apply { write(encoded) }).apply {
            readMapStart() shouldBe 2
        }
    }

    @Test
    fun `a negative length is rejected before anything is read`() {
        val lengthOfMinusOne = bytes(0x01, 0x00, 0x00)
        val reads: List<AvroBinaryDecoder.() -> Unit> =
            listOf(
                { readString() },
                { readStringBytes() },
                { readBytes() },
                { skipString() },
                { skipBytes() }
            )
        for (read in reads) {
            val source = Buffer().apply { write(lengthOfMinusOne) }
            shouldThrow<SerializationException> { KotlinxIoDecoder(source).read() }
            source.size shouldBe 2L
        }
    }

    @Test
    fun `a length above the maximum array size is rejected before anything is allocated`() {
        val reads: List<AvroBinaryDecoder.() -> Unit> =
            listOf(
                { readString() },
                { readStringBytes() },
                { readBytes() },
                { skipString() },
                { skipBytes() }
            )
        for (read in reads) {
            val source = Buffer().also { KotlinxIoEncoder(it).writeLong(MAX_BINARY_LENGTH.toLong() + 1) }
            shouldThrow<SerializationException> { KotlinxIoDecoder(source).read() }
        }
    }

    @Test
    fun `the length bound is inclusive`() {
        checkBinaryLength(0) shouldBe 0
        checkBinaryLength(MAX_BINARY_LENGTH.toLong()) shouldBe MAX_BINARY_LENGTH
        shouldThrow<SerializationException> { checkBinaryLength(MAX_BINARY_LENGTH.toLong() + 1) }
        shouldThrow<SerializationException> { checkBinaryLength(-1) }
        shouldThrow<SerializationException> { checkBinaryLength(Long.MIN_VALUE) }
        shouldThrow<SerializationException> { checkBinaryLength(Long.MAX_VALUE) }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}