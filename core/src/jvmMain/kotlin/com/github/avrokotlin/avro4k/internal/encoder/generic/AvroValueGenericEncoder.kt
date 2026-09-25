package com.github.avrokotlin.avro4k.internal.encoder.generic

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema

internal class AvroValueGenericEncoder(
    override val avro: Avro,
    override var currentAvroSchema: AvroSchema,
    private val onEncoded: (Any?) -> Unit,
) : AbstractAvroGenericEncoder() {
    override fun encodeValue(value: Any) {
        onEncoded(value)
    }

    override fun encodeNullUnchecked() {
        onEncoded(null)
    }
}