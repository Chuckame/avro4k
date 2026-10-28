package com.github.avrokotlin.avro4k.internal.encoder.direct

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryEncoder
import com.github.avrokotlin.avro4k.internal.schema.elementType
import com.github.avrokotlin.avro4k.internal.schema.valueType
import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * Encodes one map after another: its parent keeps it and hands it each next map it begins, through [reset], instead of
 * creating an encoder per map. Nothing of the previous map survives a [reset].
 */
internal class MapDirectEncoder(avro: Avro, binaryEncoder: AvroBinaryEncoder) :
    AbstractAvroDirectEncoder(avro, binaryEncoder) {
    /** The writer schema of the map's values, read once per map. */
    private var valueType: AvroSchema? = null

    /** The schema of the key or value being encoded: none until [encodeElement] begins one, as on a new encoder. */
    private var entrySchema: AvroSchema? = null
    override var currentAvroSchema: AvroSchema
        get() = entrySchema ?: throw noElementBegunError()
        set(value) {
            entrySchema = value
        }

    /** Begins a map of [mapSize] entries written with [mapSchema]: writes its start, as a new encoder did when created. */
    fun reset(
        mapSchema: AvroSchema,
        mapSize: Int,
    ): MapDirectEncoder {
        valueType = mapSchema.valueType
        entrySchema = null
        binaryEncoder.writeMapStart(mapSize.toLong())
        beginReuse()
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        binaryEncoder.writeMapEnd()
        super.endStructure(descriptor)
    }

    companion object {
        private val STRING_SCHEMA = AvroSchema.StringSchema()
    }

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        super.encodeElement(descriptor, index)
        entrySchema =
            if (index % 2 == 0) {
                binaryEncoder.startItem()
                STRING_SCHEMA
            } else {
                valueType
            }
        return true
    }
}

/**
 * Encodes one array after another: its parent keeps it and hands it each next array it begins, through [reset], instead
 * of creating an encoder per array. Nothing of the previous array survives a [reset].
 */
internal class ArrayDirectEncoder(
    avro: Avro,
    binaryEncoder: AvroBinaryEncoder,
) : AbstractAvroDirectEncoder(avro, binaryEncoder) {
    /** The writer schema of the array's items, read once per array. */
    private var elementType: AvroSchema? = null

    /** The schema of the item being encoded: none until [encodeElement] begins one, as on a new encoder. */
    private var itemSchema: AvroSchema? = null
    override var currentAvroSchema: AvroSchema
        get() = itemSchema ?: throw noElementBegunError()
        set(value) {
            itemSchema = value
        }

    /** Begins an array of [arraySize] items written with [arraySchema]: writes its start, as a new encoder did when created. */
    fun reset(
        arraySchema: AvroSchema,
        arraySize: Int,
    ): ArrayDirectEncoder {
        elementType = arraySchema.elementType
        itemSchema = null
        binaryEncoder.writeArrayStart(arraySize.toLong())
        beginReuse()
        return this
    }

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        super.encodeElement(descriptor, index)
        binaryEncoder.startItem()
        itemSchema = elementType
        return true
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        binaryEncoder.writeArrayEnd()
        super.endStructure(descriptor)
    }
}