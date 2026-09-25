@file:OptIn(ExperimentalUuidApi::class)

package com.github.avrokotlin.avro4k

import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The same record, schema and bytes on every platform. The expected schema and bytes are what the JVM produces, where
 * they are checked against Apache Avro by the JVM test suite.
 */
class CrossPlatformSmokeTest {
    @Test
    fun `the schema of a record with every kind of field is the same as on the JVM`() {
        Avro.schema<SmokeRecord>().toJsonElement().toString() shouldBe EXPECTED_SCHEMA
    }

    @Test
    fun `a record with every kind of field encodes to the same bytes as on the JVM`() {
        Avro.encodeToByteArray(Avro.schema<SmokeRecord>(), SAMPLE).toHexString() shouldBe EXPECTED_HEX
    }

    @Test
    fun `a record with every kind of field decodes back from the bytes of the JVM`() {
        val decoded = Avro.decodeFromByteArray<SmokeRecord>(Avro.schema<SmokeRecord>(), EXPECTED_HEX.hexToByteArray())

        decoded.hash.bytes.toHexString() shouldBe SAMPLE.hash.bytes.toHexString()
        decoded.copy(hash = SAMPLE.hash) shouldBe SAMPLE
    }

    @Test
    fun `null fields and the other branch of the sealed type round trip`() {
        val value = SAMPLE.copy(nullableString = null, nullableInt = null, shape = Shape.Square(side = 7), color = Color.BLUE)
        val schema = Avro.schema<SmokeRecord>()

        Avro.decodeFromByteArray<SmokeRecord>(schema, Avro.encodeToByteArray(schema, value)).copy(hash = value.hash) shouldBe value
    }
}

@Serializable
private enum class Color { RED, GREEN, BLUE }

@Serializable
private sealed interface Shape {
    @Serializable
    data class Circle(val radius: Int) : Shape

    @Serializable
    data class Square(val side: Int) : Shape
}

@JvmInline
@Serializable
private value class Hash(
    @AvroFixed(4) val bytes: ByteArray,
)

@Serializable
private data class SmokeRecord(
    val int: Int,
    val long: Long,
    val boolean: Boolean,
    val float: Float,
    val double: Double,
    val string: String,
    val nullableString: String?,
    val nullableInt: Int?,
    val list: List<Int>,
    val map: Map<String, Long>,
    val color: Color,
    val shape: Shape,
    val hash: Hash,
    val uuid: Uuid,
    val instant: Instant,
    val duration: kotlin.time.Duration,
)

private val SAMPLE =
    SmokeRecord(
        int = -42,
        long = 1L shl 40,
        boolean = true,
        float = 1.5f,
        double = -0.25,
        string = "héllo",
        nullableString = "there",
        nullableInt = 7,
        list = listOf(1, -1, 300),
        map = mapOf("a" to 1L, "b" to -2L),
        color = Color.GREEN,
        shape = Shape.Circle(radius = 3),
        hash = Hash(byteArrayOf(1, 2, 3, 4)),
        uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174000"),
        instant = Instant.fromEpochMilliseconds(1_700_000_000_123),
        duration = 3.days + 4_000.milliseconds
    )

private const val EXPECTED_SCHEMA =
    "{\"type\":\"record\",\"name\":\"SmokeRecord\",\"namespace\":\"com.github.avrokotlin.avro4k\",\"fields\":[" +
        "{\"name\":\"int\",\"type\":\"int\"}," +
        "{\"name\":\"long\",\"type\":\"long\"}," +
        "{\"name\":\"boolean\",\"type\":\"boolean\"}," +
        "{\"name\":\"float\",\"type\":\"float\"}," +
        "{\"name\":\"double\",\"type\":\"double\"}," +
        "{\"name\":\"string\",\"type\":\"string\"}," +
        "{\"name\":\"nullableString\",\"type\":[\"null\",\"string\"],\"default\":null}," +
        "{\"name\":\"nullableInt\",\"type\":[\"null\",\"int\"],\"default\":null}," +
        "{\"name\":\"list\",\"type\":{\"type\":\"array\",\"items\":\"int\"},\"default\":[]}," +
        "{\"name\":\"map\",\"type\":{\"type\":\"map\",\"values\":\"long\"},\"default\":{}}," +
        "{\"name\":\"color\",\"type\":{\"type\":\"enum\",\"name\":\"Color\",\"symbols\":[\"RED\",\"GREEN\",\"BLUE\"]}}," +
        "{\"name\":\"shape\",\"type\":[" +
        "{\"type\":\"record\",\"name\":\"Circle\",\"namespace\":\"com.github.avrokotlin.avro4k.Shape\",\"fields\":[{\"name\":\"radius\",\"type\":\"int\"}]}," +
        "{\"type\":\"record\",\"name\":\"Square\",\"namespace\":\"com.github.avrokotlin.avro4k.Shape\",\"fields\":[{\"name\":\"side\",\"type\":\"int\"}]}]}," +
        "{\"name\":\"hash\",\"type\":{\"type\":\"fixed\",\"name\":\"Hash\",\"size\":4}}," +
        "{\"name\":\"uuid\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}}," +
        "{\"name\":\"instant\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}}," +
        "{\"name\":\"duration\",\"type\":{\"type\":\"fixed\",\"name\":\"Duration\",\"namespace\":\"time\",\"doc\":" +
        "\"A 12-byte byte array encoding a duration in months, days and milliseconds.\",\"size\":12,\"logicalType\":\"duration\"}}]}"

private const val EXPECTED_HEX =
    "53808080808040010000c03f000000000000d0bf0c68c3a96c6c6f020a7468657265020e060201d80400040261020262" +
        "0300020006010203044831323365343536372d653839622d313264332d613435362d343236363134313734303030f6a1" +
        "abfef9620000000003000000a00f0000"