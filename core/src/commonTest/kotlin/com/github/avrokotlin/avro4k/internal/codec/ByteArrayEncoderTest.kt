package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.internal.codec.VarintTest.Companion.INT_VECTORS
import com.github.avrokotlin.avro4k.internal.codec.VarintTest.Companion.LONG_VECTORS
import com.github.avrokotlin.avro4k.internal.codec.VarintTest.Companion.encodeInt
import com.github.avrokotlin.avro4k.internal.codec.VarintTest.Companion.encodeLong
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ByteArrayEncoderTest {
    @Test
    fun `every varint length is written whole wherever it falls against the end of the array`() {
        // Offsets around the initial capacity: the varint fits, ends exactly at the end, or needs the array to grow
        for (prefix in 240..260) {
            val filler = ByteArray(prefix) { it.toByte() }
            for ((value, encoded) in INT_VECTORS) {
                encoded(filler) { writeInt(value) } shouldBe filler + encoded
            }
            for ((value, encoded) in LONG_VECTORS) {
                encoded(filler) { writeLong(value) } shouldBe filler + encoded
            }
        }
    }

    @Test
    fun `the bytes written across several growths come out in order`() {
        val values = (0 until 5_000).map { it * 37 - 100_000 }
        val expected = values.map { encodeInt(it) } + values.map { encodeLong(it.toLong() shl 20) }
        val bytes =
            ByteArrayEncoder()
                .apply {
                    values.forEach { writeInt(it) }
                    values.forEach { writeLong(it.toLong() shl 20) }
                }.toByteArray()
        // about 40 KB: eight doublings of the array
        bytes shouldBe expected.flatMap { it.asList() }.toByteArray()
    }

    @Test
    fun `a payload of several hundred kilobytes mixing values and varints comes out whole`() {
        val chunk = ByteArray(1_000) { (it * 13).toByte() }
        val bytes =
            ByteArrayEncoder()
                .apply {
                    repeat(400) {
                        writeFixed(chunk)
                        writeLong(Long.MIN_VALUE + it)
                    }
                }.toByteArray()
        bytes shouldBe (0 until 400).map { chunk + encodeLong(Long.MIN_VALUE + it) }.flatMap { it.asList() }.toByteArray()
    }

    @Test
    fun `given a chunk writer the full chunks are handed as they fill and their array reused once at the largest size`() {
        val chunk = ByteArray(1_000) { (it * 13).toByte() }
        val written = mutableListOf<Byte>()
        val arrays = mutableListOf<ByteArray>()
        val writeChunk = { array: ByteArray, length: Int ->
            if (arrays.none { it === array }) arrays += array
            written += array.copyOf(length).asList()
        }
        ByteArrayEncoder(writeChunk)
            .apply {
                repeat(1_000) {
                    writeFixed(chunk)
                    writeLong(Long.MIN_VALUE + it)
                }
            }.writeChunksTo(writeChunk)
        written.toByteArray() shouldBe (0 until 1_000).map { chunk + encodeLong(Long.MIN_VALUE + it) }.flatMap { it.asList() }.toByteArray()
        // 256 B doubled up to 64 KiB, then that array alone for the rest of the megabyte
        arrays.size shouldBe 9
    }

    @Test
    fun `a value larger than the doubled array is written whole`() {
        val large = ByteArray(5_000) { (it * 7).toByte() }
        val prefix = byteArrayOf(1, 2, 3)
        encoded(prefix) { writeFixed(large) } shouldBe prefix + large
        encoded(prefix) { writeBytes(large) } shouldBe prefix + encodeInt(large.size) + large
    }

    @Test
    fun `floats and doubles are written little-endian with every NaN as the canonical one`() {
        for (value in listOf(0f, -0f, 1.5f, -3.25f, Float.MIN_VALUE, Float.MAX_VALUE, Float.NEGATIVE_INFINITY, Float.NaN)) {
            ByteArrayEncoder().apply { writeFloat(value) }.toByteArray() shouldBe littleEndian(value.toBits().toLong(), 4)
        }
        for (value in listOf(0.0, -0.0, 1e-300, -2.5, Double.MIN_VALUE, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NaN)) {
            ByteArrayEncoder().apply { writeDouble(value) }.toByteArray() shouldBe littleEndian(value.toBits(), 8)
        }
        val otherNaN = Float.fromBits(0x7FC00001)
        ByteArrayEncoder().apply { writeFloat(otherNaN) }.toByteArray() shouldBe littleEndian(Float.NaN.toBits().toLong(), 4)
        // Written next to the end of the array, so that the value needs it to grow
        val filler = ByteArray(253)
        encoded(filler) { writeDouble(-2.5) } shouldBe filler + littleEndian((-2.5).toBits(), 8)
        encoded(filler) { writeFloat(-3.25f) } shouldBe filler + littleEndian((-3.25f).toBits().toLong(), 4)
    }

    @Test
    fun `an empty string or bytes or collection is a single zero byte`() {
        ByteArrayEncoder().apply { writeString("") }.toByteArray() shouldBe byteArrayOf(0)
        ByteArrayEncoder().apply { writeString(ByteArray(0)) }.toByteArray() shouldBe byteArrayOf(0)
        ByteArrayEncoder().apply { writeBytes(ByteArray(0)) }.toByteArray() shouldBe byteArrayOf(0)
        ByteArrayEncoder()
            .apply {
                writeArrayStart(0)
                writeArrayEnd()
            }.toByteArray() shouldBe byteArrayOf(0)
        ByteArrayEncoder()
            .apply {
                writeMapStart(0)
                writeMapEnd()
            }.toByteArray() shouldBe byteArrayOf(0)
    }

    @Test
    fun `nothing written is an empty array`() {
        ByteArrayEncoder().toByteArray() shouldBe ByteArray(0)
        ByteArrayEncoder().apply { writeNull() }.toByteArray() shouldBe ByteArray(0)
    }

    private fun encoded(
        prefix: ByteArray,
        write: ByteArrayEncoder.() -> Unit,
    ): ByteArray =
        ByteArrayEncoder()
            .apply {
                writeFixed(prefix)
                write()
            }.toByteArray()

    private fun littleEndian(
        bits: Long,
        size: Int,
    ): ByteArray = ByteArray(size) { (bits ushr (8 * it)).toByte() }
}