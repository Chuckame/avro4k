package com.github.avrokotlin.avro4k

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import org.apache.avro.Schema

/*
 * The generic data tests speak Apache's `Schema`, like the deprecated `GenericData` API they test. These helpers have the
 * names and shapes of core's test bridge, which those tests were written against.
 */

/** The schema core infers for [serializer], as an Apache schema. */
internal fun Avro.apacheSchema(serializer: KSerializer<*>): Schema = schema(serializer).toApacheSchema()

/** `Avro.encodeToGenericData(writerSchema, …)`. */
@Suppress("DEPRECATION")
internal fun <T> Avro.encodeToGenericDataWith(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
): Any? = encodeToGenericData(writerSchema, serializer, value)

/** `Avro.decodeFromGenericData(writerSchema, …)`. */
@Suppress("DEPRECATION")
internal fun <T> Avro.decodeFromGenericDataWith(
    writerSchema: Schema,
    deserializer: DeserializationStrategy<T>,
    value: Any?,
): T = decodeFromGenericData(writerSchema, deserializer, value)

/** The decoder's current writer schema, as an Apache schema. */
internal val AvroDecoder.apacheWriterSchema: Schema
    get() = currentWriterSchema.toApacheSchema()