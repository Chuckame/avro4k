package com.github.avrokotlin.avro4k.internal.decoder

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.AvroSerializationException
import com.github.avrokotlin.avro4k.internal.DecodingStep
import com.github.avrokotlin.avro4k.internal.NullableUnionField
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryReader
import com.github.avrokotlin.avro4k.internal.nonNullSerialName
import com.github.avrokotlin.avro4k.internal.schema.skip
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import com.github.avrokotlin.avro4k.internal.unionIndexOutOfRangeError
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonNull

/**
 * Decodes a record whose fields differ from the writer schema's: reordered, skipped, or taking a default value. The
 * others are decoded by [ExactRecordDecoder].
 */
internal class RecordDecoder(
    avro: Avro,
    binaryReader: AvroBinaryReader,
    /** The writer schema of the record being decoded, and with [resolvedDescriptor], what [decodingSteps] were resolved for. */
    private var writerRecordSchema: AvroSchema,
    descriptor: SerialDescriptor,
    // in the writer schema's field order. The missing fields are at the end to decode the default values
    private var decodingSteps: Array<DecodingStep>,
) : AbstractAvroBinaryDecoder(avro, binaryReader) {
    private var resolvedDescriptor: SerialDescriptor = descriptor

    /**
     * The next step [decodeElementIndex] takes; [NOT_IN_USE] once the record is ended. 0, the first step, on a new
     * decoder, which is created with its record begun.
     */
    private var nextDecodingStepIndex = 0

    /**
     * The index in [decodingSteps] after the [DecodingStep.GetDefaultValue] of the field being decoded, or [READ_FROM_INPUT]
     * when the field is read from the input. An int rather than the step: it is written for every field.
     */
    private var stepAfterDefaultValue = READ_FROM_INPUT

    /** Whether a record is begun on this decoder and not ended yet. */
    val inUse: Boolean
        get() = nextDecodingStepIndex >= 0

    /** Whether the [decodingSteps] are the ones resolved for [writerRecordSchema] read into [descriptor], by identity. */
    fun isResolvedFor(
        writerRecordSchema: AvroSchema,
        descriptor: SerialDescriptor,
    ): Boolean = writerRecordSchema === this.writerRecordSchema && descriptor === resolvedDescriptor

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

                is DecodingStep.SkipWriterField -> binaryReader.skip(field.schema)

                is DecodingStep.MissingElementValueFailure -> {
                    throw AvroSchemaMismatchException(
                        "Reader field '${descriptor.nonNullSerialName}.${descriptor.getElementName(
                            field.elementIndex
                        )}' has no corresponding field in writer schema ${writerRecordSchema.toSchemaJsonString()}"
                    )
                }

                is DecodingStep.DeserializeWriterField -> {
                    stepAfterDefaultValue = READ_FROM_INPUT
                    currentAvroSchema = field.schema
                    return field.elementIndex
                }

                is DecodingStep.GetDefaultValue -> {
                    stepAfterDefaultValue = nextDecodingStepIndex
                    currentAvroSchema = field.schema
                    return field.elementIndex
                }
            }
        }
    }

    /** The step of the field being decoded, which takes its default value: the one before [stepAfter]. */
    private fun defaultValueStep(stepAfter: Int): DecodingStep.GetDefaultValue = decodingSteps[stepAfter - 1] as DecodingStep.GetDefaultValue

    private fun defaultDecoder(stepAfter: Int): JsonDefaultDecoder {
        val element = defaultValueStep(stepAfter)
        return JsonDefaultDecoder(avro, element.defaultValue, element.schema)
    }

    override fun decodeNotNullMark(): Boolean {
        val step = stepAfterDefaultValue
        return if (step == READ_FROM_INPUT) super.decodeNotNullMark() else defaultValueStep(step).defaultValue !is JsonNull
    }

    override fun decodeNull(): Nothing? {
        val step = stepAfterDefaultValue
        if (step == READ_FROM_INPUT) return super.decodeNull()
        if (defaultValueStep(step).defaultValue !is JsonNull) {
            // Should not occur as decodeNotNullMark() should be called first
            throw AvroSerializationException("Trying to decode a null value for a missing field while the default value is not null")
        }
        return null
    }

    override fun decodeFixed(): ByteArray {
        val step = stepAfterDefaultValue
        return if (step == READ_FROM_INPUT) super.decodeFixed() else defaultDecoder(step).decodeFixed()
    }

    override fun decodeInt(): Int {
        val step = stepAfterDefaultValue
        return if (step == READ_FROM_INPUT) super.decodeInt() else defaultDecoder(step).decodeInt()
    }

    override fun decodeLong(): Long {
        val step = stepAfterDefaultValue
        return if (step == READ_FROM_INPUT) super.decodeLong() else defaultDecoder(step).decodeLong()
    }

    override fun decodeBoolean(): Boolean {
        val step = stepAfterDefaultValue
        return if (step == READ_FROM_INPUT) super.decodeBoolean() else defaultDecoder(step).decodeBoolean()
    }

    override fun decodeChar(): Char {
        val step = stepAfterDefaultValue
        return if (step == READ_FROM_INPUT) super.decodeChar() else defaultDecoder(step).decodeChar()
    }

    override fun decodeString(): String {
        val step = stepAfterDefaultValue
        return if (step == READ_FROM_INPUT) super.decodeString() else defaultDecoder(step).decodeString()
    }

    override fun decodeDouble(): Double {
        val step = stepAfterDefaultValue
        return if (step == READ_FROM_INPUT) super.decodeDouble() else defaultDecoder(step).decodeDouble()
    }

    override fun decodeFloat(): Float {
        val step = stepAfterDefaultValue
        return if (step == READ_FROM_INPUT) super.decodeFloat() else defaultDecoder(step).decodeFloat()
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        val step = stepAfterDefaultValue
        return if (step == READ_FROM_INPUT) super.decodeSerializableValue(deserializer) else defaultDecoder(step).decodeSerializableValue(deserializer)
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val step = stepAfterDefaultValue
        return if (step == READ_FROM_INPUT) super.decodeEnum(enumDescriptor) else defaultDecoder(step).decodeEnum(enumDescriptor)
    }

    override fun decodeBytes(): ByteArray {
        val step = stepAfterDefaultValue
        return if (step == READ_FROM_INPUT) super.decodeBytes() else defaultDecoder(step).decodeBytes()
    }

    /** Begins decoding a record written with [writerRecordSchema] into [descriptor], with its resolved [decodingSteps]. */
    fun reset(
        writerRecordSchema: AvroSchema,
        descriptor: SerialDescriptor,
        decodingSteps: Array<DecodingStep>,
    ): RecordDecoder {
        this.decodingSteps = decodingSteps
        this.writerRecordSchema = writerRecordSchema
        resolvedDescriptor = descriptor
        return reset()
    }

    /** Begins decoding a record of the type this decoder was last reset for, from its first field. */
    fun reset(): RecordDecoder {
        stepAfterDefaultValue = READ_FROM_INPUT
        currentAvroSchema = NO_ELEMENT_SCHEMA
        beginReuse()
        nextDecodingStepIndex = 0
        return this
    }
}

/** [RecordDecoder]'s field being decoded is read from the input, not taken from a default value. */
private const val READ_FROM_INPUT = 0

/**
 * Decodes a record whose fields are exactly the writer schema's, in the same order: field `i` is element `i`, none is
 * skipped and none takes a default value. Its elements are read in order ([decodeSequentially]), without a decoding
 * step per field, and a nullable element written as a union of `null` and one other type reads its branch directly.
 */
internal class ExactRecordDecoder(
    avro: Avro,
    binaryReader: AvroBinaryReader,
    /** The writer schema of the record being decoded, and with [resolvedDescriptor], what [fieldSchemas] were resolved for. */
    private var writerRecordSchema: AvroSchema,
    descriptor: SerialDescriptor,
    /** The writer schema of each field, by element index. */
    private var fieldSchemas: Array<AvroSchema>,
    /** Each field's [NullableUnionField], by element index, or null when its schema is not such a union. */
    private var nullableFields: Array<NullableUnionField?>,
) : AbstractAvroBinaryDecoder(avro, binaryReader) {
    private var resolvedDescriptor: SerialDescriptor = descriptor

    /**
     * The next element [decodeElementIndex] returns; [NOT_IN_USE] once the record is ended. 0, the first element, on a
     * new decoder, which is created with its record begun.
     */
    private var nextElementIndex = 0

    /** Whether a record is begun on this decoder and not ended yet. */
    val inUse: Boolean
        get() = nextElementIndex >= 0

    /** Whether the [fieldSchemas] are the ones resolved for [writerRecordSchema] read into [descriptor], by identity. */
    fun isResolvedFor(
        writerRecordSchema: AvroSchema,
        descriptor: SerialDescriptor,
    ): Boolean = writerRecordSchema === this.writerRecordSchema && descriptor === resolvedDescriptor

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

    /**
     * A nullable element read from a field written as `["null", T]` or `[T, "null"]` takes its branch from the index
     * read, compared with the null branch's index, without looking the union's branches up through
     * [decodeNotNullMark]; any other nullable element decodes as on every decoder. Here rather than in
     * [decodeNotNullMark], which does not know the element, so that knowing it costs no field begun anything.
     *
     * Only while the element's schema is still its field's union, compared by identity: a branch already read (asking
     * for `currentWriterSchema` after [decodeElementIndex] reads it) is not read again. And only for a deserializer of a
     * non-nullable type, the one kotlinx calls only for a value: a nullable one is handed its null too.
     *
     * An index of neither branch fails as on the general path, and the input is read with the same calls.
     */
    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? {
        beginElement(descriptor, index)
        val field = nullableFields.getOrNull(index)
        if (field == null || currentAvroSchema !== field.union || deserializer.descriptor.isNullable) return decodeNullableValue(deserializer)
        val branchIndex = binaryReader.readIndex()
        if (branchIndex == field.nullIndex) {
            currentAvroSchema = field.nullBranch
            binaryReader.readNull()
            return null
        }
        if (branchIndex != field.valueIndex) throw unionIndexOutOfRangeError(branchIndex, field.union)
        currentAvroSchema = field.valueBranch
        return decodeSerializableValue(deserializer)
    }

    /** A nullable element's value as every decoder decodes it, out of [decodeNullableSerializableElement]'s short path. */
    private fun <T : Any> decodeNullableValue(deserializer: DeserializationStrategy<T?>): T? = decodeIfNullable(deserializer) { decodeSerializableValue(deserializer) }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val index = nextElementIndex
        if (index >= fieldSchemas.size) return CompositeDecoder.DECODE_DONE
        nextElementIndex = index + 1
        currentAvroSchema = fieldSchemas[index]
        return index
    }

    /**
     * Begins decoding a record written with [writerRecordSchema] into [descriptor], whose fields have [fieldSchemas], and
     * [nullableFields] for those written as a union of `null` and one other type.
     */
    fun reset(
        writerRecordSchema: AvroSchema,
        descriptor: SerialDescriptor,
        fieldSchemas: Array<AvroSchema>,
        nullableFields: Array<NullableUnionField?>,
    ): ExactRecordDecoder {
        this.fieldSchemas = fieldSchemas
        this.nullableFields = nullableFields
        this.writerRecordSchema = writerRecordSchema
        resolvedDescriptor = descriptor
        return reset()
    }

    /** Begins decoding a record of the type this decoder was last reset for, from its first field. */
    fun reset(): ExactRecordDecoder {
        currentAvroSchema = NO_ELEMENT_SCHEMA
        beginReuse()
        nextElementIndex = 0
        return this
    }
}