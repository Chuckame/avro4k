@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.apacheWriterSchema
import com.github.avrokotlin.avro4k.decodeFromGenericDataWith
import com.github.avrokotlin.avro4k.encodeToGenericDataWith
import com.github.avrokotlin.avro4k.serializer.AnySerializer
import com.github.avrokotlin.avro4k.serializer.AvroDuration
import com.github.avrokotlin.avro4k.serializer.AvroDurationSerializer
import com.github.avrokotlin.avro4k.serializer.AvroSerializer
import com.github.avrokotlin.avro4k.serializer.BigDecimalSerializer
import com.github.avrokotlin.avro4k.serializer.BigIntegerSerializer
import com.github.avrokotlin.avro4k.serializer.InstantSerializer
import com.github.avrokotlin.avro4k.serializer.InstantToMicroSerializer
import com.github.avrokotlin.avro4k.serializer.KotlinInstantSerializer
import com.github.avrokotlin.avro4k.serializer.KotlinUuidSerializer
import com.github.avrokotlin.avro4k.serializer.LocalDateSerializer
import com.github.avrokotlin.avro4k.serializer.LocalDateTimeSerializer
import com.github.avrokotlin.avro4k.serializer.LocalTimeSerializer
import com.github.avrokotlin.avro4k.serializer.SchemaSupplierContext
import com.github.avrokotlin.avro4k.serializer.UUIDSerializer
import com.github.avrokotlin.avro4k.toAvro4k
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonPrimitive
import org.apache.avro.Schema
import org.apache.avro.SchemaBuilder
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.io.EncoderFactory
import org.apache.avro.util.Utf8
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The generic data decoder picks the branch of a union from the generic value it holds, as the binary decoder picks it from
 * the written index: `currentWriterSchema` is that branch, never the union. Serializers that read the writer schema (the
 * logical types, `AnySerializer`, custom ones) then see the schema of the value they decode.
 */
internal class GenericDataUnionDecodingTest : StringSpec({
    val uuid = "6f1b7a8e-3c2d-4e5f-9a0b-1c2d3e4f5a6b"
    val millis = 1_700_000_000_123L
    val durationFixed = """{"type":"fixed","name":"Duration","size":12,"logicalType":"duration"}"""

    // ---- a nullable logical type, ["null", X], encoded to generic data then decoded back ----

    fun <T : Any> nullableRoundTrip(
        name: String,
        serializer: KSerializer<T>,
        branch: String,
        value: T,
    ) {
        "decodes a nullable $name from generic data" {
            val schema = holderSchema("""["null", $branch]""")
            val holderSerializer = Holder.serializer(serializer.nullable)

            val encoded = Avro.encodeToGenericDataWith(schema, holderSerializer, Holder(value))
            Avro.decodeFromGenericDataWith(schema, holderSerializer, encoded) shouldBe Holder(value)

            val encodedNull = Avro.encodeToGenericDataWith(schema, holderSerializer, Holder(null))
            Avro.decodeFromGenericDataWith(schema, holderSerializer, encodedNull) shouldBe Holder(null)
        }
    }

    nullableRoundTrip("kotlin Instant", KotlinInstantSerializer, """{"type":"long","logicalType":"timestamp-millis"}""", kotlin.time.Instant.fromEpochMilliseconds(millis))
    nullableRoundTrip("java Instant", InstantSerializer, """{"type":"long","logicalType":"timestamp-millis"}""", java.time.Instant.ofEpochMilli(millis))
    nullableRoundTrip(
        "java Instant in micros",
        InstantToMicroSerializer,
        """{"type":"long","logicalType":"timestamp-micros"}""",
        java.time.Instant.ofEpochSecond(1_700_000_000, 123_456_000)
    )
    nullableRoundTrip("LocalDate", LocalDateSerializer, """{"type":"int","logicalType":"date"}""", LocalDate.of(2024, 2, 29))
    nullableRoundTrip("LocalTime", LocalTimeSerializer, """{"type":"int","logicalType":"time-millis"}""", LocalTime.of(12, 34, 56, 789_000_000))
    nullableRoundTrip("LocalTime in micros", LocalTimeSerializer, """{"type":"long","logicalType":"time-micros"}""", LocalTime.of(12, 34, 56, 789_123_000))
    nullableRoundTrip("LocalDateTime", LocalDateTimeSerializer, """{"type":"long","logicalType":"timestamp-millis"}""", LocalDateTime.of(2024, 2, 29, 12, 34, 56, 789_000_000))
    nullableRoundTrip("BigDecimal as decimal bytes", BigDecimalSerializer, """{"type":"bytes","logicalType":"decimal","precision":10,"scale":2}""", BigDecimal("12.34"))
    nullableRoundTrip("BigDecimal as big-decimal bytes", BigDecimalSerializer, """{"type":"bytes","logicalType":"big-decimal"}""", BigDecimal("1.000001"))
    nullableRoundTrip(
        "BigDecimal as decimal fixed",
        BigDecimalSerializer,
        """{"type":"fixed","name":"Dec","size":8,"logicalType":"decimal","precision":10,"scale":2}""",
        BigDecimal("-5.67")
    )
    nullableRoundTrip("BigInteger", BigIntegerSerializer, "\"long\"", BigInteger.valueOf(1_234_567_890_123))
    nullableRoundTrip("kotlin Uuid as string", KotlinUuidSerializer, """{"type":"string","logicalType":"uuid"}""", Uuid.parse(uuid))
    nullableRoundTrip("kotlin Uuid as fixed", KotlinUuidSerializer, """{"type":"fixed","name":"UuidFixed","size":16,"logicalType":"uuid"}""", Uuid.parse(uuid))
    nullableRoundTrip("java UUID as string", UUIDSerializer, """{"type":"string","logicalType":"uuid"}""", UUID.fromString(uuid))
    nullableRoundTrip("java UUID as fixed", UUIDSerializer, """{"type":"fixed","name":"UuidFixed","size":16,"logicalType":"uuid"}""", UUID.fromString(uuid))
    nullableRoundTrip("AvroDuration as fixed", AvroDurationSerializer, durationFixed, AvroDuration(1u, 2u, 3u))
    nullableRoundTrip("kotlin Duration as fixed", Duration.serializer(), durationFixed, 3.days + 4.milliseconds)

    "an int held for a nullable timestamp keeps decoding through the only non-null branch" {
        decodeRaw(InstantSerializer.nullable, """["null", {"type":"long","logicalType":"timestamp-millis"}]""", 123) shouldBe
            java.time.Instant.ofEpochMilli(123)
    }

    // ---- unions with several non-null branches: the value of a later branch used to be read with the first matching one ----

    fun <T> multiBranch(
        name: String,
        serializer: KSerializer<T>,
        union: String,
        raw: Any?,
        expected: T,
    ) {
        "decodes $name" {
            decodeRaw(serializer, union, raw) shouldBe expected
        }
    }

    val stringOrTimestampMillis = """["null", "string", {"type":"long","logicalType":"timestamp-millis"}]"""
    multiBranch(
        "a java Instant from the long branch of a union with a string branch first",
        InstantSerializer.nullable,
        stringOrTimestampMillis,
        millis,
        java.time.Instant.ofEpochMilli(millis)
    )
    multiBranch(
        "a java Instant from the string branch of a union with a long branch",
        InstantSerializer.nullable,
        stringOrTimestampMillis,
        Utf8("2023-11-14T22:13:20.123Z"),
        java.time.Instant.ofEpochMilli(millis)
    )
    multiBranch(
        "a kotlin Instant from the long branch of a union with a string branch first",
        KotlinInstantSerializer.nullable,
        stringOrTimestampMillis,
        millis,
        kotlin.time.Instant.fromEpochMilliseconds(millis)
    )
    multiBranch(
        "a LocalDateTime from the long branch of a union with a string branch first",
        LocalDateTimeSerializer.nullable,
        stringOrTimestampMillis,
        millis,
        LocalDateTime.ofEpochSecond(1_700_000_000, 123_000_000, ZoneOffset.UTC)
    )
    multiBranch(
        "a java Instant in micros from the long branch of a union with a string branch first",
        InstantToMicroSerializer.nullable,
        """["null", "string", {"type":"long","logicalType":"timestamp-micros"}]""",
        1_700_000_000_123_456L,
        java.time.Instant.ofEpochSecond(1_700_000_000, 123_456_000)
    )
    multiBranch(
        "a LocalDate from the int branch of a union with a string branch first",
        LocalDateSerializer.nullable,
        """["null", "string", {"type":"int","logicalType":"date"}]""",
        19_782,
        LocalDate.ofEpochDay(19_782)
    )
    multiBranch(
        "a LocalTime from the int branch of a union with a string branch first",
        LocalTimeSerializer.nullable,
        """["null", "string", {"type":"int","logicalType":"time-millis"}]""",
        45_296_789,
        LocalTime.of(12, 34, 56, 789_000_000)
    )
    multiBranch(
        "a BigDecimal from the bytes branch of a union with a string branch first",
        BigDecimalSerializer.nullable,
        """["null", "string", {"type":"bytes","logicalType":"decimal","precision":10,"scale":2}]""",
        ByteBuffer.wrap(BigInteger.valueOf(1234).toByteArray()),
        BigDecimal("12.34")
    )
    multiBranch(
        "a BigInteger from the long branch of a union with a string branch first",
        BigIntegerSerializer.nullable,
        """["null", "string", "long"]""",
        1_234_567_890_123L,
        BigInteger.valueOf(1_234_567_890_123)
    )
    multiBranch(
        "a kotlin Uuid from the string branch of a union with a fixed branch first",
        KotlinUuidSerializer.nullable,
        """["null", {"type":"fixed","name":"UuidFixed","size":16,"logicalType":"uuid"}, "string"]""",
        Utf8(uuid),
        Uuid.parse(uuid)
    )
    multiBranch(
        "a java UUID from the string branch of a union with a fixed branch first",
        UUIDSerializer.nullable,
        """["null", {"type":"fixed","name":"UuidFixed","size":16,"logicalType":"uuid"}, "string"]""",
        Utf8(uuid),
        UUID.fromString(uuid)
    )

    "decodes an AvroDuration from the fixed branch of a union with a string branch first" {
        val union = """["null", "string", $durationFixed]"""
        val fixedSchema = holderSchema(union).getField("value").schema().types[2]
        val raw = GenericData.Fixed(fixedSchema, byteArrayOf(1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0))

        decodeRaw(AvroDurationSerializer.nullable, union, raw) shouldBe AvroDuration(1u, 2u, 3u)
    }

    // ---- named types: the branch whose full name is the value's schema's ----

    "decodes a decimal from the fixed branch named like the value's schema, not the first decimal fixed" {
        val union =
            """["null", {"type":"fixed","name":"DecA","size":8,"logicalType":"decimal","precision":10,"scale":2},
                {"type":"fixed","name":"DecB","size":8,"logicalType":"decimal","precision":10,"scale":4}]"""
        val decB = holderSchema(union).getField("value").schema().types[2]
        val raw = GenericData.Fixed(decB, ByteBuffer.allocate(8).putLong(123_456).array())

        decodeRaw(BigDecimalSerializer.nullable, union, raw) shouldBe BigDecimal("12.3456")
    }

    "reports the branch of the value as the current writer schema" {
        val captured = mutableListOf<Schema>()
        val capturing = SchemaCapturingSerializer(captured)
        val enums = """["null", {"type":"enum","name":"A","symbols":["X","Y"]}, {"type":"enum","name":"B","symbols":["Y","Z"]}]"""
        val enumB = holderSchema(enums).getField("value").schema().types[2]
        val primitives = """["null", "string", "long", "boolean"]"""

        decodeRaw(capturing, enums, GenericData.EnumSymbol(enumB, "Z")) shouldBe "Z"
        decodeRaw(capturing, primitives, 42L) shouldBe "42"
        decodeRaw(capturing, primitives, Utf8("x")) shouldBe "x"
        decodeRaw(capturing, primitives, true) shouldBe "true"

        captured.map { it.fullName } shouldContainExactly listOf("B", "long", "string", "boolean")
    }

    "resolves the union of each map value from the value it holds" {
        val captured = mutableListOf<Schema>()
        val serializer = MapSerializer(String.serializer(), SchemaCapturingSerializer(captured).nullable)
        val schema = Schema.Parser().parse("""{"type":"map","values":["null", "string", "long"]}""")
        val raw = linkedMapOf(Utf8("a") to 1L, Utf8("b") to Utf8("x"), Utf8("c") to null)

        Avro.decodeFromGenericDataWith(schema, serializer, raw) shouldBe linkedMapOf("a" to "1", "b" to "x", "c" to null)
        captured.map { it.fullName } shouldContainExactly listOf("long", "string")
    }

    "resolves the union of each array element from the element it holds, before it is decoded" {
        val captured = mutableListOf<Schema>()
        val serializer = ListSerializer(SchemaCapturingSerializer(captured))
        val schema = Schema.Parser().parse("""{"type":"array","items":["string", "long"]}""")

        Avro.decodeFromGenericDataWith(schema, serializer, listOf(1L, Utf8("x"), 2L)) shouldBe listOf("1", "x", "2")
        captured.map { it.fullName } shouldContainExactly listOf("long", "string", "long")
    }

    "a value matching no branch of a union with several non-null branches fails when its schema is read" {
        val error =
            shouldThrow<SerializationException> {
                decodeRaw(SchemaCapturingSerializer(mutableListOf()), """["string", "long"]""", 1.5)
            }
        error.message shouldContain "1.5"
        error.message shouldContain "[\"string\",\"long\"]"
    }

    // ---- AnySerializer: decodes whatever the writer schema is, from generic data as from bytes ----

    fun anyCase(
        name: String,
        schemaJson: String,
        raw: (Schema) -> Any?,
    ) {
        "AnySerializer decodes $name from generic data as from bytes" {
            val schema = Schema.Parser().parse(schemaJson)
            val value = raw(schema)
            val fromBytes = Avro.decodeFromByteArray(schema.toAvro4k(), AnySerializer().nullable, apacheBytes(schema, value))
            val fromGenericData = Avro.decodeFromGenericDataWith(schema, AnySerializer().nullable, value)

            withClue("from bytes: $fromBytes") {
                normalize(fromGenericData) shouldBe normalize(fromBytes)
            }
        }
    }

    anyCase("an int", "\"int\"") { 42 }
    anyCase("a long", "\"long\"") { 3_000_000_000L }
    anyCase("a string", "\"string\"") { Utf8("hello") }
    anyCase("a boolean", "\"boolean\"") { true }
    anyCase("a float", "\"float\"") { 1.5f }
    anyCase("a double", "\"double\"") { 2.5 }
    anyCase("bytes", "\"bytes\"") { ByteBuffer.wrap(byteArrayOf(1, 2, 3)) }
    anyCase("a fixed", """{"type":"fixed","name":"F","size":3}""") { GenericData.Fixed(it, byteArrayOf(4, 5, 6)) }
    anyCase("an enum", """{"type":"enum","name":"E","symbols":["A","B"]}""") { GenericData.EnumSymbol(it, "B") }
    anyCase("an array", """{"type":"array","items":"int"}""") { listOf(1, 2, 3) }
    anyCase("a map", """{"type":"map","values":"string"}""") { mapOf(Utf8("k") to Utf8("v")) }
    anyCase("a timestamp", """{"type":"long","logicalType":"timestamp-millis"}""") { millis }
    anyCase("a uuid", """{"type":"string","logicalType":"uuid"}""") { Utf8(uuid) }
    anyCase("a decimal", """{"type":"bytes","logicalType":"decimal","precision":10,"scale":2}""") { ByteBuffer.wrap(BigInteger.valueOf(1234).toByteArray()) }
    anyCase("a duration", durationFixed) { GenericData.Fixed(it, byteArrayOf(1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0)) }
    anyCase("the long branch of a union", """["null", "string", "long"]""") { 7L }
    anyCase("the string branch of a union", """["null", "string", "long"]""") { Utf8("seven") }
    anyCase("the null branch of a union", """["null", "string", "long"]""") { null }
    anyCase("a record", RECORD_SCHEMA) { schema ->
        GenericData.Record(schema).apply {
            put("id", 1)
            put("label", Utf8("one"))
            put("choice", 9L)
            put("tags", listOf(Utf8("a"), Utf8("b")))
        }
    }
    anyCase("the second record branch of a union", """["null", $RECORD_SCHEMA, {"type":"record","name":"Other","fields":[{"name":"x","type":"int"}]}]""") { schema ->
        GenericData.Record(schema.types[2]).apply { put("x", 5) }
    }

    "AnySerializer decodes a missing field's default from generic data as from bytes" {
        val writerSchema = SchemaBuilder.record("WithDefaults").fields().requiredString("name").endRecord()
        val expected = WithAnyDefaults("abc", 42L, java.time.Instant.ofEpochMilli(millis), listOf(1, 2))

        Avro.decodeFromGenericDataWith(writerSchema, WithAnyDefaults.serializer(), GenericData.Record(writerSchema).apply { put("name", "abc") }) shouldBe expected
        val bytes = Avro.encodeToByteArray(writerSchema.toAvro4k(), NameOnly.serializer(), NameOnly("abc"))
        Avro.decodeFromByteArray(writerSchema.toAvro4k(), WithAnyDefaults.serializer(), bytes) shouldBe expected
    }
}) {
    @Serializable
    @SerialName("Holder")
    private data class Holder<T>(val value: T)

    @Serializable
    @SerialName("WithDefaults")
    private data class NameOnly(val name: String)

    @Serializable
    @SerialName("WithDefaults")
    private data class WithAnyDefaults(
        val name: String,
        @AvroDefault("42") @Serializable(with = AnyAsLong::class) val long: Any,
        @AvroDefault("1700000000123") @Serializable(with = AnyAsTimestamp::class) val timestamp: Any,
        @AvroDefault("[1, 2]") @Serializable(with = AnyAsIntArray::class) val array: Any,
    )

    /** Decodes with [AnySerializer] a field whose reader schema is [schema]. */
    private abstract class AnyWithSchema(name: String, private val schema: AvroSchema) : AvroSerializer<Any>(name) {
        override fun getSchema(context: SchemaSupplierContext): AvroSchema = schema

        override fun serializeAvro(
            encoder: AvroEncoder,
            value: Any,
        ) = throw UnsupportedOperationException()

        override fun deserializeAvro(decoder: AvroDecoder): Any = decoder.decodeSerializableValue(AnySerializer())
    }

    private object AnyAsLong : AnyWithSchema("AnyAsLong", AvroSchema.LongSchema())

    private object AnyAsTimestamp : AnyWithSchema("AnyAsTimestamp", AvroSchema.LongSchema(mapOf("logicalType" to JsonPrimitive("timestamp-millis"))))

    private object AnyAsIntArray : AnyWithSchema("AnyAsIntArray", AvroSchema.ArraySchema(AvroSchema.IntSchema()))

    /**
     * Records the writer schema the decoder reports, then decodes the value as a string with the typed method of that schema.
     */
    private class SchemaCapturingSerializer(
        private val captured: MutableList<Schema>,
    ) : KSerializer<String> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SchemaCapturing", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: String,
        ) = throw UnsupportedOperationException()

        override fun deserialize(decoder: Decoder): String {
            decoder as AvroDecoder
            val schema = decoder.apacheWriterSchema
            captured += schema
            return when (schema.type) {
                Schema.Type.LONG -> decoder.decodeLong().toString()
                Schema.Type.BOOLEAN -> decoder.decodeBoolean().toString()
                else -> decoder.decodeString()
            }
        }
    }

    private companion object {
        val RECORD_SCHEMA =
            """{"type":"record","name":"Rec","fields":[
                {"name":"id","type":"int"},
                {"name":"label","type":"string"},
                {"name":"choice","type":["null","string","long"]},
                {"name":"tags","type":{"type":"array","items":"string"}}
            ]}"""

        fun holderSchema(fieldType: String): Schema =
            Schema.Parser().parse("""{"type":"record","name":"Holder","fields":[{"name":"value","type":$fieldType}]}""")

        /** Decodes a `Holder` record whose `value` field, of type [fieldType], holds the generic value [raw]. */
        fun <T> decodeRaw(
            serializer: KSerializer<T>,
            fieldType: String,
            raw: Any?,
        ): T {
            val schema = holderSchema(fieldType)
            val record = GenericData.Record(schema).apply { put("value", raw) }
            return Avro.decodeFromGenericDataWith(schema, Holder.serializer(serializer), record).value
        }

        /** [value] written by Apache's own generic writer. */
        fun apacheBytes(
            schema: Schema,
            value: Any?,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val encoder = EncoderFactory.get().binaryEncoder(out, null)
            GenericDatumWriter<Any?>(schema, GenericData.get()).write(value, encoder)
            encoder.flush()
            return out.toByteArray()
        }

        /** Byte arrays as lists, to compare decoded values by content. */
        fun normalize(value: Any?): Any? =
            when (value) {
                is ByteArray -> value.toList()
                is Map<*, *> -> value.entries.associate { (k, v) -> normalize(k) to normalize(v) }
                is List<*> -> value.map { normalize(it) }
                else -> value
            }
    }
}