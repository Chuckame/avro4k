package com.github.avrokotlin.avro4k.internal.codec

import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test

/**
 * The long writers work on the two 32-bit halves of the long where `Long` arithmetic is emulated: every value here is
 * checked against a plain `Long` encoder, on each platform.
 */
class ZigZagLongWriteTest {
    @Test
    fun `the encoder working on the halves of a long writes the bytes of a plain long encoder`() {
        for (value in VALUES) {
            val expected = referenceEncode(value)
            val bytes = ByteArray(MAX_VARINT_LONG_BYTES + 4) { 0x55 }
            encodeZigZagLong(value.toInt(), (value ushr 32).toInt(), bytes, 2) shouldBe expected.size
            bytes.copyOfRange(2, 2 + expected.size).describe(value) shouldBe expected.describe(value)
            // nothing written outside of the varint
            (bytes.take(2) + bytes.drop(2 + expected.size)).all { it == 0x55.toByte() } shouldBe true
        }
    }

    @Test
    fun `writeLong writes the bytes of a plain long encoder and they are read back`() {
        for (value in VALUES) {
            val bytes = ByteArrayEncoder().apply { writeLong(value) }.toByteArray()
            bytes.describe(value) shouldBe referenceEncode(value).describe(value)
            ByteArrayDecoder(bytes).readLong() shouldBe value
        }
    }

    @Test
    fun `writeLong at the end of a chunk writes every value whole and grows the chunk`() {
        // the first chunk takes 256 bytes: the long has from 10 bytes of room to none left
        for (prefix in 246..256) {
            val filler = ByteArray(prefix) { (it * 3).toByte() }
            for (value in VALUES.take(END_OF_CHUNK_VALUES)) {
                val bytes =
                    ByteArrayEncoder()
                        .apply {
                            writeFixed(filler)
                            writeLong(value)
                        }.toByteArray()
                bytes.copyOfRange(0, prefix).contentEquals(filler) shouldBe true
                bytes.copyOfRange(prefix, bytes.size).describe(value) shouldBe referenceEncode(value).describe(value)
            }
        }
    }

    @Test
    fun `every value written one after the other across many chunks is read back in order`() {
        val expected = VALUES.flatMap { referenceEncode(it).asList() }.toByteArray()

        val kept = ByteArrayEncoder().apply { VALUES.forEach { writeLong(it) } }.toByteArray()
        kept.contentEquals(expected) shouldBe true

        val handed = mutableListOf<Byte>()
        val writeChunk = { chunk: ByteArray, length: Int -> handed += chunk.copyOf(length).asList() }
        ByteArrayEncoder(writeChunk).apply { VALUES.forEach { writeLong(it) } }.writeChunksTo(writeChunk)
        handed.toByteArray().contentEquals(expected) shouldBe true

        val decoder = ByteArrayDecoder(kept)
        for (value in VALUES) {
            decoder.readLong() shouldBe value
        }
    }

    private companion object {
        /** The values written at every position near the end of a chunk: the edge cases, then some random ones. */
        const val END_OF_CHUNK_VALUES = 400

        val VALUES: List<Long> =
            buildList {
                add(0L)
                add(1L)
                add(-1L)
                for (k in 1..9) {
                    // where the length of the varint changes: at 7 bits of the zig-zag value, and of the long itself
                    for (bits in listOf(7 * k - 1, 7 * k)) {
                        val power = 1L shl bits
                        add(power)
                        add(power - 1)
                        add(-power)
                        add(-power - 1)
                    }
                }
                for (int in listOf(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())) {
                    add(int - 1)
                    add(int)
                    add(int + 1)
                }
                for (power in listOf(1L shl 32, -(1L shl 32))) {
                    add(power - 1)
                    add(power)
                    add(power + 1)
                }
                add(Long.MIN_VALUE)
                add(Long.MIN_VALUE + 1)
                add(Long.MAX_VALUE)
                add(Long.MAX_VALUE - 1)
                // a low half that alone would take a single byte, with a high half that does not belong to it
                for (small in listOf(-64L, -1L, 0L, 1L, 63L)) {
                    add(small + (1L shl 32))
                    add(small - (1L shl 32))
                    add(small xor Long.MIN_VALUE)
                }
                val random = Random(20261029)
                repeat(1_000) { add(random.nextLong()) }
                // every length equally likely
                repeat(2_000) { add(random.nextLong() shr random.nextInt(64)) }
            }

        /** The zig-zag varint of [value] in plain `Long` arithmetic, one 7-bit group at a time. */
        fun referenceEncode(value: Long): ByteArray {
            var n = (value shl 1) xor (value shr 63)
            val bytes = mutableListOf<Byte>()
            while (n and 0x7FL.inv() != 0L) {
                bytes += ((n and 0x7FL) or 0x80L).toByte()
                n = n ushr 7
            }
            bytes += n.toByte()
            return bytes.toByteArray()
        }

        /** The bytes in hexadecimal after the value they encode, for a readable failure. */
        fun ByteArray.describe(value: Long): String = "$value: " + joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}