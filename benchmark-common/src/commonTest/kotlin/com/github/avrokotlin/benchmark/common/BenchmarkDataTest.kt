package com.github.avrokotlin.benchmark.common

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.benchmark.common.complex.ComplexBenchmark
import com.github.avrokotlin.benchmark.common.lists.ListsBenchmark
import com.github.avrokotlin.benchmark.common.simple.SimpleBenchmark
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Runs each benchmark's set-up and both measured methods once, outside the harness, and pins the bytes each set-up
 * encodes: the same constants on every platform prove that every platform measures the same work.
 */
class BenchmarkDataTest {
    @Test
    fun simple() {
        for ((recordCount, expected) in listOf(1 to Encoded(114, -796332754), 25 to Encoded(2772, -1697329536))) {
            val benchmark = SimpleBenchmark().apply { this.recordCount = recordCount }
            benchmark.setup()
            assertEquals(expected, Encoded.of(benchmark.data), "simple @$recordCount")
            assertContentEquals(benchmark.data, benchmark.write())
            assertContentEquals(benchmark.data, Avro.encodeToByteArray(benchmark.schema, benchmark.read()))
        }
    }

    @Test
    fun complex() {
        for ((clientCount, expected) in listOf(1 to Encoded(462, 1776381526), 15 to Encoded(48589, -954338421))) {
            val benchmark = ComplexBenchmark().apply { this.clientCount = clientCount }
            benchmark.setup()
            assertEquals(expected, Encoded.of(benchmark.data), "complex @$clientCount")
            assertContentEquals(benchmark.data, benchmark.write())
            assertContentEquals(benchmark.data, Avro.encodeToByteArray(benchmark.schema, benchmark.read()))
        }
    }

    @Test
    fun lists() {
        for ((entryCount, expected) in listOf(100 to Encoded(96324, -66846477))) {
            val benchmark = ListsBenchmark().apply { this.entryCount = entryCount }
            benchmark.setup()
            assertEquals(expected, Encoded.of(benchmark.data), "lists @$entryCount")
            assertContentEquals(benchmark.data, benchmark.write())
            assertContentEquals(benchmark.data, Avro.encodeToByteArray(benchmark.schema, benchmark.read()))
        }
    }

    /** The size and FNV-1a hash of an encoded payload. */
    private data class Encoded(val size: Int, val hash: Int) {
        companion object {
            fun of(bytes: ByteArray): Encoded {
                var hash = -0x7ee3623b
                for (byte in bytes) {
                    hash = (hash xor (byte.toInt() and 0xFF)) * 0x01000193
                }
                return Encoded(bytes.size, hash)
            }
        }
    }
}
