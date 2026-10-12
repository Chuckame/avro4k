package com.github.avrokotlin.avro4k.fixtures

import kotlinx.serialization.Serializable

/**
 * Arrays and maps, including the other ways a writer may split them into blocks: `count, items…, count, items…, 0`, where
 * a negative count is followed by the block's size in bytes. Varints are zig-zag encoded: 1 → 02, 2 → 04, 3 → 06, -1 → 01.
 */
internal val collectionFixtures: List<GoldenFixture<*>> =
    listOf(
        GoldenFixture(
            name = "lists",
            serializer = Lists.serializer(),
            value =
                Lists(
                    empty = emptyList(),
                    ints = listOf(0, -1, 64),
                    strings = listOf("a", "", "€"),
                    records = listOf(Point(1, 2), Point(3, 4)),
                    nested = listOf(listOf(1), emptyList(), listOf(2, 3)),
                    set = setOf("x", "y"),
                    nullableItems = listOf("a", null)
                ),
            schema =
                "{\"type\":\"record\",\"name\":\"Lists\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"empty\",\"type\":{\"type\":\"array\",\"items\":\"int\"},\"default\":[]}," +
                    "{\"name\":\"ints\",\"type\":{\"type\":\"array\",\"items\":\"int\"},\"default\":[]}," +
                    "{\"name\":\"strings\",\"type\":{\"type\":\"array\",\"items\":\"string\"},\"default\":[]}," +
                    "{\"name\":\"records\",\"type\":{\"type\":\"array\",\"items\":{\"type\":\"record\",\"name\":\"Point\",\"fields\":[{\"name\":\"x\"," +
                    "\"type\":\"int\"}," +
                    "{\"name\":\"y\",\"type\":\"int\"}]}},\"default\":[]}," +
                    "{\"name\":\"nested\",\"type\":{\"type\":\"array\",\"items\":{\"type\":\"array\",\"items\":\"int\"}},\"default\":[]}," +
                    "{\"name\":\"set\",\"type\":{\"type\":\"array\",\"items\":\"string\"},\"default\":[]}," +
                    "{\"name\":\"nullableItems\",\"type\":{\"type\":\"array\",\"items\":[\"null\",\"string\"]},\"default\":[]}]}",
            hex = "000600018001000602610006e282ac0004020406080006020200000404060000040278027900040202610000",
            generic =
                "{\"empty\"=[], \"ints\"=[0, -1, 64], \"strings\"=[\"a\", \"\", \"\\u20ac\"], \"records\"=[{\"x\"=1, \"y\"=2}, {\"x\"=3, \"y\"=4}]," +
                    " \"nested\"=[[1], [], " +
                    "[2, 3]], \"set\"=[\"x\", \"y\"], \"nullableItems\"=[\"a\", null]}"
        ),
        GoldenFixture(
            name = "maps",
            serializer = Maps.serializer(),
            value =
                Maps(
                    empty = emptyMap(),
                    ints = mapOf("one" to 1, "minus one" to -1),
                    records = mapOf("origin" to Point(0, 0)),
                    ofLists = mapOf("l" to listOf(1, 2)),
                    listOfMaps = listOf(mapOf("a" to "b"), emptyMap()),
                    intKeys = mapOf(1 to "one", -2 to "minus two"),
                    enumKeys = mapOf(Suit.HEARTS to 3)
                ),
            schema =
                "{\"type\":\"record\",\"name\":\"Maps\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"empty\",\"type\":{\"type\":\"map\",\"values\":\"int\"},\"default\":{}}," +
                    "{\"name\":\"ints\",\"type\":{\"type\":\"map\",\"values\":\"int\"},\"default\":{}}," +
                    "{\"name\":\"records\",\"type\":{\"type\":\"map\",\"values\":{\"type\":\"record\",\"name\":\"Point\",\"fields\":[{\"name\":\"x\",\"type\":\"int\"}," +
                    "{\"name\":\"y\",\"type\":\"int\"}]}},\"default\":{}}," +
                    "{\"name\":\"ofLists\",\"type\":{\"type\":\"map\",\"values\":{\"type\":\"array\",\"items\":\"int\"}},\"default\":{}}," +
                    "{\"name\":\"listOfMaps\",\"type\":{\"type\":\"array\",\"items\":{\"type\":\"map\",\"values\":\"string\"}},\"default\":[]}," +
                    "{\"name\":\"intKeys\",\"type\":{\"type\":\"map\",\"values\":\"string\"},\"default\":{}}," +
                    "{\"name\":\"enumKeys\",\"type\":{\"type\":\"map\",\"values\":\"int\"},\"default\":{}}]}",
            hex =
                "0004066f6e6502126d696e7573206f6e650100020c6f726967696e00000002026c0402040000040202610262000000040231066f6e65042d32126d696e75732074776f00020c4845415254" +
                    "530600",
            generic =
                "{\"empty\"={}, \"ints\"={\"one\"=1, \"minus one\"=-1}, \"records\"={\"origin\"={\"x\"=0, \"y\"=0}}, \"ofLists\"={\"l\"=[1, 2]}, " +
                    "\"listOfMaps\"=[{\"a\"=\"b\"}, {}], \"intKeys\"={\"1\"=\"one\", \"-2\"=\"minus two\"}, \"enumKeys\"={\"HEARTS\"=3}}"
        ),
        GoldenFixture(
            name = "primitive arrays",
            serializer = PrimitiveArrays.serializer(),
            value =
                PrimitiveArrays(
                    ints = intArrayOf(1, -1),
                    longs = longArrayOf(Long.MAX_VALUE),
                    doubles = doubleArrayOf(0.5, -2.0),
                    booleans = booleanArrayOf(true, false, true),
                    strings = arrayOf("s")
                ),
            schema =
                "{\"type\":\"record\",\"name\":\"PrimitiveArrays\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"ints\",\"type\":{\"type\":\"array\",\"items\":\"int\"},\"default\":[]}," +
                    "{\"name\":\"longs\",\"type\":{\"type\":\"array\",\"items\":\"long\"},\"default\":[]}," +
                    "{\"name\":\"doubles\",\"type\":{\"type\":\"array\",\"items\":\"double\"},\"default\":[]}," +
                    "{\"name\":\"booleans\",\"type\":{\"type\":\"array\",\"items\":\"boolean\"},\"default\":[]}," +
                    "{\"name\":\"strings\",\"type\":{\"type\":\"array\",\"items\":\"string\"},\"default\":[]}]}",
            hex = "0402010002feffffffffffffffff010004000000000000e03f00000000000000c000060100010002027300",
            structuralEquals = false,
            generic =
                "{\"ints\"=[1, -1], \"longs\"=[9223372036854775807L], \"doubles\"=[d:3fe0000000000000, d:c000000000000000], \"booleans\"=[true, false, " +
                    "true], \"strings\"=[\"s\"]}"
        ),
        GoldenFixture(
            name = "collections split into blocks",
            serializer = Blocks.serializer(),
            value = Blocks(values = listOf(1, 2, 3), byName = mapOf("a" to 1, "b" to 2)),
            schema =
                "{\"type\":\"record\",\"name\":\"Blocks\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"values\",\"type\":{\"type\":\"array\",\"items\":\"int\"},\"default\":[]}," +
                    "{\"name\":\"byName\",\"type\":{\"type\":\"map\",\"values\":\"int\"},\"default\":{}}]}",
            hex = "06020406000402610202620400",
            alternativeHex =
                listOf(
                    // values: a block of 2 items, then a block of -1 item (1 byte long); byName: two blocks of 1 entry
                    "040204" + "010206" + "00" + "02026102" + "02026204" + "00",
                    // values: three blocks of 1 item; byName: one block of -2 entries, 6 bytes long
                    "0202" + "0204" + "0206" + "00" + "030c" + "026102" + "026204" + "00"
                ),
            generic = "{\"values\"=[1, 2, 3], \"byName\"={\"a\"=1, \"b\"=2}}"
        ),
        GoldenFixture(
            name = "one block of more than 1024 items",
            serializer = LargeBlock.serializer(),
            value = LargeBlock(zeros = List(1025) { 0 }),
            schema =
                """{"type":"record","name":"LargeBlock","namespace":"com.github.avrokotlin.avro4k.fixtures","fields":[{"name":"zeros","type":{"type":"array","i""" +
                    """tems":"int"},"default":[]}]}""",
            // 1025 items: zig-zag 2050, the varint 82 10
            hex = "8210" + "00".repeat(1025) + "00",
            alternativeHex =
                listOf(
                    // a block of 1024 items (80 10), then a block of 1
                    "8010" + "00".repeat(1024) + "02" + "00" + "00",
                    // a block of -1025 items (81 10), 1025 bytes long (82 10)
                    "8110" + "8210" + "00".repeat(1025) + "00"
                ),
            generic = "{\"zeros\"=[" + List(1025) { "0" }.joinToString(", ") + "]}"
        )
    )

@Serializable
private data class Lists(
    val empty: List<Int>,
    val ints: List<Int>,
    val strings: List<String>,
    val records: List<Point>,
    val nested: List<List<Int>>,
    val set: Set<String>,
    val nullableItems: List<String?>,
)

@Serializable
private data class Maps(
    val empty: Map<String, Int>,
    val ints: Map<String, Int>,
    val records: Map<String, Point>,
    val ofLists: Map<String, List<Int>>,
    val listOfMaps: List<Map<String, String>>,
    val intKeys: Map<Int, String>,
    val enumKeys: Map<Suit, Int>,
)

@Serializable
private class PrimitiveArrays(
    val ints: IntArray,
    val longs: LongArray,
    val doubles: DoubleArray,
    val booleans: BooleanArray,
    val strings: Array<String>,
)

@Serializable
private data class Blocks(
    val values: List<Int>,
    val byName: Map<String, Int>,
)

@Serializable
private data class LargeBlock(
    val zeros: List<Int>,
)