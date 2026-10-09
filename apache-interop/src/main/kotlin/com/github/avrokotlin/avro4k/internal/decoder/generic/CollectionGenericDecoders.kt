package com.github.avrokotlin.avro4k.internal.decoder.generic

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.DecodedNullError
import com.github.avrokotlin.avro4k.internal.IllegalIndexedAccessError
import com.github.avrokotlin.avro4k.internal.elementType
import com.github.avrokotlin.avro4k.internal.fields
import com.github.avrokotlin.avro4k.internal.valueType
import com.github.avrokotlin.avro4k.toAvro4k
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import org.apache.avro.generic.IndexedRecord

/**
 * Decodes a map-like generic value, entry by entry: the key then the value, as a map serializer reads them.
 */
internal abstract class AbstractMapLikeGenericDecoder : AbstractAvroGenericDecoder() {
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

    /** Moves to the next entry and returns its key. */
    protected abstract fun nextEntryKey(): Any?

    /** The value of the current entry. */
    protected abstract val currentEntryValue: Any?

    /** The writer schema of the current entry's value. */
    protected abstract val currentEntryValueSchema: AvroSchema

    final override val currentAvroSchema: AvroSchema
        get() = if (positionedOnKey) STRING_SCHEMA else currentEntryValueSchema

    // a key's schema is never a union: only read for a value, once its key has set the entry
    final override fun currentGenericValueOrNull(): Any? = if (positionedOnKey) null else currentEntryValue

    private fun advance(): Any? {
        positionedOnKey = nextIsKey
        currentData =
            if (nextIsKey) {
                nextIsKey = false
                nextEntryKey()
            } else {
                nextIsKey = true
                currentEntryValue
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

    override fun decodeGenericValue(): Any {
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

    @OptIn(ExperimentalSerializationApi::class)
    override fun decodeSequentially() = true

    private companion object {
        val STRING_SCHEMA = AvroSchema.StringSchema()
    }
}

internal class MapGenericDecoder(
    private val map: Map<CharSequence, Any?>,
    private val writerSchema: AvroSchema,
    override val avro: Avro,
) : AbstractMapLikeGenericDecoder() {
    /**
     * Walks the entries directly instead of flattening them through a `Sequence` of [Pair]s, which cost 2 pair
     * allocations per entry plus the sequence pipeline.
     */
    private val entries = map.entries.iterator()
    private lateinit var currentEntry: Map.Entry<CharSequence, Any?>

    override fun nextEntryKey(): Any? {
        currentEntry = entries.next()
        return currentEntry.key
    }

    override val currentEntryValue: Any?
        get() = currentEntry.value

    override val currentEntryValueSchema: AvroSchema
        get() = writerSchema.valueType

    override fun decodeCollectionSize(descriptor: SerialDescriptor) = map.size
}

/**
 * A record decoded as a map of its field names to their values, as the binary decoder decodes a record for a map
 * deserializer (`AnySerializer` decodes a record so by default).
 */
internal class RecordAsMapGenericDecoder(
    private val record: IndexedRecord,
    override val avro: Avro,
) : AbstractMapLikeGenericDecoder() {
    private val fields = record.schema.toAvro4k().fields
    private var fieldIndex = -1

    override fun nextEntryKey(): Any? = fields[++fieldIndex].name

    override val currentEntryValue: Any?
        get() = record.get(fieldIndex)

    override val currentEntryValueSchema: AvroSchema
        get() = fields[fieldIndex].schema

    override fun decodeCollectionSize(descriptor: SerialDescriptor) = fields.size
}

internal class ArrayGenericDecoder(
    private val collection: Collection<Any?>,
    private val writerSchema: AvroSchema,
    override val avro: Avro,
) : AbstractAvroGenericDecoder() {
    private val iterator = collection.iterator()

    /**
     * The element at the current position, read from [iterator] at most once: a serializer may read the writer schema
     * (which resolves a union from the element), check nullability, then decode it, in any order. Decoding the element or
     * its `null` moves to the next one.
     */
    private var currentItem: Any? = null
    private var hasCurrentItem = false

    override val currentAvroSchema: AvroSchema
        get() = writerSchema.elementType

    private fun peekItem(): Any? {
        if (!hasCurrentItem) {
            currentItem = iterator.next()
            hasCurrentItem = true
        }
        return currentItem
    }

    override fun currentGenericValueOrNull(): Any? = peekItem()

    override fun decodeNotNullMark(): Boolean = peekItem() != null

    @OptIn(ExperimentalSerializationApi::class)
    override fun decodeNull(): Nothing? {
        hasCurrentItem = false
        return null
    }

    override fun decodeGenericValue(): Any {
        val value = peekItem()
        hasCurrentItem = false
        return value ?: throw DecodedNullError()
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        throw IllegalIndexedAccessError()
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor) = collection.size

    @OptIn(ExperimentalSerializationApi::class)
    override fun decodeSequentially() = true
}