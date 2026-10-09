package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.ArraySchema
import com.github.avrokotlin.avro4k.AvroSchema.EnumSchema
import com.github.avrokotlin.avro4k.AvroSchema.FixedSchema
import com.github.avrokotlin.avro4k.AvroSchema.MapSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.Type
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.internal.apacheToAvro4kUncached
import com.github.avrokotlin.avro4k.internal.avro4kToApacheUncached
import com.github.avrokotlin.avro4k.internal.decodeWithBinaryDecoder
import com.github.avrokotlin.avro4k.internal.encodeWithBinaryEncoder
import com.github.avrokotlin.avro4k.schema
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import org.apache.avro.Schema
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import org.apache.avro.util.Utf8
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Feeds the same codec call sequences to Apache Avro's `ValidatingEncoder`/`ValidatingDecoder` and to avro4k's
 * [ValidatingAvroBinaryEncoder]/[ValidatingAvroBinaryDecoder], and checks that they accept and reject them alike.
 *
 * The corpus: every schema of the test resources and the models below. The valid sequences are recorded from encoding
 * and decoding real values with avro4k, or generated from the schema with random choices (union branches, collection
 * sizes, and on the decoder side the split of collections into blocks). The invalid ones are systematic mutations of
 * each valid sequence at every position: a call dropped or duplicated, a primitive kind swapped, a union index, enum
 * index, fixed length or item count changed by one.
 *
 * Both sides wrap a codec that does nothing (the decoder returns the recorded values in order), so only the validation
 * is compared. The decoder's `skipArray`/`skipMap` are left out: Apache's validating decoder skips the whole collection
 * itself, while avro4k's hands each block's items back to the caller, so the sequences differ by design (they are
 * covered by the common tests).
 *
 * The only accepted differences are the ones where avro4k's validator is stricter on purpose, each because the data
 * would be corrupt: the end of the datum, which Apache never checks (it accepts an incomplete datum, and loops to the
 * start of a next datum); and the item counts of collections, where Apache only counts `startItem()` calls on the
 * encoder side and nothing on the decoder side. Every other difference fails the test.
 */
internal class ValidatingCodecDifferentialTest : StringSpec({
    val corpus = buildCorpus()

    "the corpus covers every resource schema and model" {
        println(
            "Validating codec differential corpus: ${corpus.size} schemas, ${corpus.sumOf {
                it.encoderSequences.size
            }} encoder and ${corpus.sumOf { it.decoderSequences.size }} decoder valid sequences"
        )
        corpus.size shouldBeGreaterThan 40
    }

    "the encoders accept and reject the same call sequences" {
        val report = DifferentialReport()
        for (entry in corpus) {
            for (valid in entry.encoderSequences) {
                withClue("valid sequence for ${entry.name}: $valid") {
                    runApacheEncoder(entry.apacheSchema, valid) shouldBe null
                    runAvro4kEncoder(entry.schema, valid) shouldBe Avro4kResult.Accepted
                }
                for ((mutation, calls) in mutants(valid, decoder = false)) {
                    report.compare(entry, mutation, calls, runApacheEncoder(entry.apacheSchema, calls), runAvro4kEncoder(entry.schema, calls)) {
                        runAvro4kEncoder(entry.schema, it)
                    }
                }
            }
        }
        report.print("encoder")
        report.unexplained.shouldBeEmpty()
        report.bothRejected shouldBeGreaterThan 1000
    }

    "the decoders accept and reject the same call sequences" {
        val report = DifferentialReport()
        for (entry in corpus) {
            for (valid in entry.decoderSequences) {
                withClue("valid sequence for ${entry.name}: $valid") {
                    runApacheDecoder(entry.apacheSchema, valid) shouldBe null
                    runAvro4kDecoder(entry.schema, valid) shouldBe Avro4kResult.Accepted
                }
                for ((mutation, calls) in mutants(valid, decoder = true)) {
                    report.compare(entry, mutation, calls, runApacheDecoder(entry.apacheSchema, calls), runAvro4kDecoder(entry.schema, calls)) {
                        runAvro4kDecoder(entry.schema, it)
                    }
                }
            }
        }
        report.print("decoder")
        report.unexplained.shouldBeEmpty()
        report.bothRejected shouldBeGreaterThan 1000
    }
}) {
    @Serializable
    @SerialName("DiffEveryType")
    internal data class EveryType(
        val nullable: String?,
        val bool: Boolean,
        val int: Int,
        val long: Long,
        val float: Float,
        val double: Double,
        val string: String,
        val bytes: ByteArray,
        @AvroFixed(4) val fixed: ByteArray,
        val enum: DiffEnum,
        val list: List<Int>,
        val map: Map<String, Long>,
        val nested: Nested?,
        val nestedList: List<Nested>,
        val mapOfLists: Map<String, List<Nested?>>,
        val sealed: DiffSealed,
        val char: Char,
    )

    @Serializable
    @SerialName("DiffNested")
    internal data class Nested(val name: String, val maybe: Int?)

    @Serializable
    internal enum class DiffEnum { A, B, C }

    @Serializable
    internal sealed interface DiffSealed {
        @Serializable
        @SerialName("DiffSealedA")
        data class A(val x: Int) : DiffSealed

        @Serializable
        @SerialName("DiffSealedB")
        data class B(val y: String, val z: List<Long>) : DiffSealed
    }

    @Serializable
    @SerialName("DiffRecursive")
    internal data class Recursive(val v: Int, val next: Recursive?, val children: List<Recursive>)
}

private enum class Kind { NULL, BOOLEAN, INT, LONG, FLOAT, DOUBLE, STRING, BYTES, FIXED, ENUM, INDEX, ARRAY_START, MAP_START, START_ITEM, ARRAY_END, MAP_END, ARRAY_NEXT, MAP_NEXT }

/** One codec call: [arg] is the fixed size, the enum or union index, the item count (written, or returned when read). */
private data class Call(val kind: Kind, val arg: Long = 0) {
    override fun toString(): String = if (arg == 0L && kind !in WITH_ARG) kind.name else "${kind.name}($arg)"
}

private val WITH_ARG = setOf(Kind.FIXED, Kind.ENUM, Kind.INDEX, Kind.ARRAY_START, Kind.MAP_START, Kind.ARRAY_NEXT, Kind.MAP_NEXT)

private class CorpusEntry(
    val name: String,
    val schema: AvroSchema,
    val apacheSchema: Schema,
) {
    val encoderSequences = mutableListOf<List<Call>>()
    val decoderSequences = mutableListOf<List<Call>>()
}

// ---- running a sequence ----

/** The exception Apache's validating encoder threw, or null if it accepted every call. */
private fun runApacheEncoder(
    schema: Schema,
    calls: List<Call>,
): Throwable? {
    val encoder = ApacheEncoderAdapter(EncoderFactory.get().validatingEncoder(schema, NoopApacheEncoder))
    return runCatching { calls.forEach { encoder.apply(it) } }.exceptionOrNull()
}

private fun runApacheDecoder(
    schema: Schema,
    calls: List<Call>,
): Throwable? {
    val scripted = ScriptedApacheDecoder()
    val decoder = ApacheDecoderAdapter(DecoderFactory.get().validatingDecoder(schema, scripted))
    return runCatching {
        calls.forEach {
            scripted.next = it.arg
            decoder.apply(it)
        }
    }.exceptionOrNull()
}

private sealed interface Avro4kResult {
    data object Accepted : Avro4kResult

    data class RejectedAt(val index: Int, val message: String) : Avro4kResult

    data class RejectedAtEnd(val message: String) : Avro4kResult
}

private fun runAvro4kEncoder(
    schema: AvroSchema,
    calls: List<Call>,
): Avro4kResult {
    val encoder = ValidatingAvroBinaryEncoder(NoopAbiEncoder, schema)
    return runAvro4k(calls, { encoder.apply(it) }, { encoder.checkDatumComplete() })
}

private fun runAvro4kDecoder(
    schema: AvroSchema,
    calls: List<Call>,
): Avro4kResult {
    val scripted = ScriptedAbiDecoder()
    val decoder = ValidatingAvroBinaryDecoder(scripted, schema)
    return runAvro4k(calls, {
        scripted.next = it.arg
        decoder.apply(it)
    }, { decoder.checkDatumComplete() })
}

/** Only [SerializationException] is expected from avro4k's validator: anything else propagates and fails the test. */
private inline fun runAvro4k(
    calls: List<Call>,
    apply: (Call) -> Unit,
    end: () -> Unit,
): Avro4kResult {
    calls.forEachIndexed { index, call ->
        try {
            apply(call)
        } catch (e: SerializationException) {
            return Avro4kResult.RejectedAt(index, e.message!!)
        }
    }
    try {
        end()
    } catch (e: SerializationException) {
        return Avro4kResult.RejectedAtEnd(e.message!!)
    }
    return Avro4kResult.Accepted
}

private fun AvroBinaryEncoder.apply(call: Call) {
    when (call.kind) {
        Kind.NULL -> writeNull()
        Kind.BOOLEAN -> writeBoolean(true)
        Kind.INT -> writeInt(0)
        Kind.LONG -> writeLong(0)
        Kind.FLOAT -> writeFloat(0f)
        Kind.DOUBLE -> writeDouble(0.0)
        Kind.STRING -> writeString("")
        Kind.BYTES -> writeBytes(ByteArray(0))
        Kind.FIXED -> writeFixed(ByteArray(call.arg.toInt()))
        Kind.ENUM -> writeEnum(call.arg.toInt())
        Kind.INDEX -> writeIndex(call.arg.toInt())
        Kind.ARRAY_START -> writeArrayStart(call.arg)
        Kind.MAP_START -> writeMapStart(call.arg)
        Kind.START_ITEM -> startItem()
        Kind.ARRAY_END -> writeArrayEnd()
        Kind.MAP_END -> writeMapEnd()
        Kind.ARRAY_NEXT, Kind.MAP_NEXT -> error("not an encoder call: $call")
    }
}

private fun AvroBinaryDecoder.apply(call: Call) {
    when (call.kind) {
        Kind.NULL -> readNull()
        Kind.BOOLEAN -> readBoolean()
        Kind.INT -> readInt()
        Kind.LONG -> readLong()
        Kind.FLOAT -> readFloat()
        Kind.DOUBLE -> readDouble()
        Kind.STRING -> readString()
        Kind.BYTES -> readBytes()
        Kind.FIXED -> readFixed(call.arg.toInt())
        Kind.ENUM -> readEnum()
        Kind.INDEX -> readIndex()
        Kind.ARRAY_START -> readArrayStart()
        Kind.MAP_START -> readMapStart()
        Kind.ARRAY_NEXT -> arrayNext()
        Kind.MAP_NEXT -> mapNext()
        Kind.START_ITEM, Kind.ARRAY_END, Kind.MAP_END -> error("not a decoder call: $call")
    }
}

// ---- comparing ----

private class DifferentialReport {
    var compared = 0
    var bothAccepted = 0
    var bothRejected = 0
    val stricter = sortedMapOf<String, Int>()
    val unexplained = mutableListOf<String>()

    fun compare(
        entry: CorpusEntry,
        mutation: String,
        calls: List<Call>,
        apache: Throwable?,
        avro4k: Avro4kResult,
        rerunAvro4k: (List<Call>) -> Avro4kResult,
    ) {
        compared++
        val apacheAccepts = apache == null
        val avro4kAccepts = avro4k == Avro4kResult.Accepted
        when {
            apacheAccepts && avro4kAccepts -> bothAccepted++

            !apacheAccepts && !avro4kAccepts -> bothRejected++

            avro4kAccepts -> unexplained += "${entry.name} [$mutation]: Apache rejects ($apache) but avro4k accepts $calls"

            else -> {
                val reason = stricterReason(avro4k, calls, rerunAvro4k)
                if (reason == null) {
                    unexplained += "${entry.name} [$mutation]: Apache accepts but avro4k rejects ($avro4k) $calls"
                } else {
                    stricter.merge(reason, 1, Int::plus)
                }
            }
        }
    }

    fun print(side: String) {
        println("Validating $side differential: $compared mutated sequences, $bothAccepted accepted by both, $bothRejected rejected by both, only rejected by avro4k: $stricter")
        unexplained.take(20).forEach { println("  UNEXPLAINED $it") }
    }
}

/** Why avro4k rejects a sequence Apache accepts, if it is one of the intended stricter rules. */
private fun stricterReason(
    avro4k: Avro4kResult,
    calls: List<Call>,
    rerunAvro4k: (List<Call>) -> Avro4kResult,
): String? {
    when (avro4k) {
        is Avro4kResult.RejectedAtEnd -> if (avro4k.message.startsWith("Incomplete datum")) return "incomplete datum"

        is Avro4kResult.RejectedAt -> {
            if ("after the end of the datum" in avro4k.message) {
                // the datum before this call is complete and valid: Apache only accepts the rest as a next datum
                if (rerunAvro4k(calls.subList(0, avro4k.index)) == Avro4kResult.Accepted) return "call after the end of the datum"
                return null
            }
            // an item where the collection expects its next startItem(), end or block, or the reverse
            val call = avro4k.message.removePrefix("Invalid call ").substringBefore(' ')
            val expected = avro4k.message.substringAfter(": expected ")
            if (call in COLLECTION_CALLS || ITEM_EXPECTATIONS.any { expected.startsWith(it) }) return "collection item count"
        }

        Avro4kResult.Accepted -> {}
    }
    return null
}

private val COLLECTION_CALLS = setOf("startItem()", "writeArrayEnd()", "writeMapEnd()", "arrayNext()", "mapNext()")

private val ITEM_EXPECTATIONS =
    listOf("startItem()", "writeArrayEnd()", "writeMapEnd()", "arrayNext()", "mapNext()", "the next item", "a non-negative item count")

// ---- mutations ----

private fun mutants(
    calls: List<Call>,
    decoder: Boolean,
): List<Pair<String, List<Call>>> {
    val result = mutableListOf<Pair<String, List<Call>>>()

    fun replaced(
        index: Int,
        call: Call,
    ) = calls.toMutableList().also { it[index] = call }
    for (i in calls.indices) {
        val call = calls[i]
        result += "drop $i" to calls.toMutableList().also { it.removeAt(i) }
        result += "duplicate $i" to calls.toMutableList().also { it.add(i, call) }
        swappedKind(call.kind, decoder)?.let { result += "swap kind $i to $it" to replaced(i, call.copy(kind = it)) }
        if (call.kind in WITH_ARG) {
            result += "arg+1 at $i" to replaced(i, call.copy(arg = call.arg + 1))
            if (call.kind != Kind.FIXED || call.arg > 0) result += "arg-1 at $i" to replaced(i, call.copy(arg = call.arg - 1))
        }
    }
    return result
}

private fun swappedKind(
    kind: Kind,
    decoder: Boolean,
): Kind? =
    when (kind) {
        Kind.NULL -> Kind.BOOLEAN
        Kind.BOOLEAN -> Kind.NULL
        Kind.INT -> Kind.LONG
        Kind.LONG -> Kind.INT
        Kind.FLOAT -> Kind.DOUBLE
        Kind.DOUBLE -> Kind.FLOAT
        Kind.STRING -> Kind.BYTES
        Kind.BYTES -> Kind.STRING
        Kind.FIXED -> if (decoder) Kind.ENUM else Kind.BYTES
        Kind.ENUM -> Kind.INT
        Kind.INDEX -> Kind.ENUM
        Kind.ARRAY_START -> Kind.MAP_START
        Kind.MAP_START -> Kind.ARRAY_START
        Kind.ARRAY_END -> Kind.MAP_END
        Kind.MAP_END -> Kind.ARRAY_END
        Kind.ARRAY_NEXT -> Kind.MAP_NEXT
        Kind.MAP_NEXT -> Kind.ARRAY_NEXT
        Kind.START_ITEM -> null
    }

// ---- the corpus ----

private const val RANDOM_SEQUENCES_PER_SCHEMA = 4

private fun buildCorpus(): List<CorpusEntry> {
    val corpus = mutableListOf<CorpusEntry>()
    val random = Random(20261009)

    // every schema of the test resources
    val resources = File(ValidatingCodecDifferentialTest::class.java.getResource("/basic.json")!!.toURI()).parentFile
    for (file in resources.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }) {
        val apacheSchema = runCatching { Schema.Parser().parse(file) }.getOrNull() ?: continue
        val entry = CorpusEntry(file.name, apacheToAvro4kUncached(apacheSchema), apacheSchema)
        repeat(RANDOM_SEQUENCES_PER_SCHEMA) { entry.addGenerated(random) }
        corpus += entry
    }

    // the models, with sequences recorded from real values, and generated ones
    corpus +=
        modelEntry(
            "EveryType",
            serializer<ValidatingCodecDifferentialTest.EveryType>(),
            random,
            ValidatingCodecDifferentialTest.EveryType(
                nullable = "x",
                bool = true,
                int = -3,
                long = 1L shl 40,
                float = 1.5f,
                double = -2.25,
                string = "héllo",
                bytes = byteArrayOf(1, 2, 3),
                fixed = byteArrayOf(9, 8, 7, 6),
                enum = ValidatingCodecDifferentialTest.DiffEnum.B,
                list = listOf(1, 2),
                map = mapOf("k" to 5L, "l" to 6L),
                nested = ValidatingCodecDifferentialTest.Nested("n", 1),
                nestedList = listOf(ValidatingCodecDifferentialTest.Nested("a", null), ValidatingCodecDifferentialTest.Nested("b", 2)),
                mapOfLists = mapOf("m" to listOf(null, ValidatingCodecDifferentialTest.Nested("c", 3)), "e" to emptyList()),
                sealed = ValidatingCodecDifferentialTest.DiffSealed.B("y", listOf(1, 2, 3)),
                char = 'c'
            ),
            ValidatingCodecDifferentialTest.EveryType(
                nullable = null,
                bool = false,
                int = 0,
                long = 0,
                float = 0f,
                double = 0.0,
                string = "",
                bytes = byteArrayOf(),
                fixed = byteArrayOf(0, 0, 0, 0),
                enum = ValidatingCodecDifferentialTest.DiffEnum.C,
                list = emptyList(),
                map = emptyMap(),
                nested = null,
                nestedList = emptyList(),
                mapOfLists = emptyMap(),
                sealed = ValidatingCodecDifferentialTest.DiffSealed.A(1),
                char = 'z'
            )
        )
    corpus +=
        modelEntry(
            "Recursive",
            serializer<ValidatingCodecDifferentialTest.Recursive>(),
            random,
            ValidatingCodecDifferentialTest.Recursive(
                1,
                ValidatingCodecDifferentialTest.Recursive(2, null, emptyList()),
                listOf(ValidatingCodecDifferentialTest.Recursive(3, null, listOf(ValidatingCodecDifferentialTest.Recursive(4, null, emptyList()))))
            )
        )
    return corpus
}

private fun <T> modelEntry(
    name: String,
    serializer: KSerializer<T>,
    random: Random,
    vararg values: T,
): CorpusEntry {
    val schema = Avro.schema(serializer)
    val entry = CorpusEntry(name, schema, avro4kToApacheUncached(schema))
    for (value in values) {
        val encoder = RecordingAbiEncoder()
        Avro.encodeWithBinaryEncoder(schema, serializer, value, encoder)
        entry.encoderSequences += encoder.calls
        val decoder = RecordingAbiDecoder(KotlinxIoDecoder(Buffer().apply { write(encoder.bytes()) }))
        Avro.decodeWithBinaryDecoder(schema, serializer, decoder)
        entry.decoderSequences += decoder.calls
    }
    repeat(RANDOM_SEQUENCES_PER_SCHEMA) { entry.addGenerated(random) }
    return entry
}

/** A valid sequence for each side, from one random value of the schema; the decoder splits collections into blocks. */
private fun CorpusEntry.addGenerated(random: Random) {
    val encoder = mutableListOf<Call>()
    val decoder = mutableListOf<Call>()
    generate(schema, random, encoder, decoder, depth = 0)
    encoderSequences += encoder
    decoderSequences += decoder
}

private const val MAX_DEPTH = 6

private fun generate(
    schema: AvroSchema,
    random: Random,
    encoder: MutableList<Call>,
    decoder: MutableList<Call>,
    depth: Int,
) {
    fun both(call: Call) {
        encoder += call
        decoder += call
    }
    when (schema.type) {
        Type.NULL -> both(Call(Kind.NULL))

        Type.BOOLEAN -> both(Call(Kind.BOOLEAN))

        Type.INT -> both(Call(Kind.INT))

        Type.LONG -> both(Call(Kind.LONG))

        Type.FLOAT -> both(Call(Kind.FLOAT))

        Type.DOUBLE -> both(Call(Kind.DOUBLE))

        Type.STRING -> both(Call(Kind.STRING))

        Type.BYTES -> both(Call(Kind.BYTES))

        Type.FIXED -> both(Call(Kind.FIXED, (schema as FixedSchema).size.toLong()))

        Type.ENUM -> both(Call(Kind.ENUM, random.nextInt((schema as EnumSchema).symbols.size).toLong()))

        Type.UNION -> {
            val types = (schema as UnionSchema).types
            // deep down, prefer a branch that cannot recurse, to end the value
            val leaves = types.indices.filter { types[it].type !in NESTING_TYPES }
            val index = if (depth >= MAX_DEPTH && leaves.isNotEmpty()) leaves.random(random) else random.nextInt(types.size)
            both(Call(Kind.INDEX, index.toLong()))
            generate(types[index], random, encoder, decoder, depth + 1)
        }

        Type.RECORD -> (schema as RecordSchema).fields.forEach { generate(it.schema, random, encoder, decoder, depth + 1) }

        Type.ARRAY -> generateCollection(isMap = false, (schema as ArraySchema).elementSchema, random, encoder, decoder, depth)

        Type.MAP -> generateCollection(isMap = true, (schema as MapSchema).valueSchema, random, encoder, decoder, depth)
    }
}

private val NESTING_TYPES = setOf(Type.RECORD, Type.ARRAY, Type.MAP)

private fun generateCollection(
    isMap: Boolean,
    itemSchema: AvroSchema,
    random: Random,
    encoder: MutableList<Call>,
    decoder: MutableList<Call>,
    depth: Int,
) {
    val count = if (depth >= MAX_DEPTH) 0 else random.nextInt(4)
    encoder += Call(if (isMap) Kind.MAP_START else Kind.ARRAY_START, count.toLong())
    var leftInBlock = 0
    for (i in 0 until count) {
        if (leftInBlock == 0) {
            leftInBlock = 1 + random.nextInt(count - i)
            val kind = if (i == 0) {
                if (isMap) {
                    Kind.MAP_START
                } else {
                    Kind.ARRAY_START
                }
            } else if (isMap) {
                Kind.MAP_NEXT
            } else {
                Kind.ARRAY_NEXT
            }
            decoder += Call(kind, leftInBlock.toLong())
        }
        encoder += Call(Kind.START_ITEM)
        if (isMap) {
            encoder += Call(Kind.STRING)
            decoder += Call(Kind.STRING)
        }
        generate(itemSchema, random, encoder, decoder, depth + 1)
        leftInBlock--
    }
    decoder += Call(
        if (count == 0) {
            if (isMap) {
                Kind.MAP_START
            } else {
                Kind.ARRAY_START
            }
        } else if (isMap) {
            Kind.MAP_NEXT
        } else {
            Kind.ARRAY_NEXT
        },
        0
    )
    encoder += Call(if (isMap) Kind.MAP_END else Kind.ARRAY_END)
}

// ---- codecs that only record, or do nothing ----

/** Records the calls of avro4k's direct encoder, and writes them for the decoder side. */
private class RecordingAbiEncoder : AvroBinaryEncoder() {
    val calls = mutableListOf<Call>()
    private val buffer = Buffer()
    private val delegate = KotlinxIoEncoder(buffer)

    fun bytes(): ByteArray = buffer.peek().readByteArray()

    override fun writeNull() = record(Call(Kind.NULL)) { delegate.writeNull() }

    override fun writeBoolean(value: Boolean) = record(Call(Kind.BOOLEAN)) { delegate.writeBoolean(value) }

    override fun writeInt(value: Int) = record(Call(Kind.INT)) { delegate.writeInt(value) }

    override fun writeLong(value: Long) = record(Call(Kind.LONG)) { delegate.writeLong(value) }

    override fun writeFloat(value: Float) = record(Call(Kind.FLOAT)) { delegate.writeFloat(value) }

    override fun writeDouble(value: Double) = record(Call(Kind.DOUBLE)) { delegate.writeDouble(value) }

    override fun writeString(value: String) = record(Call(Kind.STRING)) { delegate.writeString(value) }

    override fun writeString(utf8: ByteArray) = record(Call(Kind.STRING)) { delegate.writeString(utf8) }

    override fun writeBytes(value: ByteArray) = record(Call(Kind.BYTES)) { delegate.writeBytes(value) }

    override fun writeFixed(value: ByteArray) = record(Call(Kind.FIXED, value.size.toLong())) { delegate.writeFixed(value) }

    override fun writeEnum(index: Int) = record(Call(Kind.ENUM, index.toLong())) { delegate.writeEnum(index) }

    override fun writeIndex(index: Int) = record(Call(Kind.INDEX, index.toLong())) { delegate.writeIndex(index) }

    override fun writeArrayStart(count: Long) = record(Call(Kind.ARRAY_START, count)) { delegate.writeArrayStart(count) }

    override fun writeMapStart(count: Long) = record(Call(Kind.MAP_START, count)) { delegate.writeMapStart(count) }

    override fun startItem() = record(Call(Kind.START_ITEM)) { delegate.startItem() }

    override fun writeArrayEnd() = record(Call(Kind.ARRAY_END)) { delegate.writeArrayEnd() }

    override fun writeMapEnd() = record(Call(Kind.MAP_END)) { delegate.writeMapEnd() }

    private inline fun record(
        call: Call,
        forward: () -> Unit,
    ) {
        calls += call
        forward()
    }
}

/** Records the calls of avro4k's direct decoder with the values they returned. */
private class RecordingAbiDecoder(private val delegate: AvroBinaryDecoder) : AvroBinaryDecoder() {
    val calls = mutableListOf<Call>()

    private fun <T> record(
        kind: Kind,
        value: T,
        arg: Long = 0,
    ): T {
        calls += Call(kind, arg)
        return value
    }

    override fun readNull() = record(Kind.NULL, delegate.readNull())

    override fun readBoolean() = record(Kind.BOOLEAN, delegate.readBoolean())

    override fun readInt() = record(Kind.INT, delegate.readInt())

    override fun readLong() = record(Kind.LONG, delegate.readLong())

    override fun readFloat() = record(Kind.FLOAT, delegate.readFloat())

    override fun readDouble() = record(Kind.DOUBLE, delegate.readDouble())

    override fun readString() = record(Kind.STRING, delegate.readString())

    override fun readStringBytes() = record(Kind.STRING, delegate.readStringBytes())

    override fun skipString() = record(Kind.STRING, delegate.skipString())

    override fun readBytes() = record(Kind.BYTES, delegate.readBytes())

    override fun skipBytes() = record(Kind.BYTES, delegate.skipBytes())

    override fun readFixed(size: Int) = record(Kind.FIXED, delegate.readFixed(size), size.toLong())

    override fun skipFixed(size: Int) = record(Kind.FIXED, delegate.skipFixed(size), size.toLong())

    override fun readEnum() = delegate.readEnum().also { record(Kind.ENUM, it, it.toLong()) }

    override fun readIndex() = delegate.readIndex().also { record(Kind.INDEX, it, it.toLong()) }

    override fun readArrayStart() = delegate.readArrayStart().also { record(Kind.ARRAY_START, it, it) }

    override fun arrayNext() = delegate.arrayNext().also { record(Kind.ARRAY_NEXT, it, it) }

    override fun readMapStart() = delegate.readMapStart().also { record(Kind.MAP_START, it, it) }

    override fun mapNext() = delegate.mapNext().also { record(Kind.MAP_NEXT, it, it) }

    override fun skipArray(): Long = error("the models are decoded without skipping")

    override fun skipMap(): Long = error("the models are decoded without skipping")
}

private object NoopAbiEncoder : AvroBinaryEncoder() {
    override fun writeNull() {}

    override fun writeBoolean(value: Boolean) {}

    override fun writeInt(value: Int) {}

    override fun writeLong(value: Long) {}

    override fun writeFloat(value: Float) {}

    override fun writeDouble(value: Double) {}

    override fun writeString(value: String) {}

    override fun writeString(utf8: ByteArray) {}

    override fun writeBytes(value: ByteArray) {}

    override fun writeFixed(value: ByteArray) {}

    override fun writeEnum(index: Int) {}

    override fun writeIndex(index: Int) {}

    override fun writeArrayStart(count: Long) {}

    override fun writeMapStart(count: Long) {}

    override fun startItem() {}

    override fun writeArrayEnd() {}

    override fun writeMapEnd() {}
}

/** Returns [next] from every index and count read, and empty values otherwise. */
private class ScriptedAbiDecoder : AvroBinaryDecoder() {
    var next = 0L

    override fun readNull() {}

    override fun readBoolean() = false

    override fun readInt() = 0

    override fun readLong() = 0L

    override fun readFloat() = 0f

    override fun readDouble() = 0.0

    override fun readString() = ""

    override fun readStringBytes() = ByteArray(0)

    override fun skipString() {}

    override fun readBytes() = ByteArray(0)

    override fun skipBytes() {}

    override fun readFixed(size: Int) = ByteArray(size)

    override fun skipFixed(size: Int) {}

    override fun readEnum() = next.toInt()

    override fun readIndex() = next.toInt()

    override fun readArrayStart() = next

    override fun arrayNext() = next

    override fun skipArray() = next

    override fun readMapStart() = next

    override fun mapNext() = next

    override fun skipMap() = next
}

private object NoopApacheEncoder : org.apache.avro.io.Encoder() {
    override fun flush() {}

    override fun writeNull() {}

    override fun writeBoolean(b: Boolean) {}

    override fun writeInt(n: Int) {}

    override fun writeLong(n: Long) {}

    override fun writeFloat(f: Float) {}

    override fun writeDouble(d: Double) {}

    override fun writeString(utf8: Utf8) {}

    override fun writeBytes(bytes: ByteBuffer) {}

    override fun writeBytes(
        bytes: ByteArray,
        start: Int,
        len: Int,
    ) {}

    override fun writeFixed(
        bytes: ByteArray,
        start: Int,
        len: Int,
    ) {}

    override fun writeEnum(e: Int) {}

    override fun writeArrayStart() {}

    override fun setItemCount(itemCount: Long) {}

    override fun startItem() {}

    override fun writeArrayEnd() {}

    override fun writeMapStart() {}

    override fun writeMapEnd() {}

    override fun writeIndex(unionIndex: Int) {}
}

private class ScriptedApacheDecoder : org.apache.avro.io.Decoder() {
    var next = 0L

    override fun readNull() {}

    override fun readBoolean() = false

    override fun readInt() = 0

    override fun readLong() = 0L

    override fun readFloat() = 0f

    override fun readDouble() = 0.0

    override fun readString(old: Utf8?) = Utf8()

    override fun readString() = ""

    override fun skipString() {}

    override fun readBytes(old: ByteBuffer?): ByteBuffer = ByteBuffer.allocate(0)

    override fun skipBytes() {}

    override fun readFixed(
        bytes: ByteArray,
        start: Int,
        length: Int,
    ) {}

    override fun skipFixed(length: Int) {}

    override fun readEnum() = next.toInt()

    override fun readArrayStart() = next

    override fun arrayNext() = next

    override fun skipArray() = next

    override fun readMapStart() = next

    override fun mapNext() = next

    override fun skipMap() = next

    override fun readIndex() = next.toInt()
}