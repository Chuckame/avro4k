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
        )
    )

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

// No whole Double such as 1.0: a stringable Double is written with the platform's toString, "1.0" on the JVM but "1"
// on JS, so its bytes are not the same on every platform yet.
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