package com.github.avrokotlin.avro4k.internal.encoder

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.MissingFieldsEncodingException
import com.github.avrokotlin.avro4k.internal.EncodingWorkflow
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryWriter
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeEncoder

/** A new encoder for a record whose fields are written in another order than its elements: buffered, then replayed. */
internal fun reorderingRecordEncoder(
    schema: RecordSchema,
    encodingWorkflow: EncodingWorkflow.NonContiguous,
    avro: Avro,
    binaryWriter: AvroBinaryWriter,
): CompositeEncoder =
    ReorderingCompositeEncoder(
        schema.fields.size,
        RecordNonContiguousEncoder(
            encodingWorkflow.descriptorToWriterFieldIndex,
            schema,
            avro,
            binaryWriter
        )
    ) { _, index ->
        encodingWorkflow.descriptorToWriterFieldIndex[index]
    }

internal fun missingFieldsError(
    schema: RecordSchema,
    workflow: EncodingWorkflow.MissingWriterFields,
    descriptor: SerialDescriptor,
): MissingFieldsEncodingException = MissingFieldsEncodingException(workflow.missingWriterFields.map { schema.fields[it] }, schema, descriptor)

private class RecordNonContiguousEncoder(
    private val descriptorToWriterFieldIndex: IntArray,
    private val schema: RecordSchema,
    avro: Avro,
    binaryWriter: AvroBinaryWriter,
) : AbstractAvroBinaryEncoder(avro, binaryWriter) {
    override lateinit var currentWriterSchema: AvroSchema

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        val writerFieldIndex = descriptorToWriterFieldIndex[index]
        if (writerFieldIndex == -1) {
            return false
        }
        super.encodeElement(descriptor, index)
        currentWriterSchema = schema.fields[writerFieldIndex].schema
        return true
    }
}

/**
 * Encodes one record after another whose elements map to the writer fields in order, some elements having no field:
 * its parent keeps it and hands it each next record resolved to this workflow, through [reset]. [schema] and
 * [resolvedDescriptor] are what [skippedElements] was resolved for, compared by identity by the parent before reusing it.
 */
internal class RecordContiguousSkippingEncoder(
    schema: RecordSchema,
    resolvedDescriptor: SerialDescriptor,
    skippedElements: BooleanArray,
    avro: Avro,
    binaryWriter: AvroBinaryWriter,
) : AbstractAvroBinaryEncoder(avro, binaryWriter) {
    var schema: RecordSchema = schema
        private set
    var resolvedDescriptor: SerialDescriptor = resolvedDescriptor
        private set
    var skippedElements: BooleanArray = skippedElements
        private set
    private var nextWriterFieldIndex = 0

    /** The schema of the field being encoded: none until [encodeElement] begins one, as on a new encoder. */
    private var fieldSchema: AvroSchema? = null
    override var currentWriterSchema: AvroSchema
        get() = fieldSchema ?: throw noElementBegunError()
        set(value) {
            fieldSchema = value
        }

    fun reset(
        schema: RecordSchema,
        resolvedDescriptor: SerialDescriptor,
        skippedElements: BooleanArray,
    ): RecordContiguousSkippingEncoder {
        this.schema = schema
        this.resolvedDescriptor = resolvedDescriptor
        this.skippedElements = skippedElements
        nextWriterFieldIndex = 0
        fieldSchema = null
        beginReuse()
        return this
    }

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        if (skippedElements[index]) {
            return false
        }
        super.encodeElement(descriptor, index)
        fieldSchema = schema.fields[nextWriterFieldIndex++].schema
        return true
    }
}

/**
 * Encodes one record after another whose elements map one to one to the writer fields, in order: its parent keeps it
 * and hands it each next record resolved to this workflow, through [reset]. [schema] and [resolvedDescriptor] are what it
 * was resolved for, compared by identity by the parent before reusing it.
 */
internal class RecordContiguousExactEncoder(
    schema: RecordSchema,
    resolvedDescriptor: SerialDescriptor,
    avro: Avro,
    binaryWriter: AvroBinaryWriter,
) : AbstractAvroBinaryEncoder(avro, binaryWriter) {
    var schema: RecordSchema = schema
        private set
    var resolvedDescriptor: SerialDescriptor = resolvedDescriptor
        private set

    /** The schema of the field being encoded: none until [encodeElement] begins one, as on a new encoder. */
    private var fieldSchema: AvroSchema? = null
    override var currentWriterSchema: AvroSchema
        get() = fieldSchema ?: throw noElementBegunError()
        set(value) {
            fieldSchema = value
        }

    fun reset(
        schema: RecordSchema,
        resolvedDescriptor: SerialDescriptor,
    ): RecordContiguousExactEncoder {
        this.schema = schema
        this.resolvedDescriptor = resolvedDescriptor
        fieldSchema = null
        beginReuse()
        return this
    }

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        super.encodeElement(descriptor, index)
        fieldSchema = schema.fields[index].schema
        return true
    }
}