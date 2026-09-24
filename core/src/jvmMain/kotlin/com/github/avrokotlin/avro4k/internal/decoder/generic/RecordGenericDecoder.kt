package com.github.avrokotlin.avro4k.internal.decoder.generic

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.internal.DecodedNullError
import com.github.avrokotlin.avro4k.internal.DecodingStep
import com.github.avrokotlin.avro4k.internal.decoder.JsonDefaultDecoder
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonNull
import org.apache.avro.Schema
import org.apache.avro.generic.IndexedRecord

internal class RecordGenericDecoder(
    private val record: IndexedRecord,
    private val descriptor: SerialDescriptor,
    override val avro: Avro,
) : AbstractAvroGenericDecoder() {
    // from descriptor element index to schema field
    private val classDescriptor = avro.recordResolver.resolveFields(record.schema, descriptor)
    private lateinit var currentElement: DecodingStep.ValidatedDecodingStep
    private var nextDecodingStep = 0

    override val currentWriterSchema: Schema
        get() = currentElement.schema

    override fun decodeNotNullMark() =
        when (val element = currentElement) {
            is DecodingStep.DeserializeWriterField -> record.get(element.writerFieldIndex) != null
            is DecodingStep.GetDefaultValue -> element.defaultValue !is JsonNull
        }

    /**
     * A default value is decoded as a whole by its own decoder, which also handles structures; the primitive
     * `decode*` methods of the base class go through [decodeValue].
     */
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        return when (val element = currentElement) {
            is DecodingStep.DeserializeWriterField -> super.decodeSerializableValue(deserializer)
            is DecodingStep.GetDefaultValue -> JsonDefaultDecoder(avro, element.defaultValue, element.schema).decodeSerializableValue(deserializer)
        }
    }

    @Suppress("DEPRECATION")
    override fun decodeValue(): Any {
        return when (val element = currentElement) {
            is DecodingStep.DeserializeWriterField ->
                record.get(element.writerFieldIndex) ?: throw DecodedNullError(descriptor, element.elementIndex)

            is DecodingStep.GetDefaultValue ->
                if (element.defaultValue is JsonNull) {
                    throw DecodedNullError(descriptor, element.elementIndex)
                } else {
                    JsonDefaultDecoder(avro, element.defaultValue, element.schema).decodeValue()
                }
        }
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