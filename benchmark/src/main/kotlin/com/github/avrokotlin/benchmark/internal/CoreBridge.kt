package com.github.avrokotlin.benchmark.internal

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.decodeFromByteArray
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.avro4k.schema
import com.github.avrokotlin.avro4k.toApacheSchema
import com.github.avrokotlin.avro4k.toAvro4k
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import org.apache.avro.Schema

/*
 * The one place where the benchmarks' set-up and schema plumbing meet core's schema-typed API.
 *
 * Two worlds meet here. avro4k's own measured methods hand core a schema; the competitors (Apache, Jackson) and the
 * equivalence gate need an Apache `Schema`. Core's public API is `AvroSchema`-typed: [CoreSchema], [asApacheSchema] and
 * [toCoreSchema] convert with apache-interop; everything else delegates to them or passes a [CoreSchema] straight
 * through.
 *
 * Rules:
 * - a `@Benchmark` method is never edited to go through here. Its schema arguments are fields typed [CoreSchema], so
 *   the measured call sites compile unchanged once the alias points at `AvroSchema`;
 * - outside `@Benchmark` methods, nothing calls a schema-typed core entry point directly. Nothing enforces it but
 *   review: a grep of this module for those calls must only find `@Benchmark` methods and this file.
 */

/** The schema type of core's public API: the type of every schema field a measured avro4k method hands to core. */
internal typealias CoreSchema = AvroSchema

/** A core schema as an Apache schema, for Apache, Jackson and the equivalence gate. */
internal fun CoreSchema.asApacheSchema(): Schema = toApacheSchema()

/** An Apache-built schema as a core schema, e.g. a hand-made writer schema. */
internal fun Schema.toCoreSchema(): CoreSchema = toAvro4k()

// ---------------------------------------------------------------------------------------------------------------------
// Schema generation
// ---------------------------------------------------------------------------------------------------------------------

/** The schema core infers for [T], in core's type. Unchanged by the switch. */
internal inline fun <reified T> Avro.coreSchema(): CoreSchema = schema<T>()

/** The schema core infers for [descriptor], in core's type. Unchanged by the switch. */
internal fun Avro.coreSchema(descriptor: SerialDescriptor): CoreSchema = schema(descriptor)

/** The schema core infers for [T], as an Apache schema. Delegates to [asApacheSchema]. */
internal inline fun <reified T> Avro.apacheSchema(): Schema = coreSchema<T>().asApacheSchema()

// ---------------------------------------------------------------------------------------------------------------------
// Binary encoding and decoding with an explicit writer schema (set-up and equivalence gate only)
// ---------------------------------------------------------------------------------------------------------------------

/** `Avro.encodeToByteArray(writerSchema, …)`. Unchanged by the switch: the writer schema is already a [CoreSchema]. */
internal fun <T> Avro.encodeWith(
    writerSchema: CoreSchema,
    serializer: SerializationStrategy<T>,
    value: T,
): ByteArray = encodeToByteArray(writerSchema, serializer, value)

internal inline fun <reified T> Avro.encodeWith(
    writerSchema: CoreSchema,
    value: T,
): ByteArray = encodeToByteArray(writerSchema, value)

/** `Avro.decodeFromByteArray(writerSchema, …)`. Unchanged by the switch. */
internal fun <T> Avro.decodeWith(
    writerSchema: CoreSchema,
    deserializer: DeserializationStrategy<T>,
    bytes: ByteArray,
): T = decodeFromByteArray(writerSchema, deserializer, bytes)

internal inline fun <reified T> Avro.decodeWith(
    writerSchema: CoreSchema,
    bytes: ByteArray,
): T = decodeFromByteArray<T>(writerSchema, bytes)
