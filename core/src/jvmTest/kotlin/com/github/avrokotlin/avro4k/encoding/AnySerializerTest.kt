package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.CoreSchema
import com.github.avrokotlin.avro4k.SomeEnum
import com.github.avrokotlin.avro4k.ValueClassWithGenericField
import com.github.avrokotlin.avro4k.apacheSchema
import com.github.avrokotlin.avro4k.copy
import com.github.avrokotlin.avro4k.decodeWith
import com.github.avrokotlin.avro4k.encodeWith
import com.github.avrokotlin.avro4k.internal.decoder.direct.AbstractAvroDirectDecoder
import com.github.avrokotlin.avro4k.serializer.AnySerializer
import com.github.avrokotlin.avro4k.serializer.AvroDuration
import com.github.avrokotlin.avro4k.stubWriterSchema
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.serializer
import org.apache.avro.LogicalTypes
import org.apache.avro.Schema
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID

class AnySerializerTest : StringSpec() {
    init {
        "The AnySerializer is registered in the default serializers module" {
            Avro.serializersModule.serializer<Any>() should beInstanceOf<AnySerializer>()
        }
        "should throw error when serializing unknown non-serializable type" {
            val encoder = mockk<Encoder>()
            every { encoder.serializersModule } returns EmptySerializersModule()

            assertThrows<SerializationException> { AnySerializer().serialize(encoder, NonSerializableType) }
        }
        "should throw error when serializing union schema" {
            val decoder = mockk<AbstractAvroDirectDecoder>()
            every { decoder.serializersModule } returns EmptySerializersModule()
            every { decoder.avro } returns Avro
            decoder.stubWriterSchema(Schema.createUnion())

            assertThrows<UnsupportedOperationException> { AnySerializer().deserialize(decoder) }
        }
        "should throw error when serializing null schema" {
            val decoder = mockk<AbstractAvroDirectDecoder>()
            every { decoder.serializersModule } returns EmptySerializersModule()
            every { decoder.avro } returns Avro
            decoder.stubWriterSchema(Schema.create(Schema.Type.NULL))

            assertThrows<UnsupportedOperationException> { AnySerializer().deserialize(decoder) }
        }
        "an unknown non-serializable type can be serialized if providing a custom serialization strategy fallback" {
            val encoder = mockk<Encoder>(relaxed = true)
            val fallbackSerializer = mockk<SerializationStrategy<NonSerializableType>>()
            every { encoder.serializersModule } returns EmptySerializersModule()
            val serializer =
                object : AnySerializer() {
                    override fun SerializersModule.inferSerializationStrategyFromNonSerializableType(type: Class<out Any>) =
                        fallbackSerializer
                }

            serializer.serialize(encoder, NonSerializableType)

            verify { encoder.encodeSerializableValue(fallbackSerializer, NonSerializableType) }
        }
        "an unknown non-serializable type can be serialized if providing a custom serializer in the module" {
            val encoder = mockk<Encoder>(relaxed = true)
            val fallbackSerializer = mockk<KSerializer<NonSerializableType>>()
            every { encoder.serializersModule } returns
                SerializersModule {
                    contextual(fallbackSerializer)
                }

            AnySerializer().serialize(encoder, NonSerializableType)

            verify { encoder.encodeSerializableValue(fallbackSerializer, NonSerializableType) }
        }
        "should be able to encode an ArrayList" {
            val value = ArrayList(listOf(42, null))
            val schema = Avro.apacheSchema<List<Int?>>()
            val expectedOutputBytes = Avro.encodeWith(schema, value)

            Avro.encodeWith(schema, AnySerializer(), value) shouldBe expectedOutputBytes
        }
        "should be able to encode a listOf" {
            val value = listOf(42, null)
            val schema = Avro.apacheSchema<List<Int?>>()
            val expectedOutputBytes = Avro.encodeWith(schema, value)

            Avro.encodeWith(schema, AnySerializer(), value) shouldBe expectedOutputBytes
        }
        "should be able to encode a custom list type" {
            class CustomList : ArrayList<Int?>(listOf(42, null))

            val value = CustomList()
            val schema = Avro.apacheSchema<List<Int?>>()
            val expectedOutputBytes = Avro.encodeWith(schema, listOf(42, null))

            Avro.encodeWith(schema, AnySerializer(), value) shouldBe expectedOutputBytes
        }
        "should be able to encode a map" {
            val value = mapOf(17 to "toto", 42 to null)
            val schema = Avro.apacheSchema<Map<Int, String?>>()
            val expectedOutputBytes = Avro.encodeWith(schema, value)

            Avro.encodeWith(schema, AnySerializer(), value) shouldBe expectedOutputBytes
        }
        "should be able to encode a custom map type" {
            class CustomMap : HashMap<Int, String?>(mapOf(17 to "toto", 42 to null))

            val value = CustomMap()
            val schema = Avro.apacheSchema<Map<Int, String?>>()
            val expectedOutputBytes = Avro.encodeWith(schema, mapOf(17 to "toto", 42 to null))

            Avro.encodeWith(schema, AnySerializer(), value) shouldBe expectedOutputBytes
        }
        "should be able to encode a custom parameterized class as record type" {
            val value = SerializableGenericType("Hello")
            val schema = Avro.apacheSchema<SerializableGenericType<String?>>()
            val expectedOutputBytes = Avro.encodeWith(schema, value)
            Avro.encodeWith(schema, AnySerializer(), value) shouldBe expectedOutputBytes
        }
        "should be able to encode a custom parameterized inline type" {
            val value = ValueClassWithGenericField("Hello")
            val schema = Avro.apacheSchema<ValueClassWithGenericField<String?>>()
            val expectedOutputBytes = Avro.encodeWith(schema, value)
            Avro.encodeWith(schema, AnySerializer(), value) shouldBe expectedOutputBytes
        }
        "deserializing a BOOLEAN schema should return a Boolean" {
            val value = true
            val schema = Avro.apacheSchema<Boolean>()
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing an INT schema should return an Int" {
            val value = 42
            val schema = Avro.apacheSchema<Int>()
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a LONG schema should return a Long" {
            val value = 123456789L
            val schema = Avro.apacheSchema<Long>()
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a FLOAT schema should return a Float" {
            val value = 3.14f
            val schema = Avro.apacheSchema<Float>()
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a DOUBLE schema should return a Double" {
            val value = 2.71828
            val schema = Avro.apacheSchema<Double>()
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a STRING schema should return a String" {
            val value = "Hello"
            val schema = Avro.apacheSchema<String>()
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a ENUM schema should return a String" {
            val schema = Avro.apacheSchema<SomeEnum>()
            val bytes = Avro.encodeWith(schema, SomeEnum.B)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe "B"
        }
        "deserializing a BYTES schema should return a ByteArray" {
            val value = byteArrayOf(1, 5, -3, 27, 0)
            val schema = Avro.apacheSchema<ByteArray>()
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a FIXED schema should return a ByteArray" {
            val value = byteArrayOf(45, -100, 0, 17, 33)
            val schema = Schema.createFixed("TheFixed", null, null, 5)
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing an ARRAY schema should return a List" {
            // The polymorphic type helps to allow deserializing many scalar types
            val value = listOf(PolymorphicType.A(33), null, PolymorphicType.C(123456789L), PolymorphicType.B("Hello"))
            val schema = Avro.apacheSchema<List<PolymorphicType?>>()
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe listOf(33, null, 123456789L, "Hello")
        }
        "deserializing an ARRAY schema with a custom DeserializationStrategy should return the custom type" {
            val decoder = mockk<AbstractAvroDirectDecoder>(relaxed = true)
            val fallbackSerializer = mockk<DeserializationStrategy<Any>>()
            every { decoder.serializersModule } returns EmptySerializersModule()
            decoder.stubWriterSchema(Avro.apacheSchema<List<PolymorphicType?>>())
            val serializer =
                object : AnySerializer() {
                    override fun SerializersModule.resolveArrayDeserializationStrategy(writerSchema: CoreSchema) =
                        fallbackSerializer
                }

            serializer.deserialize(decoder)

            verify { decoder.decodeSerializableValue(fallbackSerializer) }
        }
        "deserializing a MAP schema should return a Map" {
            val value =
                mapOf(
                    PolymorphicType.A(33) to null,
                    PolymorphicType.C(123456789L) to PolymorphicType.B("Hello"),
                    PolymorphicType.B("World") to PolymorphicType.A(42),
                    PolymorphicType.A(0) to PolymorphicType.C(987654321L)
                )
            val schema = Avro.apacheSchema<Map<String, PolymorphicType?>>()
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe
                mapOf(
                    "33" to null,
                    "123456789" to "Hello",
                    "World" to 42,
                    "0" to 987654321L
                )
        }
        "deserializing a MAP schema with a custom DeserializationStrategy should return the custom type" {
            val decoder = mockk<AbstractAvroDirectDecoder>(relaxed = true)
            val fallbackSerializer = mockk<DeserializationStrategy<Any>>()
            every { decoder.serializersModule } returns EmptySerializersModule()
            decoder.stubWriterSchema(Avro.apacheSchema<Map<String, String>>())
            val serializer =
                object : AnySerializer() {
                    override fun SerializersModule.resolveMapDeserializationStrategy(writerSchema: CoreSchema) =
                        fallbackSerializer
                }

            serializer.deserialize(decoder)

            verify { decoder.decodeSerializableValue(fallbackSerializer) }
        }
        "deserializing a RECORD schema should return a Map" {
            val value =
                RecordType(
                    firstField = "Hello",
                    secondField = 42
                )
            val schema = Avro.apacheSchema<RecordType>()
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe
                mapOf(
                    "firstField" to "Hello",
                    "secondField" to 42
                )
        }
        "deserializing a RECORD schema with a custom DeserializationStrategy should return the custom type" {
            val decoder = mockk<AbstractAvroDirectDecoder>(relaxed = true)
            val fallbackSerializer = mockk<DeserializationStrategy<Any>>()
            every { decoder.serializersModule } returns EmptySerializersModule()
            decoder.stubWriterSchema(Avro.apacheSchema<RecordType>())
            val serializer =
                object : AnySerializer() {
                    override fun SerializersModule.resolveRecordDeserializationStrategy(writerSchema: CoreSchema) =
                        fallbackSerializer
                }

            serializer.deserialize(decoder)

            verify { decoder.decodeSerializableValue(fallbackSerializer) }
        }
        "deserializing a ENUM schema with a custom DeserializationStrategy should return the custom type" {
            val decoder = mockk<AbstractAvroDirectDecoder>(relaxed = true)
            val fallbackSerializer = mockk<DeserializationStrategy<Any>>()
            every { decoder.serializersModule } returns EmptySerializersModule()
            decoder.stubWriterSchema(Avro.apacheSchema<SomeEnum>())
            val serializer =
                object : AnySerializer() {
                    override fun SerializersModule.resolveEnumDeserializationStrategy(writerSchema: CoreSchema) =
                        fallbackSerializer
                }

            serializer.deserialize(decoder)

            verify { decoder.decodeSerializableValue(fallbackSerializer) }
        }
        "deserializing a FIXED schema with a custom DeserializationStrategy should return the custom type" {
            val decoder = mockk<AbstractAvroDirectDecoder>(relaxed = true)
            val fallbackSerializer = mockk<DeserializationStrategy<Any>>()
            every { decoder.serializersModule } returns EmptySerializersModule()
            decoder.stubWriterSchema(Schema.createFixed("the.name", null, null, 5))
            val serializer =
                object : AnySerializer() {
                    override fun SerializersModule.resolveFixedDeserializationStrategy(writerSchema: CoreSchema) =
                        fallbackSerializer
                }

            serializer.deserialize(decoder)

            verify { decoder.decodeSerializableValue(fallbackSerializer) }
        }
        "deserializing with a custom DeserializationStrategy given in preResolveDeserializationStrategy() should ignore the other custom serializers" {
            val decoder = mockk<AbstractAvroDirectDecoder>(relaxed = true)
            val fallbackSerializer = mockk<DeserializationStrategy<Any>>()
            val serializerThatShouldNotBeUsed = mockk<DeserializationStrategy<Any>>()
            every { decoder.serializersModule } returns EmptySerializersModule()
            val serializer =
                object : AnySerializer() {
                    override fun SerializersModule.preResolveDeserializationStrategy(writerSchema: CoreSchema) =
                        fallbackSerializer

                    override fun SerializersModule.resolveFixedDeserializationStrategy(writerSchema: CoreSchema) =
                        serializerThatShouldNotBeUsed

                    override fun SerializersModule.resolveRecordDeserializationStrategy(writerSchema: CoreSchema) =
                        serializerThatShouldNotBeUsed

                    override fun SerializersModule.resolveEnumDeserializationStrategy(writerSchema: CoreSchema) =
                        serializerThatShouldNotBeUsed
                }

            serializer.deserialize(decoder)

            verify { decoder.decodeSerializableValue(fallbackSerializer) }
        }
        "deserializing a schema with a logicalType should uses the serializer from the avro configuration" {
            class MyAwesomeLogicalTypeSerializer : KSerializer<String> {
                override val descriptor = PrimitiveSerialDescriptor("MyAwesomeLogicalType", PrimitiveKind.STRING)

                override fun serialize(encoder: Encoder, value: String) = throw NotImplementedError()

                override fun deserialize(decoder: Decoder) = "MyAwesomeLogicalType: " + decoder.decodeString()
            }

            val customizedAvro =
                Avro {
                    setLogicalTypeSerializer("my-awesome-logical-type", MyAwesomeLogicalTypeSerializer())
                }
            val schema = Schema.create(Schema.Type.STRING).copy(logicalTypeName = "my-awesome-logical-type")
            val bytes = Avro.encodeWith(schema, "Hello")

            customizedAvro.decodeWith(schema, AnySerializer(), bytes) shouldBe "MyAwesomeLogicalType: Hello"
        }
        "deserializing a schema with an unknown logicalType should return the corresponding schema's type" {
            val schema = Schema.create(Schema.Type.STRING).copy(logicalTypeName = "unknown-logical-type")
            val value = "value"
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a schema with the logicalType 'duration' should return an AvroDuration" {
            val schema = Schema.createFixed("time.Duration", null, null, 12).copy(logicalTypeName = "duration")
            val value = AvroDuration(1u, 2u, 3u)
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a schema with the logicalType 'uuid' should return an UUID" {
            val schema = Schema.create(Schema.Type.STRING).copy(logicalTypeName = "uuid")
            val value = UUID.randomUUID()
            val bytes = Avro.encodeWith(schema, value.toString())

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a schema with the logicalType 'decimal' should return a BigDecimal" {
            val schema = Schema.create(Schema.Type.BYTES).copy(logicalType = LogicalTypes.decimal(4, 2))
            val value = BigDecimal("12.12")
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a schema with the logicalType 'big-decimal' should return a BigDecimal" {
            val schema = Schema.create(Schema.Type.BYTES).copy(logicalType = LogicalTypes.bigDecimal())
            val value = BigDecimal("12.12")
            val bytes = Avro.encodeWith(schema, value)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a schema with the logicalType 'date' should return a LocalDate" {
            val schema = Schema.create(Schema.Type.INT).copy(logicalTypeName = "date")
            val value = LocalDate.now()
            val bytes = Avro.encodeWith(schema, value.toEpochDay())

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a schema with the logicalType 'time-millis' should return a LocalTime" {
            val schema = Schema.create(Schema.Type.INT).copy(logicalTypeName = "time-millis")
            val value = LocalTime.now().truncatedTo(ChronoUnit.MILLIS)
            val bytes = Avro.encodeWith(schema, value.toNanoOfDay() / 1_000_000)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a schema with the logicalType 'time-micros' should return a LocalTime" {
            val schema = Schema.create(Schema.Type.LONG).copy(logicalTypeName = "time-micros")
            val value = LocalTime.now().truncatedTo(ChronoUnit.MICROS)
            val bytes = Avro.encodeWith(schema, value.toNanoOfDay() / 1_000)

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a schema with the logicalType 'timestamp-millis' should return a LocalTime" {
            val schema = Schema.create(Schema.Type.LONG).copy(logicalTypeName = "timestamp-millis")
            val value = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val bytes = Avro.encodeWith(schema, value.toEpochMilli())

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a schema with the logicalType 'timestamp-micros' should return a LocalTime" {
            val schema = Schema.create(Schema.Type.LONG).copy(logicalTypeName = "timestamp-micros")
            val value = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val bytes = Avro.encodeWith(schema, ChronoUnit.MICROS.between(Instant.EPOCH, value))

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
    }

    @Serializable
    sealed interface PolymorphicType {
        @JvmInline
        @Serializable
        value class A(val value: Int) : PolymorphicType

        @JvmInline
        @Serializable
        value class B(val value: String) : PolymorphicType

        @JvmInline
        @Serializable
        value class C(val value: Long) : PolymorphicType
    }

    object NonSerializableType

    @Serializable
    data class SerializableGenericType<T>(
        val value: T,
    )

    @Serializable
    data class RecordType(
        val firstField: String,
        val secondField: Int?,
    )
}