package com.github.avrokotlin.avro4k.internal.decoder.generic

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.DecodedNullError
import com.github.avrokotlin.avro4k.internal.IllegalIndexedAccessError
import kotlinx.serialization.descriptors.SerialDescriptor

internal class AvroValueGenericDecoder(
    override val avro: Avro,
    private val value: Any?,
    override val currentAvroSchema: AvroSchema,
) : AbstractAvroGenericDecoder() {
    override fun decodeNotNullMark() = value != null

    override fun decodeValue() = value ?: throw DecodedNullError()

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        throw IllegalIndexedAccessError()
    }
}