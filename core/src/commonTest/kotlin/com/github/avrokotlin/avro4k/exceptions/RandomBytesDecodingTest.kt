@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package com.github.avrokotlin.avro4k.exceptions

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSerializationException
import com.github.avrokotlin.avro4k.serializer.AvroDuration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.LongArraySerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Decoding bytes that are not a valid encoding must fail with an [AvroSerializationException], never with another
 * exception or an error: random bytes, and valid encodings with a few bytes overwritten, inserted, removed or cut off,
 * decoded with and without validation.
 */
class RandomBytesDecodingTest {
    @Test
    fun `random and corrupted bytes only fail with an AvroSerializationException`() {
        RandomBytesDecoding.run(COMMON_TARGETS, seed = 20261010, iterations = 2_000)
    }
}

/**
 * What one decoding target needs: the writer schema, a deserializer, and a valid encoding to corrupt.
 *
 * The targets never contain a collection of items that take no byte (`null`, a record without fields, a `fixed(0)`):
 * a few bytes can then validly claim billions of items, which decoding builds faithfully. Capping such items is a
 * separate hardening, deferred; every other item takes at least one byte, so a claimed count is bounded by the input.
 */
internal class DecodingTarget(
    val name: String,
    val writerSchema: AvroSchema,
    val deserializer: KSerializer<*>,
    val validBytes: ByteArray,
)

internal fun <T> target(
    name: String,
    serializer: KSerializer<T>,
    sample: T,
): DecodingTarget {
    val schema = Avro.schema(serializer.descriptor)
    return DecodingTarget(name, schema, serializer, Avro.encodeToByteArray(schema, serializer, sample))
}

/** A writer schema of [writerSerializer], read into another type with [deserializer]: avro4k's conversions. */
internal fun <W> convertingTarget(
    name: String,
    writerSerializer: KSerializer<W>,
    sample: W,
    deserializer: KSerializer<*>,
): DecodingTarget {
    val schema = Avro.schema(writerSerializer.descriptor)
    return DecodingTarget(name, schema, deserializer, Avro.encodeToByteArray(schema, writerSerializer, sample))
}

internal object RandomBytesDecoding {
    private const val MAX_INPUT_BYTES = 64
    private val validating = Avro { validateSerialization = true }

    /**
     * Bounded by construction: a fixed count of decodes, of inputs of at most [MAX_INPUT_BYTES] bytes, so it cannot
     * hang. A failure names the seed, the iteration, the target and the input, to replay it.
     */
    fun run(
        targets: List<DecodingTarget>,
        seed: Int,
        iterations: Int,
    ) {
        val random = Random(seed)
        repeat(iterations) { iteration ->
            val target = targets[random.nextInt(targets.size)]
            val input = if (random.nextInt(3) == 0) random.nextBytes(random.nextInt(48)) else corrupt(target.validBytes, random)
            for (avro in listOf(Avro, validating)) {
                try {
                    avro.decodeFromByteArray(target.writerSchema, target.deserializer, input)
                } catch (e: AvroSerializationException) {
                    // expected
                } catch (e: Throwable) {
                    fail(
                        "Seed $seed, iteration $iteration, target ${target.name}, validation ${avro !== Avro}, input ${input.toHexString()}: " +
                            "${e::class.simpleName}: ${e.message}",
                        e
                    )
                }
            }
        }
    }

    /** 1 to 3 changes: a byte overwritten, the tail cut off, a byte inserted, or a byte removed. */
    private fun corrupt(
        valid: ByteArray,
        random: Random,
    ): ByteArray {
        var bytes = valid
        repeat(1 + random.nextInt(3)) {
            bytes =
                when (random.nextInt(4)) {
                    0 -> if (bytes.isEmpty()) bytes else bytes.copyOf().also { it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
                    1 -> bytes.copyOf(random.nextInt(bytes.size + 1))
                    2 -> random.nextInt(bytes.size + 1).let { at -> bytes.copyOfRange(0, at) + random.nextInt(256).toByte() + bytes.copyOfRange(at, bytes.size) }
                    else -> if (bytes.isEmpty()) bytes else random.nextInt(bytes.size).let { at -> bytes.copyOfRange(0, at) + bytes.copyOfRange(at + 1, bytes.size) }
                }
        }
        return if (bytes.size > MAX_INPUT_BYTES) bytes.copyOf(MAX_INPUT_BYTES) else bytes
    }
}

internal val COMMON_TARGETS: List<DecodingTarget> =
    listOf(
        target("Int", Int.serializer(), -123456),
        target("Long", Long.serializer(), 1L shl 40),
        target("Float", Float.serializer(), 1.5f),
        target("Double", Double.serializer(), -2.25),
        target("Boolean", Boolean.serializer(), true),
        target("String", String.serializer(), "héllo"),
        target("Char", Char.serializer(), 'é'),
        target("Byte", Byte.serializer(), 12),
        target("Short", Short.serializer(), -1234),
        target("ByteArray", ByteArraySerializer(), byteArrayOf(1, 2, 3)),
        target("List<String>", ListSerializer(String.serializer()), listOf("a", "bc")),
        target("Map<String, Int>", MapSerializer(String.serializer(), Int.serializer()), mapOf("a" to 1, "b" to 2)),
        target("List<record>", ListSerializer(FuzzRecord.serializer()), listOf(FuzzRecord("x", 1, null), FuzzRecord("y", 2, 3L))),
        target("Set<Long>", SetSerializer(Long.serializer()), setOf(1L, 300L)),
        target("IntArray", IntArraySerializer(), intArrayOf(1, -2, 3)),
        target("LongArray", LongArraySerializer(), longArrayOf(1L, Long.MIN_VALUE)),
        target("Array<String>", ArraySerializer(String.serializer()), arrayOf("a", "b")),
        target("Map<String, List<Int>>", MapSerializer(String.serializer(), ListSerializer(Int.serializer())), mapOf("k" to listOf(1, 2))),
        target("Int?", Int.serializer().nullable, 7),
        target("String?", String.serializer().nullable, "s"),
        target("record?", FuzzRecord.serializer().nullable, FuzzRecord("z", 9, 1L)),
        target("sealed", FuzzShape.serializer(), FuzzShape.Square(4)),
        target("enum", FuzzSuit.serializer(), FuzzSuit.CLUBS),
        target("record of every kind", FuzzEveryKind.serializer(), FuzzEveryKind.SAMPLE),
        convertingTarget("strings read as numbers", FuzzStrings.serializer(), FuzzStrings("12", "-3000000000", "1.5", "true", "c", "HEARTS"), FuzzConverted.serializer())
    )

@Serializable
private data class FuzzRecord(val name: String, val value: Int, val optional: Long?)

@Serializable
private sealed interface FuzzShape {
    @Serializable
    data class Circle(val radius: Double) : FuzzShape

    @Serializable
    data class Square(val side: Int) : FuzzShape
}

@Serializable
private enum class FuzzSuit { SPADES, HEARTS, CLUBS }

@Serializable
private data class FuzzEveryKind(
    val uuid: Uuid,
    @AvroFixed(16) val fixedUuid: Uuid,
    val instant: Instant,
    val duration: Duration,
    val avroDuration: AvroDuration,
    val suit: FuzzSuit,
    val shape: FuzzShape?,
    val bytes: ByteArray,
    @AvroFixed(3) val fixed: ByteArray,
) {
    companion object {
        val SAMPLE =
            FuzzEveryKind(
                uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174000"),
                fixedUuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174000"),
                instant = Instant.fromEpochMilliseconds(1_700_000_000_000),
                duration = 1500.milliseconds,
                avroDuration = AvroDuration(1u, 2u, 3u),
                suit = FuzzSuit.HEARTS,
                shape = FuzzShape.Circle(1.5),
                bytes = byteArrayOf(1, 2),
                fixed = byteArrayOf(7, 8, 9)
            )
    }
}

/** Written as strings, read with avro4k's conversions from a string. */
@Serializable
private data class FuzzStrings(val int: String, val long: String, val double: String, val boolean: String, val char: String, val suit: String)

@Serializable
private data class FuzzConverted(val int: Int, val long: Long, val double: Double, val boolean: Boolean, val char: Char, val suit: FuzzSuit)