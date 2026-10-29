package com.github.avrokotlin.avro4k.internal.decoder.direct

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.IllegalIndexedAccessError
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryDecoder
import kotlinx.serialization.descriptors.SerialDescriptor

internal class AvroValueDirectDecoder(
    writerSchema: AvroSchema,
    avro: Avro,
    binaryDecoder: AvroBinaryDecoder,
) : AbstractAvroDirectDecoder(avro, binaryDecoder) {
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
    fun reset(writerSchema: AvroSchema): AvroValueDirectDecoder {
        currentAvroSchema = writerSchema
        beginReuse()
        return this
    }
}