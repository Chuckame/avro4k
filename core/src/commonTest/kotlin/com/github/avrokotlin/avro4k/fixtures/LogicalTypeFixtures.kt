package com.github.avrokotlin.avro4k.fixtures

import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.AvroStringable
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.serializer.AvroDuration
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The logical types every platform supports (`decimal` is JVM-only), and values written as strings with
 * [AvroStringable].
 */
internal val logicalTypeFixtures: List<GoldenFixture<*>> =
    listOf(
        GoldenFixture(
            name = "logical types",
            serializer = LogicalTypes.serializer(),
            value =
                LogicalTypes(
                    uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174000"),
                    fixedUuid = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff"),
                    instant = Instant.fromEpochMilliseconds(1_700_000_000_123),
                    beforeEpoch = Instant.fromEpochMilliseconds(-1_001),
                    duration = 3.days + 4_005.milliseconds,
                    avroDuration = AvroDuration(months = 14u, days = 2u, millis = 3u)
                ),
            schema =
                "{\"type\":\"record\",\"name\":\"LogicalTypes\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"uuid\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}}," +
                    "{\"name\":\"fixedUuid\",\"type\":{\"type\":\"fixed\",\"name\":\"fixedUuid\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures.LogicalTypes\"," +
                    "\"size\":16,\"logicalType\":\"uuid\"}},{\"name\":\"instant\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}}," +
                    "{\"name\":\"beforeEpoch\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}}," +
                    "{\"name\":\"duration\",\"type\":{\"type\":\"fixed\",\"name\":\"Duration\",\"namespace\":\"time\"," +
                    "\"doc\":\"A 12-byte byte array encoding a duration in months, days and milliseconds.\",\"size\":12,\"logicalType\":\"duration\"}}," +
                    "{\"name\":\"avroDuration\",\"type\":\"time.Duration\"}]}",
            hex =
                "4831323365343536372d653839622d313264332d613435362d34323636313431373430303000112233445566778899aabbccddeefff6a1abfef962d10f0000000003000000a50f00000e00" +
                    "00000200000003000000",
            generic =
                "{\"uuid\"=123e4567-e89b-12d3-a456-426614174000, \"fixedUuid\"=00112233-4455-6677-8899-aabbccddeeff, " +
                    "\"instant\"=2023-11-14T22:13:20.123Z, \"beforeEpoch\"=1969-12-31T23:59:58.999Z, \"duration\"=AvroDuration(months=0, days=3, " +
                    "millis=4005), \"avroDuration\"=AvroDuration(months=14, days=2, millis=3)}"
        ),
        GoldenFixture(
            name = "instant as timestamp-micros",
            serializer = InstantHolder.serializer(),
            value = InstantHolder(Instant.fromEpochSeconds(1_700_000_000, 123_456_000)),
            schema =
                "{\"type\":\"record\",\"name\":\"InstantHolder\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"instant\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-micros\"}}]}",
            hex = "8089818283898506",
            inferred = false,
            generic = "{\"instant\"=2023-11-14T22:13:20.123456Z}"
        ),
        GoldenFixture(
            name = "instant as timestamp-nanos",
            serializer = InstantHolder.serializer(),
            value = InstantHolder(Instant.fromEpochSeconds(-2, 999_999_999)),
            schema =
                "{\"type\":\"record\",\"name\":\"InstantHolder\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                    "{\"name\":\"instant\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-nanos\"}}]}",
            hex = "81a8d6b907",
            inferred = false,
            generic = "{\"instant\"=-1000000001L}"
        ),
        GoldenFixture(
            name = "values written as strings",
            serializer = Stringables.serializer(),
            value =
                Stringables(
                    int = -12,
                    long = Long.MIN_VALUE,
                    double = 2.5,
                    boolean = false,
                    instant = Instant.fromEpochSeconds(1_700_000_000, 5_000_000),
                    uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
                ),
            schema =
                "{\"type\":\"record\",\"name\":\"Stringables\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[{\"name\":\"int\"," +
                    "\"type\":\"string\"}," +
                    "{\"name\":\"long\",\"type\":\"string\"},{\"name\":\"double\",\"type\":\"string\"},{\"name\":\"boolean\",\"type\":\"string\"}," +
                    "{\"name\":\"instant\",\"type\":\"string\"},{\"name\":\"uuid\",\"type\":\"string\"}]}",
            hex =
                "062d3132282d3932323333373230333638353437373538303806322e350a66616c736530323032332d31312d31345432323a31333a32302e3030355a4831323365343536372d653839622d" +
                    "313264332d613435362d343236363134313734303030",
            generic =
                "{\"int\"=\"-12\", \"long\"=\"-9223372036854775808\", \"double\"=\"2.5\", \"boolean\"=\"false\", \"instant\"=\"2023-11-14T22:13:20.005Z\", " +
                    "\"uuid\"=\"123e4567-e89b-12d3-a456-426614174000\"}"
        ),
        floatingPointStringsFixture()
    )

/**
 * Floating-point numbers written as strings, and as map keys, which are strings too. Their text is the platform's
 * `toString()`, as in kotlinx-serialization-json, so Kotlin/JS writes other bytes than the JVM and native for most of
 * them; every platform reads both forms. Known and documented on [AvroStringable].
 */
private fun floatingPointStringsFixture(): GoldenFixture<StringableFloatingPoints> {
    val texts =
        listOf(
            "one" to jvmAndNativeOrJs("1.0", js = "1"),
            "negativeZero" to jvmAndNativeOrJs("-0.0", js = "0"),
            "large" to jvmAndNativeOrJs("1.0E7", js = "10000000"),
            "small" to jvmAndNativeOrJs("1.0E-4", js = "0.0001"),
            "huge" to jvmAndNativeOrJs("1.0E21", js = "1e+21"),
            "minValue" to jvmAndNativeOrJs("4.9E-324", js = "5e-324"),
            "notANumber" to "NaN",
            "infinity" to "-Infinity",
            "inexact" to "0.1",
            "floatOne" to jvmAndNativeOrJs("1.0", js = "1"),
            "floatMax" to jvmAndNativeOrJs("3.4028235E38", js = "3.4028235e+38"),
            // a Float holding a 32-bit value, as when read from bytes: Kotlin/JS writes all the digits of that value
            "floatInexact" to jvmAndNativeOrJs("0.1", js = "0.10000000149011612")
        )
    val key = jvmAndNativeOrJs("2.0", js = "2")
    return GoldenFixture(
        name = "floating-point numbers written as strings",
        serializer = StringableFloatingPoints.serializer(),
        value =
            StringableFloatingPoints(
                one = 1.0,
                negativeZero = -0.0,
                large = 1.0E7,
                small = 1.0E-4,
                huge = 1.0E21,
                minValue = Double.MIN_VALUE,
                notANumber = Double.NaN,
                infinity = Double.NEGATIVE_INFINITY,
                inexact = 0.1,
                floatOne = 1.0f,
                floatMax = Float.MAX_VALUE,
                floatInexact = Float.fromBits(0x3dcccccd),
                byKey = mapOf(2.0 to 3)
            ),
        schema =
            "{\"type\":\"record\",\"name\":\"StringableFloatingPoints\",\"namespace\":\"com.github.avrokotlin.avro4k.fixtures\",\"fields\":[" +
                texts.joinToString("") { (name, _) -> "{\"name\":\"$name\",\"type\":\"string\"}," } +
                "{\"name\":\"byKey\",\"type\":{\"type\":\"map\",\"values\":\"int\"},\"default\":{}}]}",
        // the map: one block of 1 entry, the key, the value 3, the end of the map
        hex = texts.joinToString("") { (_, text) -> avroString(text) } + "02" + avroString(key) + "06" + "00",
        // Kotlin/JS reads "0" back as 0.0, not -0.0
        structuralEquals = jvmAndNativeOrJs(true, js = false),
        generic =
            texts.joinToString(", ", prefix = "{") { (name, text) -> "\"$name\"=\"$text\"" } +
                ", \"byKey\"={\"$key\"=3}}"
    )
}

/** A string shorter than 64 bytes: its length (one zig-zag byte), then its UTF-8 bytes. */
private fun avroString(text: String): String {
    val bytes = text.encodeToByteArray()
    return byteArrayOf((bytes.size * 2).toByte()).toHexString() + bytes.toHexString()
}

@Serializable
private data class LogicalTypes(
    val uuid: Uuid,
    @AvroFixed(16) val fixedUuid: Uuid,
    val instant: Instant,
    val beforeEpoch: Instant,
    val duration: kotlin.time.Duration,
    val avroDuration: AvroDuration,
)

@Serializable
private data class InstantHolder(
    val instant: Instant,
)

// No whole Double such as 1.0: its text is not the same on Kotlin/JS (see the floating-point fixture below)
@Serializable
@OptIn(ExperimentalAvro4kApi::class)
private data class Stringables(
    @AvroStringable val int: Int,
    @AvroStringable val long: Long,
    @AvroStringable val double: Double,
    @AvroStringable val boolean: Boolean,
    @AvroStringable val instant: Instant,
    @AvroStringable val uuid: Uuid,
)

@Serializable
@OptIn(ExperimentalAvro4kApi::class)
private data class StringableFloatingPoints(
    @AvroStringable val one: Double,
    @AvroStringable val negativeZero: Double,
    @AvroStringable val large: Double,
    @AvroStringable val small: Double,
    @AvroStringable val huge: Double,
    @AvroStringable val minValue: Double,
    @AvroStringable val notANumber: Double,
    @AvroStringable val infinity: Double,
    @AvroStringable val inexact: Double,
    @AvroStringable val floatOne: Float,
    @AvroStringable val floatMax: Float,
    @AvroStringable val floatInexact: Float,
    val byKey: Map<Double, Int>,
)