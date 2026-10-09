package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.apacheSchema
import com.github.avrokotlin.avro4k.apacheWriterSchema
import com.github.avrokotlin.avro4k.decodeFromGenericDataWith
import com.github.avrokotlin.avro4k.encodeToGenericDataWith
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
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
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import java.math.BigDecimal

/**
 * The generic (tree) array decoder, for a plain `java.util.List` in a nullable position.
 *
 * Unlike a `GenericArray`, a plain list carries no schema, so the decoder takes the schema of its position. In a nullable
 * position that is the `["null", array]` union, as the generic tree decoder does not resolve unions: the decoder must
 * pick its array branch, or reading the element schema fails with `Not an array`.
 */
internal class GenericDataArrayEncodingTest : StringSpec({
    "decodes a nullable plain list of logical-typed elements in a record" {
        val expected = RecordWithNullableList(listOf(DecimalHolder(BigDecimal("1.50")), DecimalHolder(BigDecimal("-2.25"))))
        val schema = Avro.apacheSchema(RecordWithNullableList.serializer())

        val encoded = Avro.encodeToGenericDataWith(schema, RecordWithNullableList.serializer(), expected) as GenericRecord
        val withPlainList = GenericData.Record(schema).apply { put("decimals", ArrayList(encoded.get("decimals") as Collection<*>)) }

        Avro.decodeFromGenericDataWith(schema, RecordWithNullableList.serializer(), withPlainList) shouldBe expected
    }

    "decodes a null nullable list in a record" {
        val expected = RecordWithNullableList(null)
        val schema = Avro.apacheSchema(RecordWithNullableList.serializer())

        val encoded = Avro.encodeToGenericDataWith(schema, RecordWithNullableList.serializer(), expected)

        Avro.decodeFromGenericDataWith(schema, RecordWithNullableList.serializer(), encoded) shouldBe expected
    }

    "decodes a nullable plain list at the root" {
        val expected = listOf(DecimalHolder(BigDecimal("3.00")))
        val serializer = ListSerializer(DecimalHolder.serializer()).nullable
        val schema = Avro.apacheSchema(serializer)

        val encoded = Avro.encodeToGenericDataWith(schema, serializer, expected) as Collection<*>

        Avro.decodeFromGenericDataWith(schema, serializer, ArrayList(encoded)) shouldBe expected
    }

    "decodes nullable plain lists of plain lists in map values" {
        // the inner list reads the element schema of the outer one to build its own decoder
        val expected = linkedMapOf("a" to listOf(listOf(1, 2), emptyList(), listOf(3)), "nulled" to null)
        val serializer = MapSerializer(String.serializer(), ListSerializer(ListSerializer(Int.serializer())).nullable)
        val schema = Avro.apacheSchema(serializer)

        val encoded = Avro.encodeToGenericDataWith(schema, serializer, expected) as Map<*, *>
        val withPlainLists = encoded.mapValues { (_, outer) -> (outer as Collection<*>?)?.map { ArrayList(it as Collection<*>) }?.let { ArrayList(it) } }

        Avro.decodeFromGenericDataWith(schema, serializer, withPlainLists) shouldBe expected
    }

    "reports the element schema as the current writer schema of a nullable plain list" {
        val captured = mutableListOf<Schema>()
        val plainSerializer = ListSerializer(Int.serializer()).nullable
        val capturingSerializer = ListSerializer(SchemaCapturingSerializer(Int.serializer(), captured)).nullable
        val schema = Avro.apacheSchema(plainSerializer)

        Avro.decodeFromGenericDataWith(schema, capturingSerializer, arrayListOf(1, 2)) shouldBe listOf(1, 2)
        captured shouldContainExactly listOf(Schema.create(Schema.Type.INT), Schema.create(Schema.Type.INT))
    }
}) {
    @Serializable
    private data class RecordWithNullableList(
        val decimals: List<DecimalHolder>?,
    )

    @JvmInline
    @Serializable
    private value class DecimalHolder(
        @Contextual val value: BigDecimal,
    )

    /** Records the writer schema the decoder reports right before the element is decoded. */
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