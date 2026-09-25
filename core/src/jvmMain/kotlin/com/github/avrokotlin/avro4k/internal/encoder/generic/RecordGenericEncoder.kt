@file:Suppress("DEPRECATION")

package com.github.avrokotlin.avro4k.internal.encoder.generic

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.ListRecord
import com.github.avrokotlin.avro4k.internal.EncodingWorkflow
import com.github.avrokotlin.avro4k.internal.avro4kToApache
import com.github.avrokotlin.avro4k.internal.schema.fields
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeEncoder
import org.apache.avro.generic.GenericRecord

@Suppress("FunctionName")
internal fun RecordGenericEncoder(
    descriptor: SerialDescriptor,
    schema: AvroSchema,
    avro: Avro,
    onEncoded: (GenericRecord) -> Unit,
): CompositeEncoder {
    return when (val encodingWorkflow = avro.recordResolver.resolveFields(schema, descriptor).encoding) {
        is EncodingWorkflow.ExactMatch -> RecordContiguousExactEncoder(schema, avro, onEncoded)

        is EncodingWorkflow.ContiguousWithSkips -> RecordContiguousSkippingEncoder(encodingWorkflow.fieldsToSkip, schema, avro, onEncoded)

        is EncodingWorkflow.NonContiguous -> RecordNonContiguousEncoder(encodingWorkflow.descriptorToWriterFieldIndex, schema, avro, onEncoded)

        is EncodingWorkflow.MissingWriterFields -> throw SerializationException(
            "Missing writer fields ${encodingWorkflow.missingWriterFields.map { schema.fields[it].name }} from the descriptor $descriptor"
        )
    }
}

private class RecordNonContiguousEncoder(
    private val descriptorToWriterFieldIndex: IntArray,
    schema: AvroSchema,
    avro: Avro,
    onEncoded: (GenericRecord) -> Unit,
) : AbstractRecordGenericEncoder(avro, schema, onEncoded) {
    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        val writerFieldIndex = descriptorToWriterFieldIndex[index]
        if (writerFieldIndex == -1) {
            return false
        }
        super.encodeElement(descriptor, index)
        setWriterField(writerFieldIndex)
        return true
    }
}

private class RecordContiguousSkippingEncoder(
    private val skippedElements: BooleanArray,
    schema: AvroSchema,
    avro: Avro,
    onEncoded: (GenericRecord) -> Unit,
) : AbstractRecordGenericEncoder(avro, schema, onEncoded) {
    private var nextWriterFieldIndex = 0

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        if (skippedElements[index]) {
            return false
        }
        super.encodeElement(descriptor, index)
        setWriterField(nextWriterFieldIndex++)
        return true
    }
}

private class RecordContiguousExactEncoder(
    schema: AvroSchema,
    avro: Avro,
    onEncoded: (GenericRecord) -> Unit,
) : AbstractRecordGenericEncoder(avro, schema, onEncoded) {
    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        super.encodeElement(descriptor, index)
        setWriterField(index)
        return true
    }
}

private abstract class AbstractRecordGenericEncoder(
    override val avro: Avro,
    private val schema: AvroSchema,
    private val onEncoded: (GenericRecord) -> Unit,
) : AbstractAvroGenericEncoder() {
    private val fieldValues: Array<Any?> = Array(schema.fields.size) { null }

    private var currentFieldIndex = -1

    override lateinit var currentAvroSchema: AvroSchema

    protected fun setWriterField(writerFieldIndex: Int) {
        currentFieldIndex = writerFieldIndex
        currentAvroSchema = schema.fields[writerFieldIndex].schema
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        onEncoded(ListRecord(avro4kToApache(schema), fieldValues.asList()))
    }

    override fun encodeValue(value: Any) {
        fieldValues[currentFieldIndex] = value
    }

    override fun encodeNullUnchecked() {
        fieldValues[currentFieldIndex] = null
    }
}