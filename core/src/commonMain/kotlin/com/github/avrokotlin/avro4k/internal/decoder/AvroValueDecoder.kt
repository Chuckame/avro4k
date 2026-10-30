package com.github.avrokotlin.avro4k.internal.decoder

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.IllegalIndexedAccessError
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryReader
import kotlinx.serialization.descriptors.SerialDescriptor

internal class AvroValueDecoder(
    writerSchema: AvroSchema,
    avro: Avro,
    binaryReader: AvroBinaryReader,
) : AbstractAvroBinaryDecoder(avro, binaryReader) {
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        throw IllegalIndexedAccessError()
    }

    init {
        currentAvroSchema = writerSchema
    }

    /**
     * Makes this decoder decode a new value written with [writerSchema], as a new decoder would. The polymorphic decoder
     * keeps its value decoder and hands it each next value this way, so that the decoders this one keeps (the subclass's
     * record decoder, mostly) are reused too.
     */
    fun reset(writerSchema: AvroSchema): AvroValueDecoder {
        currentAvroSchema = writerSchema
        beginReuse()
        return this
    }
}