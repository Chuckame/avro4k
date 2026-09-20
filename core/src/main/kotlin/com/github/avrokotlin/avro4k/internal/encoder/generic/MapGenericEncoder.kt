package com.github.avrokotlin.avro4k.internal.encoder.generic

import com.github.avrokotlin.avro4k.Avro
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import org.apache.avro.Schema

private val STRING_SCHEMA = Schema.create(Schema.Type.STRING)

internal class MapGenericEncoder(
    override val avro: Avro,
    mapSize: Int,
    private val schema: Schema,
    private val onEncoded: (Map<String, Any?>) -> Unit,
) : AbstractAvroGenericEncoder() {
    /**
     * Entries are accumulated straight into the resulting [LinkedHashMap] instead of into an intermediate
     * list of [Pair]s, which cost 2 allocations per entry (the pair built on insertion, and the pair
     * rebuilt by `associate`). [LinkedHashMap] keeps the encounter order, and re-putting an already
     * present key keeps its original position and overwrites its value, exactly like `associate` did.
     */
    private val entries: MutableMap<String, Any?> = LinkedHashMap(mapSize)
    private var currentKey: String? = null

    override lateinit var currentWriterSchema: Schema

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        super.encodeElement(descriptor, index)
        currentWriterSchema =
            if (index % 2 == 0) {
                currentKey = null
                STRING_SCHEMA
            } else {
                schema.valueType
            }
        return true
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        onEncoded(entries)
    }

    override fun encodeValue(value: Any) {
        val key = currentKey
        if (key == null) {
            currentKey = value.toString()
        } else {
            entries[key] = value
        }
    }

    override fun encodeNullUnchecked() {
        val key = currentKey ?: throw SerializationException("Map key cannot be null")
        entries[key] = null
    }
}