package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.ResolvedSchema
import com.github.avrokotlin.avro4k.fixtures.jvmAndNativeOrJs
import com.github.avrokotlin.avro4k.internal.codec.ByteArrayEncoder
import com.github.avrokotlin.avro4k.internal.decoder.JsonDefaultDecoder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.test.Test

/**
 * A double becomes a float only when the float holds the very same number, on every platform alike, and a float becomes
 * a double, or 4 bytes, the same way everywhere.
 *
 * Kotlin/JS stores a `Float` as a JS number and its `Double.toFloat()` does not round, so a check written as "the double
 * survives a round trip through a float" would pass there for every double. The JVM and native round to 32 bits, and are
 * the reference: these tests run on the three platforms.
 *
 * Expected floats are built from their bits with [Float.fromBits], which rounds on every platform, and compared through
 * the bits of their double value, so that a float holding more precision than 32 bits fails even on JS, and `-0.0` is
 * told apart from `0.0`. Float literals are not used: on JS, `0.1f` is the double `0.1`.
 */
class FloatNarrowingTest {
    @Test
    fun `a double exact as a float is written to a float schema`() {
        for ((value, floatBits) in EXACT_DOUBLES) {
            for ((schema, branch) in FLOAT_SCHEMAS) {
                val expected =
                    ByteArrayEncoder().apply {
                        if (branch != null) writeIndex(branch)
                        writeFloat(Float.fromBits(floatBits))
                    }.toByteArray()
                for (avro in AVROS) {
                    withClue("$value as $schema") {
                        avro.encodeToByteArray(schema, Double.serializer(), value) shouldBe expected
                    }
                }
            }
        }
    }

    @Test
    fun `a double not exact as a float is rejected by a float schema`() {
        for (value in INEXACT_DOUBLES) {
            for ((schema, _) in FLOAT_SCHEMAS) {
                for (avro in AVROS) {
                    withClue("$value as $schema") {
                        shouldThrow<AvroSchemaMismatchException> { avro.encodeToByteArray(schema, Double.serializer(), value) }
                    }
                }
            }
        }
        shouldThrow<AvroSchemaMismatchException> { Avro.encodeToByteArray(FLOAT, Double.serializer(), 0.1) }.message shouldBe
            "Value 0.1 is not a valid Float"
    }

    @Test
    fun `a float field reads a writer double only when it is exact as a float`() {
        for ((value, floatBits) in EXACT_DOUBLES) {
            for ((schema, branch) in DOUBLE_SCHEMAS) {
                val bytes = doubleBytes(value, branch)
                for (avro in AVROS) {
                    withClue("$value from $schema") {
                        avro.decodeFromByteArray(schema, Float.serializer(), bytes).shouldBeFloat(floatBits)
                    }
                }
            }
        }
        for (value in INEXACT_DOUBLES) {
            for ((schema, branch) in DOUBLE_SCHEMAS) {
                val bytes = doubleBytes(value, branch)
                for (avro in AVROS) {
                    withClue("$value from $schema") {
                        shouldThrow<AvroSchemaMismatchException> { avro.decodeFromByteArray(schema, Float.serializer(), bytes) }
                    }
                }
            }
        }
    }

    @Test
    fun `a float field takes a double default only when it is exact as a float`() {
        for ((text, floatBits) in EXACT_DEFAULTS) {
            for (schema in listOf(DOUBLE, union(NULL, DOUBLE))) {
                withClue("$text as $schema") {
                    JsonDefaultDecoder(Avro, Json.parseToJsonElement(text), schema).decodeFloat().shouldBeFloat(floatBits)
                }
            }
        }
        for (text in INEXACT_DEFAULTS) {
            for (schema in listOf(DOUBLE, union(NULL, DOUBLE))) {
                withClue("$text as $schema") {
                    shouldThrow<AvroSchemaMismatchException> {
                        JsonDefaultDecoder(Avro, Json.parseToJsonElement(text), schema).decodeFloat()
                    }
                }
            }
        }
        val exactWriter = AvroSchema.RecordSchema(Name("ExactFloatDefault"), emptyList())
        Avro.decodeFromByteArray(exactWriter, ExactFloatDefault.serializer(), byteArrayOf()).value.shouldBeFloat(HALF_BITS)
        val inexactWriter = AvroSchema.RecordSchema(Name("InexactFloatDefault"), emptyList())
        shouldThrow<AvroSchemaMismatchException> {
            Avro.decodeFromByteArray(inexactWriter, InexactFloatDefault.serializer(), byteArrayOf())
        }
    }

    /**
     * Not a narrowing check: the text is parsed by `String.toFloat()`, which rounds to the nearest float on the JVM and
     * native but not on Kotlin/JS. Pinned as it is on each platform. Rounding it on JS would change the text a stringable
     * `Float` writes back after a read there, which the golden fixture of floating-point strings pins.
     */
    @Test
    fun `a float read from text is the nearest float except on JS`() {
        for ((text, floatBits) in ROUNDED_TEXTS) {
            val expected = jvmAndNativeOrJs(Float.fromBits(floatBits).toDouble(), js = text.toDouble()).toRawBits()
            withClue("binary string $text") {
                val bytes = ByteArrayEncoder().apply { writeString(text) }.toByteArray()
                for (avro in AVROS) {
                    avro.decodeFromByteArray(STRING, Float.serializer(), bytes).toDouble().toRawBits() shouldBe expected
                }
            }
            for (schema in listOf(FLOAT, STRING)) {
                val json = if (schema === STRING) "\"$text\"" else text
                withClue("default $json as $schema") {
                    JsonDefaultDecoder(Avro, Json.parseToJsonElement(json), schema).decodeFloat().toDouble().toRawBits() shouldBe expected
                }
            }
            withClue("default $text as $FLOAT into a double") {
                JsonDefaultDecoder(Avro, Json.parseToJsonElement(text), FLOAT).decodeDouble().toRawBits() shouldBe expected
            }
        }
    }

    @Test
    fun `a double field reads a float writer as the value of the float`() {
        for ((text, floatBits) in ROUNDED_TEXTS) {
            val float = Float.fromBits(floatBits)
            val bytes = ByteArrayEncoder().apply { writeFloat(float) }.toByteArray()
            for (avro in AVROS) {
                withClue("binary float $text") {
                    avro.decodeFromByteArray(FLOAT, Double.serializer(), bytes).toRawBits() shouldBe float.toDouble().toRawBits()
                }
            }
        }
    }

    @Test
    fun `a string written to a float schema gives the bytes of the rounded float`() {
        for ((text, floatBits) in ROUNDED_TEXTS) {
            val expected = ByteArrayEncoder().apply { writeFloat(Float.fromBits(floatBits)) }.toByteArray()
            for (avro in AVROS) {
                withClue(text) {
                    avro.encodeToByteArray(FLOAT, String.serializer(), text) shouldBe expected
                }
            }
        }
    }

    /** A float whose reader schema is a double, so that its default is read as a double and narrowed to a float. */
    private object FloatWithDoubleSchema : KSerializer<Float> {
        override val descriptor = PrimitiveSerialDescriptor("FloatWithDoubleSchema", PrimitiveKind.DOUBLE)

        override fun serialize(
            encoder: Encoder,
            value: Float,
        ) = encoder.encodeFloat(value)

        override fun deserialize(decoder: Decoder): Float = decoder.decodeFloat()
    }

    @Serializable
    @SerialName("ExactFloatDefault")
    private data class ExactFloatDefault(
        @AvroDefault("0.5") @Serializable(with = FloatWithDoubleSchema::class) val value: Float,
    )

    @Serializable
    @SerialName("InexactFloatDefault")
    private data class InexactFloatDefault(
        @AvroDefault("0.1") @Serializable(with = FloatWithDoubleSchema::class) val value: Float,
    )

    private companion object {
        val AVROS = listOf(Avro, Avro { validateSerialization = true })

        val NULL = AvroSchema.NullSchema()
        val FLOAT = AvroSchema.FloatSchema()
        val DOUBLE = AvroSchema.DoubleSchema()
        val STRING = AvroSchema.StringSchema()

        const val HALF_BITS = 0x3f000000

        /** The largest finite float, as a double. `Float.MAX_VALUE.toDouble()` is not used: on JS it is `3.4028235E38`. */
        val MAX_FLOAT = Float.fromBits(0x7f7fffff).toDouble()

        /** Each double that a float holds exactly, with the bits of that float. */
        val EXACT_DOUBLES =
            listOf(
                0.5 to HALF_BITS,
                1.0 to 0x3f800000,
                -0.0 to 0x80000000.toInt(),
                MAX_FLOAT to 0x7f7fffff,
                -MAX_FLOAT to 0xff7fffff.toInt(),
                Float.fromBits(1).toDouble() to 1,
                Double.POSITIVE_INFINITY to 0x7f800000,
                Double.NEGATIVE_INFINITY to 0xff800000.toInt()
            )

        /**
         * Doubles no float holds: rounded to a nearby float (0.1), to zero (1e-50), to the largest float or to infinity
         * (just above the largest float, 1e40). NaN is rejected too: it is never equal to itself, so the exactness check
         * fails for it on every platform, as it always did on the JVM.
         */
        val INEXACT_DOUBLES = listOf(0.1, 1e-50, MAX_FLOAT.nextUp(), (-MAX_FLOAT).nextDown(), 1e40, Double.NaN)

        val EXACT_DEFAULTS =
            listOf(
                "0.5" to HALF_BITS,
                "1.0" to 0x3f800000,
                "-0.0" to 0x80000000.toInt(),
                "3.4028234663852886E38" to 0x7f7fffff,
                "1.401298464324817E-45" to 1
            )

        val INEXACT_DEFAULTS = listOf("0.1", "1e-50", "3.402823466385289E38", "1e40")

        /** Decimal texts and the float each one rounds to: the nearest float, zero, infinity. */
        val ROUNDED_TEXTS =
            listOf(
                "0.1" to 0x3dcccccd,
                "1.5" to 0x3fc00000,
                "1e-50" to 0,
                "1e40" to 0x7f800000
            )

        /** Float schemas a double is written to, with the index of the float branch in a union. */
        val FLOAT_SCHEMAS: List<Pair<AvroSchema, Int?>> =
            listOf(
                FLOAT to null,
                union(NULL, FLOAT) to 1,
                union(STRING, FLOAT) to 1
            )

        /** Writer schemas of a double read into a float field, with the index of the double branch in a union. */
        val DOUBLE_SCHEMAS: List<Pair<AvroSchema, Int?>> =
            listOf(
                DOUBLE to null,
                union(NULL, DOUBLE) to 1
            )

        fun union(vararg types: ResolvedSchema) = AvroSchema.UnionSchema(*types)

        fun doubleBytes(
            value: Double,
            branch: Int?,
        ) = ByteArrayEncoder().apply {
            if (branch != null) writeIndex(branch)
            writeDouble(value)
        }.toByteArray()

        /** Compares through the bits of the double value: exact, sign of zero included, and not rounded by the comparison. */
        fun Float.shouldBeFloat(expectedBits: Int) {
            toDouble().toRawBits() shouldBe Float.fromBits(expectedBits).toDouble().toRawBits()
        }
    }
}