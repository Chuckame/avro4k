package com.github.avrokotlin.avro4k.fixtures

import com.github.avrokotlin.avro4k.AvroAlias
import com.github.avrokotlin.avro4k.AvroDoc
import com.github.avrokotlin.avro4k.AvroProp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Records: empty, nested, renamed and annotated, recursive, a named type referenced twice, value classes inlined. */
internal val recordFixtures: List<GoldenFixture<*>> =
    listOf(
        GoldenFixture(
            name = "empty record",
            serializer = EmptyRecord.serializer(),
            value = EmptyRecord,
            schema = "{\"type\":\"record\",\"name\":\"EmptyRecord\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[]}",
            hex = "",
            generic = "{}"
        ),
        GoldenFixture(
            name = "nested records",
            serializer = Outer.serializer(),
            value = Outer(before = "b", middle = Middle(inner = Inner(value = 7), after = true), last = -3L),
            schema =
                "{\"type\":\"record\",\"name\":\"Outer\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[{\"name\":\"before\",\"type\":\"string\"}," +
                    "{\"name\":\"middle\",\"type\":{\"type\":\"record\",\"name\":\"Middle\",\"fields\":[" +
                    "{\"name\":\"inner\",\"type\":{\"type\":\"record\",\"name\":\"Inner\",\"fields\":[{\"name\":\"value\",\"type\":\"int\"}]}}," +
                    "{\"name\":\"after\",\"type\":\"boolean\"}]}},{\"name\":\"last\",\"type\":\"long\"}]}",
            hex = "02620e0105",
            generic = "{\"before\"=\"b\", \"middle\"={\"inner\"={\"value\"=7}, \"after\"=true}, \"last\"=-3L}"
        ),
        GoldenFixture(
            name = "renamed and annotated record",
            serializer = Annotated.serializer(),
            value = Annotated(field = 9, other = "x"),
            schema =
                "{\"type\":\"record\",\"name\":\"Renamed\",\"namespace\":\"custom.namespace\",\"doc\":\"A record whose names do not come from the class\"," +
                    "\"fields\":[" +
                    "{\"name\":\"renamed_field\",\"type\":\"int\",\"doc\":\"A field's doc\",\"aliases\":[\"old_field\"],\"field-prop\":{\"nested\":[1,true,null]}}," +
                    "{\"name\":\"other\",\"type\":\"string\"}],\"record-prop\":\"plain string\",\"aliases\":[\"OldRecord\",\"other.namespace.Older\"]}",
            hex = "120278",
            generic = "{\"renamed_field\"=9, \"other\"=\"x\"}"
        ),
        GoldenFixture(
            name = "recursive record",
            serializer = Node.serializer(),
            value = Node(value = 1, next = Node(value = 2, next = Node(value = 3, next = null))),
            schema =
                "{\"type\":\"record\",\"name\":\"Node\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[{\"name\":\"value\",\"type\":\"int\"}," +
                    "{\"name\":\"next\",\"type\":[\"null\",\"Node\"],\"default\":null}]}",
            hex = "020204020600",
            generic = "{\"value\"=1, \"next\"={\"value\"=2, \"next\"={\"value\"=3, \"next\"=null}}}"
        ),
        GoldenFixture(
            name = "named type referenced twice",
            serializer = Segment.serializer(),
            value = Segment(from = Point(x = 0, y = -1), to = Point(x = 100, y = 200)),
            schema =
                "{\"type\":\"record\",\"name\":\"Segment\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"from\",\"type\":{\"type\":\"record\",\"name\":\"Point\",\"fields\":[{\"name\":\"x\",\"type\":\"int\"},{\"name\":\"y\"," +
                    "\"type\":\"int\"}]}}," +
                    "{\"name\":\"to\",\"type\":\"Point\"}]}",
            hex = "0001c8019003",
            generic = "{\"from\"={\"x\"=0, \"y\"=-1}, \"to\"={\"x\"=100, \"y\"=200}}"
        ),
        GoldenFixture(
            name = "value classes inlined",
            serializer = WithValueClasses.serializer(),
            value = WithValueClasses(id = UserId(42), maybeId = UserId(-1), noId = null, label = Label("l")),
            schema =
                "{\"type\":\"record\",\"name\":\"WithValueClasses\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"id\",\"type\":\"int\"},{\"name\":\"maybeId\",\"type\":[\"null\",\"int\"],\"default\":null}," +
                    "{\"name\":\"noId\",\"type\":[\"null\",\"int\"],\"default\":null},{\"name\":\"label\",\"type\":\"string\"}]}",
            hex = "54020100026c",
            generic = "{\"id\"=42, \"maybeId\"=-1, \"noId\"=null, \"label\"=\"l\"}"
        )
    )

@Serializable
private object EmptyRecord

@Serializable
private data class Outer(
    val before: String,
    val middle: Middle,
    val last: Long,
)

@Serializable
private data class Middle(
    val inner: Inner,
    val after: Boolean,
)

@Serializable
private data class Inner(
    val value: Int,
)

@Serializable
@SerialName("custom.namespace.Renamed")
@AvroDoc("A record whose names do not come from the class")
@AvroAlias("OldRecord", "other.namespace.Older")
@AvroProp("record-prop", "plain string")
private data class Annotated(
    @SerialName("renamed_field")
    @AvroDoc("A field's doc")
    @AvroAlias("old_field")
    @AvroProp("field-prop", """{"nested":[1,true,null]}""")
    val field: Int,
    val other: String,
)

@Serializable
private data class Node(
    val value: Int,
    val next: Node?,
)

@Serializable
private data class Segment(
    val from: Point,
    val to: Point,
)

@JvmInline
@Serializable
private value class UserId(val value: Int)

@JvmInline
@Serializable
private value class Label(val value: String)

@Serializable
private data class WithValueClasses(
    val id: UserId,
    val maybeId: UserId?,
    val noId: UserId?,
    val label: Label,
)