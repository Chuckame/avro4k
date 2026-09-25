package com.github.avrokotlin.avro4k.internal.encoder.direct

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.MissingFieldsEncodingException
import com.github.avrokotlin.avro4k.internal.EncodingWorkflow
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryEncoder
import com.github.avrokotlin.avro4k.internal.encoder.ReorderingCompositeEncoder
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeEncoder

@Suppress("FunctionName")
internal fun RecordDirectEncoder(
    descriptor: SerialDescriptor,
    writerSchema: AvroSchema,
    avro: Avro,
    binaryEncoder: AvroBinaryEncoder,
): CompositeEncoder {
    // the caller checked the type: a record
    val schema = writerSchema as RecordSchema
    return when (val encodingWorkflow = avro.recordResolver.resolveFields(schema, descriptor).encoding) {
        is EncodingWorkflow.ExactMatch -> RecordContiguousExactEncoder(schema, avro, binaryEncoder)

        is EncodingWorkflow.ContiguousWithSkips -> RecordContiguousSkippingEncoder(encodingWorkflow.fieldsToSkip, schema, avro, binaryEncoder)

        is EncodingWorkflow.NonContiguous ->
            ReorderingCompositeEncoder(
                schema.fields.size,
                RecordNonContiguousEncoder(
                    encodingWorkflow.descriptorToWriterFieldIndex,
                    schema,
                    avro,
                    binaryEncoder
                )
            ) { _, index ->
                encodingWorkflow.descriptorToWriterFieldIndex[index]
            }

        is EncodingWorkflow.MissingWriterFields -> throw missingFieldsError(schema, encodingWorkflow, descriptor)
    }
}

/** The message of the public, Apache-typed constructor, built from avro4k's own schema. */
internal fun missingFieldsError(
    schema: RecordSchema,
    workflow: EncodingWorkflow.MissingWriterFields,
    descriptor: SerialDescriptor,
): MissingFieldsEncodingException {
    val missingFields = workflow.missingWriterFields.joinToString("', '") { schema.fields[it].name }
    return MissingFieldsEncodingException(
        "Missing required fields '$missingFields' when encoding with writer schema ${schema.toSchemaJsonString()} from descriptor $descriptor"
    )
}

private class RecordNonContiguousEncoder(
    private val descriptorToWriterFieldIndex: IntArray,
    private val schema: RecordSchema,
    avro: Avro,
    binaryEncoder: AvroBinaryEncoder,
) : AbstractAvroDirectEncoder(avro, binaryEncoder) {
    override lateinit var currentAvroSchema: AvroSchema

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        val writerFieldIndex = descriptorToWriterFieldIndex[index]
        if (writerFieldIndex == -1) {
            return false
        }
        super.encodeElement(descriptor, index)
        currentAvroSchema = schema.fields[writerFieldIndex].schema
        return true
    }
}

private class RecordContiguousSkippingEncoder(
    private val skippedElements: BooleanArray,
    private val schema: RecordSchema,
    avro: Avro,
    binaryEncoder: AvroBinaryEncoder,
) : AbstractAvroDirectEncoder(avro, binaryEncoder) {
    private var nextWriterFieldIndex = 0
    override lateinit var currentAvroSchema: AvroSchema

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        if (skippedElements[index]) {
            return false
        }
        super.encodeElement(descriptor, index)
        currentAvroSchema = schema.fields[nextWriterFieldIndex++].schema
        return true
    }
}

private class RecordContiguousExactEncoder(
    private val schema: RecordSchema,
    avro: Avro,
    binaryEncoder: AvroBinaryEncoder,
) : AbstractAvroDirectEncoder(avro, binaryEncoder) {
    override lateinit var currentAvroSchema: AvroSchema

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        super.encodeElement(descriptor, index)
        currentAvroSchema = schema.fields[index].schema
        return true
    }
}