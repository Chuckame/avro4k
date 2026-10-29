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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonNull

private val NO_DECODING_STEPS = emptyArray<DecodingStep>()

private val NO_FIELD_SCHEMAS = emptyArray<AvroSchema>()

/**
 * Decodes one record after another: its parent decoder keeps it and hands it each next record it begins, through
 * [reset], instead of creating a decoder per record. Nothing of the previous record survives a [reset].
 *
 * Decodes the records whose fields do not match the writer schema's exactly: reordered, skipped, or taking a default
 * value. The others are decoded by [ExactRecordDirectDecoder].
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

    /** The next step [decodeElementIndex] takes; [NOT_IN_USE] when no record is begun or once it is ended. */
    private var nextDecodingStepIndex = NOT_IN_USE

    /** The step of the field being decoded: none until [decodeElementIndex] returns a field, as on a new decoder. */
    private var decodingStep: DecodingStep.ValidatedDecodingStep? = null
    private val currentDecodingStep: DecodingStep.ValidatedDecodingStep
        get() = decodingStep ?: throw noElementBegunError()

    /** Whether a record is begun on this decoder and not ended yet. */
    val inUse: Boolean
        get() = nextDecodingStepIndex >= 0

    /** Whether the [decodingSteps] are the ones resolved for [writerRecordSchema] read into [descriptor], by identity. */
    fun isResolvedFor(
        writerRecordSchema: AvroSchema,
        descriptor: SerialDescriptor,
    ): Boolean = writerRecordSchema === this.writerRecordSchema && descriptor === resolvedDescriptor

    /** Begins decoding a record written with [writerRecordSchema] into [descriptor], with its resolved [decodingSteps]. */
    fun reset(
        writerRecordSchema: AvroSchema,
        descriptor: SerialDescriptor,
        decodingSteps: Array<DecodingStep>,
    ): RecordDirectDecoder {
        this.decodingSteps = decodingSteps
        this.writerRecordSchema = writerRecordSchema
        resolvedDescriptor = descriptor
        return reset()
    }

    /** Begins decoding a record of the type this decoder was last reset for, from its first field. */
    fun reset(): RecordDirectDecoder {
        decodingStep = null
        currentAvroSchema = NO_ELEMENT_SCHEMA
        beginReuse()
        nextDecodingStepIndex = 0
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        nextDecodingStepIndex = NOT_IN_USE
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
                    currentAvroSchema = field.schema
                    return field.elementIndex
                }

                is DecodingStep.GetDefaultValue -> {
                    decodingStep = field
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

/**
 * Decodes the records whose elements are exactly the writer schema's fields, in its order: field i is element i, no
 * writer field is skipped and no element takes a default value (the workflow's `exactFieldSchemas`). Most records are
 * read with the schema they were written with, and so are such records.
 *
 * Their elements are read in order: [decodeSequentially] lets kotlinx's generated deserializers decode each element in
 * turn, without asking [decodeElementIndex] which one comes next and dispatching on its answer. Each element's schema is
 * taken by its index when it is begun, and each value decoded as it is, without the per-field decoding step checks that
 * [RecordDirectDecoder] makes for default values. A serializer that ignores [decodeSequentially] still gets every element
 * in order from [decodeElementIndex], then [CompositeDecoder.DECODE_DONE].
 *
 * Like [RecordDirectDecoder], its parent decoder keeps it and hands it each next exact record it begins, through
 * [reset]. Nothing of the previous record survives a [reset].
 */
internal class ExactRecordDirectDecoder(
    avro: Avro,
    binaryDecoder: AvroBinaryDecoder,
) : AbstractAvroDirectDecoder(avro, binaryDecoder) {
    /** The writer schema of the record being decoded, and with [resolvedDescriptor], what [fieldSchemas] were resolved for. */
    private var writerRecordSchema: AvroSchema? = null
    private var resolvedDescriptor: SerialDescriptor? = null

    /** The writer schema of each field, by element index. */
    private var fieldSchemas: Array<AvroSchema> = NO_FIELD_SCHEMAS

    /** The next element [decodeElementIndex] returns; [NOT_IN_USE] when no record is begun or once it is ended. */
    private var nextElementIndex = NOT_IN_USE

    /** Whether a record is begun on this decoder and not ended yet. */
    val inUse: Boolean
        get() = nextElementIndex >= 0

    /** Whether the [fieldSchemas] are the ones resolved for [writerRecordSchema] read into [descriptor], by identity. */
    fun isResolvedFor(
        writerRecordSchema: AvroSchema,
        descriptor: SerialDescriptor,
    ): Boolean = writerRecordSchema === this.writerRecordSchema && descriptor === resolvedDescriptor

    /** Begins decoding a record written with [writerRecordSchema] into [descriptor], whose fields have [fieldSchemas]. */
    fun reset(
        writerRecordSchema: AvroSchema,
        descriptor: SerialDescriptor,
        fieldSchemas: Array<AvroSchema>,
    ): ExactRecordDirectDecoder {
        this.fieldSchemas = fieldSchemas
        this.writerRecordSchema = writerRecordSchema
        resolvedDescriptor = descriptor
        return reset()
    }

    /** Begins decoding a record of the type this decoder was last reset for, from its first field. */
    fun reset(): ExactRecordDirectDecoder {
        currentAvroSchema = NO_ELEMENT_SCHEMA
        beginReuse()
        nextElementIndex = 0
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        nextElementIndex = NOT_IN_USE
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun decodeSequentially(): Boolean = true

    override fun beginElement(
        descriptor: SerialDescriptor,
        index: Int,
    ) {
        // The element decodeElementIndex just returned keeps the schema it was given there, as on the general record
        // decoder: a union branch already read (asking for currentWriterSchema reads it) must not be read again. Read in
        // order, decodeElementIndex is never called, and every element is given its field's schema here.
        if (index != nextElementIndex - 1) currentAvroSchema = fieldSchemas[index]
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val index = nextElementIndex
        if (index >= fieldSchemas.size) return CompositeDecoder.DECODE_DONE
        nextElementIndex = index + 1
        currentAvroSchema = fieldSchemas[index]
        return index
    }
}