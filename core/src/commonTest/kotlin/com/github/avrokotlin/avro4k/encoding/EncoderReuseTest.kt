package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroAlias
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.internal.codec.ByteArrayBinaryWriter
import com.github.avrokotlin.avro4k.schema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test

/**
 * An encoder keeps the encoder of the records, arrays, maps and polymorphic values it begins, and hands it the next
 * structure of the same kind instead of creating one per value. These tests encode every shape where a kept encoder could
 * carry state from one structure into the next: siblings of each kind at several depths, recursion, record types and
 * writer schemas alternating on one encoder (exact, skipping and reordered records included), union branches selected
 * in turn, and serializers that do not follow the begin-end protocol, which must still encode correctly on a new encoder.
 *
 * The expected bytes are written with the codec, in the order of calls a new encoder per structure makes.
 */
class EncoderReuseTest {
    @Test
    fun `sibling records lists and maps encode at several depths`() {
        roundTrips(WTree.serializer(), TREE)
        roundTrips(ListSerializer(WTree.serializer()), listOf(TREE, TREE.copy(first = branch(0), branches = emptyList()), TREE))
    }

    @Test
    fun `a recursive type encodes six levels deep`() {
        roundTrips(WNode.serializer(), node(6))
    }

    @Test
    fun `two record types alternating as fields of one parent encode`() {
        val a = WRecA(1, "a")
        val b = WRecB(true, "b", 2L)
        roundTrips(
            WAlternating.serializer(),
            WAlternating(
                a = a,
                b = b,
                a2 = WRecA(3, "c"),
                b2 = WRecB(false, "d", -4L),
                pairs = List(4) { WAB(WRecA(it, "a$it"), WRecB(it % 2 == 0, "b$it", it * 10L)) },
                recAs = listOf(a, WRecA(5, "e")),
                bs = listOf(b, WRecB(false, "f", 6L))
            )
        )
    }

    @Test
    fun `records of one type written with several writer schemas in turn encode`() {
        // One descriptor, written with an exact, a skipping and a reordered schema, and with two skipping schemas
        val writer =
            record(
                "Siblings",
                "a" to XY_RECORD,
                "b" to XY_SKIPPED,
                "c" to XY_SWAPPED,
                "d" to XY_ONLY_Y,
                "a2" to XY_RECORD,
                "b2" to XY_SKIPPED,
                "c2" to XY_SWAPPED,
                "d2" to XY_ONLY_Y,
                "skippedList" to AvroSchema.ArraySchema(XY_SKIPPED),
                "after" to XY_RECORD
            )
        val value =
            WSiblings(
                a = WXY(1, "a"),
                b = WXY(2, "b"),
                c = WXY(3, "c"),
                d = WXY(4, "d"),
                a2 = WXY(5, "e"),
                b2 = WXY(6, "f"),
                c2 = WXY(7, "g"),
                d2 = WXY(8, "h"),
                skippedList = List(3) { WXY(10 + it, "l$it") },
                after = WXY(9, "i")
            )
        val expected =
            bytes {
                writeXY(value.a)
                writeInt(value.b.x)
                writeString(value.c.y)
                writeInt(value.c.x)
                writeString(value.d.y)
                writeXY(value.a2)
                writeInt(value.b2.x)
                writeString(value.c2.y)
                writeInt(value.c2.x)
                writeString(value.d2.y)
                writeList(value.skippedList) { writeInt(it.x) }
                writeXY(value.after)
            }
        for (avro in AVROS) {
            avro.encodeToByteArray(writer, WSiblings.serializer(), value) shouldBe expected
        }
    }

    @Test
    fun `one writer schema written from several types in turn encodes`() {
        // One writer schema instance, written from an exact type and from two skipping types skipping other elements
        val writer =
            record(
                "Writers",
                "a" to XY_RECORD,
                "p" to R,
                "c" to R,
                "q" to R,
                "r" to R,
                "p2" to R,
                "q2" to R,
                "r2" to R,
                "c2" to R,
                "rs" to AvroSchema.ArraySchema(R)
            )
        val value =
            WWriters(
                a = WXY(1, "a"),
                p = WOnlyX(2),
                c = WXY(3, "c"),
                q = WXAndY(4, "q"),
                r = WYAndX("r", 5),
                p2 = WOnlyX(6),
                q2 = WXAndY(7, "q2"),
                r2 = WYAndX("r2", 8),
                c2 = WXY(9, "c2"),
                rs = List(3) { WYAndX("y$it", it) }
            )
        val expected =
            bytes {
                writeXY(value.a)
                listOf(value.p.x, value.c.x, value.q.x, value.r.x, value.p2.x, value.q2.x, value.r2.x, value.c2.x).forEach { writeInt(it) }
                writeList(value.rs) { writeInt(it.x) }
            }
        for (avro in AVROS) {
            avro.encodeToByteArray(writer, WWriters.serializer(), value) shouldBe expected
        }
    }

    @Test
    fun `exact skipping and reordered records encode as siblings and in one list`() {
        val base = AvroSchema.UnionSchema(listOf(EXACT, SKIPPING, REORDERED))
        val writer =
            record(
                "Mixed",
                "e" to EXACT,
                "s" to SKIPPING,
                "r" to REORDERED,
                "e2" to EXACT,
                "s2" to SKIPPING,
                "r2" to REORDERED,
                "list" to AvroSchema.ArraySchema(base),
                "after" to EXACT
            )
        val values =
            listOf(
                WExact(1, "a"),
                WSkipping(2, "x", 3),
                WReordered(4, "b"),
                WExact(5, "c"),
                WReordered(6, "d"),
                WSkipping(7, "y", 8),
                WSkipping(9, "z", 10),
                WExact(11, "e"),
                WReordered(12, "f")
            )
        val value =
            WMixed(
                e = WExact(20, "e"),
                s = WSkipping(21, "s", 22),
                r = WReordered(23, "r"),
                e2 = WExact(24, "e2"),
                s2 = WSkipping(25, "s2", 26),
                r2 = WReordered(27, "r2"),
                list = values,
                after = WExact(28, "after")
            )
        val expected =
            bytes {
                writeBase(value.e)
                writeBase(value.s)
                writeBase(value.r)
                writeBase(value.e2)
                writeBase(value.s2)
                writeBase(value.r2)
                writeList(values) {
                    writeIndex(
                        when (it) {
                            is WExact -> 0
                            is WSkipping -> 1
                            is WReordered -> 2
                        }
                    )
                    writeBase(it)
                }
                writeBase(value.after)
            }
        for (avro in AVROS) {
            avro.encodeToByteArray(writer, WMixed.serializer(), value) shouldBe expected
        }
    }

    @Test
    fun `a list of records whose writer type alternates through a union encodes`() {
        val writer =
            record(
                "Shapes",
                "shape" to SHAPE,
                "maybe" to NULLABLE_SHAPE,
                "shapes" to AvroSchema.ArraySchema(SHAPE),
                "maybes" to AvroSchema.ArraySchema(NULLABLE_SHAPE),
                "rows" to AvroSchema.ArraySchema(AvroSchema.ArraySchema(SHAPE)),
                "last" to NULLABLE_SHAPE
            )
        val value =
            WShapes(
                shape = WSquare(1, "one"),
                maybe = WCircle(2),
                shapes = listOf(WCircle(null), WSquare(3, null), WSquare(4, "four"), WCircle(5)),
                maybes = listOf(WSquare(6, "six"), null, WCircle(7), WCircle(null), null, WSquare(8, null)),
                rows = listOf(listOf(WCircle(9), WSquare(10, "ten")), emptyList(), listOf(WSquare(11, null))),
                last = null
            )
        val expected =
            bytes {
                writeShape(value.shape, nullable = false)
                writeShape(value.maybe, nullable = true)
                writeList(value.shapes) { writeShape(it, nullable = false) }
                writeList(value.maybes) { writeShape(it, nullable = true) }
                writeList(value.rows) { row -> writeList(row) { writeShape(it, nullable = false) } }
                writeShape(value.last, nullable = true)
            }
        for (avro in AVROS) {
            val bytes = avro.encodeToByteArray(writer, WShapes.serializer(), value)
            bytes shouldBe expected
            avro.decodeFromByteArray(writer, WShapes.serializer(), bytes) shouldBe value
        }
    }

    @Test
    fun `a polymorphic value selecting a union branch after a sibling selected another encodes`() {
        // the second value is encoded by the encoder that selected branch 1 for the first one
        val values = listOf(WSquare(1, "a"), WCircle(2), WCircle(null), WSquare(3, null), WCircle(4))
        val expected = bytes { writeList(values) { writeShape(it, nullable = false) } }
        for (avro in AVROS) {
            avro.encodeToByteArray(AvroSchema.ArraySchema(SHAPE), ListSerializer(WShape.serializer()), values) shouldBe expected
        }
    }

    @Test
    fun `maps of lists of records encode`() {
        roundTrips(
            WMapsOfLists.serializer(),
            WMapsOfLists(
                byName = mapOf("a" to listOf(WLeaf(1, "x"), WLeaf(2, "y")), "empty" to emptyList(), "b" to listOf(WLeaf(3, "z"))),
                rows = listOf(mapOf("p" to listOf(WLeaf(4, "w"))), emptyMap(), mapOf("q" to emptyList(), "r" to listOf(WLeaf(5, "v"), WLeaf(6, "u")))),
                after = mapOf("s" to listOf(WLeaf(7, "t")))
            )
        )
    }

    @Test
    fun `a serializer beginning a second structure before ending the first encodes both`() {
        // at the root, then as a record field between records of the same kind, and as list items
        Avro.encodeToByteArray(INNER, TwoAtOnceWriter, WTwins(A, B)) shouldBe bytes { writeTwins(A, B) }
        val holder =
            record(
                "TwinsHolder",
                "before" to INNER,
                "twins" to INNER,
                "after" to INNER,
                "list" to AvroSchema.ArraySchema(INNER)
            )
        val value = WTwinsHolder(C, WTwins(A, B), D, listOf(WTwins(A, B), WTwins(C, D)))
        Avro.encodeToByteArray(holder, WTwinsHolder.serializer(), value) shouldBe
            bytes {
                writeInner(C)
                writeTwins(A, B)
                writeInner(D)
                writeList(value.list) { writeTwins(it.first, it.second) }
            }

        // records skipping an element, whose fields are written in turn: each keeps its own position
        val pair = WXAndY(1, "a") to WXAndY(2, "b")
        Avro.encodeToByteArray(R, TwoSkippingAtOnceWriter, pair) shouldBe
            bytes {
                writeInt(1)
                writeInt(2)
            }
        val pairs = listOf(pair, WXAndY(3, "c") to WXAndY(4, "d"))
        Avro.encodeToByteArray(AvroSchema.ArraySchema(R), ListSerializer(TwoSkippingAtOnceWriter), pairs) shouldBe
            bytes {
                writeList(pairs) { (first, second) ->
                    writeInt(first.x)
                    writeInt(second.x)
                }
            }
    }

    @Test
    fun `a serializer beginning a second list map or polymorphic value before ending the first encodes both`() {
        Avro.encodeToByteArray(AvroSchema.ArraySchema(AvroSchema.IntSchema()), TwoListsAtOnceWriter, listOf(1, 2) to listOf(10, 20, 30)) shouldBe
            bytes {
                writeArrayStart(2)
                writeArrayStart(3)
                listOf(1, 2, 10, 20, 30).forEach {
                    startItem()
                    writeInt(it)
                }
                writeArrayEnd()
                writeArrayEnd()
            }
        Avro.encodeToByteArray(AvroSchema.MapSchema(AvroSchema.IntSchema()), TwoMapsAtOnceWriter, mapOf("a" to 1) to mapOf("b" to 2, "c" to 3)) shouldBe
            bytes {
                writeMapStart(1)
                writeMapStart(2)
                listOf("a" to 1, "b" to 2, "c" to 3).forEach {
                    startItem()
                    writeString(it.first)
                    writeInt(it.second)
                }
                writeMapEnd()
                writeMapEnd()
            }
        Avro.encodeToByteArray(SHAPE, TwoShapesAtOnceWriter, WCircle(1) to WSquare(2, "b")) shouldBe
            bytes {
                writeShape(WCircle(1), nullable = false)
                writeShape(WSquare(2, "b"), nullable = false)
            }
    }

    @Test
    fun `a serializer that never ends its structures encodes`() {
        val value = WNoEndHolder(A, B, C, listOf(A, B, C), listOf(D, A), mapOf("a" to listOf(B), "b" to listOf(C, D)))
        val expected =
            bytes {
                writeInner(A)
                writeInner(B)
                writeInner(C)
                writeList(value.list) { writeInner(it) }
                writeList(value.after) { writeInner(it) }
                writeMap(value.byName) { list -> writeList(list) { writeInner(it) } }
            }
        for (avro in AVROS) {
            avro.encodeToByteArray(WNoEndHolder.serializer(), value) shouldBe expected
        }
    }

    @Test
    fun `a serializer catching an encoding error inside a structure goes on encoding`() {
        val value = WErrorsHolder(A, B, C, listOf(A, B, C), listOf(D, A), listOf(B, C))
        val expected =
            bytes {
                writeInner(A)
                writeInner(B)
                writeInner(C)
                listOf(value.abandonedList, value.list, value.caughtList).forEach { list -> writeList(list) { writeInner(it) } }
            }
        for (avro in AVROS) {
            avro.encodeToByteArray(WErrorsHolder.serializer(), value) shouldBe expected
        }
    }

    @Test
    fun `successive structures of one kind are encoded by one encoder`() {
        val values = listOf(A, B, C)
        val ending = RecordingWriter(endsStructure = true)
        Avro.encodeToByteArray(ListSerializer(ending), values) shouldBe Avro.encodeToByteArray(ListSerializer(WInner.serializer()), values)
        ending.encoders.shouldAllBeOne()
        // a structure never ended keeps its encoder: each next one is encoded on a new encoder
        val notEnding = RecordingWriter(endsStructure = false)
        Avro.encodeToByteArray(ListSerializer(notEnding), values)
        notEnding.encoders.shouldAllDiffer()

        val lists = listOf(listOf(1, 2), listOf(3), emptyList())
        val listsEnding = RecordingCollectionWriter(ListSerializer(Int.serializer()), endsStructure = true)
        Avro.encodeToByteArray(ListSerializer(listsEnding), lists) shouldBe Avro.encodeToByteArray(ListSerializer(ListSerializer(Int.serializer())), lists)
        listsEnding.encoders.shouldAllBeOne()
        val listsNotEnding = RecordingCollectionWriter(ListSerializer(Int.serializer()), endsStructure = false)
        Avro.encodeToByteArray(ListSerializer(listsNotEnding), lists)
        listsNotEnding.encoders.shouldAllDiffer()

        val maps = listOf(mapOf("a" to 1), emptyMap(), mapOf("b" to 2, "c" to 3))
        val mapsEnding = RecordingCollectionWriter(MapSerializer(String.serializer(), Int.serializer()), endsStructure = true)
        Avro.encodeToByteArray(ListSerializer(mapsEnding), maps) shouldBe Avro.encodeToByteArray(ListSerializer(MapSerializer(String.serializer(), Int.serializer())), maps)
        mapsEnding.encoders.shouldAllBeOne()
        val mapsNotEnding = RecordingCollectionWriter(MapSerializer(String.serializer(), Int.serializer()), endsStructure = false)
        Avro.encodeToByteArray(ListSerializer(mapsNotEnding), maps)
        mapsNotEnding.encoders.shouldAllDiffer()

        // a polymorphic structure and the encoder of its value
        val shapes = listOf(WCircle(1), WSquare(2, "b"), WCircle(null))
        val shapesEnding = RecordingShapeWriter(endsStructure = true)
        Avro.encodeToByteArray(AvroSchema.ArraySchema(SHAPE), ListSerializer(shapesEnding), shapes) shouldBe
            Avro.encodeToByteArray(AvroSchema.ArraySchema(SHAPE), ListSerializer(WShape.serializer()), shapes)
        shapesEnding.encoders.shouldAllBeOne()
        shapesEnding.valueEncoders.shouldAllBeOne()
        val shapesNotEnding = RecordingShapeWriter(endsStructure = false)
        Avro.encodeToByteArray(AvroSchema.ArraySchema(SHAPE), ListSerializer(shapesNotEnding), shapes)
        shapesNotEnding.encoders.shouldAllDiffer()
    }

    @Test
    fun `a reused encoder knows no field or item before one is begun`() {
        // as a new encoder: the peeking serializers expect it to fail, then encode normally. Each peeked structure follows
        // one of its kind whose last element selected a union branch.
        val writer =
            record(
                "Peeks",
                "nullableLast" to NULLABLE_LAST,
                "peeked" to NULLABLE_LAST,
                "skipLast" to SKIP_LAST,
                "peekedSkip" to SKIP_LAST,
                "ints" to AvroSchema.ArraySchema(NULLABLE_INT),
                "peekedInts" to AvroSchema.ArraySchema(NULLABLE_INT),
                "counts" to AvroSchema.MapSchema(NULLABLE_INT),
                "peekedCounts" to AvroSchema.MapSchema(NULLABLE_INT)
            )
        val value =
            WPeeks(
                nullableLast = WNullableLast(1, 2),
                peeked = WNullableLast(3, null),
                skipLast = WSkipLast(4, "skipped", 5),
                peekedSkip = WSkipLast(6, "skipped", 7),
                ints = listOf(8, null, 9),
                peekedInts = emptyList(),
                counts = mapOf("a" to null, "b" to 10),
                peekedCounts = emptyMap()
            )
        val expected =
            bytes {
                writeNullableLast(value.nullableLast)
                writeNullableLast(value.peeked)
                writeSkipLast(value.skipLast)
                writeSkipLast(value.peekedSkip)
                writeList(value.ints) { writeNullableInt(it) }
                writeList(value.peekedInts) { writeNullableInt(it) }
                writeMap(value.counts) { writeNullableInt(it) }
                writeMap(value.peekedCounts) { writeNullableInt(it) }
            }
        Avro.encodeToByteArray(NULLABLE_LAST, PeekingRecordWriter(WNullableLast.serializer()), value.peeked) shouldBe bytes { writeNullableLast(value.peeked) }
        Avro.encodeToByteArray(writer, WPeeks.serializer(), value) shouldBe expected
    }

    @Test
    fun `a polymorphic value encoding again on its structure from inside its value gets its own encoder`() {
        val schema = AvroSchema.UnionSchema(listOf(AvroSchema.NullSchema(), INNER))
        Avro.encodeToByteArray(schema, ReentrantPolymorphicWriter, A) shouldBe
            bytes {
                writeIndex(0)
                writeIndex(1)
                writeInner(A)
            }
    }

    private fun <T> roundTrips(
        serializer: KSerializer<T>,
        value: T,
    ) {
        val bytes = Avro.encodeToByteArray(serializer, value)
        for (avro in AVROS) {
            avro.encodeToByteArray(serializer, value) shouldBe bytes
            avro.decodeFromByteArray(serializer, bytes) shouldBe value
        }
    }

    private fun List<Any>.shouldAllBeOne() {
        size shouldBe 3
        all { it === first() } shouldBe true
    }

    private fun List<Any>.shouldAllDiffer() {
        size shouldBe 3
        (this[0] === this[1] || this[1] === this[2] || this[0] === this[2]) shouldBe false
    }

    private companion object {
        val AVROS = listOf(Avro, Avro { validateSerialization = true })

        val A = WInner(1, "a")
        val B = WInner(2, "b")
        val C = WInner(3, "c")
        val D = WInner(4, "d")

        val TREE =
            WTree(
                first = branch(1),
                second = branch(2),
                branches = List(5) { branch(it + 3) },
                branchesByName = mapOf("a" to branch(8), "empty" to branch(0), "b" to branch(9)),
                matrix = listOf(listOf(1, 2), emptyList(), listOf(3)),
                words = listOf(listOf("a"), listOf("b", "c"), emptyList()),
                nested = mapOf("x" to mapOf("p" to leaf(1), "q" to leaf(2)), "y" to emptyMap(), "z" to mapOf("r" to leaf(3))),
                last = leaf(99)
            )

        fun leaf(i: Int) = WLeaf(i, "leaf$i")

        fun branch(i: Int) =
            WBranch(
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
        fun node(depth: Int): WNode =
            if (depth == 0) {
                WNode(0, emptyList(), emptyMap(), null)
            } else {
                WNode(
                    value = depth,
                    children = listOf(node(depth - 1), WNode(-depth, emptyList(), emptyMap(), null), node(depth - 1)),
                    byName = mapOf("a" to node(depth - 1), "b" to WNode(depth * 100, emptyList(), emptyMap(), null)),
                    next = node(depth - 1)
                )
            }
    }
}

private fun record(
    name: String,
    vararg fields: Pair<String, AvroSchema>,
) = AvroSchema.RecordSchema(Name(name), fields.map { AvroSchema.RecordSchema.Field(it.first, it.second) })

private val NULLABLE_INT = AvroSchema.UnionSchema(listOf(AvroSchema.NullSchema(), AvroSchema.IntSchema()))
private val NULLABLE_STRING = AvroSchema.UnionSchema(listOf(AvroSchema.NullSchema(), AvroSchema.StringSchema()))

private val INNER = record("Inner", "x" to AvroSchema.IntSchema(), "s" to AvroSchema.StringSchema())

private val XY_RECORD = record("XY", "x" to AvroSchema.IntSchema(), "y" to AvroSchema.StringSchema())
private val XY_SKIPPED = record("XYSkipped", "x" to AvroSchema.IntSchema())
private val XY_ONLY_Y = record("XYOnlyY", "y" to AvroSchema.StringSchema())
private val XY_SWAPPED = record("XYSwapped", "y" to AvroSchema.StringSchema(), "x" to AvroSchema.IntSchema())
private val R = record("R", "x" to AvroSchema.IntSchema())

private val EXACT = record("Exact", "a" to AvroSchema.IntSchema(), "b" to AvroSchema.StringSchema())
private val SKIPPING = record("Skipping", "a" to AvroSchema.IntSchema(), "b" to AvroSchema.IntSchema())
private val REORDERED = record("Reordered", "b" to AvroSchema.StringSchema(), "a" to AvroSchema.IntSchema())

private val CIRCLE = record("Circle", "radius" to NULLABLE_INT)
private val SQUARE = record("Square", "side" to AvroSchema.IntSchema(), "label" to NULLABLE_STRING)
private val SHAPE = AvroSchema.UnionSchema(listOf(CIRCLE, SQUARE))
private val NULLABLE_SHAPE = AvroSchema.UnionSchema(listOf(AvroSchema.NullSchema(), CIRCLE, SQUARE))

private val NULLABLE_LAST = record("NullableLast", "x" to AvroSchema.IntSchema(), "n" to NULLABLE_INT)
private val SKIP_LAST = record("SkipLast", "x" to AvroSchema.IntSchema(), "n" to NULLABLE_INT)

@Serializable
private data class WLeaf(val x: Int, val s: String)

@Serializable
private data class WBranch(
    val left: WLeaf,
    val right: WLeaf?,
    val leaves: List<WLeaf>,
    val leavesByName: Map<String, WLeaf>,
    val ints: List<Int>,
    val strings: List<String>,
    val counts: Map<String, Int>,
    val flags: Map<String, Boolean>,
    val leafLists: Map<String, List<WLeaf>>,
)

@Serializable
private data class WTree(
    val first: WBranch,
    val second: WBranch,
    val branches: List<WBranch>,
    val branchesByName: Map<String, WBranch>,
    val matrix: List<List<Int>>,
    val words: List<List<String>>,
    val nested: Map<String, Map<String, WLeaf>>,
    val last: WLeaf,
)

@Serializable
private data class WNode(val value: Int, val children: List<WNode>, val byName: Map<String, WNode>, val next: WNode?)

@Serializable
private data class WRecA(val x: Int, val y: String)

@Serializable
private data class WRecB(val flag: Boolean, val y: String, val z: Long)

@Serializable
private data class WAB(val a: WRecA, val b: WRecB)

@Serializable
private data class WAlternating(val a: WRecA, val b: WRecB, val a2: WRecA, val b2: WRecB, val pairs: List<WAB>, val recAs: List<WRecA>, val bs: List<WRecB>)

@Serializable
private data class WMapsOfLists(val byName: Map<String, List<WLeaf>>, val rows: List<Map<String, List<WLeaf>>>, val after: Map<String, List<WLeaf>>)

@Serializable
@SerialName("XY")
@AvroAlias("XYSkipped", "XYOnlyY", "XYSwapped", "R")
private data class WXY(val x: Int, val y: String)

@Serializable
@SerialName("Siblings")
private data class WSiblings(
    val a: WXY,
    val b: WXY,
    val c: WXY,
    val d: WXY,
    val a2: WXY,
    val b2: WXY,
    val c2: WXY,
    val d2: WXY,
    val skippedList: List<WXY>,
    val after: WXY,
)

@Serializable
@SerialName("OnlyX")
@AvroAlias("R")
private data class WOnlyX(val x: Int)

@Serializable
@SerialName("XAndY")
@AvroAlias("R")
private data class WXAndY(val x: Int, val y: String)

@Serializable
@SerialName("YAndX")
@AvroAlias("R")
private data class WYAndX(val y: String, val x: Int)

@Serializable
@SerialName("Writers")
private data class WWriters(
    val a: WXY,
    val p: WOnlyX,
    val c: WXY,
    val q: WXAndY,
    val r: WYAndX,
    val p2: WOnlyX,
    val q2: WXAndY,
    val r2: WYAndX,
    val c2: WXY,
    val rs: List<WYAndX>,
)

@Serializable
private sealed interface WBase

@Serializable
@SerialName("Exact")
private data class WExact(val a: Int, val b: String) : WBase

/** Its writer schema has no `extra` field: the element is skipped. */
@Serializable
@SerialName("Skipping")
private data class WSkipping(val a: Int, val extra: String, val b: Int) : WBase

/** Its writer schema has its fields in the other order: the record is buffered and replayed. */
@Serializable
@SerialName("Reordered")
private data class WReordered(val a: Int, val b: String) : WBase

@Serializable
@SerialName("Mixed")
private data class WMixed(
    val e: WExact,
    val s: WSkipping,
    val r: WReordered,
    val e2: WExact,
    val s2: WSkipping,
    val r2: WReordered,
    val list: List<WBase>,
    val after: WExact,
)

@Serializable
private sealed interface WShape

@Serializable
@SerialName("Circle")
private data class WCircle(val radius: Int?) : WShape

@Serializable
@SerialName("Square")
private data class WSquare(val side: Int, val label: String?) : WShape

@Serializable
@SerialName("Shapes")
private data class WShapes(
    val shape: WShape,
    val maybe: WShape?,
    val shapes: List<WShape>,
    val maybes: List<WShape?>,
    val rows: List<List<WShape>>,
    val last: WShape?,
)

@Serializable
@SerialName("Inner")
private data class WInner(val x: Int, val s: String)

private val W_INNER: SerialDescriptor = WInner.serializer().descriptor

/** A serializer for [WInner] that only encodes in its own way. */
private abstract class InnerWriter : KSerializer<WInner> {
    override val descriptor: SerialDescriptor = W_INNER

    override fun deserialize(decoder: Decoder): WInner = WInner.serializer().deserialize(decoder)
}

private fun CompositeEncoder.writeInner(value: WInner) {
    encodeIntElement(W_INNER, 0, value.x)
    encodeStringElement(W_INNER, 1, value.s)
}

private data class WTwins(val first: WInner, val second: WInner)

/** Begins two records on one encoder before ending the first: the second must be encoded on an encoder of its own. */
private object TwoAtOnceWriter : KSerializer<WTwins> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Twins")

    override fun serialize(
        encoder: Encoder,
        value: WTwins,
    ) {
        val first = encoder.beginStructure(W_INNER)
        val second = encoder.beginStructure(W_INNER)
        (first === second) shouldBe false
        first.writeInner(value.first)
        second.writeInner(value.second)
        second.endStructure(W_INNER)
        first.endStructure(W_INNER)
    }

    override fun deserialize(decoder: Decoder): WTwins = throw UnsupportedOperationException()
}

private val X_AND_Y: SerialDescriptor = WXAndY.serializer().descriptor

/** Begins two records skipping their `y` on one encoder, then writes their elements in turn. */
private object TwoSkippingAtOnceWriter : KSerializer<Pair<WXAndY, WXAndY>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TwoSkipping")

    override fun serialize(
        encoder: Encoder,
        value: Pair<WXAndY, WXAndY>,
    ) {
        val first = encoder.beginStructure(X_AND_Y)
        val second = encoder.beginStructure(X_AND_Y)
        (first === second) shouldBe false
        first.encodeIntElement(X_AND_Y, 0, value.first.x)
        second.encodeIntElement(X_AND_Y, 0, value.second.x)
        first.encodeStringElement(X_AND_Y, 1, value.first.y)
        second.encodeStringElement(X_AND_Y, 1, value.second.y)
        second.endStructure(X_AND_Y)
        first.endStructure(X_AND_Y)
    }

    override fun deserialize(decoder: Decoder): Pair<WXAndY, WXAndY> = throw UnsupportedOperationException()
}

@Serializable
@SerialName("TwinsHolder")
private data class WTwinsHolder(
    val before: WInner,
    @Serializable(with = TwoAtOnceWriter::class) val twins: WTwins,
    val after: WInner,
    val list: List<
        @Serializable(with = TwoAtOnceWriter::class)
        WTwins
    >,
)

private val INTS: SerialDescriptor = ListSerializer(Int.serializer()).descriptor
private val INT_MAP: SerialDescriptor = MapSerializer(String.serializer(), Int.serializer()).descriptor

/** Begins two lists on one encoder before ending the first, and writes them item by item. */
private object TwoListsAtOnceWriter : KSerializer<Pair<List<Int>, List<Int>>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TwoLists")

    override fun serialize(
        encoder: Encoder,
        value: Pair<List<Int>, List<Int>>,
    ) {
        val first = encoder.beginCollection(INTS, value.first.size)
        val second = encoder.beginCollection(INTS, value.second.size)
        (first === second) shouldBe false
        value.first.forEachIndexed { index, item -> first.encodeIntElement(INTS, index, item) }
        value.second.forEachIndexed { index, item -> second.encodeIntElement(INTS, index, item) }
        second.endStructure(INTS)
        first.endStructure(INTS)
    }

    override fun deserialize(decoder: Decoder): Pair<List<Int>, List<Int>> = throw UnsupportedOperationException()
}

/** Begins two maps on one encoder before ending the first, and writes them entry by entry. */
private object TwoMapsAtOnceWriter : KSerializer<Pair<Map<String, Int>, Map<String, Int>>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TwoMaps")

    override fun serialize(
        encoder: Encoder,
        value: Pair<Map<String, Int>, Map<String, Int>>,
    ) {
        val first = encoder.beginCollection(INT_MAP, value.first.size)
        val second = encoder.beginCollection(INT_MAP, value.second.size)
        (first === second) shouldBe false
        first.writeEntries(value.first)
        second.writeEntries(value.second)
        second.endStructure(INT_MAP)
        first.endStructure(INT_MAP)
    }

    private fun CompositeEncoder.writeEntries(map: Map<String, Int>) {
        var index = 0
        map.forEach { (key, value) ->
            encodeStringElement(INT_MAP, index++, key)
            encodeIntElement(INT_MAP, index++, value)
        }
    }

    override fun deserialize(decoder: Decoder): Pair<Map<String, Int>, Map<String, Int>> = throw UnsupportedOperationException()
}

private val SHAPE_DESCRIPTOR: SerialDescriptor = WShape.serializer().descriptor

/** Begins two polymorphic structures on one encoder before ending the first. */
private object TwoShapesAtOnceWriter : KSerializer<Pair<WCircle, WSquare>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TwoShapes")

    override fun serialize(
        encoder: Encoder,
        value: Pair<WCircle, WSquare>,
    ) {
        val first = encoder.beginStructure(SHAPE_DESCRIPTOR)
        val second = encoder.beginStructure(SHAPE_DESCRIPTOR)
        (first === second) shouldBe false
        first.encodeSerializableElement(SHAPE_DESCRIPTOR, 1, WCircle.serializer(), value.first)
        second.encodeSerializableElement(SHAPE_DESCRIPTOR, 1, WSquare.serializer(), value.second)
        second.endStructure(SHAPE_DESCRIPTOR)
        first.endStructure(SHAPE_DESCRIPTOR)
    }

    override fun deserialize(decoder: Decoder): Pair<WCircle, WSquare> = throw UnsupportedOperationException()
}

private object NoEndWriter : InnerWriter() {
    override fun serialize(
        encoder: Encoder,
        value: WInner,
    ) = encoder.beginStructure(W_INNER).writeInner(value)
}

@Serializable
private data class WNoEndHolder(
    @Serializable(with = NoEndWriter::class) val a: WInner,
    @Serializable(with = NoEndWriter::class) val b: WInner,
    val c: WInner,
    val list: List<
        @Serializable(with = NoEndWriter::class)
        WInner
    >,
    val after: List<WInner>,
    val byName: Map<
        String,
        List<
            @Serializable(with = NoEndWriter::class)
            WInner
        >
    >,
)

/** Catches an error raised before anything is written, then encodes the field as it should, and ends the record. */
private object CatchingWriter : InnerWriter() {
    override fun serialize(
        encoder: Encoder,
        value: WInner,
    ) {
        val record = encoder.beginStructure(W_INNER)
        try {
            record.encodeBooleanElement(W_INNER, 0, true)
            error("x is written as an int")
        } catch (_: AvroSchemaMismatchException) {
        }
        record.writeInner(value)
        record.endStructure(W_INNER)
    }
}

/** Abandons a record on an error raised before anything is written, without ending it, and encodes the record again. */
private object AbandoningWriter : InnerWriter() {
    override fun serialize(
        encoder: Encoder,
        value: WInner,
    ) {
        try {
            encoder.beginStructure(W_INNER).encodeBooleanElement(W_INNER, 0, true)
            error("x is written as an int")
        } catch (_: AvroSchemaMismatchException) {
        }
        encoder.encodeSerializableValue(WInner.serializer(), value)
    }
}

@Serializable
private data class WErrorsHolder(
    @Serializable(with = CatchingWriter::class) val caught: WInner,
    @Serializable(with = AbandoningWriter::class) val abandoned: WInner,
    val after: WInner,
    val abandonedList: List<
        @Serializable(with = AbandoningWriter::class)
        WInner
    >,
    val list: List<WInner>,
    val caughtList: List<
        @Serializable(with = CatchingWriter::class)
        WInner
    >,
)

/** Keeps the encoder of each record it begins. */
private class RecordingWriter(private val endsStructure: Boolean) : InnerWriter() {
    val encoders = mutableListOf<Any>()

    override fun serialize(
        encoder: Encoder,
        value: WInner,
    ) {
        val record = encoder.beginStructure(W_INNER)
        encoders += record
        record.writeInner(value)
        if (endsStructure) record.endStructure(W_INNER)
    }
}

/** Keeps the encoder of each list or map it begins, writing it as [delegate] does. */
private class RecordingCollectionWriter<T>(
    private val delegate: KSerializer<T>,
    private val endsStructure: Boolean,
) : KSerializer<T> {
    val encoders = mutableListOf<Any>()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        delegate.serialize(
            object : Encoder by encoder {
                override fun beginCollection(
                    descriptor: SerialDescriptor,
                    collectionSize: Int,
                ): CompositeEncoder {
                    val collection = encoder.beginCollection(descriptor, collectionSize)
                    encoders += collection
                    return if (endsStructure) collection else NotEnding(collection)
                }
            },
            value
        )
    }

    override fun deserialize(decoder: Decoder): T = throw UnsupportedOperationException()
}

/** Never ends the structure of [delegate]. */
private class NotEnding(private val delegate: CompositeEncoder) : CompositeEncoder by delegate {
    override fun endStructure(descriptor: SerialDescriptor) {}
}

/** Keeps the encoder of each polymorphic structure it begins, and the encoder its value is encoded with. */
private class RecordingShapeWriter(private val endsStructure: Boolean) : KSerializer<WShape> {
    val encoders = mutableListOf<Any>()
    val valueEncoders = mutableListOf<Any>()
    override val descriptor: SerialDescriptor = SHAPE_DESCRIPTOR

    override fun serialize(
        encoder: Encoder,
        value: WShape,
    ) {
        val structure = encoder.beginStructure(SHAPE_DESCRIPTOR)
        encoders += structure
        val valueWriter =
            object : KSerializer<WShape> {
                override val descriptor: SerialDescriptor = SHAPE_DESCRIPTOR

                override fun serialize(
                    encoder: Encoder,
                    value: WShape,
                ) {
                    valueEncoders += encoder
                    when (value) {
                        is WCircle -> encoder.encodeSerializableValue(WCircle.serializer(), value)
                        is WSquare -> encoder.encodeSerializableValue(WSquare.serializer(), value)
                    }
                }

                override fun deserialize(decoder: Decoder): WShape = throw UnsupportedOperationException()
            }
        structure.encodeSerializableElement(SHAPE_DESCRIPTOR, 1, valueWriter, value)
        if (endsStructure) structure.endStructure(SHAPE_DESCRIPTOR)
    }

    override fun deserialize(decoder: Decoder): WShape = throw UnsupportedOperationException()
}

/**
 * Encodes a polymorphic value whose own serializer first encodes a null value on the same polymorphic structure: that
 * one must not take the encoder the outer value is still encoding with.
 */
private object ReentrantPolymorphicWriter : InnerWriter() {
    override fun serialize(
        encoder: Encoder,
        value: WInner,
    ) {
        val structure = encoder.beginStructure(SHAPE_DESCRIPTOR)
        val valueWriter =
            object : InnerWriter() {
                override fun serialize(
                    encoder: Encoder,
                    value: WInner,
                ) {
                    structure.encodeSerializableElement(SHAPE_DESCRIPTOR, 1, WInner.serializer().nullable, null)
                    encoder.encodeSerializableValue(WInner.serializer(), value)
                }
            }
        structure.encodeSerializableElement(SHAPE_DESCRIPTOR, 1, valueWriter, value)
        structure.endStructure(SHAPE_DESCRIPTOR)
    }
}

@Serializable
@SerialName("NullableLast")
private data class WNullableLast(val x: Int, val n: Int?)

/** Its writer schema has no `extra` field. */
@Serializable
@SerialName("SkipLast")
private data class WSkipLast(val x: Int, val extra: String, val n: Int?)

/**
 * Asks a record encoder for its writer schema, and to select a union branch, before any field is begun, as a new
 * encoder fails to do; ends it, and encodes the value again normally.
 */
private open class PeekingRecordWriter<T>(private val delegate: KSerializer<T>) : KSerializer<T> {
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        val record = encoder.beginStructure(descriptor)
        shouldThrow<IllegalStateException> { (record as AvroEncoder).currentWriterSchema }
        shouldThrow<IllegalStateException> { (record as AvroEncoder).encodeUnionIndex(1) }
        record.endStructure(descriptor)
        encoder.encodeSerializableValue(delegate, value)
    }

    override fun deserialize(decoder: Decoder): T = throw UnsupportedOperationException()
}

private object PeekingNullableLastWriter : PeekingRecordWriter<WNullableLast>(WNullableLast.serializer())

private object PeekingSkipLastWriter : PeekingRecordWriter<WSkipLast>(WSkipLast.serializer())

/** Asks a list or map encoder for its item schema, and to select a union branch, before any item, on an empty one. */
private abstract class PeekingEmptyWriter<T>(delegate: KSerializer<T>) : KSerializer<T> {
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        val collection = encoder.beginCollection(descriptor, 0)
        shouldThrow<IllegalStateException> { (collection as AvroEncoder).currentWriterSchema }
        shouldThrow<IllegalStateException> { (collection as AvroEncoder).encodeUnionIndex(1) }
        collection.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): T = throw UnsupportedOperationException()
}

private object PeekingIntsWriter : PeekingEmptyWriter<List<Int?>>(ListSerializer(Int.serializer().nullable))

private object PeekingCountsWriter : PeekingEmptyWriter<Map<String, Int?>>(MapSerializer(String.serializer(), Int.serializer().nullable))

@Serializable
@SerialName("Peeks")
private data class WPeeks(
    val nullableLast: WNullableLast,
    @Serializable(with = PeekingNullableLastWriter::class) val peeked: WNullableLast,
    val skipLast: WSkipLast,
    @Serializable(with = PeekingSkipLastWriter::class) val peekedSkip: WSkipLast,
    val ints: List<Int?>,
    @Serializable(with = PeekingIntsWriter::class) val peekedInts: List<Int?>,
    val counts: Map<String, Int?>,
    @Serializable(with = PeekingCountsWriter::class) val peekedCounts: Map<String, Int?>,
)

private fun bytes(write: ByteArrayBinaryWriter.() -> Unit): ByteArray = ByteArrayBinaryWriter().apply { write() }.toByteArray()

private fun ByteArrayBinaryWriter.writeInner(value: WInner) {
    writeInt(value.x)
    writeString(value.s)
}

private fun ByteArrayBinaryWriter.writeTwins(
    first: WInner,
    second: WInner,
) {
    writeInner(first)
    writeInner(second)
}

private fun ByteArrayBinaryWriter.writeXY(value: WXY) {
    writeInt(value.x)
    writeString(value.y)
}

private fun ByteArrayBinaryWriter.writeNullableInt(value: Int?) {
    if (value == null) {
        writeIndex(0)
        writeNull()
    } else {
        writeIndex(1)
        writeInt(value)
    }
}

private fun ByteArrayBinaryWriter.writeNullableLast(value: WNullableLast) {
    writeInt(value.x)
    writeNullableInt(value.n)
}

private fun ByteArrayBinaryWriter.writeSkipLast(value: WSkipLast) {
    writeInt(value.x)
    writeNullableInt(value.n)
}

private fun ByteArrayBinaryWriter.writeBase(value: WBase) {
    when (value) {
        is WExact -> {
            writeInt(value.a)
            writeString(value.b)
        }

        is WSkipping -> {
            writeInt(value.a)
            writeInt(value.b)
        }

        is WReordered -> {
            writeString(value.b)
            writeInt(value.a)
        }
    }
}

/** A shape as a branch of [SHAPE], or of [NULLABLE_SHAPE] when [nullable]. */
private fun ByteArrayBinaryWriter.writeShape(
    value: WShape?,
    nullable: Boolean,
) {
    val offset = if (nullable) 1 else 0
    when (value) {
        null -> {
            writeIndex(0)
            writeNull()
        }

        is WCircle -> {
            writeIndex(offset)
            writeNullableInt(value.radius)
        }

        is WSquare -> {
            writeIndex(offset + 1)
            writeInt(value.side)
            if (value.label == null) {
                writeIndex(0)
                writeNull()
            } else {
                writeIndex(1)
                writeString(value.label)
            }
        }
    }
}

/** [items] as one array, written with the calls an array encoder makes. */
private fun <I> ByteArrayBinaryWriter.writeList(
    items: List<I>,
    writeItem: ByteArrayBinaryWriter.(I) -> Unit,
) {
    writeArrayStart(items.size.toLong())
    items.forEach {
        startItem()
        writeItem(it)
    }
    writeArrayEnd()
}

/** [entries] as one map, written with the calls a map encoder makes. */
private fun <V> ByteArrayBinaryWriter.writeMap(
    entries: Map<String, V>,
    writeValue: ByteArrayBinaryWriter.(V) -> Unit,
) {
    writeMapStart(entries.size.toLong())
    entries.forEach { (key, value) ->
        startItem()
        writeString(key)
        writeValue(value)
    }
    writeMapEnd()
}