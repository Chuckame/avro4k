package com.github.avrokotlin.avro4k

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import org.apache.avro.Schema

/*
 * The object container and Apache codec tests speak Apache's `Schema`, like the APIs they test. These helpers have the
 * names and shapes of core's test bridge, which those tests were written against (`RecordBuilderForTest.kt` is a copy of
 * core's test file of that name).
 */

/** The schema core infers for [T], as an Apache schema. */
internal inline fun <reified T> Avro.apacheSchema(): Schema = apacheSchema(serializersModule.serializer<T>())

/** `Avro.encodeToByteArray(writerSchema, …)`. */
internal fun <T> Avro.encodeWith(
    writerSchema: Schema,
    serializer: SerializationStrategy<T>,
    value: T,
): ByteArray = encodeToByteArray(writerSchema.toAvro4k(), serializer, value)

/** The decoder's fixed value, as bytes. */
internal fun AvroDecoder.decodeFixedBytes(): ByteArray = decodeFixed()