package com.github.avrokotlin.avro4k.kafka.confluent

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.MissingFieldsEncodingException
import com.github.avrokotlin.avro4k.internal.decodeWithApacheDecoder
import com.github.avrokotlin.avro4k.internal.encodeWithApacheEncoder
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import org.apache.avro.Schema
import org.apache.avro.generic.GenericFixed

/*
 * The one place where the Confluent module meets core's schema-typed API.
 *
 * This module lives in Apache's and Confluent's world: the schema registry, `GenericData.Record`, `DatumReader` and
 * `DatumWriter` all speak `org.apache.avro.Schema`, and so does this module's own public API. Core's public API is still
 * Apache-typed today, so every helper here is an identity. When core's public API switches to `AvroSchema`, only the
 * bodies below change, converting with apache-interop's identity-cached `toAvro4k()` / `toApacheSchema()`; the call
 * sites stay as they are.
 *
 * Rule: no other file of this module calls a schema-typed core entry point directly (`Avro.schema`, `currentWriterSchema`,
 * `decodeFixed`, the Apache encoder/decoder entry points, …) or spells core's schema type in an override. Nothing
 * enforces it but review: a grep of this module for those calls must only find this file.
 */

// ---------------------------------------------------------------------------------------------------------------------
// Core's schema type, for the overrides of core's schema-typed hooks
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The schema type of core's public API, for the code here that *overrides* a core hook (`AvroSchemaSupplier.getSchema`,
 * `AnySerializer.*DeserializationStrategy(writerSchema)`). Once core's public API is `AvroSchema`-typed:
 * `typealias CoreSchema = AvroSchema`.
 */
internal typealias CoreSchema = Schema

/**
 * A schema handed over by core, as an Apache schema. Once core's public API is `AvroSchema`-typed: `toApacheSchema()`
 * (identity-cached; called per value in the `AnySerializer` hooks, accepted until this module itself moves to
 * `AvroSchema`).
 */
internal fun CoreSchema.asApacheSchema(): Schema = this

// ---------------------------------------------------------------------------------------------------------------------
// Schema generation
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The schema core infers for [descriptor], as an Apache schema. Once core's public API is `AvroSchema`-typed:
 * `schema(descriptor).toApacheSchema()`.
 */
internal fun Avro.apacheSchema(descriptor: SerialDescriptor): Schema = schema(descriptor)

// ---------------------------------------------------------------------------------------------------------------------
// Accessors used inside the generic serializers (per value)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The decoder's current writer schema, as an Apache schema. Once core's public API is `AvroSchema`-typed:
 * `currentWriterSchema.toApacheSchema()`.
 */
internal val AvroDecoder.apacheWriterSchema: Schema
    get() = currentWriterSchema

/**
 * Whether the encoder's current writer schema is a union. Kept apart from a writer-schema accessor so that it needs no
 * conversion once core's public API is `AvroSchema`-typed: `currentWriterSchema is AvroSchema.UnionSchema`.
 */
internal val AvroEncoder.isWriterSchemaUnion: Boolean
    get() = currentWriterSchema.isUnion

/**
 * Decodes a fixed value as Apache's [GenericFixed], carrying the writer schema. Once core's public API is
 * `AvroSchema`-typed and `decodeFixed()` returns the bytes: `GenericData.Fixed(currentWriterSchema.toApacheSchema(),
 * decodeFixed())`.
 */
internal fun AvroDecoder.decodeGenericFixed(): GenericFixed = decodeFixed()

/**
 * The error raised when an `IndexedRecord` misses a required field. Once core's public API is `AvroSchema`-typed:
 * whatever `MissingFieldsEncodingException`'s schema-typed constructor becomes, or its `(message)` constructor with the
 * same message (the one below produces
 * "Missing required fields '<name>' when encoding with writer schema <schema json>").
 */
internal fun missingFieldEncodingException(
    missingField: Schema.Field,
    writerSchema: Schema,
): MissingFieldsEncodingException = MissingFieldsEncodingException(listOf(missingField), writerSchema)

/**
 * Local copy of core's `internal.isNamedSchema` (an Apache-typed `@InternalAvro4kApi` helper that is retyped to
 * `AvroSchema` when the encoders and decoders move to it). Pure Apache code that never calls core, so it survives the
 * switch of core's public API unchanged.
 */
internal fun Schema.isNamedSchema(): Boolean =
    when (type) {
        Schema.Type.RECORD, Schema.Type.ENUM, Schema.Type.FIXED -> true
        else -> false
    }

// ---------------------------------------------------------------------------------------------------------------------
// Encoding and decoding through an Apache encoder/decoder handed in by Confluent
// ---------------------------------------------------------------------------------------------------------------------

/**
 * `Avro.encodeWithApacheEncoder(writerSchema, …)`, which keeps its Apache signature for now. Once core's public API is
 * `AvroSchema`-typed: `writerSchema.toAvro4k()` if core's entry point then takes an `AvroSchema`, or just the import if
 * it moves to apache-interop with its Apache signature, when Apache leaves core.
 */
internal fun <T> Avro.encodeWithApache(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
    encoder: org.apache.avro.io.Encoder,
) {
    encodeWithApacheEncoder(writerSchema, serializer, value, encoder)
}

/** `Avro.decodeWithApacheDecoder(writerSchema, …)`. Changes as [encodeWithApache] does. */
internal fun <T> Avro.decodeWithApache(
    writerSchema: Schema,
    deserializer: DeserializationStrategy<T>,
    decoder: org.apache.avro.io.Decoder,
): T = decodeWithApacheDecoder(writerSchema, deserializer, decoder)