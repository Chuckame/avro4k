package com.github.avrokotlin.avro4k.internal.decoder.direct

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.DecodingStep
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryDecoder
import com.github.avrokotlin.avro4k.internal.decoder.JsonDefaultDecoder
import com.github.avrokotlin.avro4k.internal.nonNullSerialName
import com.github.avrokotlin.avro4k.internal.schema.skip
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonNull
import org.apache.avro.generic.GenericFixed

internal class RecordDirectDecoder(
    private val writerRecordSchema: AvroSchema,
    descriptor: SerialDescriptor,
    avro: Avro,
    binaryDecoder: AvroBinaryDecoder,
) : AbstractAvroDirectDecoder(avro, binaryDecoder) {
    // from descriptor element index to schema field. The missing fields are at the end to decode the default values
    private val classDescriptor = avro.recordResolver.resolveFields(writerRecordSchema, descriptor)
    private lateinit var currentDecodingStep: DecodingStep.ValidatedDecodingStep
    private var nextDecodingStepIndex = 0

    override lateinit var currentAvroSchema: AvroSchema

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        var field: DecodingStep
        while (true) {
            if (nextDecodingStepIndex == classDescriptor.decoding.size) {
                return CompositeDecoder.DECODE_DONE
            }
            field = classDescriptor.decoding[nextDecodingStepIndex++]
            when (field) {
                is DecodingStep.IgnoreOptionalElement -> {
                    // loop again to ignore the optional element
                }

                is DecodingStep.SkipWriterField -> binaryDecoder.skip(field.schema)

                is DecodingStep.MissingElementValueFailure -> {
                    throw SerializationException(
                        "Reader field '${descriptor.nonNullSerialName}.${descriptor.getElementName(
                            field.elementIndex
                        )}' has no corresponding field in writer schema ${writerRecordSchema.toSchemaJsonString()}"
                    )
                }

                is DecodingStep.DeserializeWriterField -> {
                    currentDecodingStep = field
                    currentAvroSchema = field.schema
                    return field.elementIndex
                }

                is DecodingStep.GetDefaultValue -> {
                    currentDecodingStep = field
                    currentAvroSchema = field.schema
                    return field.elementIndex
                }
            }
        }
    }

    private fun defaultDecoder(element: DecodingStep.GetDefaultValue) = JsonDefaultDecoder(avro, element.defaultValue, element.schema)

    override fun decodeNotNullMark(): Boolean {
        return when (val element = currentDecodingStep) {
            is DecodingStep.DeserializeWriterField -> super.decodeNotNullMark()
            is DecodingStep.GetDefaultValue -> element.defaultValue !is JsonNull
        }
    }

    override fun decodeNull(): Nothing? {
        return when (val element = currentDecodingStep) {
            is DecodingStep.DeserializeWriterField -> super.decodeNull()

            is DecodingStep.GetDefaultValue -> {
                if (element.defaultValue !is JsonNull) {
                    // Should not occur as decodeNotNullMark() should be called first
                    throw SerializationException("Trying to decode a null value for a missing field while the default value is not null")
                }
                null
            }
        }
    }

    override fun decodeFixed(): GenericFixed {
        return when (val element = currentDecodingStep) {
            is DecodingStep.DeserializeWriterField -> super.decodeFixed()
            is DecodingStep.GetDefaultValue -> defaultDecoder(element).decodeFixed()
        }
    }

    override fun decodeInt(): Int {
        return when (val element = currentDecodingStep) {
            is DecodingStep.DeserializeWriterField -> super.decodeInt()
            is DecodingStep.GetDefaultValue -> defaultDecoder(element).decodeInt()
        }
    }

    override fun decodeLong(): Long {
        return when (val element = currentDecodingStep) {
            is DecodingStep.DeserializeWriterField -> super.decodeLong()
            is DecodingStep.GetDefaultValue -> defaultDecoder(element).decodeLong()
        }
    }

    override fun decodeBoolean(): Boolean {
        return when (val element = currentDecodingStep) {
            is DecodingStep.DeserializeWriterField -> super.decodeBoolean()
            is DecodingStep.GetDefaultValue -> defaultDecoder(element).decodeBoolean()
        }
    }

    override fun decodeChar(): Char {
        return when (val element = currentDecodingStep) {
            is DecodingStep.DeserializeWriterField -> super.decodeChar()
            is DecodingStep.GetDefaultValue -> defaultDecoder(element).decodeChar()
        }
    }

    override fun decodeString(): String {
        return when (val element = currentDecodingStep) {
            is DecodingStep.DeserializeWriterField -> super.decodeString()
            is DecodingStep.GetDefaultValue -> defaultDecoder(element).decodeString()
        }
    }

    override fun decodeDouble(): Double {
        return when (val element = currentDecodingStep) {
            is DecodingStep.DeserializeWriterField -> super.decodeDouble()
            is DecodingStep.GetDefaultValue -> defaultDecoder(element).decodeDouble()
        }
    }

    override fun decodeFloat(): Float {
        return when (val element = currentDecodingStep) {
            is DecodingStep.DeserializeWriterField -> super.decodeFloat()
            is DecodingStep.GetDefaultValue -> defaultDecoder(element).decodeFloat()
        }
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        return when (val element = currentDecodingStep) {
            is DecodingStep.DeserializeWriterField -> super.decodeSerializableValue(deserializer)
            is DecodingStep.GetDefaultValue -> defaultDecoder(element).decodeSerializableValue(deserializer)
        }
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        return when (val element = currentDecodingStep) {
            is DecodingStep.DeserializeWriterField -> super.decodeEnum(enumDescriptor)
            is DecodingStep.GetDefaultValue -> defaultDecoder(element).decodeEnum(enumDescriptor)
        }
    }

    override fun decodeBytes(): ByteArray {
        return when (val element = currentDecodingStep) {
            is DecodingStep.DeserializeWriterField -> super.decodeBytes()
            is DecodingStep.GetDefaultValue -> defaultDecoder(element).decodeBytes()
        }
    }
}