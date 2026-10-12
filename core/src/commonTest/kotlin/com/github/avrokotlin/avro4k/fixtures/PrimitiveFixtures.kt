package com.github.avrokotlin.avro4k.fixtures

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/** Every primitive at the values where its encoding changes shape: varint lengths, signs, IEEE 754 specials, UTF-8 widths. */
internal val primitiveFixtures: List<GoldenFixture<*>> =
    listOf(
        GoldenFixture(
            name = "int varint boundaries",
            serializer = IntEdges.serializer(),
            value =
                IntEdges(
                    zero = 0,
                    one = 1,
                    minusOne = -1,
                    lastOneByte = 63,
                    firstTwoBytes = 64,
                    firstNegativeTwoBytes = -65,
                    min = Int.MIN_VALUE,
                    max = Int.MAX_VALUE
                ),
            schema =
                "{\"type\":\"record\",\"name\":\"IntEdges\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[{\"name\":\"zero\",\"type\":\"int\"}," +
                    "{\"name\":\"one\",\"type\":\"int\"},{\"name\":\"minusOne\",\"type\":\"int\"},{\"name\":\"lastOneByte\",\"type\":\"int\"}," +
                    "{\"name\":\"firstTwoBytes\",\"type\":\"int\"},{\"name\":\"firstNegativeTwoBytes\",\"type\":\"int\"},{\"name\":\"min\",\"type\":\"int\"}," +
                    "{\"name\":\"max\",\"type\":\"int\"}]}",
            hex = "0002017e80018101ffffffff0ffeffffff0f",
            generic =
                "{\"zero\"=0, \"one\"=1, \"minusOne\"=-1, \"lastOneByte\"=63, \"firstTwoBytes\"=64, \"firstNegativeTwoBytes\"=-65, \"min\"=-2147483648, " +
                    "\"max\"=2147483647}"
        ),
        GoldenFixture(
            name = "long varint boundaries",
            serializer = LongEdges.serializer(),
            value = LongEdges(zero = 0, minusOne = -1, aboveInt = Int.MAX_VALUE + 1L, fortyBits = 1L shl 40, min = Long.MIN_VALUE, max = Long.MAX_VALUE),
            schema =
                "{\"type\":\"record\",\"name\":\"LongEdges\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[{\"name\":\"zero\",\"type\":\"long\"}," +
                    "{\"name\":\"minusOne\",\"type\":\"long\"},{\"name\":\"aboveInt\",\"type\":\"long\"},{\"name\":\"fortyBits\",\"type\":\"long\"}," +
                    "{\"name\":\"min\",\"type\":\"long\"},{\"name\":\"max\",\"type\":\"long\"}]}",
            hex = "00018080808010808080808040ffffffffffffffffff01feffffffffffffffff01",
            generic =
                "{\"zero\"=0L, \"minusOne\"=-1L, \"aboveInt\"=2147483648L, \"fortyBits\"=1099511627776L, \"min\"=-9223372036854775808L, " +
                    "\"max\"=9223372036854775807L}"
        ),
        GoldenFixture(
            name = "float specials",
            serializer = FloatEdges.serializer(),
            value =
                FloatEdges(
                    exact = 1.5f,
                    inexact = 0.1f,
                    negativeZero = -0.0f,
                    max = Float.MAX_VALUE,
                    minSubnormal = Float.MIN_VALUE,
                    positiveInfinity = Float.POSITIVE_INFINITY,
                    negativeInfinity = Float.NEGATIVE_INFINITY,
                    nan = Float.NaN
                ),
            schema =
                "{\"type\":\"record\",\"name\":\"FloatEdges\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"exact\",\"type\":\"float\"},{\"name\":\"inexact\",\"type\":\"float\"},{\"name\":\"negativeZero\",\"type\":\"float\"}," +
                    "{\"name\":\"max\",\"type\":\"float\"},{\"name\":\"minSubnormal\",\"type\":\"float\"},{\"name\":\"positiveInfinity\",\"type\":\"float\"}," +
                    "{\"name\":\"negativeInfinity\",\"type\":\"float\"},{\"name\":\"nan\",\"type\":\"float\"}]}",
            hex = "0000c03fcdcccc3d00000080ffff7f7f010000000000807f000080ff0000c07f",
            // Kotlin/JS does not round a Float literal to 32 bits (`0.1f` stays 0.1 there), while the decoded value is the
            // 32-bit one (0.10000000149011612): the decoded floats are compared by their bits, through encoding them again
            structuralEquals = false,
            generic =
                "{\"exact\"=f:3fc00000, \"inexact\"=f:3dcccccd, \"negativeZero\"=f:80000000, \"max\"=f:7f7fffff, \"minSubnormal\"=f:1, " +
                    "\"positiveInfinity\"=f:7f800000, \"negativeInfinity\"=f:ff800000, \"nan\"=f:7fc00000}"
        ),
        GoldenFixture(
            name = "double specials",
            serializer = DoubleEdges.serializer(),
            value =
                DoubleEdges(
                    exact = -0.25,
                    inexact = 0.1,
                    negativeZero = -0.0,
                    max = Double.MAX_VALUE,
                    minSubnormal = Double.MIN_VALUE,
                    positiveInfinity = Double.POSITIVE_INFINITY,
                    negativeInfinity = Double.NEGATIVE_INFINITY,
                    nan = Double.NaN
                ),
            schema =
                "{\"type\":\"record\",\"name\":\"DoubleEdges\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"exact\",\"type\":\"double\"},{\"name\":\"inexact\",\"type\":\"double\"},{\"name\":\"negativeZero\",\"type\":\"double\"}," +
                    "{\"name\":\"max\",\"type\":\"double\"},{\"name\":\"minSubnormal\",\"type\":\"double\"},{\"name\":\"positiveInfinity\",\"type\":\"double\"}," +
                    "{\"name\":\"negativeInfinity\",\"type\":\"double\"},{\"name\":\"nan\",\"type\":\"double\"}]}",
            hex = "000000000000d0bf9a9999999999b93f0000000000000080ffffffffffffef7f0100000000000000000000000000f07f000000000000f0ff000000000000f87f",
            generic =
                "{\"exact\"=d:bfd0000000000000, \"inexact\"=d:3fb999999999999a, \"negativeZero\"=d:8000000000000000, \"max\"=d:7fefffffffffffff, " +
                    "\"minSubnormal\"=d:1, \"positiveInfinity\"=d:7ff0000000000000, \"negativeInfinity\"=d:fff0000000000000, \"nan\"=d:7ff8000000000000}"
        ),
        GoldenFixture(
            name = "booleans",
            serializer = Booleans.serializer(),
            value = Booleans(yes = true, no = false),
            schema =
                "{\"type\":\"record\",\"name\":\"Booleans\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[{\"name\":\"yes\"," +
                    "\"type\":\"boolean\"}," +
                    "{\"name\":\"no\",\"type\":\"boolean\"}]}",
            hex = "0100",
            generic = "{\"yes\"=true, \"no\"=false}"
        ),
        GoldenFixture(
            name = "strings of every UTF-8 width",
            serializer = Strings.serializer(),
            value = Strings(empty = "", ascii = "avro", twoBytes = "é", threeBytes = "€", fourBytes = "😀", nul = "\u0000", mixed = "a€😀é"),
            schema =
                "{\"type\":\"record\",\"name\":\"Strings\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[{\"name\":\"empty\"," +
                    "\"type\":\"string\"}," +
                    "{\"name\":\"ascii\",\"type\":\"string\"},{\"name\":\"twoBytes\",\"type\":\"string\"},{\"name\":\"threeBytes\",\"type\":\"string\"}," +
                    "{\"name\":\"fourBytes\",\"type\":\"string\"},{\"name\":\"nul\",\"type\":\"string\"},{\"name\":\"mixed\",\"type\":\"string\"}]}",
            hex = "00086176726f04c3a906e282ac08f09f988002001461e282acf09f9880c3a9",
            generic =
                "{\"empty\"=\"\", \"ascii\"=\"avro\", \"twoBytes\"=\"\\u00e9\", \"threeBytes\"=\"\\u20ac\", \"fourBytes\"=\"\\ud83d\\ude00\", \"nul\"=\"\\u0000\", " +
                    "\"mixed\"=\"a\\u20ac\\ud83d\\ude00\\u00e9\"}"
        ),
        GoldenFixture(
            name = "bytes, chars, shorts",
            serializer = SmallTypes.serializer(),
            value = SmallTypes(byteMin = Byte.MIN_VALUE, byteMax = Byte.MAX_VALUE, shortMin = Short.MIN_VALUE, shortMax = Short.MAX_VALUE, char = 'é', surrogate = '\uD83D'),
            schema =
                "{\"type\":\"record\",\"name\":\"SmallTypes\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"byteMin\",\"type\":\"int\"},{\"name\":\"byteMax\",\"type\":\"int\"},{\"name\":\"shortMin\",\"type\":\"int\"}," +
                    "{\"name\":\"shortMax\",\"type\":\"int\"}," +
                    "{\"name\":\"char\",\"type\":{\"type\":\"int\",\"logicalType\":\"char\"}},{\"name\":\"surrogate\",\"type\":{\"type\":\"int\"," +
                    "\"logicalType\":\"char\"}}]}",
            hex = "ff01fe01ffff03feff03d203fae006",
            generic = "{\"byteMin\"=-128, \"byteMax\"=127, \"shortMin\"=-32768, \"shortMax\"=32767, \"char\"=233, \"surrogate\"=55357}"
        ),
        GoldenFixture(
            name = "root int",
            serializer = Int.serializer(),
            value = -1,
            schema = "\"int\"",
            hex = "01",
            generic = "-1"
        ),
        GoldenFixture(
            name = "root long",
            serializer = Long.serializer(),
            value = 1L shl 40,
            schema = "\"long\"",
            hex = "808080808040",
            generic = "1099511627776L"
        ),
        GoldenFixture(
            name = "root double",
            serializer = Double.serializer(),
            value = 3.25,
            schema = "\"double\"",
            hex = "0000000000000a40",
            generic = "d:400a000000000000"
        ),
        GoldenFixture(
            name = "root string",
            serializer = String.serializer(),
            value = "héllo",
            schema = "\"string\"",
            hex = "0c68c3a96c6c6f",
            generic = "\"h\\u00e9llo\""
        ),
        GoldenFixture(
            name = "root bytes, every byte value",
            serializer = ByteArraySerializer(),
            value = ByteArray(256) { it.toByte() },
            schema = "\"bytes\"",
            hex = "8004" + ByteArray(256) { it.toByte() }.toHexString(), // 256 bytes: zig-zag 512, the varint 80 04
            structuralEquals = false,
            generic = "0x" + ByteArray(256) { it.toByte() }.toHexString()
        ),
        GoldenFixture(
            name = "root nullable int, null",
            serializer = Int.serializer().nullable,
            value = null,
            schema = "[\"null\",\"int\"]",
            hex = "00",
            generic = "null"
        ),
        GoldenFixture(
            name = "root nullable int, present",
            serializer = Int.serializer().nullable,
            value = 5,
            schema = "[\"null\",\"int\"]",
            hex = "020a",
            generic = "5"
        )
    )

@Serializable
private data class IntEdges(
    val zero: Int,
    val one: Int,
    val minusOne: Int,
    val lastOneByte: Int,
    val firstTwoBytes: Int,
    val firstNegativeTwoBytes: Int,
    val min: Int,
    val max: Int,
)

@Serializable
private data class LongEdges(
    val zero: Long,
    val minusOne: Long,
    val aboveInt: Long,
    val fortyBits: Long,
    val min: Long,
    val max: Long,
)

@Serializable
private data class FloatEdges(
    val exact: Float,
    val inexact: Float,
    val negativeZero: Float,
    val max: Float,
    val minSubnormal: Float,
    val positiveInfinity: Float,
    val negativeInfinity: Float,
    val nan: Float,
)

@Serializable
private data class DoubleEdges(
    val exact: Double,
    val inexact: Double,
    val negativeZero: Double,
    val max: Double,
    val minSubnormal: Double,
    val positiveInfinity: Double,
    val negativeInfinity: Double,
    val nan: Double,
)

@Serializable
private data class Booleans(
    val yes: Boolean,
    val no: Boolean,
)

@Serializable
private data class Strings(
    val empty: String,
    val ascii: String,
    val twoBytes: String,
    val threeBytes: String,
    val fourBytes: String,
    val nul: String,
    val mixed: String,
)

@Serializable
private data class SmallTypes(
    val byteMin: Byte,
    val byteMax: Byte,
    val shortMin: Short,
    val shortMax: Short,
    val char: Char,
    val surrogate: Char,
)