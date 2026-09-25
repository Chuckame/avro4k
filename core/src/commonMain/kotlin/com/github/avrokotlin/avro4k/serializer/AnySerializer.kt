package com.github.avrokotlin.avro4k.serializer

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Encoder

/**
 * This serializer handles [Any] type, for both encoding and decoding.
 *
 * At encoding, it gets the [KSerializer] from the value's runtime type. How far that inference goes depends on the
 * platform: see the platform's declaration.
 *
 * At decoding, it delegates the process to the inferred [KSerializer] from the [AvroSchema.type] (example: [AvroSchema.Type.BOOLEAN] is handled by the [Boolean]'s serializer).
 * For more customization about named types, see [resolveFixedDeserializationStrategy], [resolveEnumDeserializationStrategy], and [resolveRecordDeserializationStrategy].
 */
@InternalAvro4kApi
public expect open class AnySerializer() : AbstractAnySerializer {
    override fun serialize(
        encoder: Encoder,
        value: Any,
    )
}