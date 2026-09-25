package com.github.avrokotlin.avro4k.kafka.confluent

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.MissingFieldsEncodingException
import com.github.avrokotlin.avro4k.internal.decodeWithApacheDecoder
import com.github.avrokotlin.avro4k.internal.encodeWithApacheEncoder
import com.github.avrokotlin.avro4k.toApacheSchema
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericFixed

/*
 * The one place where the Confluent module meets core's schema-typed API.
 *
 * This module lives in Apache's and Confluent's world: the schema registry, `GenericData.Record`, `DatumReader` and
 * `DatumWriter` all speak `org.apache.avro.Schema`, and so does this module's own public API. Core's public API is
 * `AvroSchema`-typed, so the helpers below convert with apache-interop's cached `toAvro4k()` / `toApacheSchema()`; the
 * call sites stay Apache-typed. Some of them run per value (the generic serializers' writer schema): an identity-cache
 * lookup each, accepted until this module itself reads `AvroSchema`.
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
 * `AnySerializer.*DeserializationStrategy(writerSchema)`).
 */
internal typealias CoreSchema = AvroSchema

/**
 * A schema handed over by core, as an Apache schema: identity-cached, and paired with the Apache schema it was converted
 * from, if any. The `AnySerializer` hooks call it once per writer schema (their results are cached by core).
 */
internal fun CoreSchema.asApacheSchema(): Schema = toApacheSchema()

// ---------------------------------------------------------------------------------------------------------------------
// Schema generation
// ---------------------------------------------------------------------------------------------------------------------

/** The schema core infers for [descriptor], as an Apache schema. */
internal fun Avro.apacheSchema(descriptor: SerialDescriptor): Schema = schema(descriptor).toApacheSchema()

// ---------------------------------------------------------------------------------------------------------------------
// Accessors used inside the generic serializers (per value)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The decoder's current writer schema, as an Apache schema: an identity-cache lookup per value, as the writer schema
 * nodes are paired with the Apache schema they were converted from (the registry's, for a consumer).
 */
internal val AvroDecoder.apacheWriterSchema: Schema
    get() = currentWriterSchema.toApacheSchema()

/** Whether the encoder's current writer schema is a union. No conversion. */
internal val AvroEncoder.isWriterSchemaUnion: Boolean
    get() = currentWriterSchema is AvroSchema.UnionSchema

/** Decodes a fixed value as Apache's [GenericFixed], carrying the writer schema. */
internal fun AvroDecoder.decodeGenericFixed(): GenericFixed = GenericData.Fixed(currentWriterSchema.toApacheSchema(), decodeFixed())

/**
 * The error raised when an `IndexedRecord` misses a required field, with the message core's schema-typed constructor
 * used to build from Apache's schema: "Missing required fields '<name>' when encoding with writer schema <schema json>".
 */
internal fun missingFieldEncodingException(
    missingField: Schema.Field,
    writerSchema: Schema,
): MissingFieldsEncodingException =
    MissingFieldsEncodingException("Missing required fields '${missingField.name()}' when encoding with writer schema $writerSchema")

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
 * `Avro.encodeWithApacheEncoder(writerSchema, …)`, which keeps its Apache signature: it converts the writer schema once
 * per call, and moves to apache-interop with that signature when Apache leaves core.
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