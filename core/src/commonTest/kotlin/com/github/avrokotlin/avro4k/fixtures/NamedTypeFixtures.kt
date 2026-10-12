package com.github.avrokotlin.avro4k.fixtures

import com.github.avrokotlin.avro4k.AvroAlias
import com.github.avrokotlin.avro4k.AvroDoc
import com.github.avrokotlin.avro4k.AvroEnumDefault
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Enums and fixed types, with their names, docs, aliases and enum default. */
internal val namedTypeFixtures: List<GoldenFixture<*>> =
    listOf(
        GoldenFixture(
            name = "enums",
            serializer = Enums.serializer(),
            value = Enums(first = Suit.CLUBS, last = Suit.SPADES, level = Level.HIGH),
            schema =
                "{\"type\":\"record\",\"name\":\"Enums\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"first\",\"type\":{\"type\":\"enum\",\"name\":\"Suit\",\"symbols\":[\"CLUBS\",\"DIAMONDS\",\"HEARTS\",\"SPADES\"]}}," +
                    "{\"name\":\"last\",\"type\":\"Suit\"}," +
                    "{\"name\":\"level\",\"type\":{\"type\":\"enum\",\"name\":\"Level\",\"namespace\":\"custom\",\"doc\":\"How much\",\"symbols\":[\"LOW\"," +
                    "\"MEDIUM\",\"HIGH\"]," +
                    "\"default\":\"MEDIUM\",\"aliases\":[\"Amount\"]}}]}",
            hex = "000604",
            generic = "{\"first\"=\"CLUBS\", \"last\"=\"SPADES\", \"level\"=\"HIGH\"}"
        ),
        GoldenFixture(
            name = "fixed types",
            serializer = Fixeds.serializer(),
            value = Fixeds(hash = Md5(ByteArray(16) { (it * 17).toByte() }), inline = byteArrayOf(-1, 0, 1, 127), text = "12345678"),
            schema =
                "{\"type\":\"record\",\"name\":\"Fixeds\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"hash\",\"type\":{\"type\":\"fixed\",\"name\":\"Md5\",\"size\":16}}," +
                    "{\"name\":\"inline\",\"type\":{\"type\":\"fixed\",\"name\":\"inline\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Fixeds\",\"size\":4}}," +
                    "{\"name\":\"text\",\"type\":{\"type\":\"fixed\",\"name\":\"text\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.Fixeds\",\"size\":8}}]}",
            hex = "00112233445566778899aabbccddeeffff00017f3132333435363738",
            structuralEquals = false,
            generic = "{\"hash\"=0x00112233445566778899aabbccddeeff, \"inline\"=0xff00017f, \"text\"=0x3132333435363738}"
        )
    )

@Serializable
@SerialName("custom.Level")
@AvroDoc("How much")
@AvroAlias("Amount")
@OptIn(ExperimentalAvro4kApi::class)
private enum class Level {
    LOW,

    @AvroEnumDefault
    MEDIUM,
    HIGH,
}

@Serializable
private data class Enums(
    val first: Suit,
    val last: Suit,
    val level: Level,
)

@Serializable
private class Fixeds(
    val hash: Md5,
    @AvroFixed(4) val inline: ByteArray,
    @AvroFixed(8) val text: String,
)