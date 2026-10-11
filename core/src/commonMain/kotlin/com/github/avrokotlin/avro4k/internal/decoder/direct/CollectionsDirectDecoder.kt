package com.github.avrokotlin.avro4k.internal.decoder.direct

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryDecoder
import com.github.avrokotlin.avro4k.internal.schema.elementType
import com.github.avrokotlin.avro4k.internal.schema.valueType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder

/**
 * The largest block whose items are decoded with their count known in advance ([CompositeDecoder.decodeSequentially]).
 *
 * Given the count up front, kotlinx reserves the collection's capacity for the whole block before reading any item
 * (lists, arrays and primitive arrays do). The count comes from the input, so a corrupted or hostile one (about 2
 * billion items fit in five bytes) would make it allocate gigabytes for items the input does not contain. A block above
 * this size is therefore decoded item by item instead ([CompositeDecoder.decodeElementIndex]): the collection grows as
 * items actually arrive, and a truncated input fails at its end having allocated no more than what it holds. Up to this
 * size, a reservation is small whatever the input says, and the common, small collections keep the faster path.
 */
internal const val MAX_PRESIZED_BLOCK_ITEMS: Int = 1024

/** The items of one array block, [blockSize] of them, already read and checked by the decoder that begins it. */
internal class ArrayBlockDirectDecoder(
    private val arraySchema: AvroSchema,
    private val blockSize: Int,
    avro: Avro,
    binaryDecoder: AvroBinaryDecoder,
) : AbstractAvroDirectDecoder(avro, binaryDecoder) {
    override lateinit var currentAvroSchema: AvroSchema

    /** The next item index [decodeElementIndex] returns, relative to the block: kotlinx adds the items of the previous blocks. */
    private var nextItemIndex = 0

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = blockSize

    override fun beginElement(
        descriptor: SerialDescriptor,
        index: Int,
    ) {
        // reset the current writer schema in of the element is a union (as a union is resolved)
        currentAvroSchema = arraySchema.elementType
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun decodeSequentially() = blockSize <= MAX_PRESIZED_BLOCK_ITEMS

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return if (nextItemIndex < blockSize) nextItemIndex++ else CompositeDecoder.DECODE_DONE
    }
}

/** The entries of one map block, [blockSize] of them, already read and checked by the decoder that begins it. */
internal class MapBlockDirectDecoder(
    private val mapSchema: AvroSchema,
    private val blockSize: Int,
    avro: Avro,
    binaryDecoder: AvroBinaryDecoder,
) : AbstractAvroDirectDecoder(avro, binaryDecoder) {
    override lateinit var currentAvroSchema: AvroSchema

    private var decodedEntries = 0

    /**
     * The element index of the value of the key just decoded, for [decodeElementIndex]; -1 when a key comes next.
     * kotlinx requires it to be the key's index plus one, and the key's index includes the entries of the previous
     * blocks, which only [beginElement] sees.
     */
    private var pendingValueIndex = -1

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = blockSize

    override fun beginElement(
        descriptor: SerialDescriptor,
        index: Int,
    ) {
        // reset the current writer schema in of the element is a union (as a union is resolved)
        if (index % 2 == 0) {
            currentAvroSchema = KEY_SCHEMA
            pendingValueIndex = index + 1
        } else {
            currentAvroSchema = mapSchema.valueType
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun decodeSequentially() = blockSize <= MAX_PRESIZED_BLOCK_ITEMS

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val valueIndex = pendingValueIndex
        if (valueIndex >= 0) {
            pendingValueIndex = -1
            return valueIndex
        }
        if (decodedEntries >= blockSize) return CompositeDecoder.DECODE_DONE
        // A key's index, relative to the block: kotlinx adds the elements of the previous blocks
        return 2 * decodedEntries++
    }

    companion object {
        private val KEY_SCHEMA = AvroSchema.StringSchema()
    }
}