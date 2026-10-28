package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroMalformedDataException
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.internal.codec.ByteArrayEncoder
import com.github.avrokotlin.avro4k.internal.codec.MAX_BINARY_LENGTH
import com.github.avrokotlin.avro4k.schema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.LongArraySerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.jvm.JvmInline
import kotlin.math.abs
import kotlin.test.Test

/**
 * Avro writes an array or a map as a sequence of blocks (`count, items..., count, items..., 0`, a negative count being
 * followed by the block's size in bytes), and the decoder merges them into one value. avro4k and Apache write one block
 * and the closing empty one, so the decoder reads the count of each next block before deciding to merge another block:
 * these tests decode every collection shape from bytes written by hand, in one block and in several, with and without
 * byte sizes, at the root, nested, inside records, inside unions, and with validation on.
 */
class CollectionBlocksMergeTest {
    private val schema = Avro.schema<MergedCollections>()

    @Test
    fun `collections written in one block decode`() {
        val bytes = encode(FULL, ::oneBlock)
        // the bytes written by hand are the ones avro4k writes
        bytes.toList() shouldBe Avro.encodeToByteArray(schema, MergedCollections.serializer(), FULL).toList()
        decodesTo(bytes, FULL)
    }

    @Test
    fun `collections written in one block with its byte size decode`() {
        decodesTo(encode(FULL, ::oneSizedBlock), FULL)
    }

    @Test
    fun `collections split into several blocks decode as one value`() {
        decodesTo(encode(FULL, ::severalBlocks), FULL)
    }

    @Test
    fun `collections whose every block is written with its byte size decode as one value`() {
        decodesTo(encode(FULL, ::severalSizedBlocks), FULL)
    }

    @Test
    fun `empty collections decode`() {
        for (layout in LAYOUTS) {
            decodesTo(encode(EMPTY, layout), EMPTY)
            decodesTo(encode(EMPTY_IN_UNIONS, layout), EMPTY_IN_UNIONS)
        }
    }

    @Test
    fun `a top level LongArray and Array of String and map decode from several blocks`() {
        val longs = listOf(1L, -2L, 3L, Long.MAX_VALUE)
        val strings = listOf("a", "bb", "ccc", "", "e")
        val entries = listOf("k" to 1, "l" to 2, "m" to 3)
        for (layout in LAYOUTS) {
            for (avro in listOf(Avro, VALIDATING)) {
                avro.decodeFromByteArray(LONGS, LongArraySerializer(), bytes { writeCollection(longs, layout) { writeLong(it) } })
                    .toList() shouldBe longs
                avro.decodeFromByteArray(STRINGS, ArraySerializer(String.serializer()), bytes { writeCollection(strings, layout) { writeString(it) } })
                    .toList() shouldBe strings
                val map = bytes {
                    writeCollection(entries, layout) {
                        writeString(it.first)
                        writeInt(it.second)
                    }
                }
                avro.decodeFromByteArray(INT_MAP, MapSerializer(String.serializer(), Int.serializer()), map) shouldBe entries.toMap()
                avro.decodeFromByteArray(LONGS, LongArraySerializer(), bytes { writeLong(0) }).toList() shouldBe emptyList()
                avro.decodeFromByteArray(INT_MAP, MapSerializer(String.serializer(), Int.serializer()), bytes { writeLong(0) }) shouldBe emptyMap()
            }
        }
    }

    @Test
    fun `records read as maps inside a collection split into blocks decode`() {
        // A record read as a map has no blocks: the collection decoding around it must not look for any
        val pair =
            AvroSchema.RecordSchema(
                Name("com.github.avrokotlin.avro4k.encoding.Pair"),
                listOf(AvroSchema.RecordSchema.Field("a", AvroSchema.IntSchema()), AvroSchema.RecordSchema.Field("b", AvroSchema.IntSchema()))
            )
        val values = listOf(1 to 2, 3 to 4, 5 to 6, 7 to 8)
        val reader = ListSerializer(MapSerializer(String.serializer(), Int.serializer()))
        for (layout in LAYOUTS) {
            val bytes =
                bytes {
                    writeCollection(values, layout) {
                        writeInt(it.first)
                        writeInt(it.second)
                    }
                }
            val single =
                bytes {
                    writeInt(1)
                    writeInt(2)
                }
            for (avro in listOf(Avro, VALIDATING)) {
                avro.decodeFromByteArray(AvroSchema.ArraySchema(pair), reader, bytes) shouldBe values.map { mapOf("a" to it.first, "b" to it.second) }
                avro.decodeFromByteArray(pair, MapSerializer(String.serializer(), Int.serializer()), single) shouldBe mapOf("a" to 1, "b" to 2)
            }
        }
    }

    @Test
    fun `value classes wrapping collections inside a collection split into blocks decode`() {
        // The value class serializer decodes its list on the decoder of the item it is: that item decoder's own
        // collection state, not the one of the decoder merging the outer list
        val value = WrappedCollections(listOf(WrappedInts(listOf(1, 2, 3)), WrappedInts(emptyList()), WrappedInts(listOf(4, 5))), WrappedInts(listOf(6, 7)))
        val wrappedSchema = Avro.schema<WrappedCollections>()
        for (layout in LAYOUTS) {
            val bytes =
                bytes {
                    writeCollection(value.rows, layout) { row -> writeCollection(row.values, layout) { writeInt(it) } }
                    writeCollection(value.single.values, layout) { writeInt(it) }
                }
            for (avro in listOf(Avro, VALIDATING)) {
                avro.decodeFromByteArray(wrappedSchema, WrappedCollections.serializer(), bytes) shouldBe value
            }
        }
    }

    @Test
    fun `a malformed count in a later block fails as it does in the first`() {
        for (count in listOf(MAX_BINARY_LENGTH + 1L, Int.MAX_VALUE + 2L)) {
            val array =
                bytes {
                    writeLong(1)
                    writeLong(10)
                    writeLong(1)
                    writeLong(20)
                    writeLong(count)
                    writeLong(30)
                    writeLong(0)
                }
            val map =
                bytes {
                    writeLong(1)
                    writeString("a")
                    writeInt(1)
                    writeLong(count)
                    writeString("b")
                    writeInt(2)
                    writeLong(0)
                }
            for (avro in listOf(Avro, VALIDATING)) {
                shouldThrow<AvroMalformedDataException> { avro.decodeFromByteArray(LONGS, LongArraySerializer(), array) }
                    .message shouldBe "Cannot read an array or map block of $count items after 2 items: the maximum count of a collection is $MAX_BINARY_LENGTH"
                shouldThrow<AvroMalformedDataException> { avro.decodeFromByteArray(INT_MAP, MapSerializer(String.serializer(), Int.serializer()), map) }
                    .message shouldBe "Cannot read an array or map block of $count items after 1 items: the maximum count of a collection is $MAX_BINARY_LENGTH"
            }
        }
    }

    @Test
    fun `a collection whose closing block is missing fails at the end of the input`() {
        for (avro in listOf(Avro, VALIDATING)) {
            shouldThrow<AvroMalformedDataException> {
                avro.decodeFromByteArray(LONGS, LongArraySerializer(), bytes { writeCollectionBlock(listOf(1L)) { writeLong(it) } })
            }.message shouldStartWith "Unexpected end of input"
            shouldThrow<AvroMalformedDataException> {
                avro.decodeFromByteArray(STRINGS, ArraySerializer(String.serializer()), bytes { writeCollectionBlock(listOf("a", "b")) { writeString(it) } })
            }.message shouldStartWith "Unexpected end of input"
        }
    }

    private fun decodesTo(
        bytes: ByteArray,
        expected: MergedCollections,
    ) {
        for (avro in listOf(Avro, VALIDATING)) {
            avro.decodeFromByteArray(schema, MergedCollections.serializer(), bytes) shouldBe expected
        }
    }

    /** [value] as avro4k writes it, except that each collection is split into the blocks [layout] gives for its size. */
    private fun encode(
        value: MergedCollections,
        layout: (Int) -> List<Int>,
    ): ByteArray =
        bytes {
            writeCollection(value.ints, layout) { writeInt(it) }
            writeCollection(value.longs.toList(), layout) { writeLong(it) }
            writeCollection(value.strings.toList(), layout) { writeString(it) }
            writeCollection(value.byName.entries.toList(), layout) {
                writeString(it.key)
                writeLong(it.value)
            }
            writeCollection(value.nested, layout) { map ->
                writeCollection(map.entries.toList(), layout) { entry ->
                    writeString(entry.key)
                    writeCollection(entry.value, layout) { writeInt(it) }
                }
            }
            writeCollection(value.items, layout) { item ->
                writeString(item.name)
                writeCollection(item.tags, layout) { writeString(it) }
                writeCollection(item.scores.entries.toList(), layout) {
                    writeString(it.key)
                    writeInt(it.value)
                }
            }
            val maybeList = value.maybeList
            if (maybeList == null) {
                writeIndex(branchIndex(MAYBE_LIST_FIELD, AvroSchema.Type.NULL))
            } else {
                writeIndex(branchIndex(MAYBE_LIST_FIELD, AvroSchema.Type.ARRAY))
                writeCollection(maybeList, layout) { writeInt(it) }
            }
            val maybeMap = value.maybeMap
            if (maybeMap == null) {
                writeIndex(branchIndex(MAYBE_MAP_FIELD, AvroSchema.Type.NULL))
            } else {
                writeIndex(branchIndex(MAYBE_MAP_FIELD, AvroSchema.Type.MAP))
                writeCollection(maybeMap.entries.toList(), layout) {
                    writeString(it.key)
                    writeInt(it.value)
                }
            }
        }

    private fun branchIndex(
        field: Int,
        type: AvroSchema.Type,
    ): Int = (schema as AvroSchema.RecordSchema).fields[field].schema.let { it as AvroSchema.UnionSchema }.types.indexOfFirst { it.type == type }

    private companion object {
        val VALIDATING = Avro { validateSerialization = true }

        val LONGS = AvroSchema.ArraySchema(AvroSchema.LongSchema())
        val STRINGS = AvroSchema.ArraySchema(AvroSchema.StringSchema())
        val INT_MAP = AvroSchema.MapSchema(AvroSchema.IntSchema())

        const val MAYBE_LIST_FIELD = 6
        const val MAYBE_MAP_FIELD = 7

        val FULL =
            MergedCollections(
                ints = listOf(1, 2, 3, 4),
                longs = longArrayOf(10, -20, 30),
                strings = arrayOf("a", "b", "c", "d", "e"),
                byName = mapOf("x" to 1L, "y" to 2L, "z" to 3L),
                nested = listOf(mapOf("p" to listOf(1, 2, 3), "q" to emptyList(), "r" to listOf(4)), emptyMap(), mapOf("s" to listOf(5, 6))),
                items = listOf(
                    MergedItem("i", listOf("t1", "t2", "t3"), mapOf("s" to 1, "t" to 2)),
                    MergedItem("j", emptyList(), emptyMap()),
                    MergedItem("k", listOf("u"), mapOf("v" to 3))
                ),
                maybeList = listOf(7, 8, 9),
                maybeMap = mapOf("m" to 1, "n" to 2)
            )

        val EMPTY = MergedCollections(emptyList(), LongArray(0), emptyArray(), emptyMap(), emptyList(), emptyList(), null, null)

        val EMPTY_IN_UNIONS = EMPTY.copy(maybeList = emptyList(), maybeMap = emptyMap())

        /** Block layouts: the item count of each block, negative for a block written with its byte size. */
        val LAYOUTS = listOf(::oneBlock, ::oneSizedBlock, ::severalBlocks, ::severalSizedBlocks)

        fun oneBlock(size: Int): List<Int> = if (size == 0) emptyList() else listOf(size)

        fun oneSizedBlock(size: Int): List<Int> = if (size == 0) emptyList() else listOf(-size)

        /** One block per item up to 3 items, then a first and last block of one item around a sized one. */
        fun severalBlocks(size: Int): List<Int> =
            when (size) {
                0 -> emptyList()
                1 -> listOf(1)
                2 -> listOf(1, -1)
                else -> listOf(1, -(size - 2), 1)
            }

        fun severalSizedBlocks(size: Int): List<Int> = severalBlocks(size).map { -abs(it) }
    }
}

@Serializable
private data class MergedCollections(
    val ints: List<Int>,
    val longs: LongArray,
    val strings: Array<String>,
    val byName: Map<String, Long>,
    val nested: List<Map<String, List<Int>>>,
    val items: List<MergedItem>,
    val maybeList: List<Int>?,
    val maybeMap: Map<String, Int>?,
) {
    override fun equals(other: Any?): Boolean =
        other is MergedCollections && ints == other.ints && longs.contentEquals(other.longs) && strings.contentEquals(other.strings) &&
            byName == other.byName && nested == other.nested && items == other.items && maybeList == other.maybeList && maybeMap == other.maybeMap

    override fun hashCode(): Int = ints.hashCode()

    override fun toString(): String =
        "MergedCollections(ints=$ints, longs=${longs.toList()}, strings=${strings.toList()}, byName=$byName, nested=$nested, items=$items, " +
            "maybeList=$maybeList, maybeMap=$maybeMap)"
}

@Serializable
@JvmInline
private value class WrappedInts(val values: List<Int>)

@Serializable
private data class WrappedCollections(val rows: List<WrappedInts>, val single: WrappedInts)

@Serializable
private data class MergedItem(val name: String, val tags: List<String>, val scores: Map<String, Int>)

private fun bytes(write: ByteArrayEncoder.() -> Unit): ByteArray = ByteArrayEncoder().apply { write() }.toByteArray()

/**
 * Writes [items] as one collection, split into one block per entry of [layout] (the item count of each block, negative
 * for a block written with a negative count followed by its byte size), then the closing empty block.
 */
private fun <I> ByteArrayEncoder.writeCollection(
    items: List<I>,
    layout: (Int) -> List<Int>,
    writeItem: ByteArrayEncoder.(I) -> Unit,
) {
    val blocks = layout(items.size)
    check(blocks.sumOf { abs(it) } == items.size && blocks.none { it == 0 })
    var next = 0
    for (block in blocks) {
        val count = abs(block)
        val blockItems = items.subList(next, next + count)
        next += count
        if (block < 0) {
            val content = bytes { blockItems.forEach { writeItem(it) } }
            writeLong(-count.toLong())
            writeLong(content.size.toLong())
            writeFixed(content)
        } else {
            writeCollectionBlock(blockItems, writeItem)
        }
    }
    writeLong(0)
}

/** One block of [items], without the closing empty block. */
private fun <I> ByteArrayEncoder.writeCollectionBlock(
    items: List<I>,
    writeItem: ByteArrayEncoder.(I) -> Unit,
) {
    writeLong(items.size.toLong())
    items.forEach { writeItem(it) }
}