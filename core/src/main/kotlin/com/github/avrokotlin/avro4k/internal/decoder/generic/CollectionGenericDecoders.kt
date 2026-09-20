package com.github.avrokotlin.avro4k.internal.decoder.generic

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.internal.DecodedNullError
import com.github.avrokotlin.avro4k.internal.IllegalIndexedAccessError
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import org.apache.avro.Schema

internal class MapGenericDecoder(
    private val map: Map<CharSequence, Any?>,
    @Suppress("unused") private val writerSchema: Schema,
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

    // NOTE: behaviour kept verbatim from the previous `Pair`-based implementation, which tagged BOTH the
    // key and the value of each entry as "is a key" and therefore always reported the string schema; the
    // `writerSchema.valueType` branch was dead. Fixing that is a behaviour change, tracked in
    // docs/plans/notes/c9.md.
    override val currentWriterSchema: Schema
        get() = STRING_SCHEMA

    private fun advance(): Any? {
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
        private val STRING_SCHEMA = Schema.create(Schema.Type.STRING)
    }
}

internal class ArrayGenericDecoder(
    private val collection: Collection<Any?>,
    private val writerSchema: Schema,
    override val avro: Avro,
) : AbstractAvroGenericDecoder() {
    private val iterator = collection.iterator()

    private var currentItem: Any? = null
    private var decodedNullMark = false

    override val currentWriterSchema: Schema
        get() = writerSchema.elementType

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