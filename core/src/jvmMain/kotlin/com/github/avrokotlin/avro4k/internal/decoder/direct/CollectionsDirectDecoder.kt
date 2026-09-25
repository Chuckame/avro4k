package com.github.avrokotlin.avro4k.internal.decoder.direct

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.IllegalIndexedAccessError
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryDecoder
import com.github.avrokotlin.avro4k.internal.schema.elementType
import com.github.avrokotlin.avro4k.internal.schema.valueType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor

internal class ArrayBlockDirectDecoder(
    private val arraySchema: AvroSchema,
    private val decodeFirstBlock: Boolean,
    private val onCollectionSizeDecoded: (Int) -> Unit,
    avro: Avro,
    binaryDecoder: AvroBinaryDecoder,
) : AbstractAvroDirectDecoder(avro, binaryDecoder) {
    override lateinit var currentAvroSchema: AvroSchema

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int {
        return if (decodeFirstBlock) {
            binaryDecoder.readArrayStart().toInt()
        } else {
            binaryDecoder.arrayNext().toInt()
        }.also { onCollectionSizeDecoded(it) }
    }

    override fun beginElement(
        descriptor: SerialDescriptor,
        index: Int,
    ) {
        // reset the current writer schema in of the element is a union (as a union is resolved)
        currentAvroSchema = arraySchema.elementType
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun decodeSequentially() = true

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        throw IllegalIndexedAccessError()
    }
}

internal class MapBlockDirectDecoder(
    private val mapSchema: AvroSchema,
    private val decodeFirstBlock: Boolean,
    private val onCollectionSizeDecoded: (Int) -> Unit,
    avro: Avro,
    binaryDecoder: AvroBinaryDecoder,
) : AbstractAvroDirectDecoder(avro, binaryDecoder) {
    override lateinit var currentAvroSchema: AvroSchema

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int {
        return if (decodeFirstBlock) {
            binaryDecoder.readMapStart().toInt()
        } else {
            binaryDecoder.mapNext().toInt()
        }.also { onCollectionSizeDecoded(it) }
    }

    override fun beginElement(
        descriptor: SerialDescriptor,
        index: Int,
    ) {
        // reset the current writer schema in of the element is a union (as a union is resolved)
        currentAvroSchema = if (index % 2 == 0) KEY_SCHEMA else mapSchema.valueType
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun decodeSequentially() = true

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        throw IllegalIndexedAccessError()
    }

    companion object {
        private val KEY_SCHEMA = AvroSchema.StringSchema()
    }
}