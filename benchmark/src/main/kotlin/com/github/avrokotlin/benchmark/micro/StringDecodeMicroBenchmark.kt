package com.github.avrokotlin.benchmark.micro

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromByteArray
import com.github.avrokotlin.benchmark.internal.CoreSchema
import com.github.avrokotlin.benchmark.internal.coreSchema
import com.github.avrokotlin.benchmark.internal.encodeWith
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Isolates the decoding of STRING values by what they hold, which [StringFieldMicroBenchmark] does not vary: its strings
 * are all 16 ASCII characters.
 *
 * The UTF-8 decoding cost depends on the characters: ASCII is one byte per character, and a JVM stores it, like the
 * rest of Latin-1 (two bytes in UTF-8), one byte per character; CJK characters take three bytes in UTF-8 and two in
 * the decoded string. Every record holds [STRING_DECODE_FIELDS] strings of [length] characters drawn from [content].
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = MICRO_WARMUP_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = MICRO_MEASUREMENT_ITERATIONS, time = 1, timeUnit = TimeUnit.SECONDS)
internal class StringDecodeMicroBenchmark {
    @Param("ascii", "latin1", "cjk")
    @JvmField
    final var content: String = "ascii"

    @Param("16", "256")
    @JvmField
    final var length: Int = 16

    lateinit var schema: CoreSchema
    lateinit var data: ByteArray

    @Setup
    fun setup() {
        val alphabet =
            when (content) {
                "ascii" -> ASCII_ALPHABET
                "latin1" -> LATIN1_ALPHABET
                "cjk" -> CJK_ALPHABET
                else -> error("Unknown content $content")
            }
        val random = Random(STRING_DECODE_SEED)
        val strings = List(STRING_DECODE_FIELDS) { String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] }) }
        schema = Avro.coreSchema<StringDecodeRecord>()
        data = Avro.encodeWith(schema, StringDecodeRecord(strings[0], strings[1], strings[2], strings[3], strings[4], strings[5], strings[6], strings[7]))
    }

    @Benchmark
    fun read(): StringDecodeRecord = Avro.decodeFromByteArray<StringDecodeRecord>(schema, data)
}

internal const val STRING_DECODE_FIELDS = 8

private const val STRING_DECODE_SEED = 20261015L

private const val ASCII_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

/** Accented Latin-1 letters only, so that every character takes two bytes in UTF-8. */
private const val LATIN1_ALPHABET = "àáâãäåæçèéêëìíîïñòóôõöøùúûüýÿÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÑÒÓÔÕÖØÙÚÛÜÝß"

private const val CJK_ALPHABET = "的一是不了人我在有他这中大来上国个到说们为子和你地出道也时年得就那要下以生会自着去之过家学"

@Serializable
internal data class StringDecodeRecord(
    val f0: String,
    val f1: String,
    val f2: String,
    val f3: String,
    val f4: String,
    val f5: String,
    val f6: String,
    val f7: String,
)
