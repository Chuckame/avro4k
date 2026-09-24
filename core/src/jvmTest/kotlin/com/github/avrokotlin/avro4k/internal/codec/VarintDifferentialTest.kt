package com.github.avrokotlin.avro4k.internal.codec

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.avro.io.BinaryData
import kotlin.random.Random

/**
 * Compares avro4k's zig-zag varint writer with Apache Avro's `BinaryData.encodeInt`/`encodeLong`, byte for byte.
 */
internal class VarintDifferentialTest : StringSpec({
    val ints: List<Int> =
        buildList {
            for (bits in 0..31) {
                val power = 1 shl bits
                addAll(listOf(power - 1, power, power + 1, -power, -power - 1, -power + 1))
            }
            addAll(listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE))
            val random = Random(SEED)
            repeat(RANDOM_SAMPLES) {
                add(random.nextInt())
                // also cover the short encodings, which a uniform int almost never hits
                add(random.nextInt() shr random.nextInt(32))
            }
        }
    val longs: List<Long> =
        buildList {
            for (bits in 0..63) {
                val power = 1L shl bits
                addAll(listOf(power - 1, power, power + 1, -power, -power - 1, -power + 1))
            }
            addAll(listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE))
            ints.forEach { add(it.toLong()) }
            val random = Random(SEED)
            repeat(RANDOM_SAMPLES) {
                add(random.nextLong())
                add(random.nextLong() shr random.nextInt(64))
            }
        }

    "int varints are identical to Apache's BinaryData.encodeInt" {
        for (value in ints) {
            val expected = ByteArray(MAX_VARINT_INT_BYTES)
            val expectedLength = BinaryData.encodeInt(value, expected, 0)
            val actual = ByteArray(MAX_VARINT_INT_BYTES)
            val actualLength = encodeZigZagInt(value, actual, 0)

            Pair(value, actual.copyOf(actualLength).toList()) shouldBe Pair(value, expected.copyOf(expectedLength).toList())
        }
    }

    "long varints are identical to Apache's BinaryData.encodeLong" {
        for (value in longs) {
            val expected = ByteArray(MAX_VARINT_LONG_BYTES)
            val expectedLength = BinaryData.encodeLong(value, expected, 0)
            val actual = ByteArray(MAX_VARINT_LONG_BYTES)
            val actualLength = encodeZigZagLong(value, actual, 0)

            Pair(value, actual.copyOf(actualLength).toList()) shouldBe Pair(value, expected.copyOf(expectedLength).toList())
        }
    }
}) {
    private companion object {
        const val SEED = 20260924
        const val RANDOM_SAMPLES = 100_000
    }
}