package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroAlias
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.internal.codec.ByteArrayEncoder
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import com.github.avrokotlin.avro4k.schema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.abs
import kotlin.test.Test

/**
 * A decoder keeps the decoder of the records, array blocks and map blocks it begins, and hands it the next structure of
 * the same kind instead of creating one per value. These tests decode every shape where a kept decoder could carry state
 * from one structure into the next: siblings of each kind at several depths, recursion, record types alternating on one
 * decoder (by schema and by descriptor), collections in several blocks, and serializers that do not follow the
 * begin-end protocol, which must still decode correctly on a new decoder.
 */
class DecoderReuseTest {
    @Test
    fun `sibling records lists and maps decode at several depths`() {
        roundTrips(Tree.serializer(), TREE)
        roundTrips(ListSerializer(Tree.serializer()), listOf(TREE, TREE.copy(first = branch(0), branches = emptyList()), TREE))
    }

    @Test
    fun `a recursive type decodes six levels deep`() {
        roundTrips(Node.serializer(), node(6))
    }

    @Test
    fun `two record types alternating as fields of one parent decode`() {
        val a = RecA(1, "a")
        val b = RecB(true, "b", 2L)
        roundTrips(
            Alternating.serializer(),
            Alternating(
                a = a,
                b = b,
                a2 = RecA(3, "c"),
                b2 = RecB(false, "d", -4L),
                pairs = List(4) { AB(RecA(it, "a$it"), RecB(it % 2 == 0, "b$it", it * 10L)) },
                recAs = listOf(a, RecA(5, "e")),
                bs = listOf(b, RecB(false, "f", 6L))
            )
        )
    }

    @Test
    fun `records of one writer schema read into two reader types in turn decode`() {
        // One writer schema instance, read alternately into two descriptors: each must get its own decoding steps
        val shared = AvroSchema.RecordSchema(
            Name("Shared"),
            listOf(AvroSchema.RecordSchema.Field("x", AvroSchema.IntSchema()), AvroSchema.RecordSchema.Field("y", AvroSchema.StringSchema()))
        )
        val writer =
            AvroSchema.RecordSchema(
                Name("Readers"),
                listOf(
                    AvroSchema.RecordSchema.Field("first", shared),
                    AvroSchema.RecordSchema.Field("second", shared),
                    AvroSchema.RecordSchema.Field("firsts", AvroSchema.ArraySchema(shared)),
                    AvroSchema.RecordSchema.Field("seconds", AvroSchema.ArraySchema(shared)),
                    AvroSchema.RecordSchema.Field("third", shared)
                )
            )
        val bytes =
            bytes {
                writeShared(1, "a")
                writeShared(2, "b")
                writeBlocks(listOf(3 to "c", 4 to "d"), listOf(2)) { writeShared(it.first, it.second) }
                writeBlocks(listOf(5 to "e", 6 to "f"), listOf(2)) { writeShared(it.first, it.second) }
                writeShared(7, "g")
            }
        for (avro in AVROS) {
            avro.decodeFromByteArray(writer, Readers.serializer(), bytes) shouldBe
                Readers(OnlyX(1), OnlyY("b"), listOf(OnlyX(3), OnlyX(4)), listOf(OnlyY("e"), OnlyY("f")), OnlyX(7))
        }
    }

    @Test
    fun `a list of records whose writer type alternates through a union decodes`() {
        // One descriptor, read alternately from two writer schemas whose fields are in another order
        val first = AvroSchema.RecordSchema(
            Name("First"),
            listOf(AvroSchema.RecordSchema.Field("x", AvroSchema.IntSchema()), AvroSchema.RecordSchema.Field("y", AvroSchema.StringSchema()))
        )
        val second =
            AvroSchema.RecordSchema(
                Name("Second"),
                listOf(
                    AvroSchema.RecordSchema.Field("y", AvroSchema.StringSchema()),
                    AvroSchema.RecordSchema.Field("flag", AvroSchema.BooleanSchema()),
                    AvroSchema.RecordSchema.Field("x", AvroSchema.IntSchema())
                )
            )
        val values = List(6) { XY(it, "v$it") }
        val bytes =
            bytes {
                writeBlocks(values, listOf(2, -3, 1)) {
                    if (it.x % 2 == 0) {
                        writeIndex(0)
                        writeInt(it.x)
                        writeString(it.y)
                    } else {
                        writeIndex(1)
                        writeString(it.y)
                        writeBoolean(true)
                        writeInt(it.x)
                    }
                }
            }
        val nullableValues = listOf(XY(0, "a"), null, XY(1, "b"), XY(2, "c"), null, XY(3, "d"))
        val nullableBytes =
            bytes {
                writeBlocks(nullableValues, listOf(6)) {
                    when {
                        it == null -> writeIndex(0)

                        it.x % 2 == 0 -> {
                            writeIndex(1)
                            writeInt(it.x)
                            writeString(it.y)
                        }

                        else -> {
                            writeIndex(2)
                            writeString(it.y)
                            writeBoolean(false)
                            writeInt(it.x)
                        }
                    }
                }
            }
        for (avro in AVROS) {
            avro.decodeFromByteArray(AvroSchema.ArraySchema(AvroSchema.UnionSchema(listOf(first, second))), ListSerializer(XY.serializer()), bytes) shouldBe values
            avro.decodeFromByteArray(
                AvroSchema.ArraySchema(AvroSchema.UnionSchema(listOf(AvroSchema.NullSchema(), first, second))),
                ListSerializer(XY.serializer().nullable),
                nullableBytes
            ) shouldBe nullableValues
        }
    }

    @Test
    fun `maps of lists of records decode`() {
        roundTrips(
            MapsOfLists.serializer(),
            MapsOfLists(
                byName = mapOf("a" to listOf(Leaf(1, "x"), Leaf(2, "y")), "empty" to emptyList(), "b" to listOf(Leaf(3, "z"))),
                rows = listOf(mapOf("p" to listOf(Leaf(4, "w"))), emptyMap(), mapOf("q" to emptyList(), "r" to listOf(Leaf(5, "v"), Leaf(6, "u")))),
                after = mapOf("s" to listOf(Leaf(7, "t")))
            )
        )
    }

    @Test
    fun `collections split into several blocks decode on one block decoder per level`() {
        // Above the presized block limit, items and entries are counted by the block decoder itself: its counts must
        // start again at each block. A map block decoded sequentially leaves a pending value index behind it.
        val value =
            Blocks(
                items = List(5) { Inner(it, "i$it") },
                byName = (0 until 3).associate { "k$it" to Inner(it, "n$it") },
                large = List(2130) { it },
                largeMap = (0 until 2150).associate { "e$it" to it },
                mixedMap = (0 until 1103).associate { "m$it" to -it },
                lists = listOf(listOf(1, 2), listOf(3), emptyList(), listOf(4, 5, 6))
            )
        val bytes =
            bytes {
                writeBlocks(value.items, listOf(1, -3, 1)) { writeInner(it) }
                writeBlocks(value.byName.entries.toList(), listOf(2, 1)) {
                    writeString(it.key)
                    writeInner(it.value)
                }
                writeBlocks(value.large, listOf(1100, -1030)) { writeInt(it) }
                writeBlocks(value.largeMap.entries.toList(), listOf(1100, 1050)) {
                    writeString(it.key)
                    writeInt(it.value)
                }
                writeBlocks(value.mixedMap.entries.toList(), listOf(3, 1100)) {
                    writeString(it.key)
                    writeInt(it.value)
                }
                writeBlocks(value.lists, listOf(1, 2, 1)) { list -> writeBlocks(list, list.map { 1 }) { writeInt(it) } }
            }
        for (avro in AVROS) {
            avro.decodeFromByteArray(Avro.schema<Blocks>(), Blocks.serializer(), bytes) shouldBe value
        }
    }

    @Test
    fun `a serializer beginning a second structure before ending the first decodes both`() {
        val inner = Avro.schema<Inner>()
        // at the root, then as a record field, between records of the same kind
        Avro.decodeFromByteArray(
            inner,
            TwoAtOnceSerializer,
            bytes {
                writeInner(A)
                writeInner(B)
            }
        ) shouldBe Twins(A, B)
        val holder =
            AvroSchema.RecordSchema(
                Name("TwinsHolder"),
                listOf(
                    AvroSchema.RecordSchema.Field("before", inner),
                    AvroSchema.RecordSchema.Field("twins", inner),
                    AvroSchema.RecordSchema.Field("after", inner),
                    AvroSchema.RecordSchema.Field("list", AvroSchema.ArraySchema(inner))
                )
            )
        val bytes =
            bytes {
                writeInner(C)
                writeInner(A)
                writeInner(B)
                writeInner(D)
                // two records per item: the item serializer begins both on the block decoder
                writeLong(2)
                listOf(A, B, C, D).forEach { writeInner(it) }
                writeLong(0)
            }
        Avro.decodeFromByteArray(holder, TwinsHolder.serializer(), bytes) shouldBe TwinsHolder(C, Twins(A, B), D, listOf(Twins(A, B), Twins(C, D)))
    }

    @Test
    fun `a serializer beginning a second list or map before ending the first decodes both`() {
        // Each begin reads its block count at once: the first as the collection's start, the second as its next block
        val lists =
            bytes {
                writeLong(2)
                writeLong(3)
                listOf(1, 2, 10, 20, 30).forEach { writeInt(it) }
            }
        Avro.decodeFromByteArray(AvroSchema.ArraySchema(AvroSchema.IntSchema()), TwoListsAtOnceSerializer, lists) shouldBe (listOf(1, 2) to listOf(10, 20, 30))
        val maps =
            bytes {
                writeLong(1)
                writeLong(2)
                listOf("a" to 1, "b" to 2, "c" to 3).forEach {
                    writeString(it.first)
                    writeInt(it.second)
                }
            }
        Avro.decodeFromByteArray(AvroSchema.MapSchema(AvroSchema.IntSchema()), TwoMapsAtOnceSerializer, maps) shouldBe (mapOf("a" to 1) to mapOf("b" to 2, "c" to 3))
    }

    @Test
    fun `a serializer that never ends its structures decodes`() {
        roundTrips(
            NoEndHolder.serializer(),
            NoEndHolder(A, B, C, listOf(A, B, C), listOf(D, A), mapOf("a" to listOf(B), "b" to listOf(C, D)))
        )
    }

    @Test
    fun `a serializer catching a decoding error inside a structure goes on decoding`() {
        roundTrips(
            ErrorsHolder.serializer(),
            ErrorsHolder(A, B, C, listOf(A, B, C), listOf(D, A), listOf(B, C))
        )
    }

    @Test
    fun `successive structures of one kind are decoded by one decoder`() {
        val ending = RecordingSerializer(endsStructure = true)
        val bytes = Avro.encodeToByteArray(ListSerializer(Inner.serializer()), listOf(A, B, C))
        Avro.decodeFromByteArray(ListSerializer(ending), bytes) shouldBe listOf(A, B, C)
        ending.decoders.size shouldBe 3
        (ending.decoders[0] === ending.decoders[1] && ending.decoders[1] === ending.decoders[2]) shouldBe true

        // a structure never ended keeps its decoder: each next one is decoded on a new decoder
        val notEnding = RecordingSerializer(endsStructure = false)
        Avro.decodeFromByteArray(ListSerializer(notEnding), bytes) shouldBe listOf(A, B, C)
        notEnding.decoders.size shouldBe 3
        (notEnding.decoders[0] === notEnding.decoders[1] || notEnding.decoders[1] === notEnding.decoders[2]) shouldBe false
    }

    @Test
    fun `successive lists and maps are decoded by one block decoder per level`() {
        // the items record the decoder they are decoded on: the block decoder of their list or map, which the decoder of
        // the enclosing list keeps for each next list or map once the previous one is ended
        val value = BlockItems(listOf(listOf(1, 2), listOf(3), listOf(4, 5, 6)), listOf(mapOf("a" to 1, "e" to 5), mapOf("b" to 2, "c" to 3), mapOf("d" to 4, "f" to 6)))
        for (avro in AVROS) {
            BLOCK_ITEM_DECODERS.clear()
            avro.decodeFromByteArray(BlockItems.serializer(), avro.encodeToByteArray(BlockItems.serializer(), value)) shouldBe value
            BLOCK_ITEM_DECODERS.size shouldBe 12
            // the lists' 6 items, then the maps' 6 values
            BLOCK_ITEM_DECODERS.subList(0, 6).all { it === BLOCK_ITEM_DECODERS[0] } shouldBe true
            BLOCK_ITEM_DECODERS.subList(6, 12).all { it === BLOCK_ITEM_DECODERS[6] } shouldBe true
            (BLOCK_ITEM_DECODERS[0] === BLOCK_ITEM_DECODERS[6]) shouldBe false
        }
    }

    @Test
    fun `the busy cases decode records read in order and records read by their decoding steps alike`() {
        // Inner read with its own schema is decoded in order, by the exact record decoder; read with a field more, it is
        // decoded by its decoding steps, by the general one. Each keeps its own busy state.
        for ((inner, writeOne) in listOf<Pair<AvroSchema, ByteArrayEncoder.(Inner) -> Unit>>(
            Avro.schema<Inner>() to { writeInner(it) },
            EXTENDED_INNER to { writeExtendedInner(it) }
        )) {
            Avro.decodeFromByteArray(
                inner,
                TwoAtOnceSerializer,
                bytes {
                    writeOne(A)
                    writeOne(B)
                }
            ) shouldBe Twins(A, B)
            val twinsHolder = record("TwinsHolder", "before" to inner, "twins" to inner, "after" to inner, "list" to AvroSchema.ArraySchema(inner))
            val twinsBytes =
                bytes {
                    writeOne(C)
                    writeOne(A)
                    writeOne(B)
                    writeOne(D)
                    writeLong(2)
                    listOf(A, B, C, D).forEach { writeOne(it) }
                    writeLong(0)
                }
            Avro.decodeFromByteArray(twinsHolder, TwinsHolder.serializer(), twinsBytes) shouldBe TwinsHolder(C, Twins(A, B), D, listOf(Twins(A, B), Twins(C, D)))

            val innerList = AvroSchema.ArraySchema(inner)
            val noEnd = NoEndHolder(A, B, C, listOf(A, B, C), listOf(D, A), mapOf("a" to listOf(B), "b" to listOf(C, D)))
            val noEndHolder =
                record(
                    "NoEndHolder",
                    "a" to inner,
                    "b" to inner,
                    "c" to inner,
                    "list" to innerList,
                    "after" to innerList,
                    "byName" to AvroSchema.MapSchema(innerList)
                )
            val noEndBytes =
                bytes {
                    writeOne(noEnd.a)
                    writeOne(noEnd.b)
                    writeOne(noEnd.c)
                    writeBlocks(noEnd.list, listOf(3)) { writeOne(it) }
                    writeBlocks(noEnd.after, listOf(2)) { writeOne(it) }
                    writeBlocks(noEnd.byName.entries.toList(), listOf(2)) { entry ->
                        writeString(entry.key)
                        writeBlocks(entry.value, listOf(entry.value.size)) { writeOne(it) }
                    }
                }
            val errors = ErrorsHolder(A, B, C, listOf(A, B, C), listOf(D, A), listOf(B, C))
            val errorsHolder =
                record(
                    "ErrorsHolder",
                    "caught" to inner,
                    "abandoned" to inner,
                    "after" to inner,
                    "abandonedList" to innerList,
                    "list" to innerList,
                    "caughtList" to innerList
                )
            val errorsBytes =
                bytes {
                    writeOne(errors.caught)
                    writeOne(errors.abandoned)
                    writeOne(errors.after)
                    writeBlocks(errors.abandonedList, listOf(3)) { writeOne(it) }
                    writeBlocks(errors.list, listOf(2)) { writeOne(it) }
                    writeBlocks(errors.caughtList, listOf(2)) { writeOne(it) }
                }
            for (avro in AVROS) {
                avro.decodeFromByteArray(noEndHolder, NoEndHolder.serializer(), noEndBytes) shouldBe noEnd
                avro.decodeFromByteArray(errorsHolder, ErrorsHolder.serializer(), errorsBytes) shouldBe errors
            }

            val listBytes = bytes { writeBlocks(listOf(A, B, C), listOf(3)) { writeOne(it) } }
            val ending = RecordingSerializer(endsStructure = true)
            Avro.decodeFromByteArray(innerList, ListSerializer(ending), listBytes) shouldBe listOf(A, B, C)
            (ending.decoders[0] === ending.decoders[1] && ending.decoders[1] === ending.decoders[2]) shouldBe true
            val notEnding = RecordingSerializer(endsStructure = false)
            Avro.decodeFromByteArray(innerList, ListSerializer(notEnding), listBytes) shouldBe listOf(A, B, C)
            (notEnding.decoders[0] === notEnding.decoders[1] || notEnding.decoders[1] === notEnding.decoders[2]) shouldBe false
        }
    }

    @Test
    fun `a reused decoder knows no field or item before one is begun`() {
        // as a new decoder: the peeking serializers expect it to fail, then decode normally. The record is written with a
        // field more than it reads: a record written exactly as read is decoded in order, so it answers an element read
        // without decodeElementIndex (see ExactRecordDecodingTest)
        val inner = EXTENDED_INNER
        Avro.decodeFromByteArray(inner, PeekingRecordSerializer, bytes { writeExtendedInner(A) }) shouldBe A
        Avro.decodeFromByteArray(AvroSchema.ArraySchema(AvroSchema.IntSchema()), PeekingListSerializer, bytes { writeLong(0) }) shouldBe emptyList()
        Avro.decodeFromByteArray(AvroSchema.MapSchema(AvroSchema.IntSchema()), PeekingMapSerializer, bytes { writeLong(0) }) shouldBe emptyMap()

        val withDefault = AvroSchema.RecordSchema(Name("WithDefault"), listOf(AvroSchema.RecordSchema.Field("x", AvroSchema.IntSchema())))
        val ints = AvroSchema.ArraySchema(AvroSchema.IntSchema())
        val intMap = AvroSchema.MapSchema(AvroSchema.IntSchema())
        val writer =
            AvroSchema.RecordSchema(
                Name("Peeks"),
                listOf(
                    AvroSchema.RecordSchema.Field("withDefault", withDefault),
                    AvroSchema.RecordSchema.Field("peeked", inner),
                    AvroSchema.RecordSchema.Field("ints", ints),
                    AvroSchema.RecordSchema.Field("peekedInts", ints),
                    AvroSchema.RecordSchema.Field("counts", intMap),
                    AvroSchema.RecordSchema.Field("peekedCounts", intMap)
                )
            )
        val bytes =
            bytes {
                writeInt(1)
                writeExtendedInner(B)
                writeBlocks(listOf(2, 3), listOf(2)) { writeInt(it) }
                writeLong(0)
                writeBlocks(listOf("a" to 4), listOf(1)) {
                    writeString(it.first)
                    writeInt(it.second)
                }
                writeLong(0)
            }
        Avro.decodeFromByteArray(writer, Peeks.serializer(), bytes) shouldBe Peeks(WithDefault(1, 7), B, listOf(2, 3), emptyList(), mapOf("a" to 4), emptyMap())
    }

    @Test
    fun `a list read by a delegating serializer on a reused record decoder starts a new collection`() {
        // The delegating serializer begins its list without the decoder's collection decoding: the decoder reads the
        // count as the first block of a collection only if it has not read one already, so a reused record decoder
        // must not remember the previous record's lists
        roundTrips(ListSerializer(Delegating.serializer()), List(3) { Delegating(it, emptyList()) })
    }

    @Test
    fun `a missing reader field names the writer schema of its record`() {
        val writer = AvroSchema.RecordSchema(Name("NeedsY"), listOf(AvroSchema.RecordSchema.Field("x", AvroSchema.IntSchema())))
        val bytes = bytes { writeBlocks(listOf(1, 2), listOf(2)) { writeInt(it) } }
        for (avro in AVROS) {
            shouldThrow<AvroSchemaMismatchException> {
                avro.decodeFromByteArray(AvroSchema.ArraySchema(writer), ListSerializer(NeedsY.serializer()), bytes)
            }.message shouldEndWith "has no corresponding field in writer schema ${writer.toSchemaJsonString()}"
        }
    }

    @Test
    fun `a record whose fields cannot be resolved fails again when begun again`() {
        val x = AvroSchema.RecordSchema(Name("X"), listOf(AvroSchema.RecordSchema.Field("x", AvroSchema.IntSchema())))
        val writer = AvroSchema.RecordSchema(Name("ResolveHolder"), listOf(AvroSchema.RecordSchema.Field("a", x), AvroSchema.RecordSchema.Field("b", x)))
        val bytes =
            bytes {
                writeInt(1)
                writeInt(2)
            }
        for (avro in AVROS) {
            avro.decodeFromByteArray(writer, ResolveHolder.serializer(), bytes) shouldBe ResolveHolder(OnlyX(1), OnlyX(2))
        }
    }

    private fun <T> roundTrips(
        serializer: KSerializer<T>,
        value: T,
    ) {
        val bytes = Avro.encodeToByteArray(serializer, value)
        for (avro in AVROS) {
            avro.decodeFromByteArray(serializer, bytes) shouldBe value
        }
    }

    private companion object {
        val AVROS = listOf(Avro, Avro { validateSerialization = true })

        val A = Inner(1, "a")
        val B = Inner(2, "b")
        val C = Inner(3, "c")
        val D = Inner(4, "d")

        val TREE =
            Tree(
                first = branch(1),
                second = branch(2),
                branches = List(5) { branch(it + 3) },
                branchesByName = mapOf("a" to branch(8), "empty" to branch(0), "b" to branch(9)),
                matrix = listOf(listOf(1, 2), emptyList(), listOf(3)),
                words = listOf(listOf("a"), listOf("b", "c"), emptyList()),
                nested = mapOf("x" to mapOf("p" to leaf(1), "q" to leaf(2)), "y" to emptyMap(), "z" to mapOf("r" to leaf(3))),
                last = leaf(99)
            )

        fun leaf(i: Int) = Leaf(i, "leaf$i")

        fun branch(i: Int) =
            Branch(
                left = leaf(i),
                right = if (i % 2 == 0) null else leaf(i + 1),
                leaves = List(i % 4) { leaf(i * 10 + it) },
                leavesByName = (0 until (i + 1) % 3).associate { "k$it" to leaf(i * 20 + it) },
                ints = List(i % 5) { it * i },
                strings = List((i + 2) % 4) { "s$i-$it" },
                counts = (0 until i % 3).associate { "c$it" to it },
                flags = (0 until (i + 1) % 3).associate { "f$it" to (it % 2 == 0) },
                leafLists = (0 until (i + 2) % 3).associate { k -> "l$k" to List(k + i % 2) { leaf(k * 100 + it) } }
            )

        /** A tree [depth] levels deep, each level with records, lists and maps of the next. */
        fun node(depth: Int): Node =
            if (depth == 0) {
                Node(0, emptyList(), emptyMap(), null)
            } else {
                Node(
                    value = depth,
                    children = listOf(node(depth - 1), Node(-depth, emptyList(), emptyMap(), null), node(depth - 1)),
                    byName = mapOf("a" to node(depth - 1), "b" to Node(depth * 100, emptyList(), emptyMap(), null)),
                    next = node(depth - 1)
                )
            }
    }
}

@Serializable
private data class Leaf(val x: Int, val s: String)

@Serializable
private data class Branch(
    val left: Leaf,
    val right: Leaf?,
    val leaves: List<Leaf>,
    val leavesByName: Map<String, Leaf>,
    val ints: List<Int>,
    val strings: List<String>,
    val counts: Map<String, Int>,
    val flags: Map<String, Boolean>,
    val leafLists: Map<String, List<Leaf>>,
)

@Serializable
private data class Tree(
    val first: Branch,
    val second: Branch,
    val branches: List<Branch>,
    val branchesByName: Map<String, Branch>,
    val matrix: List<List<Int>>,
    val words: List<List<String>>,
    val nested: Map<String, Map<String, Leaf>>,
    val last: Leaf,
)

@Serializable
private data class Node(val value: Int, val children: List<Node>, val byName: Map<String, Node>, val next: Node?)

@Serializable
private data class RecA(val x: Int, val y: String)

@Serializable
private data class RecB(val flag: Boolean, val y: String, val z: Long)

@Serializable
private data class AB(val a: RecA, val b: RecB)

@Serializable
private data class Alternating(val a: RecA, val b: RecB, val a2: RecA, val b2: RecB, val pairs: List<AB>, val recAs: List<RecA>, val bs: List<RecB>)

@Serializable
private data class OnlyX(val x: Int)

@Serializable
private data class OnlyY(val y: String)

@Serializable
private data class Readers(val first: OnlyX, val second: OnlyY, val firsts: List<OnlyX>, val seconds: List<OnlyY>, val third: OnlyX)

@Serializable
private data class XY(val x: Int, val y: String)

@Serializable
private data class MapsOfLists(val byName: Map<String, List<Leaf>>, val rows: List<Map<String, List<Leaf>>>, val after: Map<String, List<Leaf>>)

@Serializable
private data class Blocks(
    val items: List<Inner>,
    val byName: Map<String, Inner>,
    val large: List<Int>,
    val largeMap: Map<String, Int>,
    val mixedMap: Map<String, Int>,
    val lists: List<List<Int>>,
)

@Serializable
private data class Inner(val x: Int, val s: String)

private val INNER: SerialDescriptor = Inner.serializer().descriptor

/** [Inner]'s writer schema with a field more, at the end: [Inner] is then decoded by its decoding steps, not in order. */
private val EXTENDED_INNER =
    AvroSchema.RecordSchema(
        Name("Inner"),
        listOf(
            AvroSchema.RecordSchema.Field("x", AvroSchema.IntSchema()),
            AvroSchema.RecordSchema.Field("s", AvroSchema.StringSchema()),
            AvroSchema.RecordSchema.Field("extra", AvroSchema.BooleanSchema())
        )
    )

/** The fields of an [Inner] record begun on this decoder, read with [CompositeDecoder.decodeElementIndex]. */
private fun CompositeDecoder.readInner(): Inner {
    var x: Int? = null
    var s: String? = null
    while (true) {
        when (val index = decodeElementIndex(INNER)) {
            CompositeDecoder.DECODE_DONE -> break
            0 -> x = decodeIntElement(INNER, 0)
            1 -> s = decodeStringElement(INNER, 1)
            else -> error("Unexpected index $index")
        }
    }
    return Inner(checkNotNull(x) { "x not decoded" }, checkNotNull(s) { "s not decoded" })
}

/** A serializer for [Inner] that only decodes in its own way: it writes as the generated one. */
private abstract class InnerSerializer : KSerializer<Inner> {
    override val descriptor: SerialDescriptor = INNER

    override fun serialize(
        encoder: Encoder,
        value: Inner,
    ) = Inner.serializer().serialize(encoder, value)
}

private data class Twins(val first: Inner, val second: Inner)

/** Begins two records on one decoder before ending the first: the second must be decoded on a decoder of its own. */
private object TwoAtOnceSerializer : KSerializer<Twins> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Twins")

    override fun serialize(
        encoder: Encoder,
        value: Twins,
    ) = throw UnsupportedOperationException()

    override fun deserialize(decoder: Decoder): Twins {
        val first = decoder.beginStructure(INNER)
        val second = decoder.beginStructure(INNER)
        val firstValue = first.readInner()
        val secondValue = second.readInner()
        second.endStructure(INNER)
        first.endStructure(INNER)
        return Twins(firstValue, secondValue)
    }
}

private val INTS: SerialDescriptor = ListSerializer(Int.serializer()).descriptor
private val INT_MAP: SerialDescriptor = MapSerializer(String.serializer(), Int.serializer()).descriptor

/** Begins two lists on one decoder before ending the first, and reads them item by item. */
private object TwoListsAtOnceSerializer : KSerializer<Pair<List<Int>, List<Int>>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TwoLists")

    override fun serialize(
        encoder: Encoder,
        value: Pair<List<Int>, List<Int>>,
    ) = throw UnsupportedOperationException()

    override fun deserialize(decoder: Decoder): Pair<List<Int>, List<Int>> {
        val first = decoder.beginStructure(INTS)
        val second = decoder.beginStructure(INTS)
        val firstValue = first.readInts()
        val secondValue = second.readInts()
        second.endStructure(INTS)
        first.endStructure(INTS)
        return firstValue to secondValue
    }

    private fun CompositeDecoder.readInts(): List<Int> =
        buildList {
            while (true) {
                val index = decodeElementIndex(INTS)
                if (index == CompositeDecoder.DECODE_DONE) break
                add(decodeIntElement(INTS, index))
            }
        }
}

/** Begins two maps on one decoder before ending the first, and reads them entry by entry. */
private object TwoMapsAtOnceSerializer : KSerializer<Pair<Map<String, Int>, Map<String, Int>>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TwoMaps")

    override fun serialize(
        encoder: Encoder,
        value: Pair<Map<String, Int>, Map<String, Int>>,
    ) = throw UnsupportedOperationException()

    override fun deserialize(decoder: Decoder): Pair<Map<String, Int>, Map<String, Int>> {
        val first = decoder.beginStructure(INT_MAP)
        val second = decoder.beginStructure(INT_MAP)
        val firstValue = first.readEntries()
        val secondValue = second.readEntries()
        second.endStructure(INT_MAP)
        first.endStructure(INT_MAP)
        return firstValue to secondValue
    }

    private fun CompositeDecoder.readEntries(): Map<String, Int> =
        buildMap {
            while (true) {
                val keyIndex = decodeElementIndex(INT_MAP)
                if (keyIndex == CompositeDecoder.DECODE_DONE) break
                val key = decodeStringElement(INT_MAP, keyIndex)
                put(key, decodeIntElement(INT_MAP, decodeElementIndex(INT_MAP)))
            }
        }
}

@Serializable
private data class TwinsHolder(
    val before: Inner,
    @Serializable(with = TwoAtOnceSerializer::class) val twins: Twins,
    val after: Inner,
    val list: List<
        @Serializable(with = TwoAtOnceSerializer::class)
        Twins
    >,
)

private object NoEndSerializer : InnerSerializer() {
    override fun deserialize(decoder: Decoder): Inner = decoder.beginStructure(INNER).readInner()
}

@Serializable
private data class NoEndHolder(
    @Serializable(with = NoEndSerializer::class) val a: Inner,
    @Serializable(with = NoEndSerializer::class) val b: Inner,
    val c: Inner,
    val list: List<
        @Serializable(with = NoEndSerializer::class)
        Inner
    >,
    val after: List<Inner>,
    val byName: Map<
        String,
        List<
            @Serializable(with = NoEndSerializer::class)
            Inner
        >
    >,
)

/** Catches an error raised before anything is read, then decodes the field as it should, and ends the record. */
private object CatchingSerializer : InnerSerializer() {
    override fun deserialize(decoder: Decoder): Inner {
        val record = decoder.beginStructure(INNER)
        record.decodeElementIndex(INNER) shouldBe 0
        try {
            record.decodeBooleanElement(INNER, 0)
            error("x is written as an int")
        } catch (_: AvroSchemaMismatchException) {
        }
        val x = record.decodeIntElement(INNER, 0)
        record.decodeElementIndex(INNER) shouldBe 1
        val s = record.decodeStringElement(INNER, 1)
        record.decodeElementIndex(INNER) shouldBe CompositeDecoder.DECODE_DONE
        record.endStructure(INNER)
        return Inner(x, s)
    }
}

/** Abandons a record on an error raised before anything is read, without ending it, and decodes the record again. */
private object AbandoningSerializer : InnerSerializer() {
    override fun deserialize(decoder: Decoder): Inner {
        try {
            val record = decoder.beginStructure(INNER)
            record.decodeElementIndex(INNER)
            record.decodeBooleanElement(INNER, 0)
            error("x is written as an int")
        } catch (_: AvroSchemaMismatchException) {
        }
        return decoder.decodeSerializableValue(Inner.serializer())
    }
}

@Serializable
private data class ErrorsHolder(
    @Serializable(with = CatchingSerializer::class) val caught: Inner,
    @Serializable(with = AbandoningSerializer::class) val abandoned: Inner,
    val after: Inner,
    val abandonedList: List<
        @Serializable(with = AbandoningSerializer::class)
        Inner
    >,
    val list: List<Inner>,
    val caughtList: List<
        @Serializable(with = CatchingSerializer::class)
        Inner
    >,
)

/** Keeps the decoder of each record it begins. */
private class RecordingSerializer(private val endsStructure: Boolean) : InnerSerializer() {
    val decoders = mutableListOf<CompositeDecoder>()

    override fun deserialize(decoder: Decoder): Inner {
        val record = decoder.beginStructure(INNER)
        decoders += record
        val value = record.readInner()
        if (endsStructure) record.endStructure(INNER)
        return value
    }
}

@Serializable
private data class WithDefault(
    val x: Int,
    @AvroDefault("7") val z: Int,
)

/** Asks a record decoder for its writer schema and a field before any is begun, as a new decoder fails to answer. */
private object PeekingRecordSerializer : InnerSerializer() {
    override fun deserialize(decoder: Decoder): Inner {
        val record = decoder.beginStructure(INNER)
        shouldThrow<IllegalStateException> { (record as AvroDecoder).currentWriterSchema }
        shouldThrow<IllegalStateException> { record.decodeIntElement(INNER, 0) }
        val value = record.readInner()
        record.endStructure(INNER)
        return value
    }
}

/** Asks a block decoder for its item schema before any item is begun, on an empty collection. */
private abstract class PeekingEmptySerializer<T>(private val delegate: KSerializer<T>, private val empty: T) : KSerializer<T> {
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): T {
        val block = decoder.beginStructure(descriptor)
        shouldThrow<IllegalStateException> { (block as AvroDecoder).currentWriterSchema }
        block.decodeElementIndex(descriptor) shouldBe CompositeDecoder.DECODE_DONE
        block.endStructure(descriptor)
        return empty
    }
}

private object PeekingListSerializer : PeekingEmptySerializer<List<Int>>(ListSerializer(Int.serializer()), emptyList())

private object PeekingMapSerializer : PeekingEmptySerializer<Map<String, Int>>(MapSerializer(String.serializer(), Int.serializer()), emptyMap())

@Serializable
private data class Peeks(
    val withDefault: WithDefault,
    @Serializable(with = PeekingRecordSerializer::class) val peeked: Inner,
    val ints: List<Int>,
    @Serializable(with = PeekingListSerializer::class) val peekedInts: List<Int>,
    val counts: Map<String, Int>,
    @Serializable(with = PeekingMapSerializer::class) val peekedCounts: Map<String, Int>,
)

/** Decodes its list through the list serializer's own deserialize, not through the decoder's collection decoding. */
private object DelegatingListSerializer : KSerializer<List<Int>> {
    private val delegate = ListSerializer(Int.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: List<Int>,
    ) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<Int> = delegate.deserialize(decoder)
}

@Serializable
private data class Delegating(
    val id: Int,
    @Serializable(with = DelegatingListSerializer::class) val values: List<Int>,
)

@Serializable
private data class NeedsY(val x: Int, val y: Int)

@Serializable
private data class Ambiguous(
    val x: Int,
    @AvroAlias("x") val alsoX: Int,
)

/** Fails twice to resolve a record type whose two fields match one writer field, then decodes the record as [OnlyX]. */
private object ResolvingTwiceSerializer : KSerializer<OnlyX> {
    override val descriptor: SerialDescriptor = OnlyX.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: OnlyX,
    ) = OnlyX.serializer().serialize(encoder, value)

    override fun deserialize(decoder: Decoder): OnlyX {
        val first = shouldThrow<AvroSchemaMismatchException> { decoder.decodeSerializableValue(Ambiguous.serializer()) }
        val second = shouldThrow<AvroSchemaMismatchException> { decoder.decodeSerializableValue(Ambiguous.serializer()) }
        second.message shouldBe first.message
        return decoder.decodeSerializableValue(OnlyX.serializer())
    }
}

@Serializable
private data class ResolveHolder(
    val a: OnlyX,
    @Serializable(with = ResolvingTwiceSerializer::class) val b: OnlyX,
)

/** The decoders [RecordingIntSerializer] decoded on, in order. */
private val BLOCK_ITEM_DECODERS = mutableListOf<Decoder>()

/** Decodes an int, and records the decoder it is decoded on. */
private object RecordingIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = Int.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: Int,
    ) = encoder.encodeInt(value)

    override fun deserialize(decoder: Decoder): Int {
        BLOCK_ITEM_DECODERS += decoder
        return decoder.decodeInt()
    }
}

@Serializable
private data class BlockItems(
    val lists: List<
        List<
            @Serializable(with = RecordingIntSerializer::class)
            Int
        >
    >,
    val maps: List<
        Map<
            String,
            @Serializable(with = RecordingIntSerializer::class)
            Int
        >
    >,
)

private fun record(
    name: String,
    vararg fields: Pair<String, AvroSchema>,
): AvroSchema.RecordSchema = AvroSchema.RecordSchema(Name(name), fields.map { AvroSchema.RecordSchema.Field(it.first, it.second) })

private fun bytes(write: ByteArrayEncoder.() -> Unit): ByteArray = ByteArrayEncoder().apply { write() }.toByteArray()

private fun ByteArrayEncoder.writeInner(value: Inner) {
    writeInt(value.x)
    writeString(value.s)
}

private fun ByteArrayEncoder.writeExtendedInner(value: Inner) {
    writeInner(value)
    writeBoolean(value.x % 2 == 0)
}

private fun ByteArrayEncoder.writeShared(
    x: Int,
    y: String,
) {
    writeInt(x)
    writeString(y)
}

/**
 * Writes [items] as one collection, one block per entry of [blocks] (the item count of each block, negative for a
 * block written with a negative count followed by its byte size), then the closing empty block.
 */
private fun <I> ByteArrayEncoder.writeBlocks(
    items: List<I>,
    blocks: List<Int>,
    writeItem: ByteArrayEncoder.(I) -> Unit,
) {
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
            writeLong(count.toLong())
            blockItems.forEach { writeItem(it) }
        }
    }
    writeLong(0)
}