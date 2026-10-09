package com.github.avrokotlin.avro4k.internal.decoder

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.InternalAvro4kApi

/**
 * An avro4k decoder that knows the [Avro] instance it decodes for. [com.github.avrokotlin.avro4k.serializer.AnySerializer]
 * reads that instance's configuration (its logical types) through it, as [AvroDecoder] does not expose it.
 *
 * Implemented by every avro4k decoder: the binary ones, the decoder of a missing field's default, and the generic data
 * decoders of the `apache-interop` module, which is why it is public.
 */
@InternalAvro4kApi
public interface AvroFormatDecoder : AvroDecoder {
    public val avro: Avro
}