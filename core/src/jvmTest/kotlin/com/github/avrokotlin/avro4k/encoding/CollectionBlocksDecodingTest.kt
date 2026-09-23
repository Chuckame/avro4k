package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Avro writes an array or a map as a sequence of blocks: `count, items..., count, items..., 0`. A negative count is
 * followed by the block's size in bytes. avro4k's own encoder always writes a single block, but other writers (Apache's
 * blocking encoders, object container files...) split collections, and the direct decoder must read all the blocks of
 * one collection into one value. The bytes below are written by hand so the block boundaries are explicit.
 *
 * Varints are zig-zag encoded: 0 -> 00, 1 -> 02, 2 -> 04, 3 -> 06, -1 -> 01.
 */
internal class CollectionBlocksDecodingTest : StringSpec({
    "decodes an array and a map split into several blocks, including a negative-count block" {
        val bytes =
            hex(
                // values = [1, 2, 3]: a block of 2 items, a block of -1 items (one item, 1 byte long), the end
                "04 02 04",
                "01 02 06",
                "00",
                // byName = {a: 1, b: 2}: two blocks of one entry each, the end
                "02 02 61 02",
                "02 02 62 04",
                "00"
            )

        Avro.decodeFromByteArray(Blocks.serializer(), bytes) shouldBe Blocks(listOf(1, 2, 3), mapOf("a" to 1, "b" to 2))
    }

    "decodes nested collections whose outer and inner levels are both split into blocks" {
        val bytes =
            hex(
                // outer block 1: one item, the inner list [1, 2] in two blocks
                "02",
                "02 02",
                "02 04",
                "00",
                // outer block 2: one item, the inner list [3] in one block
                "02",
                "02 06",
                "00",
                // end of the outer list
                "00"
            )

        Avro.decodeFromByteArray(Matrix.serializer(), bytes) shouldBe Matrix(listOf(listOf(1, 2), listOf(3)))
    }

    "decodes empty collections" {
        Avro.decodeFromByteArray(Blocks.serializer(), hex("00 00")) shouldBe Blocks(emptyList(), emptyMap())
    }

    "round trips records whose collection fields alternate between collection serializers, repeatedly" {
        val value =
            Alternating(
                first = listOf(1, 2, 3),
                second = mapOf("a" to 1, "b" to 2),
                third = listOf("x", "y"),
                fourth = mapOf(1 to listOf(1L, 2L)),
                matrix = listOf(listOf(1, 2, 3), emptyList(), listOf(4))
            )
        val bytes = Avro.encodeToByteArray(Alternating.serializer(), value)

        repeat(200) {
            Avro.decodeFromByteArray(Alternating.serializer(), bytes) shouldBe value
        }
    }

    "decodes collections concurrently from several threads" {
        val blocks = Blocks(listOf(1, 2, 3), mapOf("a" to 1))
        val alternating =
            Alternating(
                first = listOf(9, 8),
                second = mapOf("k" to 3),
                third = listOf("s"),
                fourth = mapOf(2 to listOf(5L, 6L)),
                matrix = listOf(listOf(7))
            )
        val blocksBytes = Avro.encodeToByteArray(Blocks.serializer(), blocks)
        val alternatingBytes = Avro.encodeToByteArray(Alternating.serializer(), alternating)

        val threadCount = 8
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val tasks =
                List(threadCount) { index ->
                    Callable {
                        repeat(500) {
                            if (index % 2 == 0) {
                                Avro.decodeFromByteArray(Blocks.serializer(), blocksBytes) shouldBe blocks
                            } else {
                                Avro.decodeFromByteArray(Alternating.serializer(), alternatingBytes) shouldBe alternating
                            }
                        }
                    }
                }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES)
        }
    }
}) {
    @Serializable
    private data class Blocks(val values: List<Int>, val byName: Map<String, Int>)

    @Serializable
    private data class Matrix(val rows: List<List<Int>>)

    @Serializable
    private data class Alternating(
        val first: List<Int>,
        val second: Map<String, Int>,
        val third: List<String>,
        val fourth: Map<Int, List<Long>>,
        val matrix: List<List<Int>>,
    )
}

/** Space-separated hex bytes, e.g. `hex("04 02", "00")`; one argument per block keeps the block boundaries visible. */
private fun hex(vararg groups: String): ByteArray =
    groups.flatMap { it.split(' ') }.filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()