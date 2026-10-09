package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.CoreSchema
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.ResolvedSchema
import com.github.avrokotlin.avro4k.SomeEnum
import com.github.avrokotlin.avro4k.ValueClassWithGenericField
import com.github.avrokotlin.avro4k.apacheSchema
import com.github.avrokotlin.avro4k.copy
import com.github.avrokotlin.avro4k.decodeWith
import com.github.avrokotlin.avro4k.encodeWith
import com.github.avrokotlin.avro4k.internal.decoder.direct.AbstractAvroDirectDecoder
import com.github.avrokotlin.avro4k.serializer.AnySerializer
import com.github.avrokotlin.avro4k.serializer.AvroDuration
import com.github.avrokotlin.avro4k.serializer.UUIDSerializer
import com.github.avrokotlin.avro4k.toAvro4k
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.beInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.serializer
import org.apache.avro.LogicalTypes
import org.apache.avro.Schema
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

class AnySerializerTest : StringSpec() {
    init {
        "AnySerializer needs only the experimental opt-in, as neither it nor its superclasses or their members are internal API" {
            // the build opts every module in to the internal API, so this is checked on the compiled markers instead
            AnySerializer::class.java.isAnnotationPresent(ExperimentalAvro4kApi::class.java) shouldBe true
            val internal = InternalAvro4kApi::class.java
            val types = generateSequence<Class<*>>(AnySerializer::class.java) { it.superclass }.takeWhile { it != Any::class.java }.toList()
            types.map { it.simpleName } shouldBe listOf("AnySerializer", "AbstractAnySerializer")
            for (type in types) {
                withClue(type.name) { type.isAnnotationPresent(internal) shouldBe false }
                for (method in type.declaredMethods.filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }) {
                    withClue("${type.name}.${method.name}") { method.isAnnotationPresent(internal) shouldBe false }
                }
            }
        }
        "The AnySerializer is registered in the default serializers module" {
            Avro.serializersModule.serializer<Any>() should beInstanceOf<AnySerializer>()
        }
        "should throw error when serializing unknown non-serializable type" {
            val encoder = mockk<Encoder>()
            every { encoder.serializersModule } returns EmptySerializersModule()

            assertThrows<SerializationException> { AnySerializer().serialize(encoder, NonSerializableType) }
        }
        "never sees a union: the binary decoder resolves the branch of a union value handed out by decodeInlineElement" {
            val writerSchema = Avro.schema(NullableLongHolder.serializer().descriptor)
            val bytes = Avro.encodeToByteArray(writerSchema, NullableLongHolder.serializer(), NullableLongHolder(42))

            Avro.decodeFromByteArray(writerSchema, AnyThroughInlineElement, bytes) shouldBe 42L
        }
        "should throw error when serializing null schema" {
            val decoder = mockk<AbstractAvroDirectDecoder>()
            every { decoder.serializersModule } returns EmptySerializersModule()
            every { decoder.avro } returns Avro
            decoder.stubWriterSchemas(Schema.create(Schema.Type.NULL))

            assertThrows<UnsupportedOperationException> { AnySerializer().deserialize(decoder) }
        }
        "a decoder that is not an AvroDecoder fails with a ClassCastException naming AvroDecoder" {
            val decoder = mockk<Decoder>()

            assertThrows<ClassCastException> { AnySerializer().deserialize(decoder) }.message shouldContain AvroDecoder::class.java.name
        }
        "any AvroDecoder, not only avro4k's own ones, decodes with the logical types of its Avro instance" {
            val customizedAvro = Avro { setLogicalTypeSerializer("my-awesome-logical-type", String.serializer()) }
            val decoder = mockk<AvroDecoder>()
            every { decoder.serializersModule } returns EmptySerializersModule()
            every { decoder.avro } returns customizedAvro
            every { decoder.currentWriterSchema } returns
                Schema.create(Schema.Type.STRING).copy(logicalTypeName = "my-awesome-logical-type").toAvro4k() as ResolvedSchema
            every { decoder.decodeSerializableValue(String.serializer()) } returns "decoded by the logical type"

            AnySerializer().deserialize(decoder) shouldBe "decoded by the logical type"
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
            decoder.stubWriterSchemas(Avro.apacheSchema<List<PolymorphicType?>>())
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
            decoder.stubWriterSchemas(Avro.apacheSchema<Map<String, String>>())
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
            decoder.stubWriterSchemas(Avro.apacheSchema<RecordType>())
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
            decoder.stubWriterSchemas(Avro.apacheSchema<SomeEnum>())
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
            decoder.stubWriterSchemas(Schema.createFixed("the.name", null, null, 5))
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
        "deserializing a schema with the logicalType 'uuid' should return a kotlin Uuid" {
            val schema = Schema.create(Schema.Type.STRING).copy(logicalTypeName = "uuid")
            val value = Uuid.random()
            val bytes = Avro.encodeWith(schema, value.toString())

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a fixed schema with the logicalType 'uuid' should return a kotlin Uuid" {
            val schema = Schema.createFixed("the.Uuid", null, null, 16).copy(logicalTypeName = "uuid")
            val value = Uuid.random()
            val bytes = Avro.encodeWith(schema, value.toByteArray())

            Avro.decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "deserializing a schema with the logicalType 'uuid' should return a java UUID when its serializer is registered for it" {
            val schema = Schema.create(Schema.Type.STRING).copy(logicalTypeName = "uuid")
            val value = UUID.randomUUID()
            val bytes = Avro.encodeWith(schema, value.toString())

            Avro { setLogicalTypeSerializer("uuid", UUIDSerializer) }
                .decodeWith(schema, AnySerializer(), bytes) shouldBe value
        }
        "serializing a java UUID or a kotlin Uuid as Any should encode the same uuid string" {
            val schema = Schema.create(Schema.Type.STRING).copy(logicalTypeName = "uuid")
            val javaUuid = UUID.randomUUID()
            val expectedBytes = Avro.encodeWith(schema, javaUuid.toString())

            Avro.encodeWith<Any>(schema, AnySerializer(), javaUuid) shouldBe expectedBytes
            Avro.encodeWith<Any>(schema, AnySerializer(), javaUuid.toKotlinUuid()) shouldBe expectedBytes
        }
        "serializing a java UUID or a kotlin Uuid as Any should encode the same uuid fixed" {
            val schema = Schema.createFixed("the.Uuid", null, null, 16).copy(logicalTypeName = "uuid")
            val kotlinUuid = Uuid.random()
            val expectedBytes = Avro.encodeWith(schema, kotlinUuid.toByteArray())

            Avro.encodeWith<Any>(schema, AnySerializer(), kotlinUuid) shouldBe expectedBytes
            Avro.encodeWith<Any>(schema, AnySerializer(), kotlinUuid.toJavaUuid()) shouldBe expectedBytes
        }
        "a uuid decoded as Any should encode back to the same bytes" {
            val schema = Schema.create(Schema.Type.STRING).copy(logicalTypeName = "uuid")
            val bytes = Avro.encodeWith(schema, Uuid.random().toString())

            val decoded = Avro.decodeWith(schema, AnySerializer(), bytes)

            Avro.encodeWith<Any>(schema, AnySerializer(), decoded) shouldBe bytes
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

/**
 * Stubs the writer schema of a mocked core decoder, both its internal field, which [AnySerializer] reads, and the public
 * [AbstractAvroDirectDecoder.currentWriterSchema] over it: one instance for both, as in a real decoder.
 */
private fun AbstractAvroDirectDecoder.stubWriterSchemas(writerSchema: Schema) {
    val converted = writerSchema.toAvro4k()
    every { currentAvroSchema } returns converted
    every { currentWriterSchema } returns converted as ResolvedSchema
}

@Serializable
private data class NullableLongHolder(val value: Long?)

/**
 * Reads [NullableLongHolder]'s `["null", "long"]` field with [AnySerializer] through [CompositeDecoder.decodeInlineElement],
 * which hands out the decoder positioned on the field before its union index is read.
 */
private object AnyThroughInlineElement : DeserializationStrategy<Any> {
    override val descriptor = NullableLongHolder.serializer().descriptor

    override fun deserialize(decoder: Decoder): Any =
        decoder.decodeStructure(descriptor) {
            decodeElementIndex(descriptor) shouldBe 0
            val value = AnySerializer().deserialize(decodeInlineElement(descriptor, 0))
            decodeElementIndex(descriptor) shouldBe CompositeDecoder.DECODE_DONE
            value
        }
}