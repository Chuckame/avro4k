package com.github.avrokotlin.avro4k.exceptions

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroMalformedDataException
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.internal.codec.KotlinxIoEncoder
import com.github.avrokotlin.avro4k.internal.codec.MAX_BINARY_LENGTH
import com.github.avrokotlin.avro4k.internal.decoder.direct.MAX_PRESIZED_BLOCK_ITEMS
import com.github.avrokotlin.avro4k.schema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.LongArraySerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test

/**
 * The item count of an array or map block comes from the input. A corrupted or hostile count must fail as malformed
 * data, without allocating for items that are not there: about 2 billion items fit in five bytes. A large valid block
 * is decoded item by item rather than pre-sized, with the same result.
 */
class CollectionBlockGuardsTest {
    private val intList = ListSerializer(Int.serializer())
    private val stringArray = ArraySerializer(String.serializer())
    private val stringToInt = MapSerializer(String.serializer(), Int.serializer())
    private val intArraySchema = AvroSchema.ArraySchema(AvroSchema.IntSchema())
    private val longArraySchema = AvroSchema.ArraySchema(AvroSchema.LongSchema())
    private val stringArraySchema = AvroSchema.ArraySchema(AvroSchema.StringSchema())
    private val intMapSchema = AvroSchema.MapSchema(AvroSchema.IntSchema())

    // ---- a hostile count ----

    @Test
    fun `a block claiming two billion items in a few bytes fails at the end of the input without allocating them`() {
        val claimed = 2_000_000_000L
        val intItems = blocks {
            writeLong(claimed)
            writeInt(1)
            writeInt(2)
        }
        val stringItems = blocks {
            writeLong(claimed)
            writeString("a")
            writeString("b")
        }
        val entries = blocks {
            writeLong(claimed)
            writeString("a")
            writeInt(1)
        }

        malformedAtEndOfInput { Avro.decodeFromByteArray(intArraySchema, intList, intItems) }
        malformedAtEndOfInput { Avro.decodeFromByteArray(intArraySchema, IntArraySerializer(), intItems) }
        malformedAtEndOfInput { Avro.decodeFromByteArray(longArraySchema, LongArraySerializer(), intItems) }
        malformedAtEndOfInput { Avro.decodeFromByteArray(intArraySchema, SetSerializer(Int.serializer()), intItems) }
        malformedAtEndOfInput { Avro.decodeFromByteArray(stringArraySchema, stringArray, stringItems) }
        malformedAtEndOfInput { Avro.decodeFromByteArray(intMapSchema, stringToInt, entries) }
        // and the same with validation
        malformedAtEndOfInput { VALIDATING.decodeFromByteArray(intArraySchema, IntArraySerializer(), intItems) }
        malformedAtEndOfInput { VALIDATING.decodeFromByteArray(stringArraySchema, stringArray, stringItems) }
    }

    @Test
    fun `a block count above the maximum is malformed instead of being truncated`() {
        // Int.MAX_VALUE + 2 would be truncated to a negative Int, 2^32 + 1 to 1
        for (count in listOf(MAX_BINARY_LENGTH + 1L, Int.MAX_VALUE + 2L, (1L shl 32) + 1)) {
            val bytes = blocks {
                writeLong(count)
                writeInt(1)
                writeLong(0)
            }
            shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(intArraySchema, intList, bytes) }
                .message shouldBe "Cannot read an array or map block of $count items after 0 items: the maximum count of a collection is $MAX_BINARY_LENGTH"
            shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(intMapSchema, stringToInt, bytes) }
        }
    }

    @Test
    fun `a negative block count that cannot be negated is malformed`() {
        // A negative count is followed by the block's byte size; Long.MIN_VALUE stays negative once negated
        val bytes = blocks {
            writeLong(Long.MIN_VALUE)
            writeLong(1)
            writeInt(1)
            writeLong(0)
        }

        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(intArraySchema, intList, bytes) }
            .message shouldStartWith "Malformed data: negative item count"
    }

    @Test
    fun `the total count of a collection over its blocks stays within the maximum`() {
        val firstBlock = MAX_PRESIZED_BLOCK_ITEMS + 1
        val bytes =
            blocks {
                writeLong(firstBlock.toLong())
                repeat(firstBlock) { writeInt(it) }
                writeLong(MAX_BINARY_LENGTH.toLong())
                writeInt(1)
                writeLong(0)
            }

        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(intArraySchema, intList, bytes) }
            .message shouldBe "Cannot read an array or map block of $MAX_BINARY_LENGTH items after $firstBlock items: the maximum count of a collection is $MAX_BINARY_LENGTH"
    }

    @Test
    fun `a negative byte size of a skipped block is malformed`() {
        // The writer has a field the reader does not: it is skipped, block by block
        val writerSchema =
            AvroSchema.RecordSchema(
                Name("$PACKAGE_NAME.Single"),
                listOf(AvroSchema.RecordSchema.Field("skipped", intArraySchema), AvroSchema.RecordSchema.Field("a", AvroSchema.IntSchema()))
            )
        val bytes = blocks {
            writeLong(-1)
            writeLong(-5)
            writeInt(1)
            writeLong(0)
            writeInt(3)
        }

        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(writerSchema, Single.serializer(), bytes) }
            .message shouldBe "Malformed data: negative byte size -5 for an array or map block"
    }

    // ---- large valid blocks ----

    @Test
    fun `large blocks round trip as small ones do`() {
        for (size in listOf(MAX_PRESIZED_BLOCK_ITEMS - 1, MAX_PRESIZED_BLOCK_ITEMS, MAX_PRESIZED_BLOCK_ITEMS + 1, 10_000)) {
            roundTrips(Large.serializer(), Large.of(size))
        }
    }

    @Test
    fun `large blocks split into several blocks decode as one collection`() {
        val sizes = listOf(MAX_PRESIZED_BLOCK_ITEMS + 1, 3, 2000, MAX_PRESIZED_BLOCK_ITEMS)
        val expected = Large.of(sizes.sum())
        val bytes =
            blocks {
                writeBlocks(sizes) { writeInt(it) }
                writeBlocks(sizes) { writeLong(it.toLong()) }
                writeBlocks(sizes) { writeString("s$it") }
                writeBlocks(sizes) { writeLong(it.toLong()) }
                writeBlocks(sizes) {
                    writeString("k$it")
                    writeInt(it)
                }
                writeBlocks(sizes) {
                    writeString("r$it")
                    writeInt(it)
                }
            }

        for (avro in listOf(Avro, VALIDATING)) {
            avro.decodeFromByteArray(Avro.schema<Large>(), Large.serializer(), bytes) shouldBe expected
        }
    }

    @Test
    fun `a key repeated in a later large block keeps its last value`() {
        val size = MAX_PRESIZED_BLOCK_ITEMS + 1
        val bytes =
            blocks {
                writeBlocks(listOf(size, size)) {
                    writeString("k${it % size}")
                    writeInt(it)
                }
            }

        Avro.decodeFromByteArray(intMapSchema, stringToInt, bytes) shouldBe (0 until size).associate { "k$it" to it + size }
    }

    @Test
    fun `a large block written with its byte size is decoded item by item`() {
        val size = MAX_PRESIZED_BLOCK_ITEMS + 1
        val items = blocks { repeat(size) { writeInt(it) } }
        val bytes =
            blocks {
                writeLong(-size.toLong())
                writeLong(items.size.toLong())
                repeat(size) { writeInt(it) }
                writeLong(0)
            }

        Avro.decodeFromByteArray(intArraySchema, IntArraySerializer(), bytes) shouldBe IntArray(size) { it }
    }

    private fun <T> roundTrips(
        serializer: KSerializer<T>,
        value: T,
    ) {
        val schema = Avro.schema(serializer)
        for (avro in listOf(Avro, VALIDATING)) {
            avro.decodeFromByteArray(schema, serializer, avro.encodeToByteArray(schema, serializer, value)) shouldBe value
        }
    }

    private fun malformedAtEndOfInput(decode: () -> Any?) {
        shouldThrow<AvroMalformedDataException>(decode).message shouldStartWith "Unexpected end of input"
    }

    /** Writes one collection as one block per size, then the terminating empty block; item `i` counts across the blocks. */
    private fun KotlinxIoEncoder.writeBlocks(
        sizes: List<Int>,
        writeItem: KotlinxIoEncoder.(Int) -> Unit,
    ) {
        var item = 0
        for (size in sizes) {
            writeLong(size.toLong())
            repeat(size) { writeItem(item++) }
        }
        writeLong(0)
    }

    private fun blocks(write: KotlinxIoEncoder.() -> Unit): ByteArray {
        val buffer = Buffer()
        KotlinxIoEncoder(buffer).write()
        return buffer.readByteArray()
    }

    private companion object {
        val VALIDATING = Avro { validateSerialization = true }
    }
}

@Serializable
private data class Single(val a: Int)

@Serializable
private data class Large(
    val ints: IntArray,
    val longs: LongArray,
    val strings: List<String>,
    val longSet: Set<Long>,
    val map: Map<String, Int>,
    val records: List<Entry>,
) {
    override fun equals(other: Any?): Boolean =
        other is Large && ints.contentEquals(other.ints) && longs.contentEquals(other.longs) && strings == other.strings &&
            longSet == other.longSet && map == other.map && records == other.records

    override fun hashCode(): Int = ints.contentHashCode()

    companion object {
        fun of(size: Int) =
            Large(
                ints = IntArray(size) { it },
                longs = LongArray(size) { it.toLong() },
                strings = List(size) { "s$it" },
                longSet = (0 until size).mapTo(LinkedHashSet()) { it.toLong() },
                map = (0 until size).associate { "k$it" to it },
                records = List(size) { Entry("r$it", it) }
            )
    }
}

@Serializable
private data class Entry(val name: String, val value: Int)