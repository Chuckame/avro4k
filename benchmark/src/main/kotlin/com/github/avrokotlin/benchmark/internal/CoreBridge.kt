package com.github.avrokotlin.benchmark.internal

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromByteArray
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.avro4k.schema
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import org.apache.avro.Schema

/*
 * The one place where the benchmarks' set-up and schema plumbing meet core's schema-typed API (M3-03).
 *
 * Two worlds meet here. avro4k's own measured methods hand core a schema; the competitors (Apache, Jackson) and the
 * equivalence gate need an Apache `Schema`. Core's public API is still Apache-typed today, so every helper here is an
 * identity. When M3-12 flips core's public API to `AvroSchema`, only [CoreSchema], [asApacheSchema] and [toCoreSchema]
 * change; everything else delegates to them or passes a [CoreSchema] straight through.
 *
 * Rules:
 * - a `@Benchmark` method is never edited to go through here. Its schema arguments are fields typed [CoreSchema], so
 *   the measured call sites compile unchanged once the alias points at `AvroSchema`;
 * - outside `@Benchmark` methods, nothing calls a schema-typed core entry point directly. The grep that checks it, and
 *   the list of measured call sites, are in docs/plans/notes/m3-03.md.
 */

/**
 * The schema type of core's public API: the type of every schema field a measured avro4k method hands to core.
 * M3-12: `typealias CoreSchema = AvroSchema`.
 */
internal typealias CoreSchema = Schema

/** A core schema as an Apache schema, for Apache, Jackson and the equivalence gate. M3-12: `toApacheSchema()`. */
internal fun CoreSchema.asApacheSchema(): Schema = this

/** An Apache-built schema as a core schema, e.g. a hand-made writer schema. M3-12: `toAvro4k()`. */
internal fun Schema.toCoreSchema(): CoreSchema = this

// ---------------------------------------------------------------------------------------------------------------------
// Schema generation
// ---------------------------------------------------------------------------------------------------------------------

/** The schema core infers for [T], in core's type. Unchanged at M3-12. */
internal inline fun <reified T> Avro.coreSchema(): CoreSchema = schema<T>()

/** The schema core infers for [descriptor], in core's type. Unchanged at M3-12. */
internal fun Avro.coreSchema(descriptor: SerialDescriptor): CoreSchema = schema(descriptor)

/** The schema core infers for [T], as an Apache schema. Delegates to [asApacheSchema]. */
internal inline fun <reified T> Avro.apacheSchema(): Schema = coreSchema<T>().asApacheSchema()

// ---------------------------------------------------------------------------------------------------------------------
// Binary encoding and decoding with an explicit writer schema (set-up and equivalence gate only)
// ---------------------------------------------------------------------------------------------------------------------

/** `Avro.encodeToByteArray(writerSchema, …)`. Unchanged at M3-12: the writer schema is already a [CoreSchema]. */
internal fun <T> Avro.encodeWith(
    writerSchema: CoreSchema,
    serializer: SerializationStrategy<T>,
    value: T,
): ByteArray = encodeToByteArray(writerSchema, serializer, value)

internal inline fun <reified T> Avro.encodeWith(
    writerSchema: CoreSchema,
    value: T,
): ByteArray = encodeToByteArray(writerSchema, value)

/** `Avro.decodeFromByteArray(writerSchema, …)`. Unchanged at M3-12. */
internal fun <T> Avro.decodeWith(
    writerSchema: CoreSchema,
    deserializer: DeserializationStrategy<T>,
    bytes: ByteArray,
): T = decodeFromByteArray(writerSchema, deserializer, bytes)

internal inline fun <reified T> Avro.decodeWith(
    writerSchema: CoreSchema,
    bytes: ByteArray,
): T = decodeFromByteArray<T>(writerSchema, bytes)
