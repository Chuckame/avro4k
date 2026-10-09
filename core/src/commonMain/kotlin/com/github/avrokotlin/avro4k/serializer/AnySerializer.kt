package com.github.avrokotlin.avro4k.serializer

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Encoder

/**
 * This serializer handles [Any] type, for both encoding and decoding. It is the contextual serializer of [Any] in
 * [com.github.avrokotlin.avro4k.Avro.Default]'s serializers module.
 *
 * At encoding, it gets the [KSerializer] from the value's runtime type. How far that inference goes depends on the
 * platform: see the platform's declaration.
 *
 * At decoding, it decodes a value of any writer schema, without knowing it in advance: in a custom serializer,
 * `decoder.decodeSerializableValue(AnySerializer())` decodes whatever the
 * [currentWriterSchema][com.github.avrokotlin.avro4k.AvroDecoder.currentWriterSchema] is. It delegates to the [KSerializer]
 * inferred from that schema:
 * - a schema whose logical type is in [AvroConfiguration.logicalTypes][com.github.avrokotlin.avro4k.AvroConfiguration.logicalTypes]
 *   is decoded by the serializer registered for it (e.g. `uuid` as [kotlin.uuid.Uuid], `duration` as [AvroDuration]),
 * - otherwise by its [AvroSchema.type]: a primitive as the matching Kotlin type ([AvroSchema.Type.BOOLEAN] by the
 *   [Boolean]'s serializer…), bytes and fixed as a [ByteArray], an enum as its symbol [String], an array as a
 *   `List<Any?>`, a map and a record as a `Map<String, Any?>`.
 *
 * It works with every avro4k decoder: from bytes, on a missing field's default, and from Apache generic data
 * (`avro4k-apache-interop`'s `decodeFromGenericData`).
 * For more customization about named types, see [resolveFixedDeserializationStrategy], [resolveEnumDeserializationStrategy], and [resolveRecordDeserializationStrategy].
 */
@ExperimentalAvro4kApi
public expect open class AnySerializer() : AbstractAnySerializer {
    override fun serialize(
        encoder: Encoder,
        value: Any,
    )
}