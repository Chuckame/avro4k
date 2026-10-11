package com.github.avrokotlin.avro4k.internal.encoder.generic

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.internal.EncodingWorkflow
import com.github.avrokotlin.avro4k.internal.fields
import com.github.avrokotlin.avro4k.internal.resolveRecordFields
import com.github.avrokotlin.avro4k.toApacheSchema
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeEncoder
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord

@Suppress("FunctionName")
internal fun RecordGenericEncoder(
    descriptor: SerialDescriptor,
    schema: AvroSchema,
    avro: Avro,
    onEncoded: (GenericRecord) -> Unit,
): CompositeEncoder {
    return when (val encodingWorkflow = avro.resolveRecordFields(schema, descriptor).encoding) {
        is EncodingWorkflow.ExactMatch -> RecordContiguousExactEncoder(schema, avro, onEncoded)

        is EncodingWorkflow.ContiguousWithSkips -> RecordContiguousSkippingEncoder(encodingWorkflow.fieldsToSkip, schema, avro, onEncoded)

        is EncodingWorkflow.NonContiguous -> RecordNonContiguousEncoder(encodingWorkflow.descriptorToWriterFieldIndex, schema, avro, onEncoded)

        is EncodingWorkflow.MissingWriterFields -> throw AvroSchemaMismatchException(
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
    private val record = GenericData.Record(schema.toApacheSchema())

    private var currentFieldIndex = -1

    override lateinit var currentAvroSchema: AvroSchema

    protected fun setWriterField(writerFieldIndex: Int) {
        currentFieldIndex = writerFieldIndex
        currentAvroSchema = schema.fields[writerFieldIndex].schema
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        onEncoded(record)
    }

    override fun encodeValue(value: Any) {
        record.put(currentFieldIndex, value)
    }

    override fun encodeNullUnchecked() {
        record.put(currentFieldIndex, null)
    }
}