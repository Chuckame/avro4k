package com.github.avrokotlin.avro4k.exceptions

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecimal
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.AvroMalformedDataException
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaGenerationException
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.AvroSingleObject
import com.github.avrokotlin.avro4k.AvroStringable
import com.github.avrokotlin.avro4k.internal.codec.KotlinxIoEncoder
import com.github.avrokotlin.avro4k.schema
import com.github.avrokotlin.avro4k.serializer.BigDecimalSerializer
import com.github.avrokotlin.avro4k.serializer.BigIntegerSerializer
import com.github.avrokotlin.avro4k.serializer.InstantToMicroSerializer
import com.github.avrokotlin.avro4k.serializer.LocalDateSerializer
import com.github.avrokotlin.avro4k.serializer.LocalTimeSerializer
import com.github.avrokotlin.avro4k.serializer.URLSerializer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.BigInteger
import java.net.MalformedURLException
import java.net.URL
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Period
import java.util.UUID
import kotlin.test.Test

/** The exception families for the JVM-only serializers, and the random-bytes decoding over them. */
class JvmAvroSerializationExceptionTest {
    @Test
    fun `random and corrupted bytes only fail with an AvroSerializationException on the JVM types`() {
        RandomBytesDecoding.run(COMMON_TARGETS + JVM_TARGETS, seed = 20261010, iterations = 20_000)
    }

    @Test
    fun `an invalid decimal annotation fails the schema generation with its path and cause`() {
        val error = shouldThrow<AvroSchemaGenerationException> { Avro.schema<InvalidDecimal>() }

        error.path shouldBe "$PACKAGE_NAME.InvalidDecimal.value"
        error.cause.shouldBeInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `a decoded value out of the range of a JVM type is a mismatch with the conversion's exception as the cause`() {
        // a negative time of day
        shouldThrow<AvroSchemaMismatchException> { Avro.decodeFromByteArray(Avro.schema(LocalTimeSerializer), LocalTimeSerializer, encoded { writeInt(-1) }) }
            .cause.shouldBeInstanceOf<DateTimeException>()
        shouldThrow<AvroSchemaMismatchException> { Avro.decodeFromByteArray(AvroSchema.StringSchema(), URLSerializer, encoded { writeString("no scheme") }) }
            .cause.shouldBeInstanceOf<MalformedURLException>()
        shouldThrow<AvroSchemaMismatchException> { Avro.decodeFromByteArray(AvroSchema.DoubleSchema(), BigIntegerSerializer, encoded { writeDouble(Double.NaN) }) }
            .cause.shouldBeInstanceOf<NumberFormatException>()
        shouldThrow<AvroSchemaMismatchException> { Avro.decodeFromByteArray(AvroSchema.LongSchema(), LocalDateSerializer, encoded { writeLong(Long.MAX_VALUE) }) }
            .cause.shouldBeInstanceOf<DateTimeException>()
    }

    @Test
    fun `an empty unscaled decimal value is malformed`() {
        val decimal = Avro.schema<WithDecimal>()
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(decimal, WithDecimal.serializer(), encoded { writeBytes(byteArrayOf()) }) }
        val bigDecimal = Avro.schema(BigDecimalSerializer)
        // the unscaled value as bytes, then the scale
        val emptyUnscaled = encoded {
            writeBytes(
                encoded {
                    writeBytes(byteArrayOf())
                    writeInt(2)
                }
            )
        }
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(bigDecimal, BigDecimalSerializer, emptyUnscaled) }
        val leftover = encoded {
            writeBytes(
                encoded {
                    writeBytes(byteArrayOf(1))
                    writeInt(2)
                    writeInt(3)
                }
            )
        }
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(bigDecimal, BigDecimalSerializer, leftover) }
        val noScale = encoded { writeBytes(encoded { writeBytes(byteArrayOf(1)) }) }
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(bigDecimal, BigDecimalSerializer, noScale) }
    }

    @Test
    fun `a single-object input with a bad header is malformed`() {
        val singleObject = AvroSingleObject({ Avro.schema<WithDecimal>() })
        shouldThrow<AvroMalformedDataException> { singleObject.decodeFromByteArray(WithDecimal.serializer(), byteArrayOf(0, 1, 2)) }
            .message shouldBe "Not a valid single-object avro format, bad magic byte"
        shouldThrow<AvroMalformedDataException> { singleObject.decodeFromByteArray(WithDecimal.serializer(), byteArrayOf(0xC3.toByte(), 2)) }
            .message shouldBe "Not a valid single-object avro format, bad version byte"
        shouldThrow<AvroMalformedDataException> { singleObject.decodeFromByteArray(WithDecimal.serializer(), byteArrayOf(0xC3.toByte(), 1, 2)) }
    }

    private fun encoded(write: KotlinxIoEncoder.() -> Unit): ByteArray {
        val buffer = Buffer()
        KotlinxIoEncoder(buffer).write()
        return buffer.readByteArray()
    }
}

private val JVM_TARGETS: List<DecodingTarget> =
    listOf(
        target("UUID", OneUuid.serializer(), OneUuid(UUID.randomUUID(), UUID.randomUUID())),
        target("decimal", WithDecimal.serializer(), WithDecimal(BigDecimal("12.34"))),
        target("fixed decimal", FixedDecimal.serializer(), FixedDecimal(BigDecimal("-12.34"))),
        target("big-decimal", OneBigDecimal.serializer(), OneBigDecimal(BigDecimal("123.456"))),
        target("string decimal", StringDecimal.serializer(), StringDecimal(BigDecimal("1.5"))),
        target("BigInteger", OneBigInteger.serializer(), OneBigInteger(BigInteger("123456789012345678901234567890"))),
        target("LocalDate", OneLocalDate.serializer(), OneLocalDate(LocalDate.of(2024, 2, 29))),
        target("LocalTime", OneLocalTime.serializer(), OneLocalTime(LocalTime.of(12, 34, 56))),
        target("LocalDateTime", OneLocalDateTime.serializer(), OneLocalDateTime(LocalDateTime.of(2024, 2, 29, 12, 0))),
        target("Instant", OneInstant.serializer(), OneInstant(Instant.ofEpochMilli(1_700_000_000_000), Instant.ofEpochMilli(1_700_000_000_000))),
        target("URL", OneUrl.serializer(), OneUrl(URL("https://example.com/a"))),
        target("Period", OnePeriod.serializer(), OnePeriod(Period.of(1, 2, 3))),
        target("java Duration", OneJavaDuration.serializer(), OneJavaDuration(java.time.Duration.ofMillis(1500))),
        convertingTarget("strings read as JVM types", JvmStrings.serializer(), JvmStrings("2024-02-29", "12:00", "2024-02-29T12:00:00Z", "1.5"), JvmConverted.serializer())
    )

@Serializable
private data class InvalidDecimal(
    @Contextual @AvroDecimal(scale = 2, precision = 0) val value: BigDecimal,
)

@Serializable
private data class WithDecimal(
    @Contextual @AvroDecimal(scale = 2, precision = 8) val value: BigDecimal,
)

@Serializable
private data class FixedDecimal(
    @Contextual @AvroDecimal(scale = 2, precision = 8) @AvroFixed(5) val value: BigDecimal,
)

@Serializable
private data class OneBigDecimal(
    @Contextual val value: BigDecimal,
)

@Serializable
private data class StringDecimal(
    @Contextual @AvroStringable val value: BigDecimal,
)

@Serializable
private data class OneBigInteger(
    @Contextual val value: BigInteger,
)

@Serializable
private data class OneUuid(
    @Contextual val string: UUID,
    @Contextual @AvroFixed(16) val fixed: UUID,
)

@Serializable
private data class OneLocalDate(
    @Contextual val value: LocalDate,
)

@Serializable
private data class OneLocalTime(
    @Contextual val value: LocalTime,
)

@Serializable
private data class OneLocalDateTime(
    @Contextual val value: LocalDateTime,
)

@Serializable
private data class OneInstant(
    @Contextual val millis: Instant,
    @Serializable(InstantToMicroSerializer::class) val micros: Instant,
)

@Serializable
private data class OneUrl(
    @Contextual val value: URL,
)

@Serializable
private data class OnePeriod(
    @Contextual val value: Period,
)

@Serializable
private data class OneJavaDuration(
    @Contextual val value: java.time.Duration,
)

@Serializable
private data class JvmStrings(val date: String, val time: String, val instant: String, val decimal: String)

@Serializable
private data class JvmConverted(
    @Contextual val date: LocalDate,
    @Contextual val time: LocalTime,
    @Contextual val instant: Instant,
    @Contextual val decimal: BigDecimal,
)