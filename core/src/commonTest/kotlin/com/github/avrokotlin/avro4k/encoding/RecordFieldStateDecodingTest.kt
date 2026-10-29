package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.schema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test

/**
 * What a record decoder knows of the field it decodes. The general record decoder (fields reordered, skipped or taking a
 * default value) tells a field read from the input from one taking its default value; the exact record decoder knows the
 * fields written as a union of `null` and one other type only for the records that have some. These tests check that
 * each kind of value is read from the right place, that a decoder reused for the next record, or for a record of another
 * type, starts again from the input, and that a record decoder asked for a value before any field is begun fails without
 * reading, whatever the kind of value asked for.
 */
class RecordFieldStateDecodingTest {
    @Test
    fun `reordered fields read from the input and from their defaults decode every kind of value`() {
        val writers =
            listOf(
                MixedWriter("skipped", 1.5, "a", 1, null, true),
                MixedWriter("skipped", -2.25, "", -2, 3L, false),
                MixedWriter("skipped", 0.0, "c", 3, -4L, true)
            )
        val expected = writers.map { it.read() }
        val writerSchema = AvroSchema.ArraySchema(Avro.schema(MixedWriter.serializer().descriptor))
        val bytes = Avro.encodeToByteArray(writerSchema, ListSerializer(MixedWriter.serializer()), writers)
        for (avro in AVROS) {
            avro.decodeFromByteArray(writerSchema, ListSerializer(Mixed.serializer()), bytes) shouldBe expected
        }
    }

    @Test
    fun `general records of two types alternating on one decoder read each field from the right place`() {
        val writer =
            AlternatingGeneralWriter(
                MixedWriter("skipped", 1.5, "a", 1, 2L, true),
                ReorderedWriter("b", 3),
                MixedWriter("skipped", 4.5, "c", 5, null, false),
                ReorderedWriter("d", 6)
            )
        val expected =
            AlternatingGeneral(writer.first.read(), Reordered(3, "b"), writer.third.read(), Reordered(6, "d"))
        val writerSchema = AvroSchema.ArraySchema(Avro.schema(AlternatingGeneralWriter.serializer().descriptor))
        val bytes = Avro.encodeToByteArray(writerSchema, ListSerializer(AlternatingGeneralWriter.serializer()), listOf(writer, writer))
        for (avro in AVROS) {
            avro.decodeFromByteArray(writerSchema, ListSerializer(AlternatingGeneral.serializer()), bytes) shouldBe listOf(expected, expected)
        }
    }

    @Test
    fun `every read on a general record decoder fails before any field is begun also when reused after a default value`() {
        val writers = listOf(ReorderedWriter("a", 1), ReorderedWriter("b", 2), ReorderedWriter("c", 3))
        val writerSchema = AvroSchema.ArraySchema(Avro.schema(ReorderedWriter.serializer().descriptor))
        val bytes = Avro.encodeToByteArray(writerSchema, ListSerializer(ReorderedWriter.serializer()), writers)
        for (avro in AVROS) {
            // each record but the first is begun on the decoder that just ended one with a default value
            avro.decodeFromByteArray(writerSchema, ListSerializer(EarlyReorderedSerializer), bytes) shouldBe
                listOf(ReorderedWithDefault(1, "a", 7), ReorderedWithDefault(2, "b", 7), ReorderedWithDefault(3, "c", 7))
        }
    }

    @Test
    fun `exact records with and without nullable fields alternating on one decoder and nested decode`() {
        val values =
            listOf(
                AlternatingExact(
                    WithNullable(1, null),
                    WithoutNullable(2, "b"),
                    WithNullable(null, "c"),
                    WithoutNullable(3, "d"),
                    NestedWithNullable(NestedWithoutNullable(WithNullable(4, "e"), 5L), null)
                ),
                AlternatingExact(
                    WithNullable(null, null),
                    WithoutNullable(6, ""),
                    WithNullable(7, "f"),
                    WithoutNullable(8, "g"),
                    NestedWithNullable(null, 9L)
                )
            )
        val writerSchema = AvroSchema.ArraySchema(Avro.schema(AlternatingExact.serializer().descriptor))
        val bytes = Avro.encodeToByteArray(writerSchema, ListSerializer(AlternatingExact.serializer()), values)
        for (avro in AVROS) {
            avro.decodeFromByteArray(writerSchema, ListSerializer(AlternatingExact.serializer()), bytes) shouldBe values
        }
    }

    @Test
    fun `records begun on new decoders at every depth decode as on reused ones`() {
        // general, exact, general, exact records nested in each other: a value alone begins each on a new decoder, the
        // items of a list after the first on the decoders the first one left
        val writers =
            listOf(
                LevelAWriter("a", 1, LevelB(2L, LevelCWriter("skipped", 3, LevelD(4, null)))),
                LevelAWriter("b", 5, LevelB(6L, LevelCWriter("skipped", 7, LevelD(null, "e")))),
                LevelAWriter("c", 8, LevelB(9L, LevelCWriter("skipped", 10, LevelD(11, "f"))))
            )
        val expected = writers.map { LevelA(it.i, it.s, LevelBRead(it.b.x, LevelC(it.b.c.y, it.b.c.d)), 7) }
        val writerSchema = Avro.schema(LevelAWriter.serializer().descriptor)
        for (avro in AVROS) {
            for ((writer, value) in writers.zip(expected)) {
                val bytes = Avro.encodeToByteArray(writerSchema, LevelAWriter.serializer(), writer)
                avro.decodeFromByteArray(writerSchema, LevelA.serializer(), bytes) shouldBe value
            }
            val listSchema = AvroSchema.ArraySchema(writerSchema)
            val bytes = Avro.encodeToByteArray(listSchema, ListSerializer(LevelAWriter.serializer()), writers)
            avro.decodeFromByteArray(listSchema, ListSerializer(LevelA.serializer()), bytes) shouldBe expected
        }
    }

    companion object {
        val AVROS = listOf(Avro, Avro { validateSerialization = true })
    }
}

private const val NO_ELEMENT_MESSAGE = "No field or item is being decoded: decodeElementIndex or a decode...Element call begins one"

@Serializable
@SerialName("Mixed")
private data class MixedWriter(
    val skipped: String,
    val d: Double,
    val s: String,
    val i: Int,
    val nullableLong: Long?,
    val b: Boolean,
) {
    /** The record read from this one: its fields, then every default value. */
    fun read() = Mixed(i, s, b, d, nullableLong, 7, 8, "dflt", true, 0.5, 1.5f, 'z', MixedColor.GREEN, null, 9, listOf(1, 2), MixedInner(3))
}

@Serializable
@SerialName("MixedColor")
private enum class MixedColor { RED, GREEN }

@Serializable
@SerialName("MixedInner")
private data class MixedInner(val x: Int)

/** Each kind of value read from the input, then each kind of value taken from its default. */
@Serializable
@SerialName("Mixed")
private data class Mixed(
    val i: Int,
    val s: String,
    val b: Boolean,
    val d: Double,
    val nullableLong: Long?,
    @AvroDefault("7") val defaultInt: Int,
    @AvroDefault("8") val defaultLong: Long,
    @AvroDefault("dflt") val defaultString: String,
    @AvroDefault("true") val defaultBoolean: Boolean,
    @AvroDefault("0.5") val defaultDouble: Double,
    @AvroDefault("1.5") val defaultFloat: Float,
    @AvroDefault("z") val defaultChar: Char,
    @AvroDefault("GREEN") val defaultEnum: MixedColor,
    @AvroDefault("null") val defaultNull: Int?,
    @AvroDefault("9") val defaultNotNull: Int?,
    @AvroDefault("[1, 2]") val defaultList: List<Int>,
    @AvroDefault("""{"x": 3}""") val defaultRecord: MixedInner,
)

@Serializable
@SerialName("Reordered")
private data class ReorderedWriter(val s: String, val i: Int)

@Serializable
@SerialName("Reordered")
private data class Reordered(val i: Int, val s: String)

@Serializable
@SerialName("Reordered")
private data class ReorderedWithDefault(
    val i: Int,
    val s: String,
    @AvroDefault("7") val z: Int,
)

@Serializable
@SerialName("AlternatingGeneral")
private data class AlternatingGeneralWriter(
    val first: MixedWriter,
    val second: ReorderedWriter,
    val third: MixedWriter,
    val fourth: ReorderedWriter,
)

@Serializable
@SerialName("AlternatingGeneral")
private data class AlternatingGeneral(
    val first: Mixed,
    val second: Reordered,
    val third: Mixed,
    val fourth: Reordered,
)

/**
 * Begins a [ReorderedWithDefault] record, asks it for each kind of value before any field, each failing as a new decoder
 * does without reading the input, then reads its fields by index, the last one from its default value.
 */
private object EarlyReorderedSerializer : KSerializer<ReorderedWithDefault> {
    override val descriptor: SerialDescriptor get() = ReorderedWithDefault.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: ReorderedWithDefault,
    ) = ReorderedWithDefault.serializer().serialize(encoder, value)

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): ReorderedWithDefault {
        val record = decoder.beginStructure(descriptor)
        val recordDecoder = record as AvroDecoder
        val reads =
            listOf<Pair<String, () -> Any?>>(
                "currentWriterSchema" to { recordDecoder.currentWriterSchema },
                "decodeNotNullMark" to { recordDecoder.decodeNotNullMark() },
                "decodeNull" to { recordDecoder.decodeNull() },
                "decodeBoolean" to { recordDecoder.decodeBoolean() },
                "decodeByte" to { recordDecoder.decodeByte() },
                "decodeShort" to { recordDecoder.decodeShort() },
                "decodeInt" to { recordDecoder.decodeInt() },
                "decodeLong" to { recordDecoder.decodeLong() },
                "decodeFloat" to { recordDecoder.decodeFloat() },
                "decodeDouble" to { recordDecoder.decodeDouble() },
                "decodeChar" to { recordDecoder.decodeChar() },
                "decodeString" to { recordDecoder.decodeString() },
                "decodeEnum" to { recordDecoder.decodeEnum(MixedColor.serializer().descriptor) },
                "decodeBytes" to { recordDecoder.decodeBytes() },
                "decodeFixed" to { recordDecoder.decodeFixed() },
                "decodeSerializableValue" to { recordDecoder.decodeSerializableValue(Int.serializer()) },
                "decodeNullableSerializableValue" to { recordDecoder.decodeNullableSerializableValue(Int.serializer()) }
            )
        for ((name, read) in reads) {
            withClue(name) {
                val error = shouldThrow<RuntimeException> { read() }
                error::class.simpleName shouldBe "IllegalStateException"
                error.message shouldBe NO_ELEMENT_MESSAGE
            }
        }
        var i = 0
        var s = ""
        var z = 0
        while (true) {
            when (val index = record.decodeElementIndex(descriptor)) {
                0 -> i = record.decodeIntElement(descriptor, index)
                1 -> s = record.decodeStringElement(descriptor, index)
                2 -> z = record.decodeIntElement(descriptor, index)
                CompositeDecoder.DECODE_DONE -> break
                else -> error("Unexpected index $index")
            }
        }
        record.endStructure(descriptor)
        return ReorderedWithDefault(i, s, z)
    }
}

@Serializable
@SerialName("WithNullable")
private data class WithNullable(val a: Int?, val b: String?)

@Serializable
@SerialName("WithoutNullable")
private data class WithoutNullable(val a: Int, val b: String)

@Serializable
@SerialName("NestedWithoutNullable")
private data class NestedWithoutNullable(val inner: WithNullable, val y: Long)

@Serializable
@SerialName("NestedWithNullable")
private data class NestedWithNullable(val inner: NestedWithoutNullable?, val x: Long?)

@Serializable
@SerialName("AlternatingExact")
private data class AlternatingExact(
    val first: WithNullable,
    val second: WithoutNullable,
    val third: WithNullable,
    val fourth: WithoutNullable,
    val nested: NestedWithNullable,
)

@Serializable
@SerialName("LevelD")
private data class LevelD(val n: Int?, val m: String?)

@Serializable
@SerialName("LevelC")
private data class LevelCWriter(val skipped: String, val y: Int, val d: LevelD)

/** Decoded by its steps: its writer has a field more. */
@Serializable
@SerialName("LevelC")
private data class LevelC(val y: Int, val d: LevelD)

@Serializable
@SerialName("LevelB")
private data class LevelB(val x: Long, val c: LevelCWriter)

@Serializable
@SerialName("LevelA")
private data class LevelAWriter(val s: String, val i: Int, val b: LevelB)

/** Its own fields reordered and one taking its default value; its `b` read exactly, its fields read into [LevelC]. */
@Serializable
@SerialName("LevelA")
private data class LevelA(
    val i: Int,
    val s: String,
    val b: LevelBRead,
    @AvroDefault("7") val z: Int,
)

@Serializable
@SerialName("LevelB")
private data class LevelBRead(val x: Long, val c: LevelC)