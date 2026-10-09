package com.github.avrokotlin.avro4k.internal.decoder.generic

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.DecodedNullError
import com.github.avrokotlin.avro4k.internal.IllegalIndexedAccessError
import com.github.avrokotlin.avro4k.internal.schema.elementType
import com.github.avrokotlin.avro4k.internal.schema.isUnion
import com.github.avrokotlin.avro4k.internal.schema.types
import com.github.avrokotlin.avro4k.internal.schema.valueType
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder

internal class MapGenericDecoder(
    private val map: Map<CharSequence, Any?>,
    private val writerSchema: AvroSchema,
    override val avro: Avro,
) : AbstractAvroGenericDecoder() {
    /**
     * Walks the entries directly, alternating key then value, instead of flattening them through a
     * `Sequence` of [Pair]s, which cost 2 pair allocations per entry plus the sequence pipeline.
     */
    private val entries = map.entries.iterator()
    private lateinit var currentEntry: Map.Entry<CharSequence, Any?>
    private var nextIsKey = true
    private var currentData: Any? = null
    private var decodedNotNullMark = false

    /**
     * Whether the element being decoded is an entry's key (even index) or its value (odd index).
     *
     * It is set by [decodeSerializableElement] and [decodeInlineElement] *before* the element is read, so
     * that a serializer reading [currentAvroSchema] before decoding sees the right schema, and again by
     * [advance] when the element is read, which covers the primitive `decode*Element` methods that
     * [kotlinx.serialization.encoding.AbstractDecoder] does not let us intercept.
     */
    private var positionedOnKey = true

    /**
     * The generic tree decoder does not resolve unions, so a nullable map (a record field, a map value…)
     * comes with its `["null", map]` union as writer schema: pick its map branch.
     */
    private val mapSchema: AvroSchema =
        if (writerSchema.isUnion) {
            writerSchema.types.firstOrNull { it.type === AvroSchema.Type.MAP } ?: writerSchema
        } else {
            writerSchema
        }

    override val currentAvroSchema: AvroSchema
        get() = if (positionedOnKey) STRING_SCHEMA else mapSchema.valueType

    private fun advance(): Any? {
        positionedOnKey = nextIsKey
        currentData =
            if (nextIsKey) {
                currentEntry = entries.next()
                nextIsKey = false
                currentEntry.key
            } else {
                nextIsKey = true
                currentEntry.value
            }
        return currentData
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T {
        positionedOnKey = index % 2 == 0
        return super.decodeSerializableElement(descriptor, index, deserializer, previousValue)
    }

    override fun decodeInlineElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Decoder {
        positionedOnKey = index % 2 == 0
        return super.decodeInlineElement(descriptor, index)
    }

    override fun decodeNotNullMark(): Boolean {
        decodedNotNullMark = true
        return advance() != null
    }

    override fun decodeValue(): Any {
        if (!decodedNotNullMark) {
            advance()
        } else {
            decodedNotNullMark = false
        }
        return currentData ?: throw DecodedNullError()
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun decodeNull(): Nothing? {
        decodedNotNullMark = false
        return null
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        throw IllegalIndexedAccessError()
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor) = map.size

    @OptIn(ExperimentalSerializationApi::class)
    override fun decodeSequentially() = true

    companion object {
        private val STRING_SCHEMA = AvroSchema.StringSchema()
    }
}

internal class ArrayGenericDecoder(
    private val collection: Collection<Any?>,
    private val writerSchema: AvroSchema,
    override val avro: Avro,
) : AbstractAvroGenericDecoder() {
    private val iterator = collection.iterator()

    private var currentItem: Any? = null
    private var decodedNullMark = false

    /**
     * The generic tree decoder does not resolve unions, so a plain list (not a `GenericArray`, which carries its own schema)
     * in a nullable position comes with its `["null", array]` union as writer schema: pick its array branch.
     */
    private val arraySchema: AvroSchema =
        if (writerSchema.isUnion) {
            writerSchema.types.firstOrNull { it.type === AvroSchema.Type.ARRAY } ?: writerSchema
        } else {
            writerSchema
        }

    override val currentAvroSchema: AvroSchema
        get() = arraySchema.elementType

    override fun decodeNotNullMark(): Boolean {
        decodedNullMark = true
        currentItem = iterator.next()
        return currentItem != null
    }

    override fun decodeValue(): Any {
        val value = if (decodedNullMark) currentItem else iterator.next()
        decodedNullMark = false
        return value ?: throw DecodedNullError()
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        throw IllegalIndexedAccessError()
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor) = collection.size

    @OptIn(ExperimentalSerializationApi::class)
    override fun decodeSequentially() = true
}