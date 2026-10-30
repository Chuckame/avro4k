package com.github.avrokotlin.avro4k.internal.encoder

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryWriter
import com.github.avrokotlin.avro4k.internal.schema.PlainSchemas
import com.github.avrokotlin.avro4k.internal.schema.asType
import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * Encodes one map after another: its parent keeps it and hands it each next map it begins, through [reset], instead of
 * creating an encoder per map. Nothing of the previous map survives a [reset].
 */
internal class MapEncoder(avro: Avro, binaryWriter: AvroBinaryWriter) :
    AbstractAvroBinaryEncoder(avro, binaryWriter) {
    /** The writer schema of the map's values, read once per map. */
    private var valueType: AvroSchema? = null

    /** The schema of the key or value being encoded: none until [encodeElement] begins one, as on a new encoder. */
    private var entrySchema: AvroSchema? = null
    override var currentWriterSchema: AvroSchema
        get() = entrySchema ?: throw noElementBegunError()
        set(value) {
            entrySchema = value
        }

    /** Begins a map of [mapSize] entries written with [mapSchema]: writes its start, as a new encoder did when created. */
    fun reset(
        mapSchema: AvroSchema.MapSchema,
        mapSize: Int,
    ): MapEncoder {
        valueType = mapSchema.valueSchema
        entrySchema = null
        binaryWriter.writeMapStart(mapSize.toLong())
        beginReuse()
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        binaryWriter.writeMapEnd()
        super.endStructure(descriptor)
    }

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        super.encodeElement(descriptor, index)
        // kotlinx-serialization encodes key and value separately, the key (always a string) coming first, then the value
        entrySchema =
            if (index % 2 == 0) {
                binaryWriter.startItem()
                PlainSchemas.STRING
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
internal class ArrayEncoder(
    avro: Avro,
    binaryWriter: AvroBinaryWriter,
) : AbstractAvroBinaryEncoder(avro, binaryWriter) {
    /** The writer schema of the array's items, read once per array. */
    private var elementType: AvroSchema? = null

    /** The schema of the item being encoded: none until [encodeElement] begins one, as on a new encoder. */
    private var itemSchema: AvroSchema? = null
    override var currentWriterSchema: AvroSchema
        get() = itemSchema ?: throw noElementBegunError()
        set(value) {
            itemSchema = value
        }

    /** Begins an array of [arraySize] items written with [arraySchema]: writes its start, as a new encoder did when created. */
    fun reset(
        arraySchema: AvroSchema.ArraySchema,
        arraySize: Int,
    ): ArrayEncoder {
        elementType = arraySchema.elementSchema
        itemSchema = null
        binaryWriter.writeArrayStart(arraySize.toLong())
        beginReuse()
        return this
    }

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        super.encodeElement(descriptor, index)
        binaryWriter.startItem()
        itemSchema = elementType
        return true
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        binaryWriter.writeArrayEnd()
        super.endStructure(descriptor)
    }
}