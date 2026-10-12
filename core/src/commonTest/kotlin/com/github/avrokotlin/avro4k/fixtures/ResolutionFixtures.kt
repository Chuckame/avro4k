package com.github.avrokotlin.avro4k.fixtures

import com.github.avrokotlin.avro4k.AvroAlias
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroEnumDefault
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Schema resolution: missing fields filled from the reader's defaults, writer fields of every kind skipped, the
 * promotions avro4k supports (int to long, float to double, bytes and string), reordered fields, renamed types and fields found through aliases, unknown enum symbols, and
 * unions on one side only. Writer and reader records share their full name (or an alias), as Avro requires.
 */
internal val resolutionFixtures: List<ResolutionFixture<*, *>> =
    listOf(
        ResolutionFixture(
            name = "missing fields take the reader's defaults",
            writerSerializer = PersonV1.serializer(),
            writerValue = PersonV1(name = "Ada"),
            writerSchema =
                "{\"type\":\"record\",\"name\":\"Person\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[{\"name\":\"name\",\"type\":\"string\"}]}",
            hex = "06416461",
            readerSerializer = PersonV2.serializer(),
            expected =
                PersonV2(
                    name = "Ada",
                    age = 42,
                    city = "unknown",
                    nickname = null,
                    tags = emptyList(),
                    origin = Point(x = 1, y = 2),
                    suit = Suit.HEARTS,
                    scores = emptyMap(),
                    ratio = 0.5
                )
        ),
        ResolutionFixture(
            name = "writer fields of every kind are skipped",
            writerSerializer = RichV1.serializer(),
            writerValue =
                RichV1(
                    id = 1,
                    skippedString = "skip me",
                    skippedRecord = Point(5, 6),
                    skippedList = listOf(Point(7, 8)),
                    skippedMap = mapOf("k" to listOf(1, 2)),
                    skippedUnion = Animal.Dog(age = 2, goodBoy = false),
                    skippedNull = null,
                    skippedFixed = Md5(ByteArray(16) { it.toByte() }),
                    skippedEnum = Suit.DIAMONDS,
                    skippedBytes = byteArrayOf(1, 2, 3),
                    skippedDouble = 1.25,
                    skippedFloat = -1.5f,
                    skippedLong = Long.MIN_VALUE,
                    skippedBoolean = true,
                    last = "kept"
                ),
            writerSchema =
                "{\"type\":\"record\",\"name\":\"Rich\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[{\"name\":\"id\",\"type\":\"int\"}," +
                    "{\"name\":\"skippedString\",\"type\":\"string\"},{\"name\":\"skippedRecord\",\"type\":{\"type\":\"record\",\"name\":\"Point\",\"fields\":[" +
                    "{\"name\":\"x\",\"type\":\"int\"},{\"name\":\"y\",\"type\":\"int\"}]}}," +
                    "{\"name\":\"skippedList\",\"type\":{\"type\":\"array\",\"items\":\"Point\"},\"default\":[]}," +
                    "{\"name\":\"skippedMap\",\"type\":{\"type\":\"map\",\"values\":{\"type\":\"array\",\"items\":\"int\"}},\"default\":{}}," +
                    "{\"name\":\"skippedUnion\",\"type\":[\"null\"," +
                    "{\"type\":\"record\",\"name\":\"Cat\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[" +
                    "{\"name\":\"name\",\"type\":\"string\"}]}," +
                    "{\"type\":\"record\",\"name\":\"Dog\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[{\"name\":\"age\"," +
                    "\"type\":\"int\"}," +
                    "{\"name\":\"goodBoy\",\"type\":\"boolean\"}]}," +
                    "{\"type\":\"record\",\"name\":\"Fish\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[]}],\"default\":null}," +
                    "{\"name\":\"skippedNull\",\"type\":[\"null\",\"string\"],\"default\":null}," +
                    "{\"name\":\"skippedFixed\",\"type\":{\"type\":\"fixed\",\"name\":\"Md5\",\"size\":16}}," +
                    "{\"name\":\"skippedEnum\",\"type\":{\"type\":\"enum\",\"name\":\"Suit\",\"symbols\":[\"CLUBS\",\"DIAMONDS\",\"HEARTS\",\"SPADES\"]}}," +
                    "{\"name\":\"skippedBytes\",\"type\":\"bytes\"},{\"name\":\"skippedDouble\",\"type\":\"double\"},{\"name\":\"skippedFloat\",\"type\":\"float\"}," +
                    "{\"name\":\"skippedLong\",\"type\":\"long\"},{\"name\":\"skippedBoolean\",\"type\":\"boolean\"},{\"name\":\"last\",\"type\":\"string\"}]}",
            hex =
                "020e736b6970206d650a0c020e100002026b040204000004040000000102030405060708090a0b0c0d0e0f0206010203000000000000f43f0000c0bfffffffffffffffffff0101086b6570" +
                    "74",
            readerSerializer = RichV2.serializer(),
            expected = RichV2(id = 1, last = "kept")
        ),
        ResolutionFixture(
            name = "promotions",
            writerSerializer = NumbersV1.serializer(),
            writerValue =
                NumbersV1(
                    intToLong = Int.MIN_VALUE,
                    floatToDouble = 0.1f,
                    bytesToString = "é€".encodeToByteArray(),
                    stringToBytes = "abc"
                ),
            writerSchema =
                "{\"type\":\"record\",\"name\":\"Numbers\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[{\"name\":\"intToLong\"," +
                    "\"type\":\"int\"}," +
                    "{\"name\":\"floatToDouble\",\"type\":\"float\"},{\"name\":\"bytesToString\",\"type\":\"bytes\"},{\"name\":\"stringToBytes\",\"type\":\"string\"}]}",
            hex = "ffffffff0fcdcccc3d0ac3a9e282ac06616263",
            readerSerializer = NumbersV2.serializer(),
            expected =
                NumbersV2(
                    intToLong = Int.MIN_VALUE.toLong(),
                    floatToDouble = 0.10000000149011612,
                    bytesToString = "é€",
                    stringToBytes = BytesView("abc")
                )
        ),
        ResolutionFixture(
            name = "reordered fields",
            writerSerializer = OrderV1.serializer(),
            writerValue = OrderV1(a = 1, b = "two", c = true),
            writerSchema =
                "{\"type\":\"record\",\"name\":\"Order\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[{\"name\":\"a\",\"type\":\"int\"}," +
                    "{\"name\":\"b\",\"type\":\"string\"},{\"name\":\"c\",\"type\":\"boolean\"}]}",
            hex = "020674776f01",
            readerSerializer = OrderV2.serializer(),
            expected = OrderV2(c = true, a = 1, b = "two")
        ),
        ResolutionFixture(
            name = "renamed record and field found through aliases",
            writerSerializer = OldName.serializer(),
            writerValue = OldName(oldField = 11, kept = "k"),
            writerSchema =
                "{\"type\":\"record\",\"name\":\"OldName\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[{\"name\":\"old_field\"," +
                    "\"type\":\"int\"}," +
                    "{\"name\":\"kept\",\"type\":\"string\"}]}",
            hex = "16026b",
            readerSerializer = NewName.serializer(),
            expected = NewName(kept = "k", newField = 11)
        ),
        ResolutionFixture(
            name = "renamed enum found through its alias, unknown symbol takes the enum default, known ones are matched by name",
            writerSerializer = PaletteV1.serializer(),
            writerValue = PaletteV1(known = ColorV1.RED, unknown = ColorV1.PURPLE),
            writerSchema =
                "{\"type\":\"record\",\"name\":\"Palette\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"known\",\"type\":{\"type\":\"enum\",\"name\":\"Color\",\"symbols\":[\"RED\",\"GREEN\",\"BLUE\",\"PURPLE\"]}}," +
                    "{\"name\":\"unknown\",\"type\":\"Color\"}]}",
            hex = "0006",
            readerSerializer = PaletteV2.serializer(),
            expected = PaletteV2(known = ColorV2.RED, unknown = ColorV2.GREEN)
        ),
        ResolutionFixture(
            name = "writer union read as a promoted reader union",
            writerSerializer = MaybeV1.serializer(),
            writerValue = MaybeV1(value = 7),
            writerSchema =
                "{\"type\":\"record\",\"name\":\"Maybe\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"value\",\"type\":[\"null\",\"int\"],\"default\":null}]}",
            hex = "020e",
            readerSerializer = MaybeV2.serializer(),
            expected = MaybeV2(value = 7L)
        ),
        ResolutionFixture(
            name = "plain writer type read as a reader union",
            writerSerializer = PlainV1.serializer(),
            writerValue = PlainV1(value = 8, animal = Animal.Cat("c")),
            writerSchema =
                "{\"type\":\"record\",\"name\":\"Plain\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[{\"name\":\"value\",\"type\":\"int\"}," +
                    "{\"name\":\"animal\",\"type\":[{\"type\":\"record\",\"name\":\"Cat\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[" +
                    "{\"name\":\"name\",\"type\":\"string\"}]}," +
                    "{\"type\":\"record\",\"name\":\"Dog\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[{\"name\":\"age\"," +
                    "\"type\":\"int\"}," +
                    "{\"name\":\"goodBoy\",\"type\":\"boolean\"}]}," +
                    "{\"type\":\"record\",\"name\":\"Fish\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[]}]}]}",
            hex = "10000263",
            readerSerializer = PlainV2.serializer(),
            expected = PlainV2(value = 8L, animal = Animal.Cat("c"))
        )
    )

private const val NS = "com.github.avrokotlin.avro4k.fixtures"

@Serializable
@SerialName("$NS.Person")
private data class PersonV1(
    val name: String,
)

@Serializable
@SerialName("$NS.Person")
private data class PersonV2(
    val name: String,
    @AvroDefault("42") val age: Int,
    @AvroDefault("unknown") val city: String,
    val nickname: String?,
    val tags: List<String>,
    @AvroDefault("""{"x":1,"y":2}""") val origin: Point,
    @AvroDefault("HEARTS") val suit: Suit,
    val scores: Map<String, Int>,
    @AvroDefault("0.5") val ratio: Double,
)

@Serializable
@SerialName("$NS.Rich")
private class RichV1(
    val id: Int,
    val skippedString: String,
    val skippedRecord: Point,
    val skippedList: List<Point>,
    val skippedMap: Map<String, List<Int>>,
    val skippedUnion: Animal?,
    val skippedNull: String?,
    val skippedFixed: Md5,
    val skippedEnum: Suit,
    val skippedBytes: ByteArray,
    val skippedDouble: Double,
    val skippedFloat: Float,
    val skippedLong: Long,
    val skippedBoolean: Boolean,
    val last: String,
)

@Serializable
@SerialName("$NS.Rich")
private data class RichV2(
    val id: Int,
    val last: String,
)

@Serializable
@SerialName("$NS.Numbers")
private class NumbersV1(
    val intToLong: Int,
    val floatToDouble: Float,
    val bytesToString: ByteArray,
    val stringToBytes: String,
)

/** A `ByteArray` with a structural `equals`, so that [NumbersV2] can be compared with `==`. */
@Serializable(with = BytesViewSerializer::class)
internal class BytesView(val bytes: ByteArray) {
    constructor(utf8: String) : this(utf8.encodeToByteArray())

    override fun equals(other: Any?): Boolean = other is BytesView && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "BytesView(${bytes.toHexString()})"
}

internal object BytesViewSerializer : KSerializer<BytesView> {
    private val delegate = ByteArraySerializer()

    // ByteArray's own descriptor, so that avro4k infers `bytes` for it
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: BytesView,
    ) = encoder.encodeSerializableValue(delegate, value.bytes)

    override fun deserialize(decoder: Decoder): BytesView = BytesView(decoder.decodeSerializableValue(delegate))
}

@Serializable
@SerialName("$NS.Numbers")
private data class NumbersV2(
    val intToLong: Long,
    val floatToDouble: Double,
    val bytesToString: String,
    val stringToBytes: BytesView,
)

@Serializable
@SerialName("$NS.Order")
private data class OrderV1(
    val a: Int,
    val b: String,
    val c: Boolean,
)

@Serializable
@SerialName("$NS.Order")
private data class OrderV2(
    val c: Boolean,
    val a: Int,
    val b: String,
)

@Serializable
@SerialName("$NS.OldName")
private data class OldName(
    @SerialName("old_field") val oldField: Int,
    val kept: String,
)

@Serializable
@SerialName("$NS.NewName")
@AvroAlias("OldName")
private data class NewName(
    val kept: String,
    @AvroAlias("old_field") val newField: Int,
)

@Serializable
@SerialName("$NS.Color")
private enum class ColorV1 { RED, GREEN, BLUE, PURPLE }

// Renamed, found through its alias. Keeping the writer's name would make PaletteV2's descriptor equal to PaletteV1's
// (kotlinx compares elements by their descriptors' names and kinds only), and avro4k's schema cache would answer with
// PaletteV1's schema. The alias is qualified: decoding does not resolve a relative enum alias in the enum's namespace yet.
@Serializable
@SerialName("$NS.Colour")
@AvroAlias("$NS.Color")
@OptIn(ExperimentalAvro4kApi::class)
private enum class ColorV2 {
    BLUE,

    @AvroEnumDefault
    GREEN,
    RED,
}

@Serializable
@SerialName("$NS.Palette")
private data class PaletteV1(
    val known: ColorV1,
    val unknown: ColorV1,
)

@Serializable
@SerialName("$NS.Palette")
private data class PaletteV2(
    val known: ColorV2,
    val unknown: ColorV2,
)

@Serializable
@SerialName("$NS.Maybe")
private data class MaybeV1(
    val value: Int?,
)

// Nullable on purpose: a non-nullable property inside a record does not resolve a writer union yet (only nullable
// properties and whole values do), so `Long` would fail on the writer's `["null", "int"]`.
@Serializable
@SerialName("$NS.Maybe")
private data class MaybeV2(
    val value: Long?,
)

@Serializable
@SerialName("$NS.Plain")
private data class PlainV1(
    val value: Int,
    val animal: Animal,
)

@Serializable
@SerialName("$NS.Plain")
private data class PlainV2(
    val value: Long?,
    val animal: Animal?,
)