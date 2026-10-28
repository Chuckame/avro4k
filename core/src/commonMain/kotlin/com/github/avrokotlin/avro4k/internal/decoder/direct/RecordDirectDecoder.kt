package com.github.avrokotlin.avro4k.internal.decoder.direct

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.AvroSerializationException
import com.github.avrokotlin.avro4k.internal.DecodingStep
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryDecoder
import com.github.avrokotlin.avro4k.internal.decoder.JsonDefaultDecoder
import com.github.avrokotlin.avro4k.internal.nonNullSerialName
import com.github.avrokotlin.avro4k.internal.schema.skip
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonNull

private val NO_DECODING_STEPS = emptyArray<DecodingStep>()

/**
 * Decodes one record after another: its parent decoder keeps it and hands it each next record it begins, through
 * [reset], instead of creating a decoder per record. Nothing of the previous record survives a [reset].
 */
internal class RecordDirectDecoder(
    avro: Avro,
    binaryDecoder: AvroBinaryDecoder,
) : AbstractAvroDirectDecoder(avro, binaryDecoder) {
    /** The writer schema of the record being decoded, and with [resolvedDescriptor], what [decodingSteps] were resolved for. */
    private var writerRecordSchema: AvroSchema? = null
    private var resolvedDescriptor: SerialDescriptor? = null

    // in the writer schema's field order. The missing fields are at the end to decode the default values
    private var decodingSteps: Array<DecodingStep> = NO_DECODING_STEPS
    private var nextDecodingStepIndex = 0

    /** The step of the field being decoded: none until [decodeElementIndex] returns a field, as on a new decoder. */
    private var decodingStep: DecodingStep.ValidatedDecodingStep? = null
    private val currentDecodingStep: DecodingStep.ValidatedDecodingStep
        get() = decodingStep ?: throw noElementBegunError()

    private var fieldSchema: AvroSchema? = null
    override var currentAvroSchema: AvroSchema
        get() = fieldSchema ?: throw noElementBegunError()
        set(value) {
            fieldSchema = value
        }

    /**
     * Begins decoding a record written with [writerRecordSchema] into [descriptor], from its first field.
     *
     * The decoding steps are only resolved when the schema or the descriptor is not the previous record's: the records
     * of a list, or a field's successive values, are most often of one type, and so never reach the resolver. Compared
     * by identity, as resolving is: a schema or descriptor equal to the previous one but another instance is resolved.
     */
    fun reset(
        writerRecordSchema: AvroSchema,
        descriptor: SerialDescriptor,
    ): RecordDirectDecoder {
        if (writerRecordSchema !== this.writerRecordSchema || descriptor !== resolvedDescriptor) {
            // resolved before the keys change: a failing resolution leaves the previous record's steps with their keys
            decodingSteps = avro.recordResolver.resolveFields(writerRecordSchema, descriptor).decoding
            this.writerRecordSchema = writerRecordSchema
            resolvedDescriptor = descriptor
        }
        nextDecodingStepIndex = 0
        decodingStep = null
        fieldSchema = null
        beginReuse()
        return this
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        var field: DecodingStep
        while (true) {
            if (nextDecodingStepIndex == decodingSteps.size) {
                return CompositeDecoder.DECODE_DONE
            }
            field = decodingSteps[nextDecodingStepIndex++]
            when (field) {
                is DecodingStep.IgnoreOptionalElement -> {
                    // loop again to ignore the optional element
                }

                is DecodingStep.SkipWriterField -> binaryDecoder.skip(field.schema)

                is DecodingStep.MissingElementValueFailure -> {
                    throw AvroSchemaMismatchException(
                        "Reader field '${descriptor.nonNullSerialName}.${descriptor.getElementName(
                            field.elementIndex
                        )}' has no corresponding field in writer schema ${writerRecordSchema?.toSchemaJsonString()}"
                    )
                }

                is DecodingStep.DeserializeWriterField -> {
                    decodingStep = field
                    fieldSchema = field.schema
                    return field.elementIndex
                }

                is DecodingStep.GetDefaultValue -> {
                    decodingStep = field
                    fieldSchema = field.schema
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
                    throw AvroSerializationException("Trying to decode a null value for a missing field while the default value is not null")
                }
                null
            }
        }
    }

    override fun decodeFixed(): ByteArray {
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