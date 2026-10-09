package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.apacheToAvro4kUncached
import io.mockk.every
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import org.apache.avro.Schema

/*
 * The one place where the Apache-oracle test suite meets core's schema-typed API.
 *
 * The tests build and compare Apache `Schema`s: Apache is the conformance oracle, permanently. Core's public API is
 * `AvroSchema`-typed, so the helpers here convert with apache-interop's `toAvro4k()` / `toApacheSchema()`, the conversions
 * an Apache user calls; the call sites stay Apache-typed.
 *
 * Rule: a test never calls a schema-typed core entry point directly (`Avro.schema`, `encodeToByteArray(schema, …)`,
 * `currentWriterSchema`, …). Nothing enforces it but review: a grep of `core/src/jvmTest` for those calls must only
 * find this file (and the codec tests, which take no schema).
 */

// ---------------------------------------------------------------------------------------------------------------------
// Types that appear in the signatures of test serializers (overrides of core's schema-typed hooks)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The schema type of core's public API, for test code that *overrides* a core hook (`AvroSchemaSupplier.getSchema`,
 * `AnySerializer.resolve*DeserializationStrategy`).
 */
public typealias CoreSchema = AvroSchema

/**
 * Converts an Apache schema into [CoreSchema], e.g. to return it from a `getSchema` override. Uncached, as a `getSchema`
 * override creates its schema on each call.
 */
public fun Schema.toCoreSchema(): CoreSchema = apacheToAvro4kUncached(this)

// ---------------------------------------------------------------------------------------------------------------------
// Schema generation
// ---------------------------------------------------------------------------------------------------------------------

/** The schema core infers for [T], as an Apache schema. */
public inline fun <reified T> Avro.apacheSchema(): Schema = apacheSchema(serializersModule.serializer<T>().descriptor)

/** The schema core infers for [serializer], as an Apache schema. */
public fun Avro.apacheSchema(serializer: KSerializer<*>): Schema = apacheSchema(serializer.descriptor)

/** The schema core infers for [descriptor], as an Apache schema. */
public fun Avro.apacheSchema(descriptor: SerialDescriptor): Schema = schema(descriptor).toApacheSchema()

// ---------------------------------------------------------------------------------------------------------------------
// Binary encoding and decoding with an explicit writer schema
// ---------------------------------------------------------------------------------------------------------------------

/** `Avro.encodeToByteArray(writerSchema, …)`. */
public fun <T> Avro.encodeWith(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
): ByteArray = encodeToByteArray(writerSchema.toAvro4k(), serializer, value)

public inline fun <reified T> Avro.encodeWith(
    writerSchema: Schema,
    value: T,
): ByteArray = encodeWith(writerSchema, serializersModule.serializer<T>(), value)

/** `Avro.decodeFromByteArray(writerSchema, …)`. */
public fun <T> Avro.decodeWith(
    writerSchema: Schema,
    deserializer: DeserializationStrategy<T>,
    bytes: ByteArray,
): T = decodeFromByteArray(writerSchema.toAvro4k(), deserializer, bytes)

public inline fun <reified T> Avro.decodeWith(
    writerSchema: Schema,
    bytes: ByteArray,
): T = decodeWith(writerSchema, serializersModule.serializer<T>(), bytes)

/** `AvroSingleObject.encodeToByteArray(writerSchema, …)`. */
public fun <T> AvroSingleObject.encodeWith(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
): ByteArray = encodeToByteArray(writerSchema.toAvro4k(), serializer, value)

// ---------------------------------------------------------------------------------------------------------------------
// GenericData API (from apache-interop, Apache-typed)
// ---------------------------------------------------------------------------------------------------------------------

/** `Avro.encodeToGenericData(writerSchema, …)`. */
public fun <T> Avro.encodeToGenericDataWith(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
): Any? = encodeToGenericData(writerSchema, serializer, value)

/** `Avro.decodeFromGenericData(writerSchema, …)`. */
public fun <T> Avro.decodeFromGenericDataWith(
    writerSchema: Schema,
    deserializer: DeserializationStrategy<T>,
    value: Any?,
): T = decodeFromGenericData(writerSchema, deserializer, value)

// ---------------------------------------------------------------------------------------------------------------------
// Accessors used inside test serializers and on mocks
// ---------------------------------------------------------------------------------------------------------------------

/** The decoder's current writer schema, as an Apache schema. */
public val AvroDecoder.apacheWriterSchema: Schema
    get() = currentWriterSchema.toApacheSchema()

/** The decoder's fixed value, as bytes. */
public fun AvroDecoder.decodeFixedBytes(): ByteArray = decodeFixed()

/**
 * Stubs the writer schema of a mockk-mocked decoder. Kept here rather than as `every { decoder.currentWriterSchema }` in the
 * tests, because the stubbed value must have core's type.
 */
public fun AvroDecoder.stubWriterSchema(writerSchema: Schema) {
    val converted = writerSchema.toAvro4k() as ResolvedSchema
    every { currentWriterSchema } returns converted
}