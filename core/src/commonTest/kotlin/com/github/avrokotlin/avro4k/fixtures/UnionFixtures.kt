package com.github.avrokotlin.avro4k.fixtures

import kotlinx.serialization.Serializable

/** Unions: nullable fields of every kind (both branches), sealed hierarchies, and a value written to a chosen union. */
internal val unionFixtures: List<GoldenFixture<*>> =
    listOf(
        GoldenFixture(
            name = "nullable fields, all present",
            serializer = Nullables.serializer(),
            value =
                Nullables(
                    string = "s",
                    int = 0,
                    record = Point(x = 1, y = 2),
                    enum = Suit.SPADES,
                    list = listOf(1),
                    map = mapOf("k" to 2),
                    animal = Animal.Fish
                ),
            schema =
                "{\"type\":\"record\",\"name\":\"Nullables\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"string\",\"type\":[\"null\",\"string\"],\"default\":null},{\"name\":\"int\",\"type\":[\"null\",\"int\"],\"default\":null}," +
                    "{\"name\":\"record\",\"type\":[\"null\",{\"type\":\"record\",\"name\":\"Point\",\"fields\":[{\"name\":\"x\",\"type\":\"int\"}," +
                    "{\"name\":\"y\",\"type\":\"int\"}]}],\"default\":null},{\"name\":\"enum\",\"type\":[\"null\"," +
                    "{\"type\":\"enum\",\"name\":\"Suit\",\"symbols\":[\"CLUBS\",\"DIAMONDS\",\"HEARTS\",\"SPADES\"]}],\"default\":null},{\"name\":\"list\"," +
                    "\"type\":[\"null\"," +
                    "{\"type\":\"array\",\"items\":\"int\"}],\"default\":null},{\"name\":\"map\",\"type\":[\"null\",{\"type\":\"map\",\"values\":\"int\"}]," +
                    "\"default\":null}," +
                    "{\"name\":\"animal\",\"type\":[\"null\"," +
                    "{\"type\":\"record\",\"name\":\"Cat\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[" +
                    "{\"name\":\"name\",\"type\":\"string\"}]}," +
                    "{\"type\":\"record\",\"name\":\"Dog\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[{\"name\":\"age\"," +
                    "\"type\":\"int\"}," +
                    "{\"name\":\"goodBoy\",\"type\":\"boolean\"}]}," +
                    "{\"type\":\"record\",\"name\":\"Fish\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[]}],\"default\":null}]}",
            hex = "02027302000202040206020202000202026b040006",
            generic = "{\"string\"=\"s\", \"int\"=0, \"record\"={\"x\"=1, \"y\"=2}, \"enum\"=\"SPADES\", \"list\"=[1], \"map\"={\"k\"=2}, \"animal\"={}}"
        ),
        GoldenFixture(
            name = "nullable fields, all null",
            serializer = Nullables.serializer(),
            value = Nullables(string = null, int = null, record = null, enum = null, list = null, map = null, animal = null),
            schema =
                "{\"type\":\"record\",\"name\":\"Nullables\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"string\",\"type\":[\"null\",\"string\"],\"default\":null},{\"name\":\"int\",\"type\":[\"null\",\"int\"],\"default\":null}," +
                    "{\"name\":\"record\",\"type\":[\"null\",{\"type\":\"record\",\"name\":\"Point\",\"fields\":[{\"name\":\"x\",\"type\":\"int\"}," +
                    "{\"name\":\"y\",\"type\":\"int\"}]}],\"default\":null},{\"name\":\"enum\",\"type\":[\"null\"," +
                    "{\"type\":\"enum\",\"name\":\"Suit\",\"symbols\":[\"CLUBS\",\"DIAMONDS\",\"HEARTS\",\"SPADES\"]}],\"default\":null},{\"name\":\"list\"," +
                    "\"type\":[\"null\"," +
                    "{\"type\":\"array\",\"items\":\"int\"}],\"default\":null},{\"name\":\"map\",\"type\":[\"null\",{\"type\":\"map\",\"values\":\"int\"}]," +
                    "\"default\":null}," +
                    "{\"name\":\"animal\",\"type\":[\"null\"," +
                    "{\"type\":\"record\",\"name\":\"Cat\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[" +
                    "{\"name\":\"name\",\"type\":\"string\"}]}," +
                    "{\"type\":\"record\",\"name\":\"Dog\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[{\"name\":\"age\"," +
                    "\"type\":\"int\"}," +
                    "{\"name\":\"goodBoy\",\"type\":\"boolean\"}]}," +
                    "{\"type\":\"record\",\"name\":\"Fish\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[]}],\"default\":null}]}",
            hex = "00000000000000",
            generic = "{\"string\"=null, \"int\"=null, \"record\"=null, \"enum\"=null, \"list\"=null, \"map\"=null, \"animal\"=null}"
        ),
        GoldenFixture(
            name = "sealed hierarchy, every branch",
            serializer = Zoo.serializer(),
            value = Zoo(first = Animal.Cat(name = "Tom"), second = Animal.Dog(age = 3, goodBoy = true), third = Animal.Fish, all = listOf(Animal.Fish, Animal.Cat("a"))),
            schema =
                "{\"type\":\"record\",\"name\":\"Zoo\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[{\"name\":\"first\",\"type\":[" +
                    "{\"type\":\"record\",\"name\":\"Cat\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[" +
                    "{\"name\":\"name\",\"type\":\"string\"}]}," +
                    "{\"type\":\"record\",\"name\":\"Dog\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[{\"name\":\"age\"," +
                    "\"type\":\"int\"}," +
                    "{\"name\":\"goodBoy\",\"type\":\"boolean\"}]}," +
                    "{\"type\":\"record\",\"name\":\"Fish\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Animal\",\"fields\":[]}]}," +
                    "{\"name\":\"second\",\"type\":[\"com.github.avrokotlin.avro4k.fixtures.Animal.Cat\",\"com.github.avrokotlin.avro4k.fixtures.Animal.Dog\"," +
                    "\"com.github.avrokotlin.avro4k.fixtures.Animal.Fish\"]}," +
                    "{\"name\":\"third\",\"type\":[\"com.github.avrokotlin.avro4k.fixtures.Animal.Cat\",\"com.github.avrokotlin.avro4k.fixtures.Animal.Dog\"," +
                    "\"com.github.avrokotlin.avro4k.fixtures.Animal.Fish\"]}," +
                    "{\"name\":\"all\",\"type\":{\"type\":\"array\",\"items\":[\"com.github.avrokotlin.avro4k.fixtures.Animal.Cat\"," +
                    "\"com.github.avrokotlin.avro4k.fixtures.Animal.Dog\",\"com.github.avrokotlin.avro4k.fixtures.Animal.Fish\"]},\"default\":[]}]}",
            hex = "0006546f6d02060104040400026100",
            generic = "{\"first\"={\"name\"=\"Tom\"}, \"second\"={\"age\"=3, \"goodBoy\"=true}, \"third\"={}, \"all\"=[{}, {\"name\"=\"a\"}]}"
        ),
        GoldenFixture(
            name = "int written to a chosen union",
            serializer = IntHolder.serializer(),
            value = IntHolder(value = 300),
            schema =
                "{\"type\":\"record\",\"name\":\"IntHolder\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"value\",\"type\":[\"null\",\"string\",\"long\",\"int\"]}]}",
            hex = "06d804",
            inferred = false,
            generic = "{\"value\"=300}"
        ),
        GoldenFixture(
            name = "string written to a chosen union",
            serializer = StringHolder.serializer(),
            value = StringHolder(value = "x"),
            schema =
                "{\"type\":\"record\",\"name\":\"StringHolder\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"value\",\"type\":[\"int\",\"bytes\",\"string\"]}]}",
            hex = "040278",
            inferred = false,
            generic = "{\"value\"=\"x\"}"
        )
    )

@Serializable
private data class Nullables(
    val string: String?,
    val int: Int?,
    val record: Point?,
    val enum: Suit?,
    val list: List<Int>?,
    val map: Map<String, Int>?,
    val animal: Animal?,
)

@Serializable
private data class Zoo(
    val first: Animal,
    val second: Animal,
    val third: Animal,
    val all: List<Animal>,
)

// Nullable on purpose: a non-nullable property inside a record does not resolve a writer union yet (only nullable
// properties and whole values do), so decoding `Int` or `String` from these unions would fail.
@Serializable
private data class IntHolder(
    val value: Int?,
)

@Serializable
private data class StringHolder(
    val value: String?,
)