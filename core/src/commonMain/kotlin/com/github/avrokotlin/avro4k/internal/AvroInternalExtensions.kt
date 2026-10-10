package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.internal.decoder.JsonDefaultDecoder
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor

// The entry points the generic data trees of the apache-interop module build on (`encodeToGenericData` and
// `decodeFromGenericData`): each one hands out a piece of the runtime every avro4k encoder and decoder shares, so that
// the generic trees resolve records, enums, defaults and intercepted types exactly as the binary ones do.

/**
 * How [descriptor]'s elements map to [writerSchema]'s fields, as every avro4k record encoder and decoder resolves it:
 * through the [Avro] instance's cache, once per pair.
 */
@InternalAvro4kApi
public fun Avro.resolveRecordFields(
    writerSchema: AvroSchema,
    descriptor: SerialDescriptor,
): SerializationWorkflow = recordResolver.resolveFields(writerSchema, descriptor)

/** The index of [enumDescriptor]'s `@AvroEnumDefault` symbol, or `null` when it has none. Cached per descriptor. */
@InternalAvro4kApi
public fun Avro.enumDefaultIndex(enumDescriptor: SerialDescriptor): Int? = enumResolver.getDefaultValueIndex(enumDescriptor)

/**
 * avro4k's own serializer when [deserializer] is one of the built-in serializers it intercepts (`ByteArray`, `Duration`,
 * `Uuid`, `Instant`), otherwise [deserializer]: every avro4k decoder decodes through it.
 */
@InternalAvro4kApi
public fun <T> interceptedDeserializer(deserializer: DeserializationStrategy<T>): DeserializationStrategy<T> = SerializerLocatorMiddleware.apply(deserializer)

/** A decoder of the default value of [step]'s element, decoded from its json against its schema. */
@InternalAvro4kApi
public fun Avro.defaultValueDecoder(step: DecodingStep.GetDefaultValue): AvroDecoder = JsonDefaultDecoder(this, step.defaultValue, step.schema)