package com.github.avrokotlin.avro4k.internal.encoder.direct

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryEncoder
import com.github.avrokotlin.avro4k.internal.schema.elementType
import com.github.avrokotlin.avro4k.internal.schema.valueType
import kotlinx.serialization.descriptors.SerialDescriptor

internal class MapDirectEncoder(private val schema: AvroSchema, mapSize: Int, avro: Avro, binaryEncoder: AvroBinaryEncoder) :
    AbstractAvroDirectEncoder(avro, binaryEncoder) {
    init {
        binaryEncoder.writeMapStart(mapSize.toLong())
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        binaryEncoder.writeMapEnd()
    }

    override lateinit var currentAvroSchema: AvroSchema

    companion object {
        private val STRING_SCHEMA = AvroSchema.StringSchema()
    }

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        super.encodeElement(descriptor, index)
        currentAvroSchema =
            if (index % 2 == 0) {
                binaryEncoder.startItem()
                STRING_SCHEMA
            } else {
                schema.valueType
            }
        return true
    }
}

internal class ArrayDirectEncoder(
    private val arraySchema: AvroSchema,
    arraySize: Int,
    avro: Avro,
    binaryEncoder: AvroBinaryEncoder,
) : AbstractAvroDirectEncoder(avro, binaryEncoder) {
    init {
        binaryEncoder.writeArrayStart(arraySize.toLong())
    }

    override lateinit var currentAvroSchema: AvroSchema

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        super.encodeElement(descriptor, index)
        binaryEncoder.startItem()
        currentAvroSchema = arraySchema.elementType
        return true
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        binaryEncoder.writeArrayEnd()
    }
}