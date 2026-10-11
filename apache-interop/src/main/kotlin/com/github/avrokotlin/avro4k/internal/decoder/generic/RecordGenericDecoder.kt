package com.github.avrokotlin.avro4k.internal.decoder.generic

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSerializationException
import com.github.avrokotlin.avro4k.ResolvedSchema
import com.github.avrokotlin.avro4k.internal.DecodedNullError
import com.github.avrokotlin.avro4k.internal.DecodingStep
import com.github.avrokotlin.avro4k.internal.defaultValueDecoder
import com.github.avrokotlin.avro4k.internal.resolveRecordFields
import com.github.avrokotlin.avro4k.toApacheSchema
import com.github.avrokotlin.avro4k.toAvro4k
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonNull
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.IndexedRecord

internal class RecordGenericDecoder(
    private val record: IndexedRecord,
    private val descriptor: SerialDescriptor,
    override val avro: Avro,
) : AbstractAvroGenericDecoder() {
    // from descriptor element index to schema field
    private val classDescriptor = avro.resolveRecordFields(record.schema.toAvro4k(), descriptor)
    private lateinit var currentElement: DecodingStep.ValidatedDecodingStep
    private var nextDecodingStep = 0

    override val currentAvroSchema: AvroSchema
        get() = currentElement.schema

    override fun decodeNotNullMark() =
        when (val element = currentElement) {
            is DecodingStep.DeserializeWriterField -> record.get(element.writerFieldIndex) != null
            is DecodingStep.GetDefaultValue -> element.defaultValue !is JsonNull
        }

    /**
     * A default value is decoded as a whole by its own decoder, which also handles structures; the primitive
     * `decode*` methods of the base class go through [decodeGenericValue].
     */
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        return when (val element = currentElement) {
            is DecodingStep.DeserializeWriterField -> super.decodeSerializableValue(deserializer)
            is DecodingStep.GetDefaultValue -> avro.defaultValueDecoder(element).decodeSerializableValue(deserializer)
        }
    }

    override fun decodeGenericValue(): Any {
        return when (val element = currentElement) {
            is DecodingStep.DeserializeWriterField ->
                record.get(element.writerFieldIndex) ?: throw DecodedNullError(descriptor, element.elementIndex)

            is DecodingStep.GetDefaultValue ->
                if (element.defaultValue is JsonNull) {
                    throw DecodedNullError(descriptor, element.elementIndex)
                } else {
                    avro.defaultValueDecoder(element).decodeGenericDefaultValue()
                }
        }
    }

    override fun currentGenericValueOrNull(): Any? =
        when (val element = currentElement) {
            is DecodingStep.DeserializeWriterField -> record.get(element.writerFieldIndex)

            // no generic value: a default is resolved from its json, below
            is DecodingStep.GetDefaultValue -> null
        }

    /** A missing field's default picks its branch from its json value, as its own decoder does to decode it. */
    override fun resolveUnion(union: AvroSchema.UnionSchema): ResolvedSchema =
        when (val element = currentElement) {
            is DecodingStep.DeserializeWriterField -> super.resolveUnion(union)
            is DecodingStep.GetDefaultValue -> avro.defaultValueDecoder(element).currentWriterSchema
        }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        var field: DecodingStep
        do {
            if (nextDecodingStep == classDescriptor.decoding.size) {
                return CompositeDecoder.DECODE_DONE
            }
            field = classDescriptor.decoding[nextDecodingStep++]
        } while (field !is DecodingStep.ValidatedDecodingStep)
        currentElement = field
        return field.elementIndex
    }
}

/**
 * A primitive, enum or fixed default as the generic value a writer record would hold for it, so that the base class's
 * `decode*` methods convert it exactly as they convert a value read from the record. Each value is read from the default's
 * own decoder with the typed method of its schema, which is never a union there. A structure default is decoded as a whole
 * by [RecordGenericDecoder.decodeSerializableValue] instead.
 */
private fun AvroDecoder.decodeGenericDefaultValue(): Any {
    val schema = currentWriterSchema
    return when (schema.type) {
        AvroSchema.Type.BOOLEAN -> decodeBoolean()

        AvroSchema.Type.INT -> decodeInt()

        AvroSchema.Type.LONG -> decodeLong()

        AvroSchema.Type.FLOAT -> decodeFloat()

        AvroSchema.Type.DOUBLE -> decodeDouble()

        AvroSchema.Type.STRING, AvroSchema.Type.ENUM -> decodeString()

        AvroSchema.Type.BYTES -> decodeBytes()

        // an Apache fixed carrying its schema, as a writer record holds it
        AvroSchema.Type.FIXED -> GenericData.Fixed(schema.toApacheSchema(), decodeFixed())

        else -> throw AvroSerializationException("A default value of type ${schema.type} cannot be decoded as a generic value")
    }
}