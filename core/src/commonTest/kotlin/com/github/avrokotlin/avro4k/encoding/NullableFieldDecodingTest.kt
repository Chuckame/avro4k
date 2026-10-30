package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroMalformedDataException
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.codec.ByteArrayBinaryWriter
import com.github.avrokotlin.avro4k.schema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test

/**
 * The nullable fields of a record decoded exactly as written, whose writer schema is a union of `null` and one other
 * type, in either order: null and non-null values, out-of-range branch indexes, and the paths that must keep decoding as
 * any other union (a branch already read, a nullable serializer, a non-union writer field, a non-nullable reader field).
 */
@OptIn(ExperimentalSerializationApi::class)
class NullableFieldDecodingTest {
    @Test
    fun `nullable fields written as null first unions decode nulls and values`() {
        decodesBothWays(Avro.schema(NullableFields.serializer().descriptor))
    }

    @Test
    fun `nullable fields written as null last unions decode nulls and values`() {
        decodesBothWays(Avro.schema(NullableFields.serializer().descriptor).withNullLast())
    }

    @Test
    fun `an out of range branch index fails with the same message`() {
        val nullFirst = Avro.schema(NullableIntHolder.serializer().descriptor)
        for (writerSchema in listOf(nullFirst, nullFirst.withNullLast())) {
            val name = if (writerSchema === nullFirst) "union[null, int]" else "union[int, null]"
            for (index in listOf(2, -1)) {
                val bytes =
                    ByteArrayBinaryWriter().apply {
                        writeInt(1)
                        writeIndex(index)
                        writeInt(5)
                    }.toByteArray()
                shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(writerSchema, NullableIntHolder.serializer(), bytes) }
                    .message shouldBe "Malformed data: union index $index out of range [0, 2) for $name"
                // the validating decoder checks the index as it reads it
                shouldThrow<AvroMalformedDataException> { VALIDATING.decodeFromByteArray(writerSchema, NullableIntHolder.serializer(), bytes) }
                    .message shouldBe "Invalid call readIndex() at 'value' for the writer schema: expected a branch index of $name in [0, 2), but got $index"
            }
        }
    }

    @Test
    fun `a nullable field read from a non union writer field decodes`() {
        val writerSchema = Avro.schema(NonNullIntHolder.serializer().descriptor)
        val bytes = Avro.encodeToByteArray(ListSerializer(NonNullIntHolder.serializer()), listOf(NonNullIntHolder(1, 2), NonNullIntHolder(3, 4)))
        for (avro in AVROS) {
            avro.decodeFromByteArray(AvroSchema.ArraySchema(writerSchema), ListSerializer(NullableIntHolder.serializer()), bytes) shouldBe
                listOf(NullableIntHolder(1, 2), NullableIntHolder(3, 4))
        }
    }

    @Test
    fun `a non nullable field read from a nullable writer union fails or decodes as before`() {
        // A known limit kept as it is: a primitive or string property is decoded without reading the union's index
        val writerSchema = Avro.schema(NullableIntHolder.serializer().descriptor)
        val bytes = Avro.encodeToByteArray(writerSchema, NullableIntHolder.serializer(), NullableIntHolder(1, 300))
        shouldThrow<SerializationException> { Avro.decodeFromByteArray(writerSchema, NonNullIntHolder.serializer(), bytes) }.message shouldBe
            "Unsupported schema named 'union[null, int]' for decoded type of INT, and also not matching to any compatible type " +
            "(one of LONG, STRING).. Actual schema: [\"null\",\"int\"]"
        // a record property reads the union's index first, and decodes
        val recordWriter = Avro.schema(NullableRecordHolder.serializer().descriptor)
        val recordBytes = Avro.encodeToByteArray(recordWriter, NullableRecordHolder.serializer(), NullableRecordHolder(NullableIntHolder(2, 3)))
        for (avro in AVROS) {
            avro.decodeFromByteArray(recordWriter, NonNullRecordHolder.serializer(), recordBytes) shouldBe NonNullRecordHolder(NullableIntHolder(2, 3))
        }
    }

    @Test
    fun `a nullable field whose branch was read before it is decoded is not read again`() {
        val writerSchema = Avro.schema(NullableIntHolder.serializer().descriptor)
        for (writer in listOf(writerSchema, writerSchema.withNullLast())) {
            val values = listOf(NullableIntHolder(1, 2), NullableIntHolder(3, null), NullableIntHolder(4, 5))
            val bytes = Avro.encodeToByteArray(AvroSchema.ArraySchema(writer), ListSerializer(NullableIntHolder.serializer()), values)
            for (avro in AVROS) {
                avro.decodeFromByteArray(AvroSchema.ArraySchema(writer), ListSerializer(BranchPeekingSerializer), bytes) shouldBe values
                avro.decodeFromByteArray(AvroSchema.ArraySchema(writer), ListSerializer(IndexedSerializer), bytes) shouldBe values
            }
        }
    }

    @Test
    fun `a nullable serializer given for a nullable field still decodes its null`() {
        val writerSchema = Avro.schema(NullableIntHolder.serializer().descriptor)
        for (writer in listOf(writerSchema, writerSchema.withNullLast())) {
            val bytes =
                Avro.encodeToByteArray(
                    AvroSchema.ArraySchema(writer),
                    ListSerializer(NullableIntHolder.serializer()),
                    listOf(NullableIntHolder(1, null), NullableIntHolder(2, 3))
                )
            for (avro in AVROS) {
                avro.decodeFromByteArray(AvroSchema.ArraySchema(writer), ListSerializer(NullAsMinusOneHolder.serializer()), bytes) shouldBe
                    listOf(NullAsMinusOneHolder(1, -1), NullAsMinusOneHolder(2, 3))
            }
        }
    }

    private fun decodesBothWays(writerSchema: AvroSchema) {
        val values =
            listOf(
                NullableFields(1, "a", 2L, true, 3.5, NullableColor.GREEN, NullableIntHolder(4, 5), listOf(6, 7), 8.5f),
                NullableFields(null, null, null, null, null, null, null, null, null),
                NullableFields(9, "", -10L, false, -11.25, NullableColor.RED, NullableIntHolder(12, null), emptyList(), null),
                NullableFields(null, "b", null, true, null, NullableColor.GREEN, null, listOf(13), 14f)
            )
        val listSchema = AvroSchema.ArraySchema(writerSchema)
        val bytes = Avro.encodeToByteArray(listSchema, ListSerializer(NullableFields.serializer()), values)
        for (avro in AVROS) {
            avro.decodeFromByteArray(listSchema, ListSerializer(NullableFields.serializer()), bytes) shouldBe values
        }
    }

    companion object {
        val VALIDATING = Avro { validateSerialization = true }
        val AVROS = listOf(Avro, VALIDATING)
    }
}

/** The same record schema, with each union's branches in reverse order: `["null", T]` becomes `[T, "null"]`. */
private fun AvroSchema.withNullLast(): AvroSchema {
    val record = this as AvroSchema.RecordSchema
    return AvroSchema.RecordSchema(
        record.name,
        record.fields.map { field ->
            val schema = field.schema
            AvroSchema.RecordSchema.Field(field.name, if (schema is AvroSchema.UnionSchema) AvroSchema.UnionSchema(schema.types.reversed()) else schema)
        }
    )
}

@Serializable
@SerialName("NullableColor")
private enum class NullableColor { RED, GREEN }

@Serializable
@SerialName("NullableIntHolder")
private data class NullableIntHolder(val id: Int, val value: Int?)

@Serializable
@SerialName("NonNullIntHolder")
private data class NonNullIntHolder(val id: Int, val value: Int)

@Serializable
@SerialName("NullableRecordHolder")
private data class NullableRecordHolder(val record: NullableIntHolder?)

@Serializable
@SerialName("NonNullRecordHolder")
private data class NonNullRecordHolder(val record: NullableIntHolder)

@Serializable
@SerialName("NullableFields")
private data class NullableFields(
    val int: Int?,
    val string: String?,
    val long: Long?,
    val boolean: Boolean?,
    val double: Double?,
    val color: NullableColor?,
    val record: NullableIntHolder?,
    val list: List<Int>?,
    val float: Float?,
)

/** Decodes `null` as -1: a serializer of a nullable type, handed the null itself. */
@OptIn(ExperimentalSerializationApi::class)
private object NullAsMinusOne : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = Int.serializer().nullable.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Int?,
    ) = Int.serializer().nullable.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): Int? =
        if (decoder.decodeNotNullMark()) {
            decoder.decodeInt()
        } else {
            decoder.decodeNull()
            -1
        }
}

@Serializable
@SerialName("NullAsMinusOneHolder")
private data class NullAsMinusOneHolder(
    val id: Int,
    @Serializable(with = NullAsMinusOne::class) val value: Int?,
)

/**
 * Reads [NullableIntHolder] by index, asking for the writer schema of its nullable field before decoding it: that reads
 * the union's branch, which the decoding of the field must then not read again.
 */
private object BranchPeekingSerializer : KSerializer<NullableIntHolder> {
    override val descriptor: SerialDescriptor get() = NullableIntHolder.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: NullableIntHolder,
    ) = NullableIntHolder.serializer().serialize(encoder, value)

    override fun deserialize(decoder: Decoder): NullableIntHolder {
        val record = decoder.beginStructure(descriptor)
        record.decodeElementIndex(descriptor) shouldBe 0
        val id = record.decodeIntElement(descriptor, 0)
        record.decodeElementIndex(descriptor) shouldBe 1
        val branch = (record as AvroDecoder).currentWriterSchema
        val value = record.decodeNullableSerializableElement(descriptor, 1, Int.serializer(), null)
        branch.type shouldBe if (value == null) AvroSchema.Type.NULL else AvroSchema.Type.INT
        record.decodeElementIndex(descriptor) shouldBe CompositeDecoder.DECODE_DONE
        record.endStructure(descriptor)
        return NullableIntHolder(id, value)
    }
}

/** Reads [NullableIntHolder] by index, as a serializer ignoring sequential decoding does. */
private object IndexedSerializer : KSerializer<NullableIntHolder> {
    override val descriptor: SerialDescriptor get() = NullableIntHolder.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: NullableIntHolder,
    ) = NullableIntHolder.serializer().serialize(encoder, value)

    override fun deserialize(decoder: Decoder): NullableIntHolder {
        val record = decoder.beginStructure(descriptor)
        record.decodeElementIndex(descriptor) shouldBe 0
        val id = record.decodeIntElement(descriptor, 0)
        record.decodeElementIndex(descriptor) shouldBe 1
        val value = record.decodeNullableSerializableElement(descriptor, 1, Int.serializer(), null)
        record.decodeElementIndex(descriptor) shouldBe CompositeDecoder.DECODE_DONE
        record.endStructure(descriptor)
        return NullableIntHolder(id, value)
    }
}