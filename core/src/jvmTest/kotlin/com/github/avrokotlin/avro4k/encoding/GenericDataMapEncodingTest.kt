package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.apacheSchema
import com.github.avrokotlin.avro4k.apacheWriterSchema
import com.github.avrokotlin.avro4k.decodeFromGenericDataWith
import com.github.avrokotlin.avro4k.encodeToGenericDataWith
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
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
import org.apache.avro.Schema
import java.math.BigDecimal

/**
 * Regression tests for the generic (tree) map encoder and decoder, which no longer build an
 * intermediate `Pair` per entry. Entry ordering, null values and the null-key failure must be
 * unchanged.
 *
 * The decoder must also report the map's value schema as `currentWriterSchema` while positioned on a
 * value: it used to report the key's `string` schema for both (see docs/plans/notes/c9.md).
 */
internal class GenericDataMapEncodingTest : StringSpec({
    "encodes map entries into generic data preserving the insertion order" {
        val value = linkedMapOf("z" to 1, "a" to 2, "m" to 3)
        val serializer = MapSerializer(String.serializer(), Int.serializer())

        val encoded = Avro.encodeToGenericDataWith(Avro.apacheSchema(serializer), serializer, value)

        encoded as Map<*, *>
        encoded.keys.map { it.toString() } shouldContainExactly listOf("z", "a", "m")
        encoded.values.toList() shouldContainExactly listOf(1, 2, 3)
    }

    "round-trips a map of nullable values through generic data" {
        val value = linkedMapOf("first" to "a", "nulled" to null, "last" to "z")
        val serializer = MapSerializer(String.serializer(), String.serializer().nullable)
        val schema = Avro.apacheSchema(serializer)

        val encoded = Avro.encodeToGenericDataWith(schema, serializer, value)
        encoded as Map<*, *>
        encoded.keys.map { it.toString() } shouldContainExactly listOf("first", "nulled", "last")
        encoded["nulled"] shouldBe null

        Avro.decodeFromGenericDataWith(schema, serializer, encoded) shouldBe value
    }

    "round-trips an empty map through generic data" {
        val serializer = MapSerializer(String.serializer(), Int.serializer())
        val schema = Avro.apacheSchema(serializer)

        val encoded = Avro.encodeToGenericDataWith(schema, serializer, emptyMap())
        encoded shouldBe emptyMap<String, Int>()

        Avro.decodeFromGenericDataWith(schema, serializer, encoded) shouldBe emptyMap()
    }

    "round-trips a map nested in a record through generic data" {
        val value = RecordWithMap("before", linkedMapOf("k1" to 1, "k2" to 2), "after")
        val schema = Avro.apacheSchema(RecordWithMap.serializer())

        val encoded = Avro.encodeToGenericDataWith(schema, RecordWithMap.serializer(), value)

        Avro.decodeFromGenericDataWith(schema, RecordWithMap.serializer(), encoded) shouldBe value
    }

    "round-trips a map whose values are nested maps through generic data" {
        val value =
            linkedMapOf(
                "a" to linkedMapOf("x" to linkedMapOf("deep" to 1), "y" to emptyMap<String, Int>()),
                "b" to linkedMapOf("z" to linkedMapOf("deeper" to 2, "deepest" to 3))
            )
        val serializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), MapSerializer(String.serializer(), Int.serializer())))
        val schema = Avro.apacheSchema(serializer)

        val encoded = Avro.encodeToGenericDataWith(schema, serializer, value)

        Avro.decodeFromGenericDataWith(schema, serializer, encoded) shouldBe value
    }

    "round-trips a map whose values are nullable nested maps through generic data" {
        val value =
            linkedMapOf(
                "a" to linkedMapOf("x" to linkedMapOf("deep" to 1)),
                "nulled" to null
            )
        val serializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), MapSerializer(String.serializer(), Int.serializer())).nullable)
        val schema = Avro.apacheSchema(serializer)

        val encoded = Avro.encodeToGenericDataWith(schema, serializer, value)

        Avro.decodeFromGenericDataWith(schema, serializer, encoded) shouldBe value
    }

    "round-trips a map whose values are lists through generic data" {
        val value = linkedMapOf("a" to listOf(1, 2, 3), "empty" to emptyList(), "b" to listOf(4))
        val serializer = MapSerializer(String.serializer(), ListSerializer(Int.serializer()))
        val schema = Avro.apacheSchema(serializer)

        val encoded = Avro.encodeToGenericDataWith(schema, serializer, value)

        Avro.decodeFromGenericDataWith(schema, serializer, encoded) shouldBe value
    }

    "decodes a map whose values are plain lists of logical-typed elements" {
        // a plain java.util.List (unlike a GenericArray) carries no schema: the array decoder takes it
        // from the map's value position, and the decimal element serializer then reads its element type.
        val expected = linkedMapOf("a" to listOf(DecimalHolder(BigDecimal("1.50")), DecimalHolder(BigDecimal("-2.25"))))
        val serializer = MapSerializer(String.serializer(), ListSerializer(DecimalHolder.serializer()))
        val schema = Avro.apacheSchema(serializer)

        val encoded = Avro.encodeToGenericDataWith(schema, serializer, expected) as Map<*, *>
        val withPlainLists = encoded.mapValues { (_, list) -> ArrayList(list as Collection<*>) }

        Avro.decodeFromGenericDataWith(schema, serializer, withPlainLists) shouldBe expected
    }

    "round-trips a map whose values are records through generic data" {
        val value =
            linkedMapOf(
                "first" to RecordWithMap("b1", linkedMapOf("k" to 1), "a1"),
                "second" to RecordWithMap("b2", emptyMap(), "a2")
            )
        val serializer = MapSerializer(String.serializer(), RecordWithMap.serializer())
        val schema = Avro.apacheSchema(serializer)

        val encoded = Avro.encodeToGenericDataWith(schema, serializer, value)

        Avro.decodeFromGenericDataWith(schema, serializer, encoded) shouldBe value
    }

    "round-trips a map whose values are polymorphic through generic data" {
        val value =
            linkedMapOf<String, SealedValue>(
                "circle" to SealedValue.Circle(1.5),
                "square" to SealedValue.Square(2),
                "none" to SealedValue.Nothing
            )
        val serializer = MapSerializer(String.serializer(), SealedValue.serializer())
        val schema = Avro.apacheSchema(serializer)

        val encoded = Avro.encodeToGenericDataWith(schema, serializer, value)

        Avro.decodeFromGenericDataWith(schema, serializer, encoded) shouldBe value
    }

    "round-trips a map whose values are logical-typed through generic data" {
        val value = linkedMapOf("price" to DecimalHolder(BigDecimal("12.34")), "discount" to DecimalHolder(BigDecimal("-0.50")))
        val serializer = MapSerializer(String.serializer(), DecimalHolder.serializer())
        val schema = Avro.apacheSchema(serializer)

        val encoded = Avro.encodeToGenericDataWith(schema, serializer, value)

        Avro.decodeFromGenericDataWith(schema, serializer, encoded) shouldBe value
    }

    "reports the key schema then the value schema as the current writer schema" {
        val keySchemas = mutableListOf<Schema>()
        val valueSchemas = mutableListOf<Schema>()
        val plainSerializer = MapSerializer(String.serializer(), Int.serializer())
        val capturingSerializer =
            MapSerializer(
                SchemaCapturingSerializer(String.serializer(), keySchemas),
                SchemaCapturingSerializer(Int.serializer(), valueSchemas)
            )
        val schema = Avro.apacheSchema(plainSerializer)
        val value = linkedMapOf("a" to 1, "b" to 2)

        val encoded = Avro.encodeToGenericDataWith(schema, plainSerializer, value)

        Avro.decodeFromGenericDataWith(schema, capturingSerializer, encoded) shouldBe value
        keySchemas shouldContainExactly listOf(Schema.create(Schema.Type.STRING), Schema.create(Schema.Type.STRING))
        valueSchemas shouldContainExactly listOf(schema.valueType, schema.valueType)
    }

    "fails when a map key is null" {
        val serializer = MapSerializer(String.serializer().nullable, Int.serializer())
        val schema = Avro.apacheSchema(MapSerializer(String.serializer(), Int.serializer()))

        // the key position always reports the non-nullable string schema, so the generic encoder's own
        // "Map key cannot be null" guard is defensive: AbstractAvroEncoder.encodeNull rejects it first.
        shouldThrow<SerializationException> {
            Avro.encodeToGenericDataWith(schema, serializer, mapOf(null to 1))
        }.message shouldBe "Cannot encode null value for non-null schema: \"string\""
    }
}) {
    @Serializable
    private data class RecordWithMap(
        val before: String,
        val map: Map<String, Int>,
        val after: String,
    )

    @Serializable
    private sealed interface SealedValue {
        @Serializable
        data class Circle(val radius: Double) : SealedValue

        @Serializable
        data class Square(val side: Int) : SealedValue

        @Serializable
        data object Nothing : SealedValue
    }

    @JvmInline
    @Serializable
    private value class DecimalHolder(
        @Contextual val value: BigDecimal,
    )

    /**
     * Records the writer schema the decoder reports right before the element is decoded, as a custom
     * serializer choosing its decoding strategy from it would.
     */
    private class SchemaCapturingSerializer<T>(
        private val delegate: KSerializer<T>,
        private val captured: MutableList<Schema>,
    ) : KSerializer<T> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("SchemaCapturing${delegate.descriptor.serialName}", delegate.descriptor.kind as PrimitiveKind)

        override fun serialize(
            encoder: Encoder,
            value: T,
        ) = delegate.serialize(encoder, value)

        override fun deserialize(decoder: Decoder): T {
            captured += (decoder as AvroDecoder).apacheWriterSchema
            return delegate.deserialize(decoder)
        }
    }
}