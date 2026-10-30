package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.schema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test

/**
 * Every decoder kind reports, through [AvroDecoder.currentWriterSchema], the writer schema of the value a serializer is
 * handed it for, union branches resolved; and, asked before any field or item is begun, fails as it always did, without
 * reading anything from the input.
 */
@OptIn(ExperimentalSerializationApi::class)
class CurrentWriterSchemaTest {
    @Test
    fun `each decoder kind reports the writer schema of the value it decodes`() {
        val int = AvroSchema.IntSchema()
        val string = AvroSchema.StringSchema()
        val circle = Avro.schema(ProbedCircleSurrogate.serializer().descriptor)
        val written =
            listOf(
                ProbedWriter(1, 2, null, listOf("a", null, "b"), mapOf("k" to 3), ProbedAsMap(4, 5), ProbedGeneralWriter("skipped", 6), ProbedCircle(7)),
                ProbedWriter(8, null, 9, emptyList(), emptyMap(), ProbedAsMap(10, 11), ProbedGeneralWriter("", 12), ProbedSquare(13))
            )
        val bytes = Avro.encodeToByteArray(ListSerializer(ProbedWriter.serializer()), written)
        for (avro in AVROS) {
            SEEN.clear()
            avro.decodeFromByteArray(Avro.schema(ListSerializer(ProbedWriter.serializer()).descriptor), ListSerializer(Probed.serializer()), bytes) shouldBe
                listOf(
                    Probed(1, 2, null, listOf("a", null, "b"), mapOf("k" to 3), mapOf("a" to 4, "b" to 5), ProbedGeneral(6), ProbedCircle(7)),
                    Probed(8, null, 9, emptyList(), emptyMap(), mapOf("a" to 10, "b" to 11), ProbedGeneral(12), ProbedSquare(13))
                )
            SEEN shouldBe
                listOf(
                    // exact record field, nullable field (its union resolved), list items of a union, map key and value
                    int,
                    int,
                    string,
                    string,
                    string,
                    int,
                    // record read as a map: keys and values
                    string,
                    int,
                    string,
                    int,
                    // field of a record not matching its writer exactly, then a polymorphic value
                    int,
                    circle,
                    // second record, whose polymorphic value is a record with a probed field
                    int,
                    int,
                    string,
                    int,
                    string,
                    int,
                    int,
                    int
                )
        }
    }

    @Test
    fun `a root value reports its writer schema with a union resolved`() {
        for (avro in AVROS) {
            SEEN.clear()
            avro.decodeFromByteArray(AvroSchema.IntSchema(), ProbedInt, Avro.encodeToByteArray(Int.serializer(), 21)) shouldBe 21
            val union = AvroSchema.UnionSchema(AvroSchema.StringSchema(), AvroSchema.IntSchema())
            avro.decodeFromByteArray(union, ProbedInt, Avro.encodeToByteArray(union, Int.serializer(), 22)) shouldBe 22
            SEEN shouldBe listOf(AvroSchema.IntSchema(), AvroSchema.IntSchema())
        }
    }

    @Test
    fun `asked before any field or item is begun each decoder kind fails without reading`() {
        val value = EarlyPeeked(EarlyPair(1, "a"), EarlyTripleWriter("skipped", 2, true), emptyList(), emptyMap(), EarlyPair(6, "b"))
        val bytes = Avro.encodeToByteArray(
            EarlyPeekedWriter.serializer(),
            EarlyPeekedWriter(EarlyPair(1, "a"), EarlyTripleWriter("skipped", 2, true), emptyList(), emptyMap(), EarlyPair(6, "b"))
        )
        // Without validation, as the block decoder tests of DecoderReuseTest: the validating decoder rejects a collection
        // begun by a serializer that is not a collection serializer right after another one on the same decoder
        Avro.decodeFromByteArray(Avro.schema(EarlyPeekedWriter.serializer().descriptor), EarlyPeeked.serializer(), bytes) shouldBe value
    }

    companion object {
        val AVROS = listOf(Avro, Avro { validateSerialization = true })
    }
}

/** The writer schemas the probes were handed, in decoding order. */
private val SEEN = mutableListOf<AvroSchema>()

/** Records the writer schema of the decoder it is handed, then decodes as [delegate] does. */
private abstract class Probe<T>(private val delegate: KSerializer<T>) : KSerializer<T> {
    override val descriptor: SerialDescriptor get() = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): T {
        SEEN += (decoder as AvroDecoder).currentWriterSchema
        return delegate.deserialize(decoder)
    }
}

private object ProbedInt : Probe<Int>(Int.serializer())

private object ProbedString : Probe<String>(String.serializer())

@Serializable
@SerialName("ProbedCircle")
private data class ProbedCircleSurrogate(val r: Int)

private object ProbedCircleSerializer : KSerializer<ProbedCircle> {
    private val probe = object : Probe<ProbedCircleSurrogate>(ProbedCircleSurrogate.serializer()) {}
    override val descriptor: SerialDescriptor get() = probe.descriptor

    override fun serialize(
        encoder: Encoder,
        value: ProbedCircle,
    ) = probe.serialize(encoder, ProbedCircleSurrogate(value.r))

    override fun deserialize(decoder: Decoder): ProbedCircle = ProbedCircle(probe.deserialize(decoder).r)
}

@Serializable
private sealed interface ProbedShape

@Serializable(with = ProbedCircleSerializer::class)
private data class ProbedCircle(val r: Int) : ProbedShape

@Serializable
@SerialName("ProbedSquare")
private data class ProbedSquare(
    @Serializable(with = ProbedInt::class) val side: Int,
) : ProbedShape

@Serializable
@SerialName("ProbedAsMap")
private data class ProbedAsMap(val a: Int, val b: Int)

@Serializable
@SerialName("ProbedGeneral")
private data class ProbedGeneralWriter(val skipped: String, val x: Int)

@Serializable
@SerialName("ProbedGeneral")
private data class ProbedGeneral(
    @Serializable(with = ProbedInt::class) val x: Int,
)

@Serializable
@SerialName("Probed")
private data class ProbedWriter(
    val plain: Int,
    val nullable: Int?,
    val nullableLast: Int?,
    val items: List<String?>,
    val counts: Map<String, Int>,
    val asMap: ProbedAsMap,
    val general: ProbedGeneralWriter,
    val shape: ProbedShape,
)

@Serializable
@SerialName("Probed")
private data class Probed(
    @Serializable(with = ProbedInt::class) val plain: Int,
    @Serializable(with = ProbedInt::class) val nullable: Int?,
    @Serializable(with = ProbedInt::class) val nullableLast: Int?,
    val items: List<
        @Serializable(with = ProbedString::class)
        String?
    >,
    val counts: Map<
        @Serializable(with = ProbedString::class)
        String,
        @Serializable(with = ProbedInt::class)
        Int
    >,
    val asMap: Map<
        @Serializable(with = ProbedString::class)
        String,
        @Serializable(with = ProbedInt::class)
        Int
    >,
    val general: ProbedGeneral,
    val shape: ProbedShape,
)

private const val NO_ELEMENT_MESSAGE = "No field or item is being decoded: decodeElementIndex or a decode...Element call begins one"

/**
 * Asks the structure just begun for its writer schema in every way a serializer can, before any field or item: each
 * fails with an exception of the class named [exceptionName] (by name: Kotlin's error for a `lateinit` property is not
 * public on every platform) and [message], as it did before any decoder was reused, and nothing is read from the input.
 */
private fun CompositeDecoder.shouldFailBeforeAnyElement(
    exceptionName: String,
    message: String,
) {
    val decoder = this as Decoder
    val reads =
        listOf(
            { (this as AvroDecoder).currentWriterSchema },
            { decoder.decodeNotNullMark() },
            { decoder.decodeNull() },
            { decoder.decodeInt() },
            { decoder.decodeLong() },
            { decoder.decodeSerializableValue(Int.serializer()) },
            { decoder.beginStructure(ListSerializer(Int.serializer()).descriptor) },
            { decoder.beginStructure(EarlyPair.serializer().descriptor) },
            { decoder.beginStructure(ProbedShape.serializer().descriptor) }
        )
    for (read in reads) {
        val error = shouldThrow<RuntimeException> { read() }
        error::class.simpleName shouldBe exceptionName
        error.message shouldBe message
    }
}

@Serializable
@SerialName("EarlyPair")
private data class EarlyPair(val x: Int, val y: String)

@Serializable
@SerialName("EarlyTripleWriter")
private data class EarlyTripleWriter(val skipped: String, val x: Int, val flag: Boolean)

@Serializable
@SerialName("EarlyTripleWriter")
private data class EarlyTriple(val x: Int, val flag: Boolean)

/** Peeks before the first field of a record matching its writer exactly. */
private object EarlyExactSerializer : KSerializer<EarlyPair> {
    override val descriptor: SerialDescriptor get() = EarlyPair.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: EarlyPair,
    ) = EarlyPair.serializer().serialize(encoder, value)

    override fun deserialize(decoder: Decoder): EarlyPair {
        val record = decoder.beginStructure(descriptor)
        record.shouldFailBeforeAnyElement("IllegalStateException", NO_ELEMENT_MESSAGE)
        val value = EarlyPair(record.decodeIntElement(descriptor, 0), record.decodeStringElement(descriptor, 1))
        record.endStructure(descriptor)
        return value
    }
}

/** Peeks before the first field of a record skipping a writer field, then reads its fields by index. */
private object EarlyGeneralSerializer : KSerializer<EarlyTriple> {
    override val descriptor: SerialDescriptor get() = EarlyTriple.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: EarlyTriple,
    ) = EarlyTriple.serializer().serialize(encoder, value)

    override fun deserialize(decoder: Decoder): EarlyTriple {
        val record = decoder.beginStructure(descriptor)
        record.shouldFailBeforeAnyElement("IllegalStateException", NO_ELEMENT_MESSAGE)
        record.decodeElementIndex(descriptor) shouldBe 0
        val x = record.decodeIntElement(descriptor, 0)
        record.decodeElementIndex(descriptor) shouldBe 1
        val flag = record.decodeBooleanElement(descriptor, 1)
        record.decodeElementIndex(descriptor) shouldBe CompositeDecoder.DECODE_DONE
        record.endStructure(descriptor)
        return EarlyTriple(x, flag)
    }
}

/**
 * Peeks before the first item of an array block. An empty one: a serializer that is not a collection serializer reads a
 * single block, without the empty block that ends a non-empty array (a known limit of such serializers).
 */
private object EarlyIntsSerializer : KSerializer<List<Int>> {
    private val delegate = ListSerializer(Int.serializer())
    override val descriptor: SerialDescriptor get() = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: List<Int>,
    ) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<Int> {
        val block = decoder.beginStructure(descriptor)
        block.shouldFailBeforeAnyElement("IllegalStateException", NO_ELEMENT_MESSAGE)
        val size = block.decodeCollectionSize(descriptor)
        val items = List(size) { block.decodeIntElement(descriptor, it) }
        block.endStructure(descriptor)
        return items
    }
}

/** Peeks before the first key of a map block, empty for the same reason as the array block. */
private object EarlyCountsSerializer : KSerializer<Map<String, Int>> {
    private val delegate = MapSerializer(String.serializer(), Int.serializer())
    override val descriptor: SerialDescriptor get() = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Map<String, Int>,
    ) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): Map<String, Int> {
        val block = decoder.beginStructure(descriptor)
        block.shouldFailBeforeAnyElement("IllegalStateException", NO_ELEMENT_MESSAGE)
        val size = block.decodeCollectionSize(descriptor)
        val entries = (0 until size).associate { block.decodeStringElement(descriptor, 2 * it) to block.decodeIntElement(descriptor, 2 * it + 1) }
        block.endStructure(descriptor)
        return entries
    }
}

/** Peeks before the first key of a record read as a map. */
private object EarlyRecordAsMapSerializer : KSerializer<Map<String, String>> {
    private val delegate = MapSerializer(String.serializer(), String.serializer())
    override val descriptor: SerialDescriptor get() = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Map<String, String>,
    ) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val record = decoder.beginStructure(descriptor)
        record.shouldFailBeforeAnyElement("IllegalStateException", NO_ELEMENT_MESSAGE)
        val size = record.decodeCollectionSize(descriptor)
        val entries = (0 until size).associate { record.decodeStringElement(descriptor, 2 * it) to record.decodeSerializableElement(descriptor, 2 * it + 1, EarlyPairValue) }
        record.endStructure(descriptor)
        return entries
    }
}

/** Reads any field of [EarlyPair] as a string. */
private object EarlyPairValue : KSerializer<String> {
    override val descriptor: SerialDescriptor get() = String.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: String,
    ) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String = decoder.decodeString()
}

@Serializable
@SerialName("EarlyPeeked")
private data class EarlyPeekedWriter(
    val exact: EarlyPair,
    val general: EarlyTripleWriter,
    val ints: List<Int>,
    val counts: Map<String, Int>,
    val asMap: EarlyPair,
)

@Serializable
@SerialName("EarlyPeeked")
private data class EarlyPeeked(
    @Serializable(with = EarlyExactSerializer::class) val exact: EarlyPair,
    @Serializable(with = EarlyGeneralSerializer::class) val general: EarlyTriple,
    @Serializable(with = EarlyIntsSerializer::class) val ints: List<Int>,
    @Serializable(with = EarlyCountsSerializer::class) val counts: Map<String, Int>,
    @Serializable(with = EarlyRecordAsMapSerializer::class) val asMap: Map<String, String>,
) {
    constructor(exact: EarlyPair, general: EarlyTripleWriter, ints: List<Int>, counts: Map<String, Int>, asMap: EarlyPair) :
        this(exact, EarlyTriple(general.x, general.flag), ints, counts, mapOf("x" to asMap.x.toString(), "y" to asMap.y))
}