package com.github.avrokotlin.avro4k

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
 * The tests build and compare Apache `Schema`s: Apache is the conformance oracle, permanently. Core's public API is still
 * Apache-typed today, so every helper here is an identity. When core's public API switches to `AvroSchema`, only the
 * bodies below change, converting with apache-interop's `toAvro4k()` / `toApacheSchema()`; the call sites stay as they are.
 * Each helper's KDoc says what its body becomes "after the switch".
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
 * `AnySerializer.resolve*DeserializationStrategy`). After the switch: `typealias CoreSchema = AvroSchema`.
 */
public typealias CoreSchema = Schema

/**
 * Converts an Apache schema into [CoreSchema], e.g. to return it from a `getSchema` override. After the switch:
 * `toAvro4k()`.
 */
public fun Schema.toCoreSchema(): CoreSchema = this

// ---------------------------------------------------------------------------------------------------------------------
// Schema generation
// ---------------------------------------------------------------------------------------------------------------------

/** The schema core infers for [T], as an Apache schema. After the switch: `schema<T>().toApacheSchema()`. */
public inline fun <reified T> Avro.apacheSchema(): Schema = apacheSchema(serializersModule.serializer<T>().descriptor)

/** The schema core infers for [serializer], as an Apache schema. */
public fun Avro.apacheSchema(serializer: KSerializer<*>): Schema = apacheSchema(serializer.descriptor)

/**
 * The schema core infers for [descriptor], as an Apache schema. After the switch: `schema(descriptor).toApacheSchema()`.
 */
public fun Avro.apacheSchema(descriptor: SerialDescriptor): Schema = schema(descriptor)

// ---------------------------------------------------------------------------------------------------------------------
// Binary encoding and decoding with an explicit writer schema
// ---------------------------------------------------------------------------------------------------------------------

/** `Avro.encodeToByteArray(writerSchema, …)`. After the switch: `writerSchema.toAvro4k()`. */
public fun <T> Avro.encodeWith(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
): ByteArray = encodeToByteArray(writerSchema, serializer, value)

public inline fun <reified T> Avro.encodeWith(
    writerSchema: Schema,
    value: T,
): ByteArray = encodeWith(writerSchema, serializersModule.serializer<T>(), value)

/** `Avro.decodeFromByteArray(writerSchema, …)`. After the switch: `writerSchema.toAvro4k()`. */
public fun <T> Avro.decodeWith(
    writerSchema: Schema,
    deserializer: DeserializationStrategy<T>,
    bytes: ByteArray,
): T = decodeFromByteArray(writerSchema, deserializer, bytes)

public inline fun <reified T> Avro.decodeWith(
    writerSchema: Schema,
    bytes: ByteArray,
): T = decodeWith(writerSchema, serializersModule.serializer<T>(), bytes)

/** `AvroSingleObject.encodeToByteArray(writerSchema, …)`. After the switch: `writerSchema.toAvro4k()`. */
public fun <T> AvroSingleObject.encodeWith(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
): ByteArray = encodeToByteArray(writerSchema, serializer, value)

// ---------------------------------------------------------------------------------------------------------------------
// Deprecated GenericData API (moving to apache-interop, where it may stay Apache-typed)
// ---------------------------------------------------------------------------------------------------------------------

/** `Avro.encodeToGenericData(writerSchema, …)`. */
@Suppress("DEPRECATION")
public fun <T> Avro.encodeToGenericDataWith(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
): Any? = encodeToGenericData(writerSchema, serializer, value)

/** `Avro.decodeFromGenericData(writerSchema, …)`. */
@Suppress("DEPRECATION")
public fun <T> Avro.decodeFromGenericDataWith(
    writerSchema: Schema,
    deserializer: DeserializationStrategy<T>,
    value: Any?,
): T = decodeFromGenericData(writerSchema, deserializer, value)

// ---------------------------------------------------------------------------------------------------------------------
// Accessors used inside test serializers and on mocks
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The decoder's current writer schema, as an Apache schema. After the switch: `currentWriterSchema.toApacheSchema()`.
 */
public val AvroDecoder.apacheWriterSchema: Schema
    get() = currentWriterSchema

/** The decoder's fixed value, as bytes. After the switch: `decodeFixed()` (it returns a `ByteArray` then). */
public fun AvroDecoder.decodeFixedBytes(): ByteArray = decodeFixed().bytes()

/**
 * Stubs the writer schema of a mockk-mocked decoder. Kept here rather than as `every { decoder.currentWriterSchema }` in the
 * tests, because the stubbed value must have core's type. After the switch: `returns writerSchema.toAvro4k()`.
 */
public fun AvroDecoder.stubWriterSchema(writerSchema: Schema) {
    every { currentWriterSchema } returns writerSchema
}