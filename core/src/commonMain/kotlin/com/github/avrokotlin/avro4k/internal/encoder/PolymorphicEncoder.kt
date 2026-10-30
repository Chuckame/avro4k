package com.github.avrokotlin.avro4k.internal.encoder

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryWriter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.modules.SerializersModule

/**
 * Encodes the value of a polymorphic structure, the discriminator being the union branch its value selects. Its parent
 * keeps it and hands it each next polymorphic structure through [reset]; it keeps the encoder of its values in turn.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class PolymorphicEncoder(
    private val avro: Avro,
    schema: AvroSchema,
    private val binaryWriter: AvroBinaryWriter,
) : AbstractEncoder() {
    private var schema: AvroSchema = schema

    /** The encoder of this structure's values, created on first use. */
    private var valueEncoder: AvroValueEncoder? = null

    /** From [reset] to [endStructure], as for the other encoders' `inUse`. */
    var inUse = false
        private set

    /**
     * Whether [valueEncoder] encodes a value not done yet: a value serializer that encodes on this encoder again from
     * inside its value, or a value that failed, leaves it set, and every next value gets a new encoder of its own, as
     * before values were reused.
     */
    private var encodingValue = false

    override val serializersModule: SerializersModule
        get() = avro.serializersModule

    /** Begins a polymorphic structure written with [schema]. Its value encoder is kept, with its own guard. */
    fun reset(schema: AvroSchema): PolymorphicEncoder {
        this.schema = schema
        inUse = true
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        inUse = false
    }

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        // index 0 is the type discriminator, index 1 is the value itself
        // we don't need the type discriminator here
        return index == 1
    }

    override fun <T> encodeSerializableValue(
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        if (encodingValue) {
            AvroValueEncoder(schema, avro, binaryWriter).encodeSerializableValue(serializer, value)
            return
        }
        val encoder = valueEncoder?.reset(schema) ?: AvroValueEncoder(schema, avro, binaryWriter).also { valueEncoder = it }
        encodingValue = true
        encoder.encodeSerializableValue(serializer, value)
        encodingValue = false
    }
}