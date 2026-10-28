package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroAlias
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.internal.DecodingStep
import com.github.avrokotlin.avro4k.internal.codec.ByteArrayEncoder
import com.github.avrokotlin.avro4k.internal.exactFieldSchemas
import com.github.avrokotlin.avro4k.schema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

/**
 * A record whose elements are exactly its writer schema's fields, in the same order, is decoded in order: its decoder
 * answers `decodeSequentially()` with true, and kotlinx's generated deserializers then read each element in turn without
 * asking `decodeElementIndex`. Any other record is decoded by its decoding steps, as before. These tests check which
 * records are exact, that both kinds decode the same values with and without validation, that a serializer reading an
 * exact record by index still gets every field, and that the two kinds of record decoder are reused side by side.
 */
class ExactRecordDecodingTest {
    @Test
    fun `a record is exact only when step i decodes writer field i into element i`() {
        val int = AvroSchema.IntSchema()
        val string = AvroSchema.StringSchema()
        val exact = arrayOf<DecodingStep>(DecodingStep.DeserializeWriterField(0, 0, int), DecodingStep.DeserializeWriterField(1, 1, string))
        val schemas = exactFieldSchemas(exact, elementsCount = 2, writerFieldsCount = 2)!!
        schemas.size shouldBe 2
        (schemas[0] === int && schemas[1] === string) shouldBe true
        exactFieldSchemas(emptyArray(), elementsCount = 0, writerFieldsCount = 0)!!.size shouldBe 0

        // counts
        exactFieldSchemas(exact, elementsCount = 3, writerFieldsCount = 2) shouldBe null
        exactFieldSchemas(exact, elementsCount = 2, writerFieldsCount = 3) shouldBe null
        // the step kind: a default value for element 1, at its place
        exactFieldSchemas(
            arrayOf(DecodingStep.DeserializeWriterField(0, 0, int), DecodingStep.GetDefaultValue(1, string, JsonPrimitive("b"))),
            elementsCount = 2,
            writerFieldsCount = 2
        ) shouldBe null
        // the element index
        exactFieldSchemas(
            arrayOf(DecodingStep.DeserializeWriterField(1, 0, int), DecodingStep.DeserializeWriterField(0, 1, string)),
            elementsCount = 2,
            writerFieldsCount = 2
        ) shouldBe null
        // the writer field index
        exactFieldSchemas(
            arrayOf(DecodingStep.DeserializeWriterField(0, 1, int), DecodingStep.DeserializeWriterField(1, 0, string)),
            elementsCount = 2,
            writerFieldsCount = 2
        ) shouldBe null
    }

    @Test
    fun `a record read with its own schema is decoded in order`() {
        decodes(SeqAB.serializer(), SeqAB(1, "b"), SeqAB.serializer(), SeqAB(1, "b")) shouldBe listOf(true)
        decodes(SeqWithObject.serializer(), SeqWithObject(1, SeqObject, "b"), SeqWithObject.serializer(), SeqWithObject(1, SeqObject, "b")) shouldBe
            listOf(true)
    }

    @Test
    fun `a record whose fields are reordered is decoded by index`() {
        decodes(SeqBAWriter.serializer(), SeqBAWriter("b", 1), SeqAB.serializer(), SeqAB(1, "b")) shouldBe listOf(false)
    }

    @Test
    fun `a record whose writer has a field more is decoded by index`() {
        decodes(SeqABCWriter.serializer(), SeqABCWriter(1, "b", true), SeqAB.serializer(), SeqAB(1, "b")) shouldBe listOf(false)
    }

    @Test
    fun `a record whose missing field takes a default value is decoded by index`() {
        decodes(SeqAB.serializer(), SeqAB(1, "b"), SeqABDefault.serializer(), SeqABDefault(1, "b", 5)) shouldBe listOf(false)
        decodes(SeqAB.serializer(), SeqAB(1, "b"), SeqABNullable.serializer(), SeqABNullable(1, "b", null)) shouldBe listOf(false)
        decodes(SeqAB.serializer(), SeqAB(1, "b"), SeqABOptional.serializer(), SeqABOptional(1, "b")) shouldBe listOf(false)
    }

    @Test
    fun `a field matched by an alias does not prevent decoding in order`() {
        decodes(SeqAB.serializer(), SeqAB(1, "b"), SeqAliased.serializer(), SeqAliased(1, "b")) shouldBe listOf(true)
    }

    @Test
    fun `a field whose writer type is promoted does not prevent decoding in order`() {
        decodes(SeqAB.serializer(), SeqAB(-7, "b"), SeqWideAB.serializer(), SeqWideAB(-7L, "b")) shouldBe listOf(true)
    }

    @Test
    fun `an exact record nested in a record decoded by index is decoded in order and the reverse`() {
        decodes(
            SeqOuterWriter.serializer(),
            SeqOuterWriter(SeqAB(1, "b"), 2, "extra"),
            SeqOuterNonExact.serializer(),
            SeqOuterNonExact(SeqAB(1, "b"), 2)
        ) shouldBe listOf(false, true)
        decodes(
            SeqOuterExactWriter.serializer(),
            SeqOuterExactWriter(SeqBAWriter("b", 1), 2),
            SeqOuterExact.serializer(),
            SeqOuterExact(SeqAB(1, "b"), 2)
        ) shouldBe listOf(true, false)
    }

    @Test
    fun `a serializer reading an exact record by index gets every field in order`() {
        val value = SeqMixed(1, "b", 3L, SeqAB(4, "e"))
        val nulls = SeqMixed(1, null, 3L, null)
        for (avro in AVROS) {
            // exact: the serializer still reads by index, and asks for each field's writer schema before decoding it
            for (mixed in listOf(value, nulls)) {
                val bytes = avro.encodeToByteArray(SeqMixed.serializer(), mixed)
                val serializer = IndexLoopSerializer()
                avro.decodeFromByteArray(Avro.schema(SeqMixed.serializer()), serializer, bytes) shouldBe mixed
                serializer.sequentially shouldBe true
                serializer.indexes shouldBe listOf(0, 1, 2, 3)
                serializer.writerTypes shouldBe
                    listOf(
                        AvroSchema.Type.INT,
                        if (mixed.b == null) AvroSchema.Type.NULL else AvroSchema.Type.STRING,
                        AvroSchema.Type.LONG,
                        if (mixed.d == null) AvroSchema.Type.NULL else AvroSchema.Type.RECORD
                    )
            }
            // the same serializer on a record decoded by its steps, for comparison: a field more, skipped at the end
            val writer = Avro.schema(SeqMixedWriter.serializer())
            val bytes = avro.encodeToByteArray(SeqMixedWriter.serializer(), SeqMixedWriter(1, "b", 3L, SeqAB(4, "e"), true))
            val serializer = IndexLoopSerializer()
            avro.decodeFromByteArray(writer, serializer, bytes) shouldBe value
            serializer.sequentially shouldBe false
            serializer.indexes shouldBe listOf(0, 1, 2, 3)
        }
    }

    @Test
    fun `an exact record knows no field before one is begun`() {
        // at the root, on a new decoder, then as fields after an exact sibling, on a reused one
        Avro.decodeFromByteArray(Avro.schema(SeqAB.serializer()), PeekingExactSerializer, Avro.encodeToByteArray(SeqAB.serializer(), SeqAB(1, "a"))) shouldBe
            SeqAB(1, "a")
        val value = SeqPeeks(SeqAB(1, "a"), SeqAB(2, "b"), listOf(SeqAB(3, "c"), SeqAB(4, "d")))
        for (avro in AVROS) {
            avro.decodeFromByteArray(SeqPeeks.serializer(), avro.encodeToByteArray(SeqPeeks.serializer(), value)) shouldBe value
        }
    }

    @Test
    fun `exact and non-exact records alternate as siblings and as list items`() {
        val writer =
            SeqSiblingsWriter(
                e = SeqAB(1, "a"),
                n = SeqPlus(2, "b", true),
                e2 = SeqAB(3, "c"),
                n2 = SeqPlus(4, "d", false),
                items = List(4) { SeqItemWriter(SeqPlus(it, "n$it", it % 2 == 0), SeqAB(-it, "e$it")) },
                exacts = listOf(SeqAB(5, "f"), SeqAB(6, "g")),
                plus = listOf(SeqPlus(7, "h", true), SeqPlus(8, "i", false))
            )
        val expected =
            SeqSiblings(
                e = SeqAB(1, "a"),
                n = SeqAB(2, "b"),
                e2 = SeqAB(3, "c"),
                n2 = SeqAB(4, "d"),
                items = List(4) { SeqItem(SeqAB(it, "n$it"), SeqAB(-it, "e$it")) },
                exacts = listOf(SeqAB(5, "f"), SeqAB(6, "g")),
                plus = listOf(SeqAB(7, "h"), SeqAB(8, "i"))
            )
        decodes(SeqSiblingsWriter.serializer(), writer, SeqSiblings.serializer(), expected) shouldBe listOf(true)
    }

    @Test
    fun `records of one descriptor alternate between two writer schemas of each kind`() {
        // exact against XY: x then y, x written as an int or as a string
        val intX = record("IntX", "x" to AvroSchema.IntSchema(), "y" to AvroSchema.StringSchema())
        val stringX = record("StringX", "x" to AvroSchema.StringSchema(), "y" to AvroSchema.StringSchema())
        // decoded by their steps: y first, with or without a field more
        val reversed = record("Reversed", "y" to AvroSchema.StringSchema(), "x" to AvroSchema.IntSchema())
        val extended = record("Extended", "y" to AvroSchema.StringSchema(), "flag" to AvroSchema.BooleanSchema(), "x" to AvroSchema.IntSchema())
        val branches = listOf(intX, stringX, reversed, extended)
        val values = List(12) { SeqXY(it * 7, "v$it") }
        // every pair of kinds alternates: exact and exact, steps and steps, exact and steps
        val orders = listOf(listOf(0, 1), listOf(2, 3), listOf(0, 2), listOf(1, 3), listOf(0, 1, 2, 3))
        for (order in orders) {
            val bytes =
                bytes {
                    writeBlocks(values) {
                        val branch = order[it.x / 7 % order.size]
                        writeIndex(branch)
                        when (branch) {
                            0 -> {
                                writeInt(it.x)
                                writeString(it.y)
                            }

                            1 -> {
                                writeString(it.x.toString())
                                writeString(it.y)
                            }

                            2 -> {
                                writeString(it.y)
                                writeInt(it.x)
                            }

                            else -> {
                                writeString(it.y)
                                writeBoolean(true)
                                writeInt(it.x)
                            }
                        }
                    }
                }
            for (avro in AVROS) {
                avro.decodeFromByteArray(AvroSchema.ArraySchema(AvroSchema.UnionSchema(branches)), ListSerializer(SeqXY.serializer()), bytes) shouldBe values
            }
        }
    }

    @Test
    fun `records of one writer schema alternate between an exact reader and a non-exact one`() {
        val shared = record("Shared", "x" to AvroSchema.IntSchema(), "y" to AvroSchema.StringSchema())
        val writer =
            record(
                "SharedReaders",
                "first" to shared,
                "second" to shared,
                "third" to shared,
                "firsts" to AvroSchema.ArraySchema(shared),
                "seconds" to AvroSchema.ArraySchema(shared)
            )
        val bytes =
            bytes {
                writeInt(1)
                writeString("a")
                writeInt(2)
                writeString("b")
                writeInt(3)
                writeString("c")
                writeBlocks(listOf(SeqXY(4, "d"), SeqXY(5, "e"))) {
                    writeInt(it.x)
                    writeString(it.y)
                }
                writeBlocks(listOf(SeqXY(6, "f"), SeqXY(7, "g"))) {
                    writeInt(it.x)
                    writeString(it.y)
                }
            }
        for (avro in AVROS) {
            avro.decodeFromByteArray(writer, SeqSharedReaders.serializer(), bytes) shouldBe
                SeqSharedReaders(SeqXY(1, "a"), SeqOnlyX(2), SeqXY(3, "c"), listOf(SeqXY(4, "d"), SeqXY(5, "e")), listOf(SeqOnlyX(6), SeqOnlyX(7)))
        }
    }

    /**
     * Encodes [writerValue] with [writerSerializer], then decodes it with [readerSerializer] into [expected], with and
     * without validation, alone and through a probe. Returns what the probed records answered to `decodeSequentially()`,
     * in the order they were begun: the root first, then the fields read by a [SequentialProbe].
     */
    private fun <W, R> decodes(
        writerSerializer: KSerializer<W>,
        writerValue: W,
        readerSerializer: KSerializer<R>,
        expected: R,
    ): List<Boolean> {
        val writerSchema = Avro.schema(writerSerializer)
        val bytes = Avro.encodeToByteArray(writerSerializer, writerValue)
        val answersByAvro =
            AVROS.map { avro ->
                SEQUENTIAL_ANSWERS.clear()
                avro.decodeFromByteArray(writerSchema, SequentialProbe(readerSerializer), bytes) shouldBe expected
                val answers = SEQUENTIAL_ANSWERS.toList()
                SEQUENTIAL_ANSWERS.clear()
                avro.decodeFromByteArray(writerSchema, readerSerializer, bytes) shouldBe expected
                answers
            }
        answersByAvro.distinct().size shouldBe 1
        return answersByAvro.first()
    }

    private companion object {
        val AVROS = listOf(Avro, Avro { validateSerialization = true })
    }
}

/** What each [SequentialProbe] answered to `decodeSequentially()`, in the order the records were begun. */
private val SEQUENTIAL_ANSWERS = mutableListOf<Boolean>()

/** Decodes as [delegate] does, and records what the record it begins answers to `decodeSequentially()`. */
private open class SequentialProbe<T>(private val delegate: KSerializer<T>) : KSerializer<T> {
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): T = delegate.deserialize(ProbingDecoder(decoder))

    private class ProbingDecoder(private val decoder: Decoder) : Decoder by decoder {
        @OptIn(ExperimentalSerializationApi::class)
        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
            decoder.beginStructure(descriptor).also { SEQUENTIAL_ANSWERS += it.decodeSequentially() }
    }
}

private object SeqABProbe : SequentialProbe<SeqAB>(SeqAB.serializer())

@Serializable
private data class SeqAB(val a: Int, val b: String)

@Serializable
private data class SeqBAWriter(val b: String, val a: Int)

@Serializable
private data class SeqABCWriter(val a: Int, val b: String, val c: Boolean)

@Serializable
private data class SeqABDefault(
    val a: Int,
    val b: String,
    @AvroDefault("5") val c: Int,
)

@Serializable
private data class SeqABNullable(val a: Int, val b: String, val c: String?)

@Serializable
private data class SeqABOptional(val a: Int, val b: String, val c: Int = 3)

@Serializable
private data class SeqAliased(
    val a: Int,
    @AvroAlias("b") val renamed: String,
)

@Serializable
private data class SeqWideAB(val a: Long, val b: String)

@Serializable
private object SeqObject

@Serializable
private data class SeqWithObject(val a: Int, val o: SeqObject, val b: String)

@Serializable
private data class SeqOuterWriter(val child: SeqAB, val n: Int, val extra: String)

@Serializable
private data class SeqOuterNonExact(
    @Serializable(with = SeqABProbe::class) val child: SeqAB,
    val n: Int,
)

@Serializable
private data class SeqOuterExactWriter(val child: SeqBAWriter, val n: Int)

@Serializable
private data class SeqOuterExact(
    @Serializable(with = SeqABProbe::class) val child: SeqAB,
    val n: Int,
)

@Serializable
private data class SeqMixed(val a: Int, val b: String?, val c: Long, val d: SeqAB?)

@Serializable
private data class SeqMixedWriter(val a: Int, val b: String?, val c: Long, val d: SeqAB?, val extra: Boolean)

/**
 * Reads a [SeqMixed] by index whatever `decodeSequentially()` answers, as a serializer written before it existed would, and
 * asks for each field's writer schema between `decodeElementIndex` and the field's decoding: a union's branch read there
 * must not be read again by the field.
 */
private class IndexLoopSerializer : KSerializer<SeqMixed> {
    override val descriptor: SerialDescriptor = SeqMixed.serializer().descriptor
    var sequentially: Boolean? = null
    val indexes = mutableListOf<Int>()
    val writerTypes = mutableListOf<AvroSchema.Type>()

    override fun serialize(
        encoder: Encoder,
        value: SeqMixed,
    ) = SeqMixed.serializer().serialize(encoder, value)

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): SeqMixed {
        val record = decoder.beginStructure(descriptor)
        sequentially = record.decodeSequentially()
        var a = 0
        var b: String? = null
        var c = 0L
        var d: SeqAB? = null
        while (true) {
            val index = record.decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) break
            indexes += index
            writerTypes += (record as AvroDecoder).currentWriterSchema.type
            when (index) {
                0 -> a = record.decodeIntElement(descriptor, 0)
                1 -> b = record.decodeNullableSerializableElement(descriptor, 1, NULLABLE_STRING)
                2 -> c = record.decodeLongElement(descriptor, 2)
                3 -> d = record.decodeNullableSerializableElement(descriptor, 3, SeqAB.serializer().nullable)
                else -> error("Unexpected index $index")
            }
        }
        record.decodeElementIndex(descriptor) shouldBe CompositeDecoder.DECODE_DONE
        record.endStructure(descriptor)
        return SeqMixed(a, b, c, d)
    }

    private companion object {
        val NULLABLE_STRING: DeserializationStrategy<String?> = String.serializer().nullable
    }
}

/** Asks an exact record for its writer schema before any field is begun, as a new decoder fails to answer, then reads it in order. */
private object PeekingExactSerializer : KSerializer<SeqAB> {
    override val descriptor: SerialDescriptor = SeqAB.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: SeqAB,
    ) = SeqAB.serializer().serialize(encoder, value)

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): SeqAB {
        val record = decoder.beginStructure(descriptor)
        shouldThrow<IllegalStateException> { (record as AvroDecoder).currentWriterSchema }
        record.decodeSequentially() shouldBe true
        val value = SeqAB(record.decodeIntElement(descriptor, 0), record.decodeStringElement(descriptor, 1))
        record.endStructure(descriptor)
        return value
    }
}

@Serializable
private data class SeqPeeks(
    val before: SeqAB,
    @Serializable(with = PeekingExactSerializer::class) val peeked: SeqAB,
    val peekedList: List<
        @Serializable(with = PeekingExactSerializer::class)
        SeqAB
    >,
)

@Serializable
private data class SeqPlus(val a: Int, val b: String, val c: Boolean)

@Serializable
private data class SeqItemWriter(val n: SeqPlus, val e: SeqAB)

@Serializable
private data class SeqItem(val n: SeqAB, val e: SeqAB)

@Serializable
private data class SeqSiblingsWriter(
    val e: SeqAB,
    val n: SeqPlus,
    val e2: SeqAB,
    val n2: SeqPlus,
    val items: List<SeqItemWriter>,
    val exacts: List<SeqAB>,
    val plus: List<SeqPlus>,
)

@Serializable
private data class SeqSiblings(
    val e: SeqAB,
    val n: SeqAB,
    val e2: SeqAB,
    val n2: SeqAB,
    val items: List<SeqItem>,
    val exacts: List<SeqAB>,
    val plus: List<SeqAB>,
)

@Serializable
private data class SeqXY(val x: Int, val y: String)

@Serializable
private data class SeqOnlyX(val x: Int)

@Serializable
private data class SeqSharedReaders(val first: SeqXY, val second: SeqOnlyX, val third: SeqXY, val firsts: List<SeqXY>, val seconds: List<SeqOnlyX>)

private fun record(
    name: String,
    vararg fields: Pair<String, AvroSchema>,
): AvroSchema.RecordSchema = AvroSchema.RecordSchema(Name(name), fields.map { AvroSchema.RecordSchema.Field(it.first, it.second) })

private fun bytes(write: ByteArrayEncoder.() -> Unit): ByteArray = ByteArrayEncoder().apply { write() }.toByteArray()

/** Writes [items] as one array block, then the closing empty block. */
private fun <I> ByteArrayEncoder.writeBlocks(
    items: List<I>,
    writeItem: ByteArrayEncoder.(I) -> Unit,
) {
    writeLong(items.size.toLong())
    items.forEach { writeItem(it) }
    writeLong(0)
}