package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroAlias
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.internal.codec.ByteArrayEncoder
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import com.github.avrokotlin.avro4k.schema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.findPolymorphicSerializer
import kotlinx.serialization.internal.AbstractPolymorphicSerializer
import kotlin.test.Test

/**
 * A decoder keeps the polymorphic decoder it begins, with its value decoder, and hands them each next polymorphic value
 * instead of creating them per value; the polymorphic decoder remembers the serial names it found by descriptor and
 * writer schema branch. These tests decode every shape where a kept polymorphic decoder could carry state from one value
 * into the next: subclasses of every kind alternating, null values, unions read by the polymorphic decoder itself,
 * unknown subclasses, default values, polymorphic values nested in polymorphic values, more subclasses than it
 * remembers, two polymorphic types sharing writer schemas, and serializers that do not follow the begin-end protocol,
 * which must still decode correctly on a new decoder.
 */
class PolymorphicDecoderReuseTest {
    @Test
    fun `records objects and enums alternating in one list decode`() {
        val items = listOf(Point(1, 2), Label("a"), Empty, Color.RED, Point(3, 4), Color.GREEN, Empty, Label("b"), Label("c"), Point(5, 6), Empty)
        roundTrips(ItemList.serializer(), ItemList(items, Point(7, 8)))
        roundTrips(ListSerializer(ItemList.serializer()), listOf(ItemList(items, Label("x")), ItemList(emptyList(), Empty), ItemList(items.reversed(), Color.GREEN)))
    }

    @Test
    fun `nullable polymorphic fields decode`() {
        roundTrips(
            ListSerializer(NullableItems.serializer()),
            listOf(
                NullableItems(1, Point(1, 1), null),
                NullableItems(2, null, Label("a")),
                NullableItems(3, Color.RED, Empty),
                NullableItems(4, null, null),
                NullableItems(5, Point(2, 2), Point(3, 3))
            )
        )
    }

    @Test
    fun `a union read by the polymorphic decoder itself decodes`() {
        // The parent resolves a union before handing it to a deserializer, so the polymorphic decoder only reads the
        // branch itself when a serializer begins it on the decoder of an inline element, as this one does
        val quads = listOf(Quad(Point(1, 2), Label("a"), Empty, Color.GREEN), Quad(Label("b"), Label("c"), Point(3, 4), Empty), Quad(Color.RED, Empty, Color.GREEN, Point(5, 6)))
        val bytes = Avro.encodeToByteArray(ListSerializer(Quad.serializer()), quads)
        for (avro in AVROS) {
            avro.decodeFromByteArray(ListSerializer(Quad.serializer()), bytes) shouldBe quads
            avro.decodeFromByteArray(Avro.schema(ListSerializer(Quad.serializer())), ListSerializer(QuadByInlineElementsSerializer), bytes) shouldBe quads
        }
    }

    @Test
    fun `an unknown subclass fails with the same message`() {
        val unknown = AvroSchema.RecordSchema(Name("Unknown"), listOf(AvroSchema.RecordSchema.Field("z", AvroSchema.IntSchema())))
        val point = Avro.schema<Point>() as AvroSchema.RecordSchema
        val expectedMessage = "Unknown schema name 'Unknown' for polymorphic type ${ITEM.serialName}. Full schema: ${unknown.toSchemaJsonString()}"
        val single = bytes { writeInt(1) }
        // the unknown value after a known one, whose serial name is then remembered
        val list =
            bytes {
                writeLong(3)
                writeIndex(0)
                writeInt(1)
                writeInt(2)
                writeIndex(0)
                writeInt(3)
                writeInt(4)
                writeIndex(1)
                writeInt(5)
                writeLong(0)
            }
        for (avro in AVROS) {
            shouldThrow<AvroSchemaMismatchException> { avro.decodeFromByteArray(unknown, Item.serializer(), single) }.message shouldBe expectedMessage
            shouldThrow<AvroSchemaMismatchException> {
                avro.decodeFromByteArray(AvroSchema.ArraySchema(AvroSchema.UnionSchema(point, unknown)), ListSerializer(Item.serializer()), list)
            }.message shouldBe expectedMessage
        }
    }

    @Test
    fun `polymorphic default values decode`() {
        val writer = AvroSchema.RecordSchema(Name("WithItemDefaults"), listOf(AvroSchema.RecordSchema.Field("id", AvroSchema.IntSchema())))
        val bytes =
            bytes {
                writeLong(3)
                listOf(1, 2, 3).forEach { writeInt(it) }
                writeLong(0)
            }
        for (avro in AVROS) {
            avro.decodeFromByteArray(AvroSchema.ArraySchema(writer), ListSerializer(WithItemDefaults.serializer()), bytes) shouldBe
                List(3) { WithItemDefaults(it + 1, DefaultPoint(1, 2), DefaultColor.GREEN, DefaultLabel("t"), null) }
        }
    }

    @Test
    fun `polymorphic values nested in polymorphic values decode`() {
        val expressions =
            listOf(
                Sum(Lit(1), Neg(Sum(Neg(Neg(Lit(2))), Many(listOf(Lit(3), Neg(Lit(4)), Many(emptyList())))))),
                Lit(5),
                Many(listOf(Sum(Lit(6), Lit(7)), Neg(Many(listOf(Lit(8)))), Lit(9))),
                Neg(Neg(Neg(Neg(Neg(Lit(10))))))
            )
        roundTrips(ListSerializer(Expr.serializer()), expressions)
        roundTrips(Expr.serializer(), Many(expressions))
    }

    @Test
    fun `more subclasses than the decoder remembers alternate in one list`() {
        val one = listOf(S1(1), S2("b"), S3(true), S4(4L), S5(5.5), S6("f", 6))
        val values = one + one + one.reversed() + listOf(S5(1.0), S1(2), S5(3.0), S6("g", 7), S5(4.0), S2("c"), S4(8L), S3(false), S6("h", 9))
        roundTrips(ListSerializer(Six.serializer()), values)
    }

    @Test
    fun `two polymorphic types reading the same writer schemas decode`() {
        // Each writer schema is one instance, read by both types, whose subclasses have different serial names for it:
        // a serial name found for one type must not be taken for the other
        val shared = (1..4).map { AvroSchema.RecordSchema(Name("Shared$it"), listOf(AvroSchema.RecordSchema.Field("x", AvroSchema.IntSchema()))) }
        val writer =
            record(
                "Bases",
                "a1" to shared[0],
                "a2" to shared[1],
                "a3" to shared[2],
                "a4" to shared[3],
                "b4" to shared[3],
                "b3" to shared[2],
                "b2" to shared[1],
                "b1" to shared[0]
            )
        val bytes = bytes { (1..8).forEach { writeInt(it) } }
        for (avro in AVROS) {
            avro.decodeFromByteArray(writer, Bases.serializer(), bytes) shouldBe Bases(SubA1(1), SubA2(2), SubA3(3), SubA4(4), SubB4(5), SubB3(6), SubB2(7), SubB1(8))
        }
    }

    @Test
    fun `a serializer beginning a second polymorphic value before ending the first decodes both`() {
        val shared = AvroSchema.RecordSchema(Name("Shared1"), listOf(AvroSchema.RecordSchema.Field("x", AvroSchema.IntSchema())))
        // at the root
        Avro.decodeFromByteArray(
            shared,
            TwoBasesAtOnceSerializer,
            bytes {
                writeInt(1)
                writeInt(2)
            }
        ) shouldBe TwoBases(SubA1(1), SubB1(2))
        // as a record field between polymorphic values of the same type, then as list items
        val holder = record("TwoBasesHolder", "before" to shared, "twins" to shared, "after" to shared, "list" to AvroSchema.ArraySchema(shared))
        val holderBytes =
            bytes {
                (1..4).forEach { writeInt(it) }
                writeLong(2)
                (5..8).forEach { writeInt(it) }
                writeLong(0)
            }
        Avro.decodeFromByteArray(holder, TwoBasesHolder.serializer(), holderBytes) shouldBe
            TwoBasesHolder(SubA1(1), TwoBases(SubA1(2), SubB1(3)), SubA1(4), listOf(TwoBases(SubA1(5), SubB1(6)), TwoBases(SubA1(7), SubB1(8))))
    }

    @Test
    fun `a serializer that never ends its polymorphic values decodes`() {
        roundTrips(
            NoEndItems.serializer(),
            NoEndItems(Point(1, 2), Label("a"), Empty, listOf(Color.RED, Point(3, 4), Label("b")), listOf(Empty, Color.GREEN))
        )
    }

    @Test
    fun `successive polymorphic values are decoded by one decoder`() {
        val bytes = Avro.encodeToByteArray(ListSerializer(Item.serializer()), listOf(Point(1, 2), Point(3, 4), Label("a"), Point(5, 6)))
        val ending = RecordingItemSerializer(endsStructure = true)
        Avro.decodeFromByteArray(ListSerializer(ending), bytes) shouldBe listOf(Point(1, 2), Point(3, 4), Label("a"), Point(5, 6))
        ending.decoders.size shouldBe 4
        ending.decoders.all { it === ending.decoders[0] } shouldBe true
        ending.valueDecoders.all { it === ending.valueDecoders[0] } shouldBe true

        // a value never ended keeps its decoder: each next one is decoded on a new decoder
        val notEnding = RecordingItemSerializer(endsStructure = false)
        Avro.decodeFromByteArray(ListSerializer(notEnding), bytes) shouldBe listOf(Point(1, 2), Point(3, 4), Label("a"), Point(5, 6))
        notEnding.decoders.toSet().size shouldBe 4
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
    }
}

@Serializable
private sealed interface Item

@Serializable
@SerialName("ItemPoint")
private data class Point(val x: Int, val y: Int) : Item

@Serializable
@SerialName("ItemLabel")
private data class Label(val text: String) : Item

@Serializable
@SerialName("ItemEmpty")
private data object Empty : Item

@Serializable
@SerialName("ItemColor")
private enum class Color : Item { RED, GREEN }

private val ITEM: SerialDescriptor = Item.serializer().descriptor

@Serializable
private data class ItemList(val items: List<Item>, val last: Item)

@Serializable
private data class NullableItems(val id: Int, val first: Item?, val second: Item?)

@Serializable
private data class Quad(val a: Item, val b: Item, val c: Item, val d: Item)

/** Reads each field by handing the item serializer the decoder of the inline element: its union is not resolved yet. */
private object QuadByInlineElementsSerializer : KSerializer<Quad> {
    override val descriptor: SerialDescriptor = Quad.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: Quad,
    ) = Quad.serializer().serialize(encoder, value)

    override fun deserialize(decoder: Decoder): Quad {
        val record = decoder.beginStructure(descriptor)
        val items = List(4) { Item.serializer().deserialize(record.decodeInlineElement(descriptor, it)) }
        record.endStructure(descriptor)
        return Quad(items[0], items[1], items[2], items[3])
    }
}

@Serializable
private sealed interface DefaultItem

@Serializable
@SerialName("DefaultPoint")
private data class DefaultPoint(val x: Int, val y: Int) : DefaultItem

@Serializable
@SerialName("DefaultLabel")
private data class DefaultLabel(val text: String) : DefaultItem

@Serializable
@SerialName("DefaultColor")
private enum class DefaultColor : DefaultItem { RED, GREEN }

@Serializable
private data class WithItemDefaults(
    val id: Int,
    @AvroDefault("""{"x": 1, "y": 2}""") val point: DefaultItem,
    @AvroDefault("\"GREEN\"") val color: DefaultItem,
    @AvroDefault("""{"text": "t"}""") val label: DefaultItem,
    val nothing: DefaultItem?,
)

@Serializable
private sealed interface Expr

@Serializable
@SerialName("Lit")
private data class Lit(val value: Int) : Expr

@Serializable
@SerialName("Neg")
private data class Neg(val inner: Expr) : Expr

@Serializable
@SerialName("Sum")
private data class Sum(val left: Expr, val right: Expr) : Expr

@Serializable
@SerialName("Many")
private data class Many(val items: List<Expr>) : Expr

@Serializable
private sealed interface Six

@Serializable
@SerialName("S1")
private data class S1(val a: Int) : Six

@Serializable
@SerialName("S2")
private data class S2(val b: String) : Six

@Serializable
@SerialName("S3")
private data class S3(val c: Boolean) : Six

@Serializable
@SerialName("S4")
private data class S4(val d: Long) : Six

@Serializable
@SerialName("S5")
private data class S5(val e: Double) : Six

@Serializable
@SerialName("S6")
private data class S6(val f: String, val g: Int) : Six

@Serializable
private sealed interface BaseA

@Serializable
@SerialName("Shared1")
private data class SubA1(val x: Int) : BaseA

@Serializable
@SerialName("Shared2")
private data class SubA2(val x: Int) : BaseA

@Serializable
@SerialName("Shared3")
private data class SubA3(val x: Int) : BaseA

@Serializable
@SerialName("Shared4")
private data class SubA4(val x: Int) : BaseA

@Serializable
private sealed interface BaseB

@Serializable
@SerialName("SubB1")
@AvroAlias("Shared1")
private data class SubB1(val x: Int) : BaseB

@Serializable
@SerialName("SubB2")
@AvroAlias("Shared2")
private data class SubB2(val x: Int) : BaseB

@Serializable
@SerialName("SubB3")
@AvroAlias("Shared3")
private data class SubB3(val x: Int) : BaseB

@Serializable
@SerialName("SubB4")
@AvroAlias("Shared4")
private data class SubB4(val x: Int) : BaseB

@Serializable
private data class Bases(
    val a1: BaseA,
    val a2: BaseA,
    val a3: BaseA,
    val a4: BaseA,
    val b4: BaseB,
    val b3: BaseB,
    val b2: BaseB,
    val b1: BaseB,
)

private val BASE_A: SerialDescriptor = BaseA.serializer().descriptor
private val BASE_B: SerialDescriptor = BaseB.serializer().descriptor

private data class TwoBases(val a: BaseA, val b: BaseB)

/**
 * Begins a polymorphic value of each type on one decoder before ending the first, and finds both serial names before
 * decoding either value: the second must be decoded on a decoder of its own.
 */
private object TwoBasesAtOnceSerializer : KSerializer<TwoBases> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TwoBases")

    override fun serialize(
        encoder: Encoder,
        value: TwoBases,
    ) = throw UnsupportedOperationException()

    override fun deserialize(decoder: Decoder): TwoBases {
        val first = decoder.beginStructure(BASE_A)
        val second = decoder.beginStructure(BASE_B)
        first.decodeStringElement(BASE_A, 0) shouldBe "Shared1"
        second.decodeStringElement(BASE_B, 0) shouldBe "SubB1"
        val a = first.decodeSerializableElement(BASE_A, 1, SubA1.serializer())
        val b = second.decodeSerializableElement(BASE_B, 1, SubB1.serializer())
        second.endStructure(BASE_B)
        first.endStructure(BASE_A)
        return TwoBases(a, b)
    }
}

@Serializable
private data class TwoBasesHolder(
    val before: BaseA,
    @Serializable(with = TwoBasesAtOnceSerializer::class) val twins: TwoBases,
    val after: BaseA,
    val list: List<
        @Serializable(with = TwoBasesAtOnceSerializer::class)
        TwoBases
    >,
)

/** Decodes an [Item] as the polymorphic serializer does, ending the value only if [endsStructure]. */
@OptIn(InternalSerializationApi::class)
private fun decodeItem(
    decoder: Decoder,
    endsStructure: Boolean,
    onBegun: (CompositeDecoder) -> Unit = {},
    onValueDecoder: (Decoder) -> Unit = {},
): Item {
    val composite = decoder.beginStructure(ITEM)
    onBegun(composite)
    val name = composite.decodeStringElement(ITEM, 0)
    val subclass = (Item.serializer() as AbstractPolymorphicSerializer<Item>).findPolymorphicSerializer(composite, name)
    val value =
        composite.decodeSerializableElement(
            ITEM,
            1,
            object : DeserializationStrategy<Item> {
                override val descriptor: SerialDescriptor = subclass.descriptor

                override fun deserialize(decoder: Decoder): Item {
                    onValueDecoder(decoder)
                    return subclass.deserialize(decoder)
                }
            }
        )
    if (endsStructure) composite.endStructure(ITEM)
    return value
}

/** An [Item] serializer that never ends its polymorphic value. */
private object NoEndItemSerializer : KSerializer<Item> {
    override val descriptor: SerialDescriptor = ITEM

    override fun serialize(
        encoder: Encoder,
        value: Item,
    ) = Item.serializer().serialize(encoder, value)

    override fun deserialize(decoder: Decoder): Item = decodeItem(decoder, endsStructure = false)
}

@Serializable
private data class NoEndItems(
    @Serializable(with = NoEndItemSerializer::class) val a: Item,
    @Serializable(with = NoEndItemSerializer::class) val b: Item,
    val c: Item,
    val list: List<
        @Serializable(with = NoEndItemSerializer::class)
        Item
    >,
    val after: List<Item>,
)

/** Keeps the polymorphic decoder of each value it begins, and the decoder its value is decoded on. */
private class RecordingItemSerializer(private val endsStructure: Boolean) : KSerializer<Item> {
    val decoders = mutableListOf<CompositeDecoder>()
    val valueDecoders = mutableListOf<Decoder>()

    override val descriptor: SerialDescriptor = ITEM

    override fun serialize(
        encoder: Encoder,
        value: Item,
    ) = Item.serializer().serialize(encoder, value)

    override fun deserialize(decoder: Decoder): Item = decodeItem(decoder, endsStructure, { decoders += it }, { valueDecoders += it })
}

private fun record(
    name: String,
    vararg fields: Pair<String, AvroSchema>,
): AvroSchema.RecordSchema = AvroSchema.RecordSchema(Name(name), fields.map { AvroSchema.RecordSchema.Field(it.first, it.second) })

private fun bytes(write: ByteArrayEncoder.() -> Unit): ByteArray = ByteArrayEncoder().apply { write() }.toByteArray()