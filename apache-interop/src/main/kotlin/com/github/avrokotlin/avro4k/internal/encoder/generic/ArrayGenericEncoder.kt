package com.github.avrokotlin.avro4k.internal.encoder.generic

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.elementType
import com.github.avrokotlin.avro4k.toApacheSchema
import kotlinx.serialization.descriptors.SerialDescriptor
import org.apache.avro.generic.GenericArray
import org.apache.avro.generic.GenericData

internal class ArrayGenericEncoder(
    override val avro: Avro,
    arraySize: Int,
    private val schema: AvroSchema,
    private val onEncoded: (GenericArray<*>) -> Unit,
) : AbstractAvroGenericEncoder() {
    private val values: Array<Any?> = Array(arraySize) { null }
    private var index = 0

    override lateinit var currentAvroSchema: AvroSchema

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        super.encodeElement(descriptor, index)
        currentAvroSchema = schema.elementType
        return true
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        onEncoded(GenericData.Array(schema.toApacheSchema(), values.asList()))
    }

    override fun encodeValue(value: Any) {
        values[index++] = value
    }

    override fun encodeNullUnchecked() {
        values[index++] = null
    }
}